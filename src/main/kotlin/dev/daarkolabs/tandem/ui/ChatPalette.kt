package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.colorPalette

/**
 * The chat's colours beyond the global text and border colours (spec 4.2): the bubble's and boxes' fill (one step toward the text
 * colour), the diff stats' colours and the diff lines' fills.
 */
internal data class ChatPalette(
    val bubbleFill: Color,
    val boxFill: Color,
    val added: Color,
    val removed: Color,
    val insertedFill: Color,
    val deletedFill: Color,
)

/** The [ChatPalette] of the current theme and editor colour scheme, read again whenever the theme changes. */
@Composable
internal fun chatPalette(): ChatPalette {
    val dark = JewelTheme.isDark
    val palette = JewelTheme.colorPalette
    val disabledBorder = JewelTheme.globalColors.borders.disabled
    return remember(JewelTheme.instanceUuid) {
        val step = palette.grayOrNull(if (dark) 3 else 12) ?: disabledBorder
        val scheme = EditorColorsManager.getInstance().globalScheme
        val added = scheme.getColor(EditorColors.ADDED_LINES_COLOR)?.toComposeColor()
            ?: palette.greenOrNull(if (dark) 6 else 4) ?: Color.Unspecified
        val removed = scheme.getColor(EditorColors.DELETED_LINES_COLOR)?.toComposeColor()
            ?: palette.redOrNull(if (dark) 6 else 4) ?: Color.Unspecified
        ChatPalette(
            bubbleFill = step,
            boxFill = step,
            added = added,
            removed = removed,
            insertedFill = scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor?.toComposeColor() ?: added.copy(alpha = 0.15f),
            deletedFill = scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor?.toComposeColor() ?: removed.copy(alpha = 0.15f),
        )
    }
}
