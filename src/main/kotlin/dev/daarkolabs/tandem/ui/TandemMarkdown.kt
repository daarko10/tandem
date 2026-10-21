@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.intui.markdown.bridge.styling.extensions.github.tables.create
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughRendererExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableStyling
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableRendererExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultInlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * The chat's Markdown parser (spec 4.4): bare URLs stay links, and GFM tables and strikethrough parse. The presenter keeps one and
 * runs it off the EDT.
 */
internal fun tandemMarkdownProcessor(): MarkdownProcessor =
    MarkdownProcessor(listOf(AutolinkProcessorExtension, GitHubTableProcessorExtension, GitHubStrikethroughProcessorExtension()))

/**
 * The bridge's block renderer with a copy button on every fenced code block (spec 4.4, decision 9): `Copy code`, top right,
 * while the pointer is over the fence; it puts the fence's text on the clipboard. A subclass, never interface delegation: Jewel
 * dispatches [RenderFencedCodeBlock] on the renderer itself, so a delegating wrapper's override would never run. [createCopy] and
 * [plus] are overridden too, so a copy Jewel makes keeps the button.
 */
class TandemBlockRenderer(
    styling: MarkdownStyling,
    extensions: List<MarkdownRendererExtension>,
    inline: InlineMarkdownRenderer = DefaultInlineMarkdownRenderer(extensions),
) : DefaultMarkdownBlockRenderer(styling, extensions, inline) {
    @Composable
    override fun RenderFencedCodeBlock(
        block: MarkdownBlock.CodeBlock.FencedCodeBlock,
        styling: MarkdownStyling.Code.Fenced,
        enabled: Boolean,
        modifier: Modifier,
    ) {
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Box(Modifier.hoverable(hover)) {
            super.RenderFencedCodeBlock(block, styling, enabled, modifier)
            if (hovered) {
                IconActionButton(
                    AllIconsKeys.General.InlineCopy,
                    contentDescription = "Copy code",
                    onClick = { CopyPasteManager.getInstance().setContents(StringSelection(block.content)) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }

    override fun createCopy(
        rootStyling: MarkdownStyling?,
        rendererExtensions: List<MarkdownRendererExtension>?,
        inlineRenderer: InlineMarkdownRenderer?,
    ): MarkdownBlockRenderer = TandemBlockRenderer(
        rootStyling ?: this.rootStyling,
        rendererExtensions ?: this.rendererExtensions,
        inlineRenderer ?: this.inlineRenderer,
    )

    // 0.37.0's own plus builds a DefaultMarkdownBlockRenderer directly instead of calling createCopy.
    override fun plus(extension: MarkdownRendererExtension): MarkdownBlockRenderer =
        TandemBlockRenderer(rootStyling, rendererExtensions + extension, inlineRenderer)
}

/**
 * The bridge's Markdown styling for [project] (code fences coloured by the IDE's lexers) with the GFM table and strikethrough
 * renderers and [TandemBlockRenderer], both built once per theme, and [LocalUrlHandler] for the links ([rememberUrlHandler]).
 * Inline code is the pill of polish spec 2.5 ([inlineCodeStyle]): the bridge's `MarkdownStyling.create` hands its one
 * `inlinesStyling` to the paragraph styling, which the paragraphs inside list items and block quotes use too, and the table renderer
 * reads it for its cells; headings build their own from their sizes and keep the bridge's inline code (open item 5).
 */
@Composable
internal fun TandemMarkdownStyling(project: Project, content: @Composable () -> Unit) {
    val palette = chatPalette()
    val editorFont = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Monospace
    val bodySize = JewelTheme.defaultTextStyle.fontSize
    val (styling, renderer) = remember(JewelTheme.instanceUuid) {
        val s = MarkdownStyling.create(inlinesStyling = InlinesStyling.create(inlineCode = inlineCodeStyle(palette, editorFont, bodySize)))
        val extensions = listOf(GitHubTableRendererExtension(GfmTableStyling.create(), s), GitHubStrikethroughRendererExtension)
        s to TandemBlockRenderer(s, extensions)
    }
    val onUrl = rememberUrlHandler(project)
    ProvideMarkdownStyling(project, markdownStyling = styling, markdownBlockRenderer = renderer) {
        CompositionLocalProvider(LocalUrlHandler provides onUrl, content = content)
    }
}

/**
 * The inline code pill (polish spec 2.5): [ChatPalette.codeTint] on [ChatPalette.boxFill] in the [editorFont], one point under
 * [bodySize]. A `SpanStyle` has no border or radius, so the pill has square corners (polish spec open item 17).
 */
internal fun inlineCodeStyle(palette: ChatPalette, editorFont: FontFamily, bodySize: TextUnit): SpanStyle = SpanStyle(
    color = palette.codeTint,
    background = palette.boxFill,
    fontFamily = editorFont,
    fontSize = if (bodySize.isSp) (bodySize.value - 1).sp else bodySize,
)

/**
 * What a click on a Markdown link does; every `Markdown(...)` in the chat passes it as `onUrlClick`. Outside
 * [TandemMarkdownStyling] a click does nothing.
 */
internal val LocalUrlHandler: ProvidableCompositionLocal<(String) -> Unit> = staticCompositionLocalOf { {} }

/** Where a Markdown link leads ([linkTarget]). */
internal sealed interface LinkTarget {
    /** A web page, opened in the browser. */
    data class Browse(val url: String) : LinkTarget

    /** A file inside the project, opened in the editor. */
    data class Open(val path: Path) : LinkTarget

    /** Nowhere: the link opens nothing. */
    data object None : LinkTarget
}

private val WEB = Regex("^https?://", RegexOption.IGNORE_CASE)
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val LINE_SUFFIX = Regex(":\\d+(:\\d+)?$")

/**
 * Where a link to [url] in a reply leads (spec 4.4): an `http(s)` URL to the browser; a path relative to [projectBase] that stays
 * inside it to the editor, without the `#L42` fragment or the `:42` / `:42:7` line suffix Claude writes after file references.
 * Anything else opens nothing: a blank link, another scheme (`mailto:`, `file:`), an absolute path, a path that climbs out of the
 * project with `..`, the project directory itself, a path the platform cannot parse, and any path when there is no project base.
 * The URL comes from the model, so it is never trusted to name a file outside the project.
 */
internal fun linkTarget(url: String, projectBase: Path?): LinkTarget {
    if (url.isBlank()) return LinkTarget.None
    if (WEB.containsMatchIn(url)) return LinkTarget.Browse(url)
    val path = url.substringBefore('#').replace(LINE_SUFFIX, "")
    if (SCHEME.containsMatchIn(path) || projectBase == null) return LinkTarget.None
    // Path.of throws InvalidPathException for a path the platform cannot parse (a NUL, a reserved character on Windows).
    val relative = runCatching { Path.of(path) }.getOrNull() ?: return LinkTarget.None
    if (relative.isAbsolute) return LinkTarget.None
    val base = projectBase.normalize()
    val resolved = base.resolve(relative).normalize()
    return if (resolved.startsWith(base) && resolved != base) LinkTarget.Open(resolved) else LinkTarget.None
}

/**
 * The click handler for [project]'s Markdown links ([linkTarget]): a web page opens in the browser, a project file in the editor
 * ([openInEditor]: the VFS lookup runs on a background thread, never on the EDT), anything else does nothing.
 */
@Composable
internal fun rememberUrlHandler(project: Project): (String) -> Unit = remember(project) {
    { url ->
        val base = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
        when (val target = linkTarget(url, base)) {
            is LinkTarget.Browse -> BrowserUtil.browse(target.url)
            is LinkTarget.Open -> openInEditor(project, target.path)
            LinkTarget.None -> Unit
        }
    }
}
