package com.smartphonekey.photoframe.gphotos

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

        fun runPending() {
            while (delayed.isNotEmpty()) delayed.removeFirst().invoke()
        }
    }

    private class FakeApi(
        var itemsReady: Boolean = true,
        var items: List<PickedItem> = emptyList(),
        var failOnCreate: Boolean = false,
        var onDownload: (PickedItem) -> Unit = {},
    ) : PickerClient {
        val deletedSessions = ArrayList<String>()
        var createdSessions = 0

        override fun createSession(token: String): PickerSession {
            if (failOnCreate) throw IOException("boom")
            createdSessions++
            return PickerSession("sess-1", "https://pick/me", 10, 1_000, false)
        }

        override fun getSession(token: String, sessionId: String) =
            PickerSession(sessionId, "https://pick/me", 10, 1_000, itemsReady)

        override fun listAllMediaItems(token: String, sessionId: String) = items

        override fun deleteSession(token: String, sessionId: String) {
            deletedSessions.add(sessionId)
        }

        override fun download(token: String, item: PickedItem, maxDimension: Int, target: File) {
            onDownload(item)
            target.writeText("bytes")
        }
    }

    private class FakeStore(private val dir: File) : PhotoStore {
        val committed = ArrayList<String>()
        var evictedWithCap: Long? = null
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
    ) = GPhotosSyncManager(
        cache = store,
        cacheCapBytes = { cap },
        ioExecutor = DirectExecutor(),
        api = api,
        poster = poster,
        clock = { 1_000L },
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

private typealias State = GPhotosSyncManager.State
private typealias Poster = GPhotosSyncManager.Poster
