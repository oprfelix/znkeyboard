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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/**
 * Top of the About page: the app icon, name and version with the update status, then the update
 * and usage switches, then the website and its mirror.
 */
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
    anonymousStats: Boolean,
    onAnonymousStatsChange: (Boolean) -> Unit,
) {
    SettingsCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon()
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.about_version, currentVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = ZnKeyboardColors.Muted,
            )
            val statusColor = if (availableRelease != null) ZnKeyboardColors.Accent else ZnKeyboardColors.Success
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(statusColor.copy(alpha = 0.13f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
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
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                if (availableRelease != null) {
                    Button(onClick = { onView(availableRelease) }) { Text(stringResource(R.string.update_view)) }
                }
                FilledTonalButton(onClick = onCheck, enabled = !checking) {
                    Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
                }
            }
        }
    }

    SettingsGroup(
        label = stringResource(R.string.group_updates),
        items = listOf(
            {
                SwitchItem(
                    iconResId = R.drawable.ic_restart_24,
                    title = stringResource(R.string.update_auto),
                    description = stringResource(R.string.update_auto_desc),
                    checked = autoCheck,
                    onCheckedChange = onAutoCheckChange,
                )
            },
            {
                SwitchItem(
                    iconResId = R.drawable.ic_bar_chart_24,
                    title = stringResource(R.string.usage_stats),
                    description = stringResource(R.string.usage_stats_desc),
                    checked = anonymousStats,
                    onCheckedChange = onAnonymousStatsChange,
                )
            },
        ),
    )

    SettingsGroup(
        label = stringResource(R.string.group_links),
        items = listOf(
            { LinkItem(R.drawable.ic_language_24, stringResource(R.string.about_website), UpdateDefaults.WEBSITE_URL) },
            { LinkItem(R.drawable.ic_code_24, stringResource(R.string.about_mirror), UpdateDefaults.MIRROR_URL) },
        ),
    )
}

/** The launcher icon: its foreground on white, cropped to the adaptive icon's visible part. */
@Composable
private fun AppIcon() {
    val shape = RoundedCornerShape(24.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .shadow(10.dp, shape, ambientColor = Color(0x552850C8), spotColor = Color(0x552850C8))
            .background(Color.White, shape)
            .clip(shape),
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            // 108 dp of artwork shows 72 in the mask, so draw it 1.5 times the tile.
            modifier = Modifier.requiredSize(120.dp),
        )
    }
}

@Composable
private fun SwitchItem(
    iconResId: Int,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsSectionHeader(
            title = title,
            iconResId = iconResId,
            subtitle = description,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LinkItem(iconResId: Int, label: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, url) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsSectionHeader(title = label, iconResId = iconResId, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_open_in_new_24),
            contentDescription = null,
            tint = ZnKeyboardColors.Muted.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Release notes with Update Now: the APK is downloaded here and handed to the system installer,
 * which asks to confirm. Settings and the API key stay. The release page stays one tap away.
 */
@Composable
internal fun UpdateDialog(release: UpdateChecker.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    val busy = job?.isActive == true

    fun start() {
        status = null
        fraction = 0f
        job = scope.launch {
            when (val outcome = UpdateInstaller.install(context, release) { value -> fraction = value }) {
                UpdateInstaller.Outcome.Started -> status = context.getString(R.string.update_install_confirm)
                UpdateInstaller.Outcome.NeedsPermission -> status = context.getString(R.string.update_install_permission)
                is UpdateInstaller.Outcome.Failed -> status = outcome.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = UpdateChecker.plainNotes(release.notes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (busy) {
                    Text(stringResource(R.string.update_install_downloading), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                TextButton(onClick = { openUrl(context, release.pageUrl) }) {
                    Text(stringResource(R.string.update_release_page))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::start, enabled = !busy) { Text(stringResource(R.string.update_now)) }
        },
        dismissButton = {
            TextButton(onClick = {
                job?.cancel()
                onDismiss()
            }) { Text(stringResource(if (busy) R.string.update_install_cancel else R.string.update_later)) }
        },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
