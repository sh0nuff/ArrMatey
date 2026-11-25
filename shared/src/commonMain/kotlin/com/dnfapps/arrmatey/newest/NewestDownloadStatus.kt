package com.dnfapps.arrmatey.newest

import com.dnfapps.arrmatey.arr.api.model.ArrMedia
import com.dnfapps.arrmatey.arr.api.model.ArrMovie
import com.dnfapps.arrmatey.arr.api.model.ArrSeries
import com.dnfapps.arrmatey.arr.api.model.QueueItem
import com.dnfapps.arrmatey.arr.api.model.RadarrQueueItem
import com.dnfapps.arrmatey.arr.api.model.SonarrQueueItem
import com.dnfapps.arrmatey.arr.api.model.hasDownloadedFiles
import kotlin.math.roundToInt

/** Where a Prowlarr release's title stands, shown as a badge on its card. */
sealed interface NewestDownloadStatus {
    data class Downloading(
        val percent: Int,
    ) : NewestDownloadStatus

    object InLibrary : NewestDownloadStatus

    object Requested : NewestDownloadStatus

    object None : NewestDownloadStatus
}

/**
 * Works out [NewestDownloadStatus] from Radarr and Sonarr's queues and libraries and the
 * Seerr match. A queue entry wins, then a file in the library, then a request.
 */
object NewestStatusResolver {
    private const val SEERR_PENDING = 2
    private const val SEERR_PROCESSING = 3
    private const val SEERR_PARTIAL = 4

    fun resolve(
        item: NewestDownloadableItem,
        queue: List<QueueItem>,
        library: List<ArrMedia>,
    ): NewestDownloadStatus {
        queue.firstOrNull { matchesQueue(item, it) }?.let { entry ->
            return NewestDownloadStatus.Downloading(entry.progressPercent.coerceIn(0f, 100f).roundToInt())
        }
        val owned = library.firstOrNull { matchesLibrary(item, it) }
        val seerrStatus = item.match?.mediaInfo?.status
        return when {
            item.isInLibrary && !NewestStatusResolver.isNewSeason(item) -> NewestDownloadStatus.InLibrary
            owned != null && owned.hasDownloadedFiles && item.release.kind == ReleaseKind.Movie -> NewestDownloadStatus.InLibrary
            owned != null && hasSeasonFiles(owned, item.release.parsed.season) -> NewestDownloadStatus.InLibrary
            seerrStatus == SEERR_PENDING || seerrStatus == SEERR_PROCESSING -> NewestDownloadStatus.Requested
            seerrStatus == SEERR_PARTIAL && item.release.kind == ReleaseKind.Movie -> NewestDownloadStatus.Requested
            owned != null && owned.monitored -> NewestDownloadStatus.Requested
            else -> NewestDownloadStatus.None
        }
    }

    /** True for a TV release of a season Seerr does not list as available. */
    fun isNewSeason(item: NewestDownloadableItem): Boolean {
        val season = item.release.parsed.season ?: return false
        val seasons = item.match?.mediaInfo?.seasons.orEmpty()
        if (item.release.kind != ReleaseKind.Tv || seasons.isEmpty()) return false
        val available = seasons.filter { it.status == NewestDownloadableItem.AVAILABLE_STATUS }.maxOfOrNull { it.seasonNumber } ?: 0
        return season > available
    }

    fun matchesQueue(
        item: NewestDownloadableItem,
        entry: QueueItem,
    ): Boolean {
        val tmdbId = item.match?.id
        val idMatch = when (entry) {
            is RadarrQueueItem -> item.release.kind == ReleaseKind.Movie && tmdbId != null && entry.movie?.tmdbId == tmdbId
            is SonarrQueueItem ->
                item.release.kind == ReleaseKind.Tv &&
                    sameSeries(item, entry.series) &&
                    (item.release.parsed.season == null || entry.seasonNumber == null || entry.seasonNumber == item.release.parsed.season)
            else -> false
        }
        if (idMatch) return true
        val queued = entry.title?.let(::normalizeRelease) ?: return false
        return item.release.sources.any { source -> source.title?.let(::normalizeRelease) == queued }
    }

    fun matchesLibrary(
        item: NewestDownloadableItem,
        media: ArrMedia,
    ): Boolean = when (media) {
        is ArrMovie ->
            item.release.kind == ReleaseKind.Movie &&
                (item.match?.id?.let { it == media.tmdbId } ?: sameTitle(item, media.title, media.year))
        is ArrSeries -> item.release.kind == ReleaseKind.Tv && sameSeries(item, media)
        else -> false
    }

    /** Release names compared without case, separators or a trailing file extension. */
    fun normalizeRelease(title: String): String = TitleMatcher.normalize(title.removeSuffix(".mkv").removeSuffix(".mp4"))

    private fun sameSeries(
        item: NewestDownloadableItem,
        series: ArrSeries?,
    ): Boolean {
        series ?: return false
        val match = item.match
        if (match != null) {
            if (series.tmdbId != null && series.tmdbId == match.id) return true
            if (match.mediaInfo?.tvdbId != null && match.mediaInfo.tvdbId == series.tvdbId) return true
        }
        return sameTitle(item, series.title, null)
    }

    private fun sameTitle(
        item: NewestDownloadableItem,
        title: String?,
        year: Int?,
    ): Boolean {
        title ?: return false
        val yearOk = year == null || item.release.parsed.year == null || kotlin.math.abs(year - item.release.parsed.year) <= 1
        return yearOk && TitleMatcher.similarity(item.release.parsed.title, title) >= TitleMatcher.MIN_SIMILARITY
    }

    private fun hasSeasonFiles(
        media: ArrMedia,
        season: Int?,
    ): Boolean {
        if (media !is ArrSeries) return false
        if (season == null) return media.episodeFileCount > 0
        return media.seasons.any { it.seasonNumber == season && (it.statistics?.episodeFileCount ?: 0) > 0 }
    }
}
