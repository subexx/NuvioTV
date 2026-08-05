package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.AddonStreams
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory session cache of addon stream results keyed by `type|videoId`.
 *
 * Shared across Meta Details prefetch, StreamScreen, and the player sources
 * panel so streams fetched once can be reused instantly for the rest of the
 * app session.
 */
@Singleton
class SessionAddonStreamsCache @Inject constructor() {
    data class Entry(
        val groups: List<AddonStreams>,
        val isComplete: Boolean,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun key(type: String, videoId: String): String =
        "${type.trim().lowercase()}|${videoId.trim()}"

    fun get(key: String): Entry? = synchronized(lock) { entries[key] }

    fun get(type: String, videoId: String): Entry? = get(key(type, videoId))

    fun put(key: String, groups: List<AddonStreams>, isComplete: Boolean) {
        if (key.isBlank() || groups.isEmpty() && !isComplete) return
        synchronized(lock) {
            val existing = entries[key]
            // Don't regress a complete entry with an incomplete empty update.
            if (existing?.isComplete == true && !isComplete && groups.isEmpty()) return
            entries[key] = Entry(
                groups = groups,
                isComplete = isComplete || existing?.isComplete == true && groups.size >= existing.groups.size
            )
        }
    }

    fun put(type: String, videoId: String, groups: List<AddonStreams>, isComplete: Boolean) {
        put(key(type, videoId), groups, isComplete)
    }

    fun markComplete(key: String) {
        synchronized(lock) {
            val existing = entries[key] ?: return
            if (!existing.isComplete) {
                entries[key] = existing.copy(isComplete = true, updatedAtMs = System.currentTimeMillis())
            }
        }
    }

    fun markComplete(type: String, videoId: String) {
        markComplete(key(type, videoId))
    }

    companion object {
        private const val MAX_ENTRIES = 24
    }
}
