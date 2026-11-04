package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.AboutDefaults

/** Bottom of the Backup page: version, the anonymous usage ping, the website and the privacy policy. */
@Composable
internal fun AboutSection(
    currentVersion: String,
    anonymousStats: Boolean,
    onAnonymousStatsChange: (Boolean) -> Unit,
) {
    SettingsSectionCard(verticalSpacing = 12.dp) {
        SettingsSectionHeader(
            title = stringResource(R.string.about_title),
            iconResId = R.drawable.ic_restart_24,
            trailingText = stringResource(R.string.about_version, currentVersion),
        )

        SettingsSwitchRow(
            title = stringResource(R.string.usage_stats),
            description = stringResource(R.string.usage_stats_desc),
            checked = anonymousStats,
            onCheckedChange = onAnonymousStatsChange,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AboutLink(stringResource(R.string.about_website), AboutDefaults.WEBSITE_URL)
            AboutLink(stringResource(R.string.about_privacy), AboutDefaults.PRIVACY_URL)
        }
    }
}

@Composable
private fun AboutLink(label: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { openUrl(context, url) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
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

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
