package com.dnfapps.arrmatey.newest

import com.dnfapps.arrmatey.arr.api.client.LookupParams
import com.dnfapps.arrmatey.arr.api.client.SonarrClient
import com.dnfapps.arrmatey.arr.api.model.ArrMedia
import com.dnfapps.arrmatey.arr.api.model.ArrMovie
import com.dnfapps.arrmatey.arr.api.model.ArrRelease
import com.dnfapps.arrmatey.arr.api.model.ArrSeries
import com.dnfapps.arrmatey.arr.api.model.DownloadReleasePayload
import com.dnfapps.arrmatey.arr.api.model.ProwlarrSearchResult
import com.dnfapps.arrmatey.arr.api.model.ReleaseParams
import com.dnfapps.arrmatey.datastore.InstancePreferenceStoreRepository
import com.dnfapps.arrmatey.instances.model.InstanceType
import com.dnfapps.arrmatey.instances.repository.ArrInstanceRepository
import com.dnfapps.arrmatey.instances.repository.InstanceManager
import com.dnfapps.arrmatey.instances.repository.ProwlarrInstanceRepository
import com.dnfapps.networking.NetworkResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/** What "Download this release" did, for the confirmation shown to the user. */
sealed interface GrabNewestResult {
    /** Radarr or Sonarr grabbed it, so it will be imported into the library. */
    data class Grabbed(
        val service: InstanceType,
        val addedToLibrary: Boolean,
    ) : GrabNewestResult

    /** Prowlarr sent it straight to the download client. Nothing will import it automatically. */
    object SentToDownloadClient : GrabNewestResult

    data class Failed(
        val message: String,
    ) : GrabNewestResult
}

/**
 * Downloads one exact release from the newest feed.
 *
 * With Radarr (movies) or Sonarr (TV) configured, the title is added there if missing
 * (monitored, the instance's saved add defaults, no automatic search), then Radarr or
 * Sonarr runs its own release search for it and grabs the result with the same release
 * name. A grab through `POST /release` is taken even when the quality profile would have
 * rejected the release, which is the point: the profile may only allow a quality the
 * release does not have. Without Radarr or Sonarr, Prowlarr sends the release to the
 * download client directly.
 */
