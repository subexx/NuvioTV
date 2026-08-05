package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream

/**
 * Lightweight video/audio summary for the first available stream on the detail hero.
 */
data class StreamAvSummary(
    val videoDetails: String?,
    val audioDetails: String?
) {
    val hasDetails: Boolean
        get() = !videoDetails.isNullOrBlank() || !audioDetails.isNullOrBlank()
}

object StreamAvSummaryBuilder {
    private val defaultSettings = DebridSettings()

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

            val languages = facts.languages.map { it.label }.ifEmpty {
                parsed?.languages.orEmpty().map { it.uppercase() }
            }
            addAll(languages.take(2))
        }.distinct()

        // Fallback: when structured parsing finds nothing, surface the stream description line.
        val fallback = stream.getDisplayDescription()
            ?.takeIf { it.isNotBlank() && it != stream.getDisplayNameOrNull() }
            ?: stream.getDisplayNameOrNull()

        return StreamAvSummary(
            videoDetails = videoParts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
                ?: fallback?.takeIf { audioParts.isEmpty() },
            audioDetails = audioParts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
        )
    }
}
