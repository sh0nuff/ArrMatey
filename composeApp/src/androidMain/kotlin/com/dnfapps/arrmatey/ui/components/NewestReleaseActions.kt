package com.dnfapps.arrmatey.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dnfapps.arrmatey.newest.GrabNewestResult
import com.dnfapps.arrmatey.newest.NewestDownloadStatus
import com.dnfapps.arrmatey.newest.NewestDownloadableItem
import com.dnfapps.arrmatey.newest.NewestDownloadableViewModel
import com.dnfapps.arrmatey.shared.*
import com.dnfapps.arrmatey.shared.MR
import com.dnfapps.arrmatey.utils.mokoString

/** A small label for a release's download status; draws nothing for [NewestDownloadStatus.None]. */
@Composable
fun NewestStatusBadge(
    status: NewestDownloadStatus?,
    modifier: Modifier = Modifier,
) {
    val text = when (status) {
        is NewestDownloadStatus.Downloading -> mokoString(MR.strings.newest_status_downloading, status.percent)
        NewestDownloadStatus.InLibrary -> mokoString(MR.strings.newest_status_in_library)
        NewestDownloadStatus.Requested -> mokoString(MR.strings.newest_status_requested)
        NewestDownloadStatus.None, null -> return
    }
    val colours = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (status is NewestDownloadStatus.Downloading) colours.primaryContainer else colours.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The plain-words message for a finished "Download this release". */
@Composable
fun grabResultMessage(result: GrabNewestResult): String = when (result) {
    is GrabNewestResult.Grabbed -> mokoString(
        if (result.addedToLibrary) MR.strings.newest_grabbed_added else MR.strings.newest_grabbed,
        result.service.name,
    )
    GrabNewestResult.SentToDownloadClient -> mokoString(MR.strings.newest_sent_to_client)
    is GrabNewestResult.Failed -> result.message
}

/**
 * The actions for one release from the newest feed: download that exact release, or open
 * the title's details when Seerr matched it. The result of a download shows in the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewestReleaseSheet(
    item: NewestDownloadableItem,
    status: NewestDownloadStatus?,
    isGrabbing: Boolean,
    result: GrabNewestResult?,
    onDownload: () -> Unit,
    onOpenDetails: (() -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    val release = item.release.best
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = listOfNotNull(item.displayTitle, item.displayYear?.toString()).joinToString(" "),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            NewestStatusBadge(status)
            release?.title?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            val details = listOfNotNull(
                release?.indexer,
                release?.seeders?.let { mokoString(MR.strings.seeders_count, it) },
                item.release.qualities.joinToString("/").ifEmpty { null },
            ).joinToString(" • ")
            if (details.isNotEmpty()) {
                Text(text = details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result != null) {
                Text(
                    text = grabResultMessage(result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result is GrabNewestResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = onDownload,
                enabled = release != null && !isGrabbing && result !is GrabNewestResult.Grabbed && result != GrabNewestResult.SentToDownloadClient,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isGrabbing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = mokoString(MR.strings.download_this_release),
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onOpenDetails != null) {
                OutlinedButton(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
                    Text(text = mokoString(MR.strings.open_details), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Shows [NewestReleaseSheet] for [item] while it is not null, wired to [viewModel]'s grab.
 * [onOpenDetails] opens the Seerr details page of a matched title.
 */
@Composable
fun NewestReleaseSheetHost(
    viewModel: NewestDownloadableViewModel,
    item: NewestDownloadableItem?,
    onDismiss: () -> Unit,
    onOpenDetails: (NewestDownloadableItem) -> Unit,
) {
    item ?: return
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val grabbingKey by viewModel.grabbingKey.collectAsStateWithLifecycle()
    val grabResult by viewModel.grabResult.collectAsStateWithLifecycle()
    NewestReleaseSheet(
        item = item,
        status = statuses[item.key],
        isGrabbing = grabbingKey == item.key,
        result = grabResult?.takeIf { it.first == item.key }?.second,
        onDownload = { viewModel.grab(item) },
        onOpenDetails = item.match?.let {
            {
                onDismiss()
                onOpenDetails(item)
            }
        },
        onDismissRequest = {
            viewModel.clearGrabResult()
            onDismiss()
        },
    )
}
