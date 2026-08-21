package com.smartphonekey.photoframe.gphotos

import java.io.File

/**
 * The Picker REST surface the sync state machine depends on. Exists so the
 * machine can be driven by fakes in JVM tests (never hit the network in
 * tests — google-photos-picker skill).
 */
interface PickerClient {
    fun createSession(token: String): PickerSession
    fun getSession(token: String, sessionId: String): PickerSession
    fun listAllMediaItems(token: String, sessionId: String): List<PickedItem>
    fun deleteSession(token: String, sessionId: String)
    fun download(token: String, item: PickedItem, maxDimension: Int, target: File)
}

/**
 * The cache operations the sync state machine depends on, for the same
 * reason. [GPhotosCache] is the production implementation.
 */
interface PhotoStore {
    val mediaDir: File
    fun fileNameFor(item: PickedItem): String
    fun contains(item: PickedItem): Boolean
    fun commit(item: PickedItem, tmp: File, now: Long): Boolean

    /** Applies the LRU policy; returns true when the cap is too small. */
    fun evictToCap(capBytes: Long): Boolean
}
