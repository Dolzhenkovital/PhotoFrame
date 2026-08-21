package com.smartphonekey.photoframe.gphotos.oauth

/**
 * Invalidation token for asynchronous auth work. Sign-out must win against
 * any in-flight token operation: a code exchange or refresh that was already
 * past its network call when the user signed out would otherwise write fresh
 * tokens back into the cleared store and silently restore access.
 *
 * Usage: snapshot [current] when the operation starts, [invalidate] on
 * sign-out, and gate every store write / token delivery on [isCurrent].
 * Pure Kotlin so the race rule itself is JVM-testable.
 */
class AuthGeneration {

    @Volatile
    private var generation = 0L

    /** Snapshot to carry through an async operation. */
    fun current(): Long = generation

    /** Kills every operation started before this call. */
    fun invalidate() {
        generation++
    }

    /** True while no invalidation happened since [snapshot] was taken. */
    fun isCurrent(snapshot: Long): Boolean = snapshot == generation
}
