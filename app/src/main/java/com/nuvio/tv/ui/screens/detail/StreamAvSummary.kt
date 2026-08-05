package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.util.LANGUAGE_OVERRIDES
import com.nuvio.tv.ui.util.languageCodeToName

/**
 * Lightweight A/V + language summary for the first available stream on the detail hero.
 */
data class StreamAvSummary(
    val videoDetails: String?,
    val audioDetails: String?,
    val audioLanguageDetails: String? = null,
    val subtitleLanguageDetails: String? = null
) {
    val hasDetails: Boolean
        get() = !videoDetails.isNullOrBlank() ||
            !audioDetails.isNullOrBlank() ||
            !audioLanguageDetails.isNullOrBlank() ||
            !subtitleLanguageDetails.isNullOrBlank()
}

object StreamAvSummaryBuilder {
    private val defaultSettings = DebridSettings()

    private val subtitleMarkerRegex = Regex(
        """(?i)(?:^|[^a-z0-9])(?:subs?|subtitles?|softsubs?|msubs?)\s*[:.]?\s*([a-z]{2,12}(?:\s*[,+/&.\- ]\s*[a-z]{2,12}){0,8})"""
    )
    private val langBeforeSubsRegex = Regex(
        """(?i)\b([a-z]{2,12})\s+subs?\b"""
    )
    private val vostfrRegex = Regex("""(?i)\bvostfr\b""")
    private val nonLanguageTokens = setOf(
        "rip", "web", "bluray", "remux", "hdr", "dv", "hevc", "x264", "x265", "avc", "aac",
        "dts", "atmos", "truehd", "hybrid", "proper", "repack", "internal", "limited",
        "extended", "theatrical", "uncut", "nf", "amzn", "dsnp", "hulu", "mkv", "mp4"
    )

    fun from(stream: Stream): StreamAvSummary {
        val facts = DirectDebridStreamFilter.facts(stream, defaultSettings)
        val parsed = stream.clientResolve?.stream?.raw?.parsed

        val videoParts = buildList {
            val resolution = parsed?.resolution?.takeIf { it.isNotBlank() }
                ?: facts.resolution.takeUnless { it == DebridStreamResolution.UNKNOWN }?.label
            resolution?.let { add(it) }

            val quality = parsed?.quality?.takeIf { it.isNotBlank() }
                ?: facts.quality.takeUnless { it == DebridStreamQuality.UNKNOWN }?.label
            quality?.let { add(it) }

            val encode = parsed?.codec?.takeIf { it.isNotBlank() }?.uppercase()
                ?: facts.encode.takeUnless { it == DebridStreamEncode.UNKNOWN }?.label
            encode?.let { add(it) }

            val visual = facts.visualTags
                .filter { it != DebridStreamVisualTag.UNKNOWN }
            val compactVisual = when {
                DebridStreamVisualTag.HDR_DV in visual ->
                    listOf(DebridStreamVisualTag.HDR_DV.label)
                DebridStreamVisualTag.DV_ONLY in visual ->
                    listOf(DebridStreamVisualTag.DV_ONLY.label)
                DebridStreamVisualTag.HDR_ONLY in visual ->
                    listOf(DebridStreamVisualTag.HDR_ONLY.label)
                else -> visual
                    .filter {
                        it != DebridStreamVisualTag.DV && it != DebridStreamVisualTag.HDR
                    }
                    .ifEmpty { visual }
                    .map { it.label }
            }
            addAll(compactVisual.take(2))
        }.distinct()

        val audioParts = buildList {
            val audio = facts.audioTags
                .filter { it != DebridStreamAudioTag.UNKNOWN }
                .map { it.label }
            addAll(audio.take(2))

            val channels = facts.audioChannels
                .filter { it != DebridStreamAudioChannel.UNKNOWN }
                .map { it.label }
            addAll(channels.take(1))
        }.distinct()

        val audioLanguages = facts.languages
            .filter { it != DebridStreamLanguage.UNKNOWN }
            .map { it.label }
            .ifEmpty {
                parsed?.languages.orEmpty().mapNotNull { formatLanguageLabel(it) }
            }
            .distinct()

        val subtitleLanguages = extractSubtitleLanguages(stream).distinct()

        // Fallback: when structured parsing finds nothing, surface the stream description line.
        val fallback = stream.getDisplayDescription()
            ?.takeIf { it.isNotBlank() && it != stream.getDisplayNameOrNull() }
            ?: stream.getDisplayNameOrNull()

        return StreamAvSummary(
            videoDetails = videoParts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
                ?: fallback?.takeIf {
                    audioParts.isEmpty() && audioLanguages.isEmpty() && subtitleLanguages.isEmpty()
                },
            audioDetails = audioParts.takeIf { it.isNotEmpty() }?.joinToString(" • "),
            audioLanguageDetails = audioLanguages.takeIf { it.isNotEmpty() }
                ?.take(6)
                ?.joinToString(" • "),
            subtitleLanguageDetails = subtitleLanguages.takeIf { it.isNotEmpty() }
                ?.take(6)
                ?.joinToString(" • ")
        )
    }