class GrabNewestReleaseUseCase(
    private val instanceManager: InstanceManager,
    private val preferences: InstancePreferenceStoreRepository,
) {
    suspend operator fun invoke(
        item: NewestDownloadableItem,
        prowlarr: ProwlarrInstanceRepository?,
    ): GrabNewestResult {
        val release = item.release.best ?: return GrabNewestResult.Failed("This release can no longer be found.")
        val type = if (item.release.kind == ReleaseKind.Movie) InstanceType.Radarr else InstanceType.Sonarr
        val arr = instanceManager.getSelectedArrRepository(type).firstOrNull()
        if (arr == null) return grabWithProwlarr(release, prowlarr, type)

        val existing = findInLibrary(arr, item)
        val media = existing ?: addToLibrary(arr, item, type)
            ?: return GrabNewestResult.Failed("${item.displayTitle} could not be added to ${type.name}.")
        val mediaId = media.id ?: return GrabNewestResult.Failed("${type.name} did not return an id for ${item.displayTitle}.")

        val params = releaseParams(arr, item, mediaId) ?: return GrabNewestResult.Failed("Sonarr has no episode matching this release.")
        val found = when (val releases = arr.client.getReleases(params)) {
            is NetworkResult.Success -> pickRelease(release, releases.data)
            is NetworkResult.Error -> return GrabNewestResult.Failed("${type.name}'s release search failed: ${releases.message ?: "unknown error"}.")
            is NetworkResult.Loading -> null
        } ?: return GrabNewestResult.Failed(
            "${type.name}'s indexers did not return this release, so it was not downloaded. " +
                "Check that the indexer is set up in ${type.name}.",
        )

        val payload = when (params) {
            is ReleaseParams.Series -> DownloadReleasePayload.Series(
                guid = found.guid,
                indexerId = found.indexerId,
                seriesId = mediaId.toInt(),
                seasonNumber = params.seasonNumber,
                episodeId = params.episodeId,
            )
            else -> DownloadReleasePayload.Movie(guid = found.guid, indexerId = found.indexerId, movieId = mediaId.toInt())
        }
        return when (val result = arr.downloadRelease(payload)) {
            is NetworkResult.Success -> GrabNewestResult.Grabbed(type, addedToLibrary = existing == null)
            is NetworkResult.Error -> GrabNewestResult.Failed("${type.name} could not grab the release: ${result.message ?: "unknown error"}.")
            is NetworkResult.Loading -> GrabNewestResult.Failed("${type.name} did not answer.")
        }
    }

    private suspend fun grabWithProwlarr(
        release: ProwlarrSearchResult,
        prowlarr: ProwlarrInstanceRepository?,
        wanted: InstanceType,
    ): GrabNewestResult {
        val guid = release.guid
        if (prowlarr == null || guid == null) return GrabNewestResult.Failed("No ${wanted.name} or Prowlarr instance can download this release.")
        return when (val result = prowlarr.grabRelease(guid, release.indexerId)) {
            is NetworkResult.Success -> GrabNewestResult.SentToDownloadClient
            is NetworkResult.Error -> GrabNewestResult.Failed("Prowlarr could not send the release: ${result.message ?: "unknown error"}.")
            is NetworkResult.Loading -> GrabNewestResult.Failed("Prowlarr did not answer.")
        }
    }

    private fun findInLibrary(
        arr: ArrInstanceRepository,
        item: NewestDownloadableItem,
    ): ArrMedia? {
        val library = (arr.library.value as? NetworkResult.Success)?.data.orEmpty()
        return library.firstOrNull { NewestStatusResolver.matchesLibrary(item, it) }
    }

    private suspend fun addToLibrary(
        arr: ArrInstanceRepository,
        item: NewestDownloadableItem,
        type: InstanceType,
    ): ArrMedia? {
        val candidates = lookupTerms(item).firstNotNullOfOrNull { term ->
            (arr.client.lookup(LookupParams(term)) as? NetworkResult.Success)?.data?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        val found = candidates.firstOrNull { pickLookupResult(item, it) } ?: return null
        // A lookup result that Radarr or Sonarr already has carries its id.
        if (found.id != null && found.id != 0L) return found

        val prefs = preferences.getInstancePreferences(arr.instance.id).observePreferences().first()
        if (arr.qualityProfiles.value.isEmpty()) arr.refreshQualityProfiles()
        if (arr.rootFolders.value.isEmpty()) arr.refreshRootFolders()
        val profileId = prefs.addQualityProfileId ?: arr.qualityProfiles.value.firstOrNull()?.id ?: return null
        val rootFolder = prefs.addRootFolderPath ?: arr.rootFolders.value.firstOrNull()?.path ?: return null
        val toAdd: ArrMedia = when (found) {
            is ArrMovie -> found.copyForCreation(
                monitored = true,
                minimumAvailability = prefs.addMovieMinimumAvailability,
                qualityProfileId = profileId,
                rootFolderPath = rootFolder,
                tags = emptyList(),
            )
            is ArrSeries -> found.copyForCreation(
                monitor = prefs.addSeriesMonitor,
                qualityProfileId = profileId,
                seriesType = prefs.addSeriesType,
                seasonFolder = prefs.addSeriesSeasonFolder,
                rootFolderPath = rootFolder,
                tags = emptyList(),
            )
            else -> return null
        }
        return if (type == InstanceType.Radarr || type == InstanceType.Sonarr) arr.addItem(toAdd, searchOnAdd = false) else null
    }

    private suspend fun releaseParams(
        arr: ArrInstanceRepository,
        item: NewestDownloadableItem,
        mediaId: Long,
    ): ReleaseParams? {
        if (item.release.kind == ReleaseKind.Movie) return ReleaseParams.Movie(mediaId)
        val season = item.release.parsed.season
        val episode = item.release.parsed.episode
        if (episode == null || season == null) return ReleaseParams.Series(seriesId = mediaId, seasonNumber = season)
        val client = arr.client as? SonarrClient ?: return null
        val episodes = (client.getEpisodes(mediaId, season, includeEpisodeFile = false, includeImages = false) as? NetworkResult.Success)?.data
        val episodeId = episodes?.firstOrNull { it.episodeNumber == episode }?.id ?: return null
        return ReleaseParams.Series(episodeId = episodeId)
    }

    companion object {
        /** Radarr's and Sonarr's search result with the same release name as [wanted], if any. */
        fun pickRelease(
            wanted: ProwlarrSearchResult,
            releases: List<ArrRelease>,
        ): ArrRelease? {
            val title = wanted.title?.let(NewestStatusResolver::normalizeRelease) ?: return null
            val sameTitle = releases.filter { NewestStatusResolver.normalizeRelease(it.title) == title }
            return sameTitle.firstOrNull { it.guid == wanted.guid } ?: sameTitle.firstOrNull()
        }

        /** Searches to try in Radarr or Sonarr's lookup, most exact first. */
        fun lookupTerms(item: NewestDownloadableItem): List<String> {
            val match = item.match
            val name = listOfNotNull(item.displayTitle, item.displayYear?.takeIf { item.release.kind == ReleaseKind.Movie }?.toString()).joinToString(" ")
            return listOfNotNull(
                match?.id?.let { if (item.release.kind == ReleaseKind.Movie) "tmdb:$it" else null },
                match?.mediaInfo?.tvdbId?.let { if (item.release.kind == ReleaseKind.Tv) "tvdb:$it" else null },
                name,
            )
        }

        /** Whether a lookup result is the title the release names. */
        fun pickLookupResult(
            item: NewestDownloadableItem,
            candidate: ArrMedia,
        ): Boolean {
            val tmdbId = item.match?.id
            return when (candidate) {
                is ArrMovie -> if (tmdbId != null) candidate.tmdbId == tmdbId else NewestStatusResolver.matchesLibrary(item, candidate)
                is ArrSeries -> NewestStatusResolver.matchesLibrary(item, candidate)
                else -> false
            }
        }
    }
}
