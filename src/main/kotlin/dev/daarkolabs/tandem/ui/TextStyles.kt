package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.copyWithSize

/** The body text size, 13 sp when the theme gives none. */
@Composable
private fun bodySize(): TextUnit = JewelTheme.defaultTextStyle.fontSize.takeIf { it.isSp } ?: 13.sp

/** Body text one point smaller (spec 4.2): the working row, the turn footer, sub-lines. */
@Composable
internal fun smallTextStyle(): TextStyle = JewelTheme.defaultTextStyle.copyWithSize((bodySize().value - 1).sp)

/**
 * The editor font one point smaller than body text, with the body's line height so that a mono span never changes a row's height
 * (spec 4.2): paths, commands and bodies.
 */
@Composable
internal fun monoTextStyle(): TextStyle =
    JewelTheme.editorTextStyle.copy(fontSize = (bodySize().value - 1).sp, lineHeight = JewelTheme.defaultTextStyle.lineHeight)

/** One line of [style]: its line height, else 1.4 times its size (12 sp when it has none). */
@Composable
internal fun lineHeight(style: TextStyle): Dp {
    val size = style.fontSize.takeIf { it.isSp } ?: 12.sp
    val line = when {
        style.lineHeight.isSp -> style.lineHeight
        style.lineHeight.isEm -> size * style.lineHeight.value
        else -> size * 1.4f
    }
    return with(LocalDensity.current) { line.toDp() }
}
