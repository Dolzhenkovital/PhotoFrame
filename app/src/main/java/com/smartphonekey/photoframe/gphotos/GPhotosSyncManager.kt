package com.smartphonekey.photoframe.gphotos

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executor

/**
 * Orchestrates one "add photos" round-trip:
 * auth token → create session → user picks (QR on the frame, or locally) →
 * poll until mediaItemsSet → list items → download into the cache → evict.
 *
 * App-scoped: keeps working when the settings screen closes (the frame's
 * process is effectively immortal — the screen never sleeps). Callbacks land
 * on the main thread via [poster]; every network/disk step hops onto the IO
 * executor. Downloads are sequential — parallel I/O chokes old frames.
 *
 * Cancellation is scoped by a per-run [generation]: every async step
 * captures the generation it was started under and becomes a no-op the
 * moment it is stale. A plain boolean flag is not enough — cancel() followed
 * by an immediate begin() would reset it and let the OLD run resume,
 * clobbering the new run's session and states.
 *
 * [poster] and [clock] are injectable so the whole state machine — including
 * cancellation races — is unit-testable on the JVM without Android.
 */
class GPhotosSyncManager(
    private val cache: PhotoStore,
    private val cacheCapBytes: () -> Long,
    private val ioExecutor: Executor,
    private val api: PickerClient = PickerApi(),
    private val poster: Poster = MainThreadPoster(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val evictAfterBytes: Long = DEFAULT_EVICT_AFTER_BYTES,
    /**
     * Persistence for the open picker session id, so an unfinished pick
     * survives the polling window (and app restarts): picking a big album
     * by hand takes longer than any reasonable window, and the session
     * itself lives ~a day server-side. Stored on WaitingForPick, cleared on
     * finish/cancel/failure; the next begin() resumes a stored session
     * instead of opening a new one.
     */
    private val storeSession: (String?) -> Unit = {},
    private val loadStoredSession: () -> String? = { null },
) {

    /** Main-thread dispatch, abstracted for tests. */
    interface Poster {
        fun post(block: () -> Unit)
        fun postDelayed(delayMs: Long, block: () -> Unit)
        fun cancelPending()
    }

    class MainThreadPoster : Poster {
        private val handler = Handler(Looper.getMainLooper())
        override fun post(block: () -> Unit) {
            handler.post(block)
        }

        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            handler.postDelayed(block, delayMs)
        }

        override fun cancelPending() {
            handler.removeCallbacksAndMessages(null)
        }
    }

    sealed class State {
        object Idle : State()
        object Connecting : State()
        data class WaitingForPick(val pickerUri: String) : State()
        data class Downloading(val done: Int, val total: Int) : State()
        data class Finished(val added: Int, val capTooSmall: Boolean) : State()
        data class Failed(val detail: String?, val timedOut: Boolean = false) : State()
    }

    fun interface Listener {
        fun onState(state: State)
    }

    /** Bumped on every begin() and cancel(); stale runs see the mismatch. */
    @Volatile
    private var generation = 0

    // Several observers at once: the settings dialog renders progress while
    // the slideshow listens for Finished to reload its queue.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    // Tracked only so cancel() can clean up the server-side session.
    // Written on the main thread under the current generation.
    private var activeToken: String? = null
    private var activeSessionId: String? = null

    var state: State = State.Idle
        private set

    val isActive: Boolean
        get() = state is State.Connecting ||
            state is State.WaitingForPick ||
            state is State.Downloading

    /**
     * Starts observing and immediately replays the current state — what a
     * re-opened progress dialog needs to render itself.
     */
    fun attach(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onState(state)
    }

    /**
     * Starts observing without the replay, for observers that react only to
     * *transitions* — e.g. the slideshow reloading on Finished must not
     * re-fire for a stale terminal state it already handled.
     */
    fun attachTransitionsOnly(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    /** Removes exactly [listener]; other observers are untouched. */
    fun detach(listener: Listener) {
        listeners.remove(listener)
    }

    fun begin(token: String, maxDimension: Int) {
        if (isActive) return
        val gen = ++generation
        activeToken = token
        activeSessionId = null
        setState(State.Connecting)
        io {
            try {
                // An unfinished pick from an earlier round is resumed, not
                // replaced: the user may have spent an hour selecting an
                // album after the frame stopped polling. Any failure on the
                // stored id (expired, deleted) falls back to a new session.
                val stored = loadStoredSession()
                val session = if (stored != null) {
                    try {
                        api.getSession(token, stored)
                    } catch (e: Exception) {
                        // Best-effort cleanup of the unusable stored session
                        // before replacing it — otherwise abandoned Picker
                        // sessions pile up server-side until they expire.
                        api.deleteSession(token, stored)
                        api.createSession(token)
                    }
                } else {
                    api.createSession(token)
                }
                post {
                    if (gen != generation) {
                        // cancel() (or a newer begin) won while we were
                        // creating this session — it could not have known the
                        // id, so clean it up here.
                        deleteQuietly(token, session.id)
                        return@post
                    }
                    activeSessionId = session.id
                    storeSession(session.id)
                    if (session.mediaItemsSet) {
                        // The pick was completed while nobody was polling.
                        io { downloadAll(gen, token, session.id, maxDimension) }
                        return@post
                    }
                    setState(State.WaitingForPick(session.pickerUri))
                    // pollingConfig.timeoutIn (~30 min) is Google's hint for
                    // when to give up, but the session itself lives ~a day —
                    // and hand-picking a large album takes longer than the
                    // hint (observed: an 815-photo selection outlived it, the
                    // frame deleted the session, and Done had nowhere to
                    // save). Keep polling for at least two hours.
                    val window = maxOf(session.timeoutMs, MIN_PICK_WINDOW_MS)
                    schedulePoll(gen, token, session, maxDimension, clock() + window)
                }
            } catch (e: Exception) {
                failWith(gen, token, null, e)
            }
        }
    }

    fun cancel() {
        if (!isActive) return
        generation++
        poster.cancelPending()
        val token = activeToken
        val sessionId = activeSessionId
        activeToken = null
        activeSessionId = null
        storeSession(null) // explicit cancel really abandons the pick
        if (token != null && sessionId != null) deleteQuietly(token, sessionId)
        setState(State.Idle)
    }

    private fun schedulePoll(
        gen: Int,
        token: String,
        session: PickerSession,
        maxDimension: Int,
        deadline: Long,
    ) {
        poster.postDelayed(session.pollIntervalMs) {
            if (gen != generation) return@postDelayed
            io {
                if (gen != generation) return@io
                try {
                    val fresh = api.getSession(token, session.id)
                    post {
                        if (gen != generation) return@post
                        when {
                            fresh.mediaItemsSet ->
                                io { downloadAll(gen, token, session.id, maxDimension) }

                            clock() > deadline -> {
                                // Stop polling but KEEP the session (it is
                                // also still stored): the user may simply
                                // not be done picking — the next begin()
                                // resumes exactly where they are.
                                clearActive()
                                setState(State.Failed(null, timedOut = true))
                            }

                            else -> schedulePoll(gen, token, session, maxDimension, deadline)
                        }
                    }
                } catch (e: Exception) {
                    failWith(gen, token, session.id, e)
                }
            }
        }
    }

    private fun downloadAll(gen: Int, token: String, sessionId: String, maxDimension: Int) {
        try {
            val picked = api.listAllMediaItems(token, sessionId)
                .distinctBy { it.id } // a repeated API page must not download twice
                .filter { !it.isVideo } // photos only in this phase
            val fresh = picked.filter { !cache.contains(it) }
            post { if (gen == generation) setState(State.Downloading(0, fresh.size)) }

            var added = 0
            var bytesSinceEvict = 0L
            for ((index, item) in fresh.withIndex()) {
                if (gen != generation) break
                val tmp = File(cache.mediaDir, cache.fileNameFor(item) + ".tmp")
                try {
                    api.download(token, item, maxDimension, tmp)
                    val size = tmp.length()
                    if (cache.commit(item, tmp, clock())) {
                        added++
                        bytesSinceEvict += size
                    }
                } catch (e: Exception) {
                    tmp.delete() // one broken download must not kill the batch
                }
                // Evict as we go, triggered by BYTES landed rather than item
                // count: counting items would let ten 30 MB downloads pile
                // ~300 MB over the cap before a pass ran — enough to fill a
                // small frame's storage. Still batched (a pass walks the
                // whole index), but the overshoot is bounded by
                // evictAfterBytes + one file.
                if (bytesSinceEvict >= evictAfterBytes) {
                    cache.evictToCap(cacheCapBytes())
                    bytesSinceEvict = 0L
                }
                val done = index + 1
                post { if (gen == generation) setState(State.Downloading(done, fresh.size)) }
            }

            // A stale run must not report success: cancel() already moved the
            // state to Idle (or a newer run owns it now). Photos downloaded
            // before the cancel stay — they are complete and indexed. No
            // session cleanup here: by the time a download run can be stale,
            // cancel() has already deleted the session it knew about —
            // deleting again would be a second authenticated request.
            if (gen != generation) return

            api.deleteSession(token, sessionId)
            storeSession(null) // this pick is fully consumed
            val capTooSmall = cache.evictToCap(cacheCapBytes())
            val total = added
            post {
                if (gen != generation) return@post
                clearActive()
                setState(State.Finished(total, capTooSmall))
            }
        } catch (e: Exception) {
            failWith(gen, token, sessionId, e)
        }
    }

    private fun failWith(gen: Int, token: String, sessionId: String?, e: Exception) {
        // Only the generation that still owns the run cleans up its session;
        // for a stale run cancel() has already done it.
        if (sessionId != null && gen == generation) {
            storeSession(null)
            deleteQuietly(token, sessionId)
        }
        post {
            if (gen != generation) return@post
            clearActive()
            setState(State.Failed(e.message?.take(200)))
        }
    }

    private fun clearActive() {
        activeToken = null
        activeSessionId = null
    }

    private fun deleteQuietly(token: String, sessionId: String) {
        io { api.deleteSession(token, sessionId) } // deleteSession logs its own failures
    }

    private fun setState(newState: State) {
        state = newState
        listeners.forEach { it.onState(newState) }
    }

    private fun io(block: () -> Unit) = ioExecutor.execute(block)

    private fun post(block: () -> Unit) = poster.post(block)

    private companion object {
        /** Bytes landed between mid-sync eviction passes (~1 large photo over). */
        const val DEFAULT_EVICT_AFTER_BYTES = 32L * 1024 * 1024

        /** Floor for the picking window — see the schedulePoll call site. */
        const val MIN_PICK_WINDOW_MS = 2 * 60 * 60_000L
    }
}
