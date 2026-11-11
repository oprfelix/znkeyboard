package dev.zain.znkeyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 常用语: added here or kept from the keyboard's clipboard, typed from the keyboard's phrases panel. */
@Composable
internal fun PhrasesSettingsSection() {
    val context = LocalContext.current
    var phrases by remember { mutableStateOf(PhrasesStore.read(context)) }
    var draft by remember { mutableStateOf("") }
    fun save(next: List<String>) {
        PhrasesStore.write(context, next)
        phrases = PhrasesStore.read(context)
    }

    SettingsSectionCard {
        SettingsSectionHeader(
            title = stringResource(R.string.phrases_title),
            iconResId = R.drawable.ic_toolbar_phrases_24,
            trailingText = "${phrases.size}/${PhrasesStore.MAX_PHRASES}",
        )
        Text(
            text = stringResource(R.string.phrases_desc),
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(PhrasesStore.MAX_LENGTH) },
                placeholder = { Text(stringResource(R.string.phrases_new)) },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    PhrasesStore.add(context, draft)
                    phrases = PhrasesStore.read(context)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.phrases_add)) }
        }
        phrases.forEachIndexed { index, phrase ->
            if (index > 0) HorizontalDivider(color = ZnKeyboardColors.Muted.copy(alpha = 0.15f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = phrase.replace('\n', ' '),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { save(phrases.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                    enabled = index > 0,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_expand_more_24),
                        contentDescription = stringResource(R.string.phrases_move_up),
                        modifier = Modifier.size(20.dp).rotate(180f),
                    )
                }
                IconButton(onClick = { save(phrases.filterIndexed { i, _ -> i != index }) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete_24),
                        contentDescription = stringResource(R.string.phrases_delete),
                        tint = ZnKeyboardColors.DeleteContent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
