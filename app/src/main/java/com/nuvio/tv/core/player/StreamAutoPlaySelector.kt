package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = streams.partition {
            it.streams.any { stream -> stream.isDirectDebrid() }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries
        val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN -> return false
            StreamDebridCacheState.CACHED,
            null -> Unit
        }
        return stream.getStreamUrl() != null || stream.isTorrent() || stream.isDirectDebrid()
    }

    /**
     * Looser playability for playback failover: still skip externals and known
     * uncached debrid rows, but allow CHECKING/UNKNOWN so the chain can keep
     * walking the list when cache probes are stale or incomplete.
     */
    fun isFailoverPlayable(stream: Stream): Boolean {
        if (stream.isExternal()) return false
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) return false
        return stream.getStreamUrl() != null || stream.isTorrent() || stream.isDirectDebrid()
    }

    fun isPlayableStream(stream: Stream): Boolean = isPlayable(stream)

    /** Identity tokens used to exclude a stream across resolve URL changes. */
    fun streamIdentityKeys(stream: Stream): Set<String> = buildSet {
        add("sk:${stream.stableKey()}")
        stream.getStreamUrl()?.takeIf { it.isNotBlank() }?.let { add("u:$it") }
        stream.url?.takeIf { it.isNotBlank() }?.let { add("u:$it") }
        stream.externalUrl?.takeIf { it.isNotBlank() }?.let { add("u:$it") }
        stream.getEffectiveInfoHash()?.takeIf { it.isNotBlank() }?.let { add("h:$it") }
    }

    fun isExcludedByKeys(stream: Stream, excludeKeys: Set<String>): Boolean {
        if (excludeKeys.isEmpty()) return false
        return streamIdentityKeys(stream).any { it in excludeKeys }
    }

    fun countFailoverPlayableStreams(streams: List<Stream>): Int =
        streams.count { isFailoverPlayable(it) }

    /**
     * Returns the next playable stream after [current], skipping [excludeKeys].
     * Matching uses URL / infoHash / stableKey so nav-launched playback can
     * locate itself in a session-cached list.
     *
     * When [forFailover] is true, uses [isFailoverPlayable] and treats any
     * overlapping [streamIdentityKeys] as excluded (not only stableKey).
     */
    fun selectNextPlayableStream(
        streams: List<Stream>,
        current: Stream? = null,
        currentUrl: String? = null,
        excludeKeys: Set<String> = emptySet(),
        forFailover: Boolean = false
    ): Stream? {
        if (streams.isEmpty()) return null
        fun keyOf(stream: Stream): String = stream.stableKey()
        fun matchesCurrent(stream: Stream): Boolean {
            if (current != null && keyOf(stream) == keyOf(current)) return true
            val url = currentUrl?.takeIf { it.isNotBlank() } ?: return false
            return stream.getStreamUrl() == url ||
                stream.url == url ||
                stream.externalUrl == url ||
                (forFailover && stream.getEffectiveInfoHash() != null &&
                    current?.getEffectiveInfoHash() == stream.getEffectiveInfoHash())
        }
        fun isCandidate(stream: Stream): Boolean =
            if (forFailover) isFailoverPlayable(stream) else isPlayable(stream)
        fun isExcluded(stream: Stream): Boolean =
            if (forFailover) isExcludedByKeys(stream, excludeKeys)
            else keyOf(stream) in excludeKeys

        val startIndex = streams.indexOfFirst { matchesCurrent(it) }.let { index ->
            if (index >= 0) index + 1 else 0
        }
        for (i in startIndex until streams.size) {
            val candidate = streams[i]
            if (!isCandidate(candidate)) continue
            if (isExcluded(candidate)) continue
            if (matchesCurrent(candidate)) continue
            return candidate
        }
        // Wrap: try earlier streams that weren't the failing one.
        for (i in 0 until startIndex.coerceAtMost(streams.size)) {
            val candidate = streams[i]
            if (!isCandidate(candidate)) continue
            if (isExcluded(candidate)) continue
            if (matchesCurrent(candidate)) continue
            return candidate
        }
        return null
    }

    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false
    ): Stream? {
        if (streams.isEmpty()) return null

        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }

        val sourceScopedStreams = when (effectiveSource) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return null

        // Binge group matching takes priority over mode — even in MANUAL mode,
        // a persisted binge group should auto-play without showing the picker.
        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = candidateStreams.firstOrNull { stream ->
                stream.behaviorHints?.bingeGroup == targetBingeGroup && isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
            // When bingeGroupOnly is set (MANUAL mode with only binge-group
            // preference enabled), don't fall back to a non-matching stream —
            // return null so the caller shows the stream picker instead.
            if (bingeGroupOnly) return null
        }

        if (mode == StreamAutoPlayMode.MANUAL) return null

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams.firstOrNull { isPlayable(it) }
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                if (matchingStreams.isEmpty()) return null
                matchingStreams.firstOrNull { isPlayable(it) }
            }

        }
    }
}
