package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.UpdateDefaults

/** Shown at the top of the Typing page while a newer release is out and not dismissed. */
@Composable
internal fun UpdateBanner(version: String, onView: () -> Unit, onDismiss: () -> Unit) {
    SettingsSectionCard(verticalSpacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.update_available, version),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onView) { Text(stringResource(R.string.update_view)) }
            IconButton(onClick = onDismiss) {
                Icon(
                    painterResource(R.drawable.ic_close_24),
                    contentDescription = stringResource(R.string.update_later),
                    tint = ZnKeyboardColors.Muted,
                )
            }
        }
    }
}

/** Bottom of the Backup page: version, manual and automatic update checks, project link. */
@Composable
internal fun AboutUpdateSection(
    currentVersion: String,
    availableRelease: UpdateChecker.Release?,
    lastCheckedAt: Long,
    checking: Boolean,
    autoCheck: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onView: (UpdateChecker.Release) -> Unit,
) {
    val context = LocalContext.current
    SettingsSectionCard(verticalSpacing = 12.dp) {
        SettingsSectionHeader(
            title = stringResource(R.string.about_title),
            iconResId = R.drawable.ic_restart_24,
            trailingText = stringResource(R.string.about_version, currentVersion),
        )

        Text(
            text = when {
                availableRelease != null -> stringResource(R.string.update_available, availableRelease.version)
                lastCheckedAt > System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.update_just_checked)
                lastCheckedAt > 0L -> stringResource(
                    R.string.update_last_checked,
                    DateUtils.getRelativeTimeSpanString(lastCheckedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                )
                else -> stringResource(R.string.update_never_checked)
            },
            color = if (availableRelease != null) MaterialTheme.colorScheme.primary else ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (availableRelease != null) {
                Button(onClick = { onView(availableRelease) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_view))
                }
            }
            OutlinedButton(
                onClick = onCheck,
                enabled = !checking,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.update_auto),
            description = stringResource(R.string.update_auto_desc),
            checked = autoCheck,
            onCheckedChange = onAutoCheckChange,
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { openUrl(context, UpdateDefaults.PROJECT_URL) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.about_source),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                painterResource(R.drawable.ic_open_in_new_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Release notes with Download (the APK, installed over this version) and the release page. */
@Composable
internal fun UpdateDialog(release: UpdateChecker.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = UpdateChecker.plainNotes(release.notes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                TextButton(onClick = { openUrl(context, release.pageUrl) }) {
                    Text(stringResource(R.string.update_release_page))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUrl(context, release.downloadUrl)
                onDismiss()
            }) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
