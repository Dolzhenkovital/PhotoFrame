package com.smartphonekey.photoframe.gphotos

import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager.Poster
import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager.State
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the sync state machine with fakes. Everything runs inline
 * (direct executor + immediate poster), so each test is deterministic and
 * needs neither Android nor the network.
 */
class GPhotosSyncManagerTest {

    // --- Fakes ---------------------------------------------------------------

    private class DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    /** Runs posts immediately; delayed work is queued until [runPending]. */
    private class TestPoster : Poster {
        private val delayed = ArrayDeque<() -> Unit>()

        override fun post(block: () -> Unit) = block()

        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            delayed.addLast(block)
        }

        override fun cancelPending() = delayed.clear()

        /** Drains only what was queued before the call — a poll that
         *  reschedules itself must not spin this into an infinite loop. */
        fun runPending() {
            repeat(delayed.size) {
                if (delayed.isEmpty()) return
                delayed.removeFirst().invoke()
            }
        }
    }

    private class FakeApi(
        var itemsReady: Boolean = true,
        var items: List<PickedItem> = emptyList(),
        var failOnCreate: Boolean = false,
        var onDownload: (PickedItem) -> Unit = {},
        var downloadSizeBytes: Int = 5,
        /** Session ids for which getSession/deleteSession throw 404 (dead). */
        var deadSessionIds: Set<String> = emptySet(),
        /** Session ids for which getSession throws a plain (transient) IO error. */
        var transientFailureIds: Set<String> = emptySet(),
    ) : PickerClient {
        val deletedSessions = ArrayList<String>()
        var createdSessions = 0

        override fun createSession(token: String): PickerSession {
            if (failOnCreate) throw IOException("boom")
            createdSessions++
            return PickerSession("sess-$createdSessions", "https://pick/me", 10, 1_000, false)
        }

        override fun getSession(token: String, sessionId: String): PickerSession {
            if (sessionId in deadSessionIds) {
                throw PickerApi.ApiException(404, "session gone")
            }
            if (sessionId in transientFailureIds) throw IOException("network down")
            return PickerSession(sessionId, "https://pick/me", 10, 1_000, itemsReady)
        }

        override fun listAllMediaItems(token: String, sessionId: String) = items

        override fun deleteSession(token: String, sessionId: String) {
            deletedSessions.add(sessionId) // the attempt is what tests assert
            if (sessionId in deadSessionIds) throw IOException("session gone")
        }

        override fun download(token: String, item: PickedItem, maxDimension: Int, target: File) {
            onDownload(item)
            target.writeBytes(ByteArray(downloadSizeBytes))
        }
    }

    private open class FakeStore(private val dir: File) : PhotoStore {
        val committed = ArrayList<String>()
        var evictedWithCap: Long? = null
        var evictCalls = 0
        var capTooSmall = false

        override val mediaDir: File get() = dir
        override fun fileNameFor(item: PickedItem) = item.id + ".jpg"
        override fun contains(item: PickedItem) = false
        override fun commit(item: PickedItem, tmp: File, now: Long): Boolean {
            committed.add(item.id)
            tmp.delete()
            return true
        }

        override fun evictToCap(capBytes: Long): Boolean {
            evictedWithCap = capBytes
            evictCalls++
            return capTooSmall
        }
    }

    private fun photo(id: String) = PickedItem(
        id = id, filename = "$id.jpg", mimeType = "image/jpeg",
        baseUrl = "https://base/$id", isVideo = false, width = 100, height = 100,
    )

    private fun video(id: String) = photo(id).copy(isVideo = true)

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "gp-test-" + System.nanoTime())
            .apply { mkdirs(); deleteOnExit() }

    private fun manager(
        api: FakeApi,
        store: FakeStore,
        poster: TestPoster,
        cap: Long = 1_000L,
        clock: () -> Long = { 1_000L },
        evictAfterBytes: Long = 32L * 1024 * 1024,
        storeSession: (String?) -> Unit = {},
        loadStoredSession: () -> String? = { null },
    ) = GPhotosSyncManager(
        cache = store,
        cacheCapBytes = { cap },
        ioExecutor = DirectExecutor(),
        api = api,
        poster = poster,
        clock = clock,
        evictAfterBytes = evictAfterBytes,
        storeSession = storeSession,
        loadStoredSession = loadStoredSession,
    )

    // --- Tests ---------------------------------------------------------------

    @Test
    fun `happy path downloads photos and finishes`() {
        val poster = TestPoster()
        val api = FakeApi(items = listOf(photo("a"), photo("b")))
        val store = FakeStore(tempDir())
        val states = ArrayList<State>()
        val sync = manager(api, store, poster)
        sync.attach { states.add(it) }

        sync.begin("token", 1280)
        assertTrue(sync.state is State.WaitingForPick)
        poster.runPending() // poll fires → items ready → download

        val finished = sync.state as State.Finished
        assertEquals(2, finished.added)
        assertFalse(finished.capTooSmall)
        assertEquals(listOf("a", "b"), store.committed)
        assertEquals(listOf("sess-1"), api.deletedSessions)
        assertEquals(1_000L, store.evictedWithCap)
        assertTrue(states.any { it is State.Downloading })
    }

    @Test
    fun `videos are skipped in this phase`() {
        val poster = TestPoster()
        val api = FakeApi(items = listOf(photo("a"), video("v")))
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)

        sync.begin("token", 1280)
        poster.runPending()

        assertEquals(listOf("a"), store.committed)
    }

    @Test
    fun `cancel during downloads does not report success`() {
        val poster = TestPoster()
        val store = FakeStore(tempDir())
        lateinit var sync: GPhotosSyncManager
        val api = FakeApi(items = listOf(photo("a"), photo("b"), photo("c")))
        api.onDownload = { item -> if (item.id == "b") sync.cancel() }
        sync = manager(api, store, poster)
        val states = ArrayList<State>()
        sync.attach { states.add(it) }

        sync.begin("token", 1280)
        poster.runPending()

        // Cancel wins: the run must not overwrite Idle with Finished.
        assertEquals(State.Idle, sync.state)
        assertFalse(states.any { it is State.Finished })
        // Photos already downloaded stay — they are complete and indexed.
        assertTrue(store.committed.containsAll(listOf("a", "b")))
        assertFalse(store.committed.contains("c"))
        // The session is cleaned up exactly once despite the mid-flight cancel.
        assertEquals(listOf("sess-1"), api.deletedSessions)
    }

    @Test
    fun `cancel before pick stops polling and cleans up`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false)
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)

        sync.begin("token", 1280)
        assertTrue(sync.state is State.WaitingForPick)
        sync.cancel()
        poster.runPending() // nothing should be pending anymore

        assertEquals(State.Idle, sync.state)
        assertEquals(listOf("sess-1"), api.deletedSessions)
        assertTrue(store.committed.isEmpty())
    }

    @Test
    fun `cancel then immediate begin does not let the old run clobber the new one`() {
        // The review-found race: a boolean cancelled flag would be reset by
        // the new begin(), letting the old in-flight run resume and finish.
        val poster = TestPoster()
        val store = FakeStore(tempDir())
        lateinit var sync: GPhotosSyncManager
        val api = FakeApi(items = listOf(photo("a"), photo("b"), photo("c")))
        var interrupted = false
        api.onDownload = { item ->
            if (!interrupted && item.id == "a") {
                interrupted = true
                sync.cancel()
                api.itemsReady = false // the new run should park at WaitingForPick
                sync.begin("token2", 1280)
            }
        }
        sync = manager(api, store, poster)
        val states = ArrayList<State>()
        sync.attach { states.add(it) }

        sync.begin("token", 1280)
        poster.runPending() // old run's poll → download, cancel+begin mid-flight

        // The NEW run owns the state machine; the old one went silent.
        assertTrue(sync.state is State.WaitingForPick)
        assertFalse(states.any { it is State.Finished })
        assertEquals(2, api.createdSessions)
        assertTrue(api.deletedSessions.contains("sess-1"))
        assertFalse("new run's session must survive", api.deletedSessions.contains("sess-2"))
        assertFalse("old run must stop downloading", store.committed.contains("c"))
    }

    @Test
    fun `failure to create a session surfaces as Failed`() {
        val poster = TestPoster()
        val api = FakeApi(failOnCreate = true)
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)

        sync.begin("token", 1280)

        val failed = sync.state as State.Failed
        assertEquals("boom", failed.detail)
        assertFalse(failed.timedOut)
        assertEquals(0, api.createdSessions)
    }

    @Test
    fun `begin is ignored while a sync is already active`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false)
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)

        sync.begin("token", 1280)
        sync.begin("token", 1280)

        assertEquals(1, api.createdSessions)
    }

    @Test
    fun `attach immediately replays the current state`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false)
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)
        sync.begin("token", 1280)

        // A reopened settings screen must see the in-flight sync at once.
        var replayed: State? = null
        sync.attach { replayed = it }
        assertTrue(replayed is State.WaitingForPick)
    }

    @Test
    fun `transition-only listeners get no replay but see new states`() {
        val poster = TestPoster()
        val api = FakeApi(items = listOf(photo("a")))
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)
        sync.begin("token", 1280)

        // The slideshow attaches after the fact and must NOT react to the
        // stale current state — only to transitions from now on.
        val seen = ArrayList<State>()
        sync.attachTransitionsOnly { seen.add(it) }
        assertTrue(seen.isEmpty())
        poster.runPending()
        assertTrue(seen.any { it is State.Finished })
    }

    @Test
    fun `detaching one listener leaves the others attached`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false)
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster)
        val kept = ArrayList<State>()
        val keeper = GPhotosSyncManager.Listener { kept.add(it) }
        val leaver = GPhotosSyncManager.Listener { }
        sync.attach(keeper)
        sync.attach(leaver)
        sync.detach(leaver)

        sync.begin("token", 1280)
        assertTrue(kept.any { it is State.WaitingForPick })
    }

    @Test
    fun `items rejected by the cache are not counted as added`() {
        val poster = TestPoster()
        val api = FakeApi(items = listOf(photo("good"), photo("corrupt")))
        // Mimics a non-image body served with 2xx: commit() refuses it.
        val store = object : FakeStore(tempDir()) {
            override fun commit(item: PickedItem, tmp: File, now: Long): Boolean {
                if (item.id == "corrupt") {
                    tmp.delete()
                    return false
                }
                return super.commit(item, tmp, now)
            }
        }
        val sync = manager(api, store, poster)

        sync.begin("token", 1280)
        poster.runPending()

        assertEquals(1, (sync.state as State.Finished).added)
        assertEquals(listOf("good"), store.committed)
    }

    @Test
    fun `pick timeout keeps the session for a later resume`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false) // session timeoutMs = 1000
        val store = FakeStore(tempDir())
        var now = 1_000L
        var storedSession: String? = null
        val sync = manager(
            api, store, poster, clock = { now },
            storeSession = { storedSession = it },
            loadStoredSession = { storedSession },
        )

        sync.begin("token", 1280)
        assertTrue(sync.state is State.WaitingForPick)
        assertEquals("sess-1", storedSession)
        // The polling window is floored at two real-world hours — Google's
        // ~30-min timeoutIn hint is shorter than hand-picking a big album.
        now = 1_000L + 2 * 60 * 60_000L + 1
        poster.runPending()

        val failed = sync.state as State.Failed
        assertTrue(failed.timedOut)
        // The unfinished pick survives: session neither deleted nor forgotten.
        assertEquals(emptyList<String>(), api.deletedSessions)
        assertEquals("sess-1", storedSession)
    }

    @Test
    fun `dead stored session falls back to a fresh one, even if DELETE fails`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false, deadSessionIds = setOf("dead-sess"))
        val store = FakeStore(tempDir())
        var storedSession: String? = "dead-sess"
        val sync = manager(
            api, store, poster,
            storeSession = { storedSession = it },
            loadStoredSession = { storedSession },
        )

        sync.begin("token", 1280)

        // getSession AND the cleanup DELETE both threw for the stored id —
        // the sync must still land on a brand-new session, not Failed.
        assertTrue(sync.state is State.WaitingForPick)
        assertEquals(1, api.createdSessions)
        assertEquals(listOf("dead-sess"), api.deletedSessions)
        assertEquals("sess-1", storedSession)
    }

    @Test
    fun `transient getSession failure keeps the stored session for retry`() {
        val poster = TestPoster()
        val api = FakeApi(transientFailureIds = setOf("mid-pick-sess"))
        val store = FakeStore(tempDir())
        var storedSession: String? = "mid-pick-sess"
        val sync = manager(
            api, store, poster,
            storeSession = { storedSession = it },
            loadStoredSession = { storedSession },
        )

        sync.begin("token", 1280)

        // A Wi-Fi blip while the user is an hour into picking must NOT
        // replace their session: fail, keep the id, let them retry.
        assertTrue(sync.state is State.Failed)
        assertEquals(0, api.createdSessions)
        assertEquals(emptyList<String>(), api.deletedSessions)
        assertEquals("mid-pick-sess", storedSession)
    }

    @Test
    fun `403 on the stored session is treated as transient, not dead`() {
        val api = object : PickerClient {
            var createdSessions = 0
            val deletedSessions = ArrayList<String>()
            override fun createSession(token: String) = error("must not create")
            override fun getSession(token: String, sessionId: String): PickerSession =
                throw PickerApi.ApiException(403, "policy says no, today")
            override fun listAllMediaItems(token: String, sessionId: String) =
                emptyList<PickedItem>()
            override fun deleteSession(token: String, sessionId: String) {
                deletedSessions.add(sessionId)
            }
            override fun download(
                token: String, item: PickedItem, maxDimension: Int, target: File,
            ) = error("no downloads here")
        }
        val store = FakeStore(tempDir())
        var storedSession: String? = "picky-sess"
        val sync = GPhotosSyncManager(
            cache = store,
            cacheCapBytes = { 1_000L },
            ioExecutor = DirectExecutor(),
            api = api,
            poster = TestPoster(),
            clock = { 1_000L },
            storeSession = { storedSession = it },
            loadStoredSession = { storedSession },
        )

        sync.begin("token", 1280)

        // 403 may be a token/scope/policy problem — the pick must survive.
        assertTrue(sync.state is State.Failed)
        assertEquals(emptyList<String>(), api.deletedSessions)
        assertEquals("picky-sess", storedSession)
    }

    @Test
    fun `auth failure mid-poll keeps the session for a re-authorized retry`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = false)
        val store = FakeStore(tempDir())
        var storedSession: String? = null
        val sync = manager(
            api, store, poster,
            storeSession = { storedSession = it },
            loadStoredSession = { storedSession },
        )

        sync.begin("token", 1280)
        assertTrue(sync.state is State.WaitingForPick)
        assertEquals("sess-1", storedSession)

        // The ~1h access token expires inside the 2h picking window: the
        // next poll's getSession throws. The user's in-progress selection
        // must survive — session neither deleted nor forgotten; the next
        // "Add photos" re-authorizes and resumes it.
        api.transientFailureIds = setOf("sess-1")
        poster.runPending()

        assertTrue(sync.state is State.Failed)
        assertEquals(emptyList<String>(), api.deletedSessions)
        assertEquals("sess-1", storedSession)
    }

    @Test
    fun `begin resumes a stored session and downloads a finished pick`() {
        val poster = TestPoster()
        val api = FakeApi(itemsReady = true, items = listOf(photo("late")))
        val store = FakeStore(tempDir())
        var storedSession: String? = "old-sess"
        val sync = manager(
            api, store, poster,
            storeSession = { storedSession = it },
            loadStoredSession = { storedSession },
        )

        sync.begin("token", 1280)

        // No new session: the stored one was picked up, its selection
        // downloaded immediately, and the store cleared afterwards.
        assertEquals(0, api.createdSessions)
        assertEquals(1, (sync.state as State.Finished).added)
        assertEquals(listOf("late"), store.committed)
        assertEquals(listOf("old-sess"), api.deletedSessions)
        assertEquals(null, storedSession)
    }

    @Test
    fun `eviction runs mid-sync once enough bytes have landed`() {
        // Guards the storage-exhaustion path: a nearly-full cache receiving
        // several large downloads must be trimmed as bytes land, not only at
        // the end of the whole batch.
        val poster = TestPoster()
        val api = FakeApi(
            items = (1..5).map { photo("p$it") },
            downloadSizeBytes = 6,
        )
        val store = FakeStore(tempDir())
        val sync = manager(api, store, poster, evictAfterBytes = 10L)

        sync.begin("token", 1280)
        poster.runPending()

        // 6 bytes per photo, threshold 10 → passes after photos 2 and 4,
        // plus the final pass on completion.
        assertEquals(3, store.evictCalls)
        assertEquals(5, (sync.state as State.Finished).added)
    }

    @Test
    fun `cap too small is reported on finish`() {
        val poster = TestPoster()
        val api = FakeApi(items = listOf(photo("a")))
        val store = FakeStore(tempDir()).apply { capTooSmall = true }
        val sync = manager(api, store, poster, cap = 10L)

        sync.begin("token", 1280)
        poster.runPending()

        assertTrue((sync.state as State.Finished).capTooSmall)
        assertEquals(10L, store.evictedWithCap)
    }
}
