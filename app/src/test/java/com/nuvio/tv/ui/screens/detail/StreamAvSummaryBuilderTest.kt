package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAvSummaryBuilderTest {

    @Test
    fun `separates audio languages from codec details`() {
        val summary = StreamAvSummaryBuilder.from(
            stream(
                name = "TB 1080p",
                description = "Movie.2024.1080p.BluRay.REMUX.DTS-HD.MA.5.1.mkv",
                languages = listOf("en", "it"),
                audio = listOf("DTS Lossless"),
                channels = listOf("5.1"),
                resolution = "1080p",
                quality = "REMUX",
                codec = "hevc"
            )
        )

        assertEquals("1080p • REMUX • HEVC", summary.videoDetails)
        assertTrue(summary.audioDetails?.contains("DTS") == true || summary.audioDetails?.contains("5.1") == true)
        assertEquals("English • Italian", summary.audioLanguageDetails)
    }

    @Test
    fun `extracts subtitle languages from Sub markers`() {
        val summary = StreamAvSummaryBuilder.from(
            stream(
                name = "Cached",
                description = "Movie.2024.1080p.BluRay.Sub.Ita.Eng.x264.mkv"
            )
        )

        assertEquals("Italian • English", summary.subtitleLanguageDetails)
    }

    @Test
    fun `uses embedded stream subtitle languages when present`() {
        val summary = StreamAvSummaryBuilder.from(
            stream(
                name = "HTTP",
                url = "https://example.com/a.mkv",
                subtitleLanguages = listOf("fr", "de")
            )
        )

        assertEquals("French • German", summary.subtitleLanguageDetails)
    }

    @Test
    fun `vostfr maps to french subtitles`() {
        val summary = StreamAvSummaryBuilder.from(
            stream(name = "Film.2020.VOSTFR.1080p.WEB")
        )

        assertEquals("French", summary.subtitleLanguageDetails)
    }

    @Test
    fun `returns null subtitle details when none found`() {
        val summary = StreamAvSummaryBuilder.from(
            stream(
                name = "Plain",
                description = "Movie.2024.1080p.BluRay.x264.mkv",
                languages = listOf("en")
            )
        )

        assertNull(summary.subtitleLanguageDetails)
        assertEquals("English", summary.audioLanguageDetails)
    }

    private fun stream(
        name: String? = null,
        description: String? = null,
        url: String? = null,
        languages: List<String>? = null,
        audio: List<String>? = null,
        channels: List<String>? = null,
        resolution: String? = null,
        quality: String? = null,
        codec: String? = null,
        subtitleLanguages: List<String> = emptyList()
    ): Stream {
        val parsed = if (
            languages != null || audio != null || channels != null ||
            resolution != null || quality != null || codec != null
        ) {
            StreamClientResolveParsed(
                rawTitle = description,
                parsedTitle = null,
                year = null,
                resolution = resolution,
                seasons = null,
                episodes = null,
                quality = quality,
                hdr = null,
                codec = codec,
                audio = audio,
                channels = channels,
                languages = languages,
                group = null,
                network = null,
                edition = null,
                duration = null,
                bitDepth = null,
                extended = null,
                theatrical = null,
                remastered = null,
                unrated = null
            )
        } else {
            null
        }

        return Stream(
            name = name,
            title = null,
            description = description,
            url = url,
            ytId = null,
            infoHash = if (url == null) "abc" else null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = description
            ),
            addonName = "Test",
            addonLogo = null,
            clientResolve = parsed?.let {
                StreamClientResolve(
                    type = "debrid",
                    infoHash = "abc",
                    fileIdx = 0,
                    magnetUri = null,
                    sources = null,
                    torrentName = description,
                    filename = description,
                    mediaType = null,
                    mediaId = null,
                    mediaOnlyId = null,
                    title = null,
                    season = null,
                    episode = null,
                    service = "torbox",
                    serviceIndex = null,
                    serviceExtension = null,
                    isCached = true,
                    stream = StreamClientResolveStream(
                        raw = StreamClientResolveRaw(
                            torrentName = description,
                            filename = description,
                            size = null,
                            folderSize = null,
                            tracker = null,
                            indexer = null,
                            network = null,
                            parsed = it
                        )
                    )
                )
            },
            subtitleLanguages = subtitleLanguages
        )
    }
}
