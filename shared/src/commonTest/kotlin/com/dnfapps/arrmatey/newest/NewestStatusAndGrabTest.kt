package com.dnfapps.arrmatey.newest

import com.dnfapps.arrmatey.arr.api.model.ArrMovie
import com.dnfapps.arrmatey.arr.api.model.Language
import com.dnfapps.arrmatey.arr.api.model.MediaStatus
import com.dnfapps.arrmatey.arr.api.model.MovieRelease
import com.dnfapps.arrmatey.arr.api.model.ProwlarrSearchResult
import com.dnfapps.arrmatey.arr.api.model.Quality
import com.dnfapps.arrmatey.arr.api.model.QualityInfo
import com.dnfapps.arrmatey.arr.api.model.RadarrQueueItem
import com.dnfapps.arrmatey.arr.api.model.ReleaseProtocol
import com.dnfapps.arrmatey.arr.api.model.Revision
import com.dnfapps.arrmatey.seerr.api.model.DiscoverResult
import com.dnfapps.arrmatey.seerr.api.model.MediaInfo
import com.dnfapps.arrmatey.seerr.api.model.RequestType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewestStatusAndGrabTest {
    private fun source(
        title: String,
        seeders: Int = 10,
        guid: String = title,
        publishDate: String = "2026-11-18T00:00:00Z",
    ) = ProwlarrSearchResult(
        guid = guid,
        title = title,
        indexerId = 1,
        size = 1000,
        age = 0,
        ageHours = 1.0,
        ageMinutes = 60.0,
        publishDate = publishDate,
        protocol = ReleaseProtocol.Torrent,
        seeders = seeders,
    )

    private fun item(
        sources: List<ProwlarrSearchResult> = listOf(source("The.Life.of.Chuck.2025.1080p.WEB-DL-GRP")),
        match: DiscoverResult? = DiscoverResult(id = 42, mediaType = RequestType.Movie, title = "The Life of Chuck", releaseDate = "2025-06-06"),
    ) = NewestDownloadableItem(
        release = NewestRelease(
            parsed = ParsedRelease("The Life of Chuck", 2025),
            kind = ReleaseKind.Movie,
            seeders = sources.maxOf { it.seeders ?: 0 },
            publishDate = sources.first().publishDate,
            ageHours = 1.0,
            qualities = listOf("1080p"),
            releaseCount = sources.size,
            sources = sources,
        ),
        match = match,
    )

    private fun movie(
        tmdbId: Long = 42,
        hasFile: Boolean = false,
        monitored: Boolean = true,
        title: String = "The Life of Chuck",
    ) = ArrMovie(
        id = 7,
        title = title,
        cleanTitle = title.lowercase().replace(" ", ""),
        originalLanguage = Language(1, "English"),
        year = 2025,
        qualityProfileId = 1,
        monitored = monitored,
        runtime = 100,
        status = MediaStatus.Released,
        tmdbId = tmdbId,
        secondaryYearSourceId = 0,
        minimumAvailability = MediaStatus.Released,
        hasFile = hasFile,
    )

    private fun queued(
        title: String?,
        movie: ArrMovie? = null,
        size: Float = 100f,
        left: Float = 25f,
    ) = RadarrQueueItem(
        id = 1,
        title = title,
        protocol = ReleaseProtocol.Torrent,
        size = size,
        sizeleft = left,
        quality = QualityInfo(quality = Quality(id = 1, name = "1080p"), revision = Revision(version = 1, real = 0, isRepack = false)),
        movie = movie,
    )

    @Test
    fun testQueueMatchByTmdbIdShowsProgress() {
        val status = NewestStatusResolver.resolve(item(), listOf(queued("Other.Name", movie = movie())), emptyList())
        assertEquals(NewestDownloadStatus.Downloading(75), status)
    }

    @Test
    fun testQueueMatchByReleaseTitleWithoutSeerr() {
        val status = NewestStatusResolver.resolve(item(match = null), listOf(queued("the life of chuck 2025 1080p web dl grp")), emptyList())
        assertEquals(NewestDownloadStatus.Downloading(75), status)
    }

    @Test
    fun testOtherQueueEntriesDoNotMatch() {
        val status = NewestStatusResolver.resolve(item(), listOf(queued("Another.Movie.2025.1080p", movie = movie(tmdbId = 9))), emptyList())
        assertEquals(NewestDownloadStatus.None, status)
    }

    @Test
    fun testLibraryWithFileIsInLibrary() {
        assertEquals(NewestDownloadStatus.InLibrary, NewestStatusResolver.resolve(item(), emptyList(), listOf(movie(hasFile = true))))
    }

    @Test
    fun testMonitoredWithoutFileIsRequested() {
        assertEquals(NewestDownloadStatus.Requested, NewestStatusResolver.resolve(item(), emptyList(), listOf(movie())))
    }

    @Test
    fun testSeerrPendingIsRequested() {
        val pending = DiscoverResult(id = 42, mediaType = RequestType.Movie, title = "The Life of Chuck", mediaInfo = MediaInfo(status = 2))
        assertEquals(NewestDownloadStatus.Requested, NewestStatusResolver.resolve(item(match = pending), emptyList(), emptyList()))
    }

    @Test
    fun testBestSourceHasMostSeeders() {
        val group = item(sources = listOf(source("A.2025.720p", seeders = 5), source("A.2025.2160p", seeders = 40), source("A.2025.1080p", seeders = 12)))
        assertEquals("A.2025.2160p", group.release.best?.title)
    }

    @Test
    fun testPickReleaseMatchesNameNotGuid() {
        val wanted = source("The.Life.of.Chuck.2025.1080p.WEB-DL-GRP", guid = "prowlarr-guid")
        val radarrResults = listOf(
            MovieRelease(guid = "a", indexerId = 3, title = "The.Life.of.Chuck.2025.2160p.WEB-DL-GRP", protocol = ReleaseProtocol.Torrent),
            MovieRelease(guid = "b", indexerId = 3, title = "The Life of Chuck 2025 1080p WEB-DL-GRP", protocol = ReleaseProtocol.Torrent),
        )
        assertEquals("b", GrabNewestReleaseUseCase.pickRelease(wanted, radarrResults)?.guid)
        assertNull(GrabNewestReleaseUseCase.pickRelease(source("Something.Else.2025"), radarrResults))
    }

    @Test
    fun testLookupTermsPreferTmdbForMovies() {
        assertEquals(listOf("tmdb:42", "The Life of Chuck 2025"), GrabNewestReleaseUseCase.lookupTerms(item()))
        assertEquals(listOf("The Life of Chuck 2025"), GrabNewestReleaseUseCase.lookupTerms(item(match = null)))
    }

    @Test
    fun testLookupResultMatchesByTmdbId() {
        assertEquals(true, GrabNewestReleaseUseCase.pickLookupResult(item(), movie()))
        assertEquals(false, GrabNewestReleaseUseCase.pickLookupResult(item(), movie(tmdbId = 1)))
    }
}
