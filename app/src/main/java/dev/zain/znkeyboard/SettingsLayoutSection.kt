package dev.zain.znkeyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zain.znkeyboard.KeyboardSettings.ChineseKeyboard

/**
 * 键盘布局, its own card as on iOS, folded with the current layout as its summary: the three
 * Chinese keyboards drawn as small keyboards, the chosen one outlined.
 */
@Composable
internal fun KeyboardLayoutSection() {
    val context = LocalContext.current
    var keyboard by remember { mutableStateOf(KeyboardSettings.readChineseKeyboard(context)) }
    CollapsibleSettingsSection(
        title = stringResource(R.string.chinese_keyboard_section),
        iconResId = R.drawable.ic_keyboard_24,
        summary = stringResource(layoutName(keyboard)),
        verticalSpacing = 14.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            for (option in ChineseKeyboard.entries) {
                LayoutCard(
                    option = option,
                    selected = keyboard == option,
                    onClick = {
                        keyboard = option
                        KeyboardSettings.saveChineseKeyboard(context, option)
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.chinese_keyboard_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun layoutName(option: ChineseKeyboard) = when (option) {
    ChineseKeyboard.Qwerty -> R.string.chinese_keyboard_qwerty
    ChineseKeyboard.NineKeyPinyin -> R.string.chinese_keyboard_nine
    ChineseKeyboard.NineKeyStroke -> R.string.chinese_keyboard_stroke
}

@Composable
private fun RowScope.LayoutCard(option: ChineseKeyboard, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(2.dp, if (selected) accent else Color.Transparent),
        modifier = Modifier.weight(1f).clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                    .padding(6.dp),
            ) {
                LayoutPreview(option, if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(28.dp))
                Text(
                    text = stringResource(layoutName(option)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Rows of keys for the full keyboard; a 3 × 3 grid for 9-key, its stroke keys marked; a wide space under both. */
@Composable
private fun LayoutPreview(option: ChineseKeyboard, keyColor: Color) {
    @Composable
    fun RowScope.key(weight: Float = 1f, label: String? = null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(weight).fillMaxSize().background(keyColor, RoundedCornerShape(2.dp)),
        ) {
            if (label != null) Text(label, fontSize = 7.sp, lineHeight = 7.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxSize()) {
        when (option) {
            ChineseKeyboard.Qwerty -> for ((count, inset) in listOf(10 to 0, 9 to 3, 7 to 9)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f).padding(horizontal = inset.dp)) {
                    repeat(count) { key() }
                }
            }
            else -> {
                val strokes = listOf("一", "丨", "丿", "丶", "乛", "＊", "", "", "")
                for (row in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f).width(78.dp).align(Alignment.CenterHorizontally)) {
                        repeat(3) { column ->
                            key(label = strokes[row * 3 + column].takeIf { option == ChineseKeyboard.NineKeyStroke && it.isNotEmpty() })
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (option == ChineseKeyboard.Qwerty) 2.dp else 3.dp),
            modifier = Modifier.weight(1f).then(
                if (option == ChineseKeyboard.Qwerty) Modifier.fillMaxWidth() else Modifier.width(78.dp).align(Alignment.CenterHorizontally),
            ),
        ) {
            key(1f)
            key(4f)
            key(1f)
        }
    }
}
