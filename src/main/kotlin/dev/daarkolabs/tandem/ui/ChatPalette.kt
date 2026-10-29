package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.colorPalette

/**
 * The chat's colours beyond the global text and border colours (spec 4.2): the bubble's and boxes' fill (one step toward the text
 * colour), the diff stats' colours and the diff lines' fills, and the polish spec's two muted tiers and accent (its section 1):
 * [secondary], the body text at 0.8 alpha (the footer's mode label and model name), [dim], the theme's info text (menu headers,
 * descriptions, digits, the effort), and [accent], the theme's blue (a menu's check). [shell] colours a Bash body's command from the
 * editor scheme (polish spec 2.4) and [codeTint] is the inline code pill's text (polish spec 2.5). [codeFill] is the pill's fill:
 * [boxFill] made half transparent ([translucentOver]), so a text selection, which Compose draws under a span's background, shows
 * through it.
 */
internal data class ChatPalette(
    val bubbleFill: Color,
    val boxFill: Color,
    val added: Color,
    val removed: Color,
    val insertedFill: Color,
    val deletedFill: Color,
    val dim: Color,
    val secondary: Color,
    val accent: Color,
    val shell: ShellColors,
    val codeTint: Color,
    val codeFill: Color = boxFill,
)

/** A Bash body's colours by token kind (polish spec 2.4), and the `$ ` prompt's. */
internal data class ShellColors(
    val prompt: Color,
    val command: Color,
    val argument: Color,
    val operator: Color,
    val comment: Color,
    val plain: Color,
)

/** The editor scheme's foreground in [attributes], else [fallback] (a scheme that gives the key no foreground, polish spec 2.4). */
internal fun schemeColor(attributes: TextAttributes?, fallback: Color): Color = attributes?.foregroundColor?.toComposeColor() ?: fallback

/** The [ChatPalette] of the current theme and editor colour scheme, read again whenever the theme changes. */
@Composable
internal fun chatPalette(): ChatPalette {
    val dark = JewelTheme.isDark
    val palette = JewelTheme.colorPalette
    val colors = JewelTheme.globalColors
    val disabledBorder = colors.borders.disabled
    return remember(JewelTheme.instanceUuid) {
        val step = palette.grayOrNull(if (dark) 3 else 12) ?: disabledBorder
        val scheme = EditorColorsManager.getInstance().globalScheme
        val added = scheme.getColor(EditorColors.ADDED_LINES_COLOR)?.toComposeColor()
            ?: palette.greenOrNull(if (dark) 6 else 4) ?: Color.Unspecified
        val removed = scheme.getColor(EditorColors.DELETED_LINES_COLOR)?.toComposeColor()
            ?: palette.redOrNull(if (dark) 6 else 4) ?: Color.Unspecified
        val secondary = colors.text.normal.copy(alpha = 0.8f)
        val shell = ShellColors(
            prompt = secondary,
            command = schemeColor(
                scheme.getAttributes(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
                palette.blueOrNull(if (dark) 6 else 5) ?: colors.text.normal,
            ),
            argument = schemeColor(
                scheme.getAttributes(DefaultLanguageHighlighterColors.STRING),
                palette.greenOrNull(if (dark) 6 else 5) ?: colors.text.normal,
            ),
            operator = colors.text.normal,
            comment = schemeColor(scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT), secondary),
            plain = colors.text.normal,
        )
        ChatPalette(
            bubbleFill = step,
            boxFill = step,
            added = added,
            removed = removed,
            insertedFill = scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor?.toComposeColor() ?: added.copy(alpha = 0.15f),
            deletedFill = scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor?.toComposeColor() ?: removed.copy(alpha = 0.15f),
            dim = colors.text.info,
            secondary = secondary,
            accent = palette.blueOrNull(if (dark) 6 else 5) ?: colors.outlines.focused,
            shell = shell,
            codeTint = palette.redOrNull(if (dark) 9 else 2) ?: colors.text.error,
            codeFill = translucentOver(step, colors.panelBackground, CODE_FILL_ALPHA),
        )
    }
}

/** The inline code pill's opacity: a selection under it still shows at half strength. */
internal const val CODE_FILL_ALPHA: Float = 0.5f

/**
 * A colour at [alpha] that, drawn over [behind], looks like [target], so whatever is drawn under it (a text selection) shows through.
 * [target] itself when no colour at that [alpha] does (a channel would leave 0 to 1) or [behind] is not opaque.
 */
internal fun translucentOver(target: Color, behind: Color, alpha: Float): Color {
    if (behind.alpha < 1f || alpha <= 0f) return target
    fun channel(t: Float, b: Float): Float = (t - b * (1 - alpha)) / alpha
    val r = channel(target.red, behind.red)
    val g = channel(target.green, behind.green)
    val b = channel(target.blue, behind.blue)
    return if (listOf(r, g, b).all { it in 0f..1f }) Color(r, g, b, alpha) else target
}
