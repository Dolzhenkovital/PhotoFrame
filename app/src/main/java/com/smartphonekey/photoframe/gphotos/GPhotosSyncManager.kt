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

    @Volatile
    private var cancelled = false
    private var listener: Listener? = null
    private var currentToken: String? = null

    @Volatile
    private var currentSessionId: String? = null

    var state: State = State.Idle
        private set

    val isActive: Boolean
        get() = state is State.Connecting ||
            state is State.WaitingForPick ||
            state is State.Downloading

    /** UI attaches to observe; immediately receives the current state. */
    fun attach(listener: Listener) {
        this.listener = listener
        listener.onState(state)
    }

    fun detach() {
        listener = null
    }

    fun begin(token: String, maxDimension: Int) {
        if (isActive) return
        cancelled = false
        currentToken = token
        setState(State.Connecting)
        io {
            try {
                val session = api.createSession(token)
                currentSessionId = session.id
                // cancel() may have run while we were creating the session —
                // it could not have known this id, so clean it up here.
                if (cancelled) {
                    deleteSessionQuietly()
                    return@io
                }
                val deadline = clock() + session.timeoutMs
                post {
                    if (cancelled) return@post
                    setState(State.WaitingForPick(session.pickerUri))
                    schedulePoll(token, session, maxDimension, deadline)
                }
            } catch (e: Exception) {
                failWith(e)
            }
        }
    }

    fun cancel() {
        if (!isActive) return
        cancelled = true
        poster.cancelPending()
        deleteSessionQuietly()
        setState(State.Idle)
    }

    private fun schedulePoll(
        token: String,
        session: PickerSession,
        maxDimension: Int,
        deadline: Long,
    ) {
        poster.postDelayed(session.pollIntervalMs) {
            if (cancelled) return@postDelayed
            io {
                if (cancelled) return@io
                try {
                    val fresh = api.getSession(token, session.id)
                    post {
                        if (cancelled) return@post
                        when {
                            fresh.mediaItemsSet ->
                                io { downloadAll(token, session.id, maxDimension) }

                            clock() > deadline -> {
                                deleteSessionQuietly()
                                setState(State.Failed(null, timedOut = true))
                            }

                            else -> schedulePoll(token, session, maxDimension, deadline)
                        }
                    }
                } catch (e: Exception) {
                    failWith(e)
                }
            }
        }
    }

    private fun downloadAll(token: String, sessionId: String, maxDimension: Int) {
        try {
            val picked = api.listAllMediaItems(token, sessionId)
                .filter { !it.isVideo } // photos only in this phase
            val fresh = picked.filter { !cache.contains(it) }
            post { if (!cancelled) setState(State.Downloading(0, fresh.size)) }

            var added = 0
            for ((index, item) in fresh.withIndex()) {
                if (cancelled) break
                val tmp = File(cache.mediaDir, cache.fileNameFor(item) + ".tmp")
                try {
                    api.download(token, item, maxDimension, tmp)
                    if (cache.commit(item, tmp, clock())) added++
                } catch (e: Exception) {
                    tmp.delete() // one broken download must not kill the batch
                }
                val done = index + 1
                post { if (!cancelled) setState(State.Downloading(done, fresh.size)) }
            }

            // A cancelled run must not report success: cancel() already moved
            // the state to Idle and it stays there. Photos downloaded before
            // the cancel stay in the cache — they are complete and indexed.
            if (cancelled) {
                deleteSessionQuietly()
                return
            }

            api.deleteSession(token, sessionId)
            currentSessionId = null
            val capTooSmall = cache.evictToCap(cacheCapBytes())
            val total = added
            post { if (!cancelled) setState(State.Finished(total, capTooSmall)) }
        } catch (e: Exception) {
            failWith(e)
        }
    }

    private fun failWith(e: Exception) {
        deleteSessionQuietly()
        post { if (!cancelled) setState(State.Failed(e.message?.take(200))) }
    }

    private fun deleteSessionQuietly() {
        val token = currentToken
        val sessionId = currentSessionId
        currentSessionId = null
        if (token != null && sessionId != null) {
            io { api.deleteSession(token, sessionId) }
        }
    }

    private fun setState(newState: State) {
        state = newState
        listener?.onState(newState)
    }

    private fun io(block: () -> Unit) = ioExecutor.execute(block)

    private fun post(block: () -> Unit) = poster.post(block)
}