    private fun extractSubtitleLanguages(stream: Stream): List<String> {
        val fromEmbedded = stream.subtitleLanguages
            .mapNotNull { formatLanguageLabel(it) }
        if (fromEmbedded.isNotEmpty()) return fromEmbedded

        val searchText = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.clientResolve?.torrentName,
            stream.clientResolve?.filename,
            stream.clientResolve?.stream?.raw?.torrentName,
            stream.clientResolve?.stream?.raw?.filename,
            stream.behaviorHints?.filename,
            stream.debridCacheStatus?.cachedName
        ).joinToString(" ")
        if (searchText.isBlank()) return emptyList()

        val normalized = searchText
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
        val labels = linkedSetOf<String>()

        if (vostfrRegex.containsMatchIn(normalized)) {
            formatLanguageLabel("fr")?.let { labels += it }
        }

        subtitleMarkerRegex.findAll(normalized).forEach { match ->
            val chunk = match.groupValues.getOrNull(1).orEmpty()
            chunk.split(Regex("""[\s,+/&.\-]+"""))
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { token ->
                    if (token.lowercase() in nonLanguageTokens) return@forEach
                    formatLanguageLabel(token)?.let { labels += it }
                }
        }

        langBeforeSubsRegex.findAll(normalized).forEach { match ->
            formatLanguageLabel(match.groupValues[1])?.let { labels += it }
        }

        return labels.toList()
    }

    private fun formatLanguageLabel(raw: String): String? {
        val token = raw.trim()
        if (token.isBlank()) return null
        val lower = token.lowercase()
        if (lower in nonLanguageTokens) return null

        DebridStreamLanguage.entries.firstOrNull {
            it != DebridStreamLanguage.UNKNOWN &&
                (lower == it.code || lower == it.label.lowercase())
        }?.let { return it.label }

        val normalized = LANGUAGE_OVERRIDES[lower] ?: lower
        if (normalized == "multi" || lower == "multi" || lower == "dual") {
            return DebridStreamLanguage.MULTI.label
        }
        if (lower == "la" || lower == "latino" || lower == "lat") {
            return DebridStreamLanguage.LA.label
        }

        // Accept short ISO-ish tokens; skip long garbage.
        if (token.length !in 2..12) return null
        if (!token.all { it.isLetter() || it == '-' || it == '_' }) return null

        val display = languageCodeToName(normalized)
        // Reject unresolved codes that only echo the raw token (e.g. REMUX, HDR).
        if (display.equals(token, ignoreCase = true) || display.equals(normalized, ignoreCase = true)) {
            return null
        }
        return display.takeIf { it.isNotBlank() }
    }
}
