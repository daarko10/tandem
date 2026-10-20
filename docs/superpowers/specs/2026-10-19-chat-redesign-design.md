# Chat redesign (Phase 2a): the desktop app's chat feel

Status: final spec for review, 2026-10-19. Follows `docs/design.md` (architecture, hard rules) and `docs/protocol.md` (wire protocol, corrected from the probes). Built from the desktop reference `docs/superpowers/research/2026-10-18-claude-code-desktop-ui-reference.md` (cited as **ref §n**; sections 2–5 are the visual facts) and the wire probes `docs/superpowers/research/2026-10-18-protocol-probes.md` (cited as **probes §n**; fixture lines as `name:line` under `src/test/resources/fixtures/`, on-disk twins as `transcripts/<name>.jsonl`). Jewel facts are for Jewel 0.37.0 as bundled in IntelliJ 2026.1.5 (build 261.27258.48), read from the jars; `verifyPlugin` runs against every supported release, so nothing newer may be used. Structure matches `docs/superpowers/specs/2026-10-16-sessions-and-pickers-design.md`, plus Data model and Performance sections.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

Conventions: every UI string is quoted exactly as rendered; measurements are Compose `dp`; "body" is `JewelTheme.defaultTextStyle`, "mono" is `JewelTheme.editorTextStyle` one size step under the body (`fontSize = (body.fontSize.value − 1).sp`) at the body's `lineHeight` (ref §2.4: mono inside rows = *b* − 1); "secondary" is `JewelTheme.globalColors.text.info`. **[proposal]** marks a choice the reference does not settle; **[decision]** marks one of the twelve decisions taken before this spec. Where a draft of this spec disagreed with the reference or the probes, the reference and the probes won; where the reference disagrees with a decision, the decision ships and the alternative is an open item (section 13). The redactor replaces every path by `<path>` in the fixtures, so a file name in a fixture-backed test is literally `<path>`.

Branch: `feat/chat-redesign` → `feat/sessions-and-pickers`, one PR.

---

## 1. Goal

Make the Tandem chat look and behave like the Claude Code desktop app's Code tab (Sept–Oct 2026 builds; ref §2–3) for one session:

- one flat, centred column: a right-aligned user bubble, plain assistant Markdown, quiet grey tool rows with a trailing chevron, no labels, avatars or timestamps, theme tokens only, the coral spark as the single brand colour (owner: "it looks awful and very ugly");
- tool calls as the desktop's summary rows with grouping, running / done / failed / denied states and expandable detail (owner: "tool calls is fucked");
- a working indicator as the last transcript row: coral spark cycle, step word or verb, elapsed, tokens; amber while waiting on the user; red after 60 s of silence; a quiet `Worked for …` footer after the turn (owner: "NO ANIMATION WHEN WORKING");
- thinking visible: spawn with `--thinking-display summarized`; `Thinking… · N tokens` while streaming, `Thought for Ns` after, expandable to the summary; visible by default, expansion remembered per session, a setting hides the rows (owner: "NO THINKING ALSO");
- stick-to-bottom only while the user is at the bottom, wheel-up pauses, a `↓ N new` affordance, scroll on send, cards never force-scroll (owner: "it's always panning me to the bottom");
- AskUserQuestion as a question card, ExitPlanMode as a plan approval card, the permission prompt as a card docked above the composer, each sending the exact `control_response` the CLI expects (probes §2.1, §2.2);
- composer polish: placeholder, Enter sends, Shift+Enter newline, Esc stops, footer with the existing pickers;
- subagent activity nested under its Agent row (`task_*` events); protocol decoding and fixture tests for all of it; transcript reload parity wherever the on-disk file carries the data.

Everything the user sees is built by a pure, Compose-free presentation layer, `dev.daarkolabs.tandem.chat`, unit-tested against the recorded fixtures and gated by Kover like `protocol/` and `session/` (decision 10). `ui/` only renders rows.

## 2. Non-goals (deferred, with the phase that takes them)

| Deferred | Phase | Why |
|---|---|---|
| IDE edit tools, diff cards for real edits, the `Edited N files` card with `View changes` / `Revert` (ref §3.5.3) | 3 | `Edit`/`Write` stay disallowed; the `ToolBody.Diff` model from `structuredPatch` is specified (5.3) and rendered only if free |
| Todo/task tools (`TaskCreate/Update/Get/List`, `CLAUDE_CODE_ENABLE_TODO_TOOLS`), inline checklist card | 2b | needs the env var and a widget look nobody has seen (ref §6 Q8); they render as generic rows if they appear |
| Tasks/Plan side panes, PR/branch bar, side chat, usage ring, usage-limit billing card, `rate_limit_event` display | 2b | outside the chat column |
| Slash-command menu, `@file` mentions, attachments, image prompts, the queued-prompt list and `queued` caption (ref §3.1) | 2b | `/` does nothing in 2a, so the placeholder does not promise it |
| Per-chunk streaming fade-in (ref §3.2), hover row with copy + relative time, `Revert to here`, message timestamps | 2b | needs an inline-span hook into Jewel's Markdown renderer; not in the four complaints |
| Image thumbnails for image Read results, `Open in editor` for bodies over 200 lines | 2b | the data is decoded; 2a shows a `[image W×H png]` line |
| Verbose toggle (one row per call) | 2b | grouping is deterministic; the toggle is a builder flag later |
| `session_state_changed` (either env var), `compact_boundary` card, `informational` / `notification` lines, `permission_denied` without a prompt and the dim `Not run` row it would produce (ref §3.4; no fixture), `tool_progress`, `task_summary` | 2b | not needed: `busy` + `pendingPermissions` drive the indicator (ref §4.1); no fixture for the others (probes §1) |
| Background subagents (`run_in_background`), `TaskStop`, subagent sidecar transcripts on reload | 2b | no fixture (probes §2.5 Unknown) |
| Feedback field on the permission card (`Tell Claude what to do instead`), `Always allow` written to settings files | 2b | Deny sends the fixed message; "Always allow for session" stays session-scoped |
| `run` icon on one-line `bash`/`sh` fences (ref §3.2) | 2b | needs the terminal API |
| Card collapse chevron / `×` that folds a question or permission card to a title row (ref §3.5.1 `View question options`) | 2b | the cards are short; Esc already dismisses |
| Bundling Anthropic's fonts, exact Desktop hex colours | never | theme tokens only (decision 8) |

---

## 3. Slices

Three slices, sixteen tasks (section 12). Every task leaves `./gradlew check` green and the IDE Starter tests passing; a slice may land on the PR branch on its own.

| Slice | Delivers | Tasks | What stays as today until the slice lands |
|---|---|---|---|
| **A: the four complaints** | protocol fields; reducer joins and turn state; host answers behind one `answer()`; the pure `chat/` row model with labels, states, grouping, bodies, working indicator, follow state; the keyed `LazyColumn` with tool rows, the bubble, thinking rows, the working row and footer; composer keys and icons; intent-based autoscroll with `↓ N new` and the IDE scrollbar | T1–T9 | today's `PermissionPrompt` (`Allow <tool>?`) below the list, so the permission IDE test keeps passing until T10 |
| **B: cards** | permission card, question card, plan approval card, each with its fake-CLI test and host frames | T10–T12 | — |
| **C: polish and parity** | subagent nesting, Markdown extensions and the fence copy button, theme polish, transcript reload parity, the IDE Starter tests for the working indicator and autoscroll, docs, PR | T13–T16 | — |

The one structural change everything hangs on is the typed row list replacing `ChatTranscript.kt`'s Markdown flattening (T7). Grouping is a pure fold in `chat/` (T5) and ships in A, so the first slice already meets the owner's first criterion.

---

## 4. What the user sees

### 4.0 Delivery per element

| Element | Slice A (T1–T9) | Slice B (T10–T12) | Slice C (T13–T16) |
|---|---|---|---|
| Layout and tokens (4.1, 4.2) | the column, gutters, pitch, every token, no labels | — | hard-coded colours out of the header; `InlineWarningBanner` |
| User message (4.3) | right-aligned bubble, plain text | — | 8-line clamp with `Show more` |
| Assistant text (4.4) | today's renderer on `TextRow`s, cached per row | — | GFM tables and strikethrough, fence copy button |
| Thinking (4.5, 4.17) | complete | — | — |
| Tool rows, grouping, bodies (4.6, 4.7) | complete | the `WAITING` state gains its card | — |
| Subagents (4.8) | rows of subagent entries are built (hidden behind the Agent call) | — | nested rendering, progress sub-line, `From the agent` line on cards |
| Working indicator and footer (4.9, 4.10) | complete | amber `Waiting for you` becomes visible with the cards | — |
| Notices (4.11) | the same texts as `NoticeRow`s on tokens | — | icons, model-warning banner |
| Permission card (4.12) | unchanged (today's prompt) | the card | — |
| Question and plan cards (4.13, 4.14) | fall into today's generic prompt | the cards and the host answers | — |
| Composer (4.15) | complete | — | — |
| Autoscroll and scrollbar (4.16) | complete | cards never scroll (already a rule in A) | — |
| Protocol, reducer, session (5.1, 5.2, 5.5) | complete | — | reload parity (T15) |

### 4.1 Layout

- One column: `width = min(768.dp, paneWidth − 32.dp)`, centred when the pane is wider, 16 dp side gutters (ref §2.1 column rule, §2.5 Compose defaults). At 350 px the column is 318 dp; nothing is hidden, rows ellipsise.
- Vertical order: header (unchanged: sidebar toggle, title, status line) → transcript (`LazyColumn` inside `VerticallyScrollableContainer(listState)`, fills the height) → pending card, when one is open (4.12–4.14), docked above the composer at the column's width → composer box → footer row. The composer stays visible under a card (ref §2.1 dock).
- No labels (`You`, `Claude`, `Claude ▍`, `Tool result`, `Tandem` go), no avatars, no timestamps, no dot rail (ref §2.1).
- Row pitch (ref §2.5; ±2 dp tolerance on every measured value), expressed in the `RowGap` names of 5.3 so the builder and a reviewer check the same numbers: consecutive tool/thinking rows 30 dp = 24 dp row + `ROW` (6); bubble → first row `AFTER_BUBBLE` (18); tool/thinking row → reply text `BEFORE_TEXT` (20); paragraph gap inside a reply = Jewel's `MarkdownStyling.blockVerticalSpacing`; reply → working row, notice or footer `SECTION` (16; ref 17); anything → next user bubble `TURN` (24 **[proposal]**; ref §2.5 measured 48 px on the Sept 2026 Windows build, whose other gaps were also ~2–3× the Oct 2026 values, and has no Oct measurement); last content → composer 16 dp; card → composer 8 dp. The `LazyColumn` uses `contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)` and no `verticalArrangement` spacing: each row brings its own top gap from `RowGap` (5.3) so grouping can tighten it.
- Hairline borders only; nothing is outlined unless it is a card, a box or the composer (ref §2.2).
- Empty state unchanged: `Ask Claude anything about this project.` / `This session's claude process has stopped. Send a prompt to start it again.` (centred, secondary).
- Header status line keeps its single `Text` node `<model>  ·  mode: <wire>  ·  claude <version>` (the IDE test reads it) and drops `working…` (the working row replaces it). The status line duplicates the footer pickers (the reference's column has no such header: mode at the footer's left, model and effort at its right, ref §3.11); it stays in 2a as a Tandem addition **[proposal]** because `ToolWindowIdeTest` reads it; reducing it to `claude <version>` (and re-pointing the pickers test at the pickers' own text) is open item 22. The model-mismatch banner becomes Jewel `InlineWarningBanner(text = modelWarning)` (T14).

### 4.2 Visual tokens (decision 8)

| Role | Token / value | Notes |
|---|---|---|
| Page | `JewelTheme.globalColors.panelBackground` | chat column and cards |
| Body text | `JewelTheme.defaultTextStyle`, `globalColors.text.normal` | prose, bubble text |
| Secondary (tool rows, thinking rows, INFO notices, footer, chevrons, elapsed, tokens) | `globalColors.text.info` | summary and thinking rows at body size (ref §2.4: "not smaller"); the working row, the turn footer and the composer footer at body − 1 (`copyWithSize((body.fontSize.value − 1).sp)`; ref §2.4 footer/meta = *b* − 1, §2.5 12–13 px) |
| Mono (paths, commands, patterns, hosts, Bash body, JSON) | `JewelTheme.editorTextStyle.copy(fontSize = (body.fontSize.value − 1).sp, lineHeight = body.lineHeight)` | body − 1 (ref §2.4: mono inside rows = *b* − 1); explicit `lineHeight` so a mono span never changes the row height (jewel.md risk) |
| Failed row, ERROR notice | `globalColors.text.error` | whole row |
| Warning (WARNING notice, amber working row, the `WAITING` row label, the permission card's dot) | `globalColors.text.warning` | |
| Denied / interrupted rows | `globalColors.text.disabled` | |
| User bubble fill, group box hover | `JewelTheme.colorPalette.grayOrNull(if (JewelTheme.isDark) 3 else 12) ?: globalColors.borders.disabled` | one small step toward the text colour (ref §2.3 `--cds-alpha-1`); tune in `runIde` (open item 9) |
| Card and group-box border | `globalColors.borders.normal`, 1 dp, radius 8 dp; focused card `borders.focused` | no fill (ref §2.2) |
| Command / code / output box fill | `colorPalette.grayOrNull(if (isDark) 2 else 13) ?: panelBackground` | radius 6 dp (ref §2.2 gray-830) |
| Diff `+N` / `-N` text | `EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.ADDED_LINES_COLOR / DELETED_LINES_COLOR)?.toComposeColor()`, fallback `colorPalette.green(if (isDark) 6 else 4)` / `red(…)` | read in `remember(JewelTheme.instanceUuid)` (jewel.md 3) |
| Diff row fills (`ToolBody.Diff` if rendered; the Edit permission payload) | `globalScheme.getAttributes(DiffColors.DIFF_INSERTED / DIFF_DELETED)?.backgroundColor?.toComposeColor()`, fallback the `+N`/`-N` colours at 15 % alpha | same `remember` |
| Links, inline code, fences, tables | the bridge `ProvideMarkdownStyling(project)` | unchanged |
| **Coral spark** | `Color(0xFFD97757)`, constant `TandemColors.spark` in `ui/` | the only literal colour in the chat column (the drawer scrim, Phase 1, outside the column, keeps `Color.Black.copy(alpha)`); used by the spark glyph only (tool rows carry no dot in 2a, 4.6.1) |
| Chevron | `AllIconsKeys.General.ChevronRight`, 16 dp, tint secondary, `Modifier.rotate(angle)` with `animateFloatAsState(if (open) 90f else 0f, tween(150))` | trailing, 8 dp after the text (ref §3.4) |

Every token is read in composition or in `remember(JewelTheme.instanceUuid) { … }`; nothing from the theme is stored in a row, a builder or a companion object (jewel.md 7), so a LaF switch recomposes everything through `SwingBridgeTheme`. `Color(0xFFB3261E)`/`Color.White` in `SessionHeader.kt` go in T14; the drawer scrim keeps `Color.Black.copy(alpha)` (a shade, not a colour).

### 4.3 User message

Right-aligned filled bubble: fill per 4.2, radius 8 dp, padding 12 dp × 10 dp, width wraps the text up to 85 % of the column, right edge flush with the column (ref §3.1). Text is **plain** (`Text` in a `SelectionContainer`), line breaks preserved, never Markdown (ref §3.1 proposal: a `#` must not become a heading). A prompt taller than 8 lines is clamped at 160 dp with a bottom fade and a `Show more` `Link` under it (T14; before that it shows unclamped). The fade is one helper, `Modifier.bottomFade(height: Dp)` in `ui/`: `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0.7f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) }` on the clipped box; the offscreen layer is required, because `DstIn` drawn into the shared surface multiplies the alpha of everything already painted there (no fade on an opaque surface, a hole on a transparent `ComposePanel`; jewel.md 6). 4.7 and 4.14 reuse the same modifier. Prompts reloaded from a transcript as `/name args` or `!cmd` render as the same bubble in mono **[proposal]** (no capture shows a reloaded slash command). One `Text` node, so IDE tests keep matching the prompt text exactly.

```
                                       ┌──────────────────────────────────────┐
                                       │ cart.spec.ts is flaky on CI. Fix it. │
                                       └──────────────────────────────────────┘
```

### 4.4 Assistant text

Plain Markdown flush with the column's left edge, no bubble, no label (ref §3.2), rendered with `Markdown(blocks, markdown, Modifier.fillMaxWidth(), selectable = true)` from blocks parsed off the EDT (9). Interstitial sentences between tool rows are plain text at the same margin. Headings, bullets and paragraph gaps are the bridge `MarkdownStyling.create()` defaults; ref §3.2's compact heading scale (h1/h2/h3 = *b* + 2 / *b* + 1 / *b*) is a proposal with no capture behind it and is not applied in 2a.

T14 declares the bundled GFM modules in both `build.gradle.kts` (`bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")`, `…gfmStrikethrough`) and `plugin.xml` (`<module name="intellij.platform.jewel.markdown.extensions.gfmTables"/>`, `…gfmStrikethrough`), builds `MarkdownProcessor(listOf(AutolinkProcessorExtension, GitHubTableProcessorExtension, GitHubStrikethroughProcessorExtension()))` (the strikethrough processor is a class with a no-arg constructor; the table processor and the strikethrough renderer are objects; verified in the jars) and the block renderer `TandemBlockRenderer(styling, listOf(GitHubTableRendererExtension(GfmTableStyling.create(), styling), GitHubStrikethroughRendererExtension))` (the subclass below), where `val styling = remember(JewelTheme.instanceUuid) { MarkdownStyling.create() }` is the one object passed to `ProvideMarkdownStyling(project, markdownStyling = styling, markdownBlockRenderer = renderer)` and to the table extension. Without the button the renderer would be `MarkdownBlockRenderer.create(styling, extensions)`, which is **not** a companion member (the core `Companion` is empty): it is the extension `org.jetbrains.jewel.intui.markdown.bridge.create` on the Companion in the bridge jar and does nothing but `DefaultMarkdownBlockRenderer(styling, extensions, inlineRenderer)`; `GfmTableStyling.create()` lives in `org.jetbrains.jewel.intui.markdown.bridge.styling.extensions.github.tables` (`BridgeGitHubTableStylingKt`); both imports are verified in the 2026.1.5 jars. Tables render through `GfmTableStyling.create()` defaults (1 dp borders, header fill; ref §3.2).

Fence copy button (decision 9): `class TandemBlockRenderer(styling: MarkdownStyling, extensions: List<MarkdownRendererExtension>, inline: InlineMarkdownRenderer = DefaultInlineMarkdownRenderer(extensions)) : DefaultMarkdownBlockRenderer(styling, extensions, inline)` (the class and its `RenderFencedCodeBlock`, `createCopy` and `plus` are open in 0.37.0; the bridge `MarkdownBlockRenderer.create` only calls this constructor; verified in the 2026.1.5 jars) overrides `@Composable RenderFencedCodeBlock(block: FencedCodeBlock, styling: MarkdownStyling.Code.Fenced, enabled: Boolean, modifier: Modifier)` with a `Box` holding `super.RenderFencedCodeBlock(block, styling, enabled, modifier)` (keeps `LocalCodeHighlighter`) and an `IconActionButton(AllIconsKeys.General.InlineCopy, contentDescription = "Copy code", onClick = { CopyPasteManager.getInstance().setContents(StringSelection(block.content)) })` at `TopEnd`, visible on hover; it overrides `createCopy(…)` and `plus(ext)` to return a `TandemBlockRenderer`, so a copy Jewel makes keeps the button. Interface delegation (`class … : MarkdownBlockRenderer by base`) must **not** be used: `Markdown()` enters the renderer through `MarkdownBlockRenderer.RenderBlock`, a delegating wrapper forwards that to `base.RenderBlock`, and `DefaultMarkdownBlockRenderer.RenderBlock` dispatches `RenderFencedCodeBlock` on itself (`invokevirtual` on `base`), so the wrapper's override compiles, passes a "does it compile" check and never runs (the jewel.md 4 sketch has that flaw). The subclass is a few lines, so it is in scope (T14). `verifyPlugin` checks the built ZIP's binary compatibility against 0.41.0 (2026.2.3), not compilation: if it reports the subclass, drop the button and list it for 2b. Checklist 10's manual click (the clipboard equals the fence) is the verification (open item 7).

Links: `http(s)` → `BrowserUtil.browse`; a project-relative path → on `Dispatchers.IO` in `TandemProjectScope`: `LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)` (a lookup of a path the VFS has not cached stats the disk, so it never runs on the EDT: hard rule 1); then `OpenFileDescriptor(project, file).navigate(true)` on the EDT, the same path as `Open plan file` (4.14); a miss does nothing and logs the miss at debug without the path.

### 4.5 Thinking row (decision 2)

Spawn adds `--thinking-display summarized` (probes §2.3), so summaries stream. A muted row drawn like a tool row, produced for every `AssistantBlock.Thinking` of a main-thread message when `TandemSettings.showThinking` (default `true`) is on:

| State | Row text | Chevron | Body when expanded |
|---|---|---|---|
| streaming | `Thinking… · <n> tokens` with `n` = the latest `system/thinking_tokens.estimated_tokens` or `thinking_delta.estimated_tokens` (`think-default:9-14`); `Thinking…` alone before the first count; no dot or bullet (ref §3.3: Desktop draws none; the working row below is the busy signal) | only when summary text has arrived | the summary so far, plain `Text` paragraphs, secondary |
| done | `Thought for <s>s`, `s = round(thinking_duration_ms / 1000)` half up (`think-display-summarized:34`: 1991 → `Thought for 2s`; `0s` is allowed, ref §3.3); when the line lacks the field (older CLI) the host clock between the block's `content_block_start` and the complete line; else `Thinking` (noun) | when the summary is non-empty | the summary split on `\n\n`, one `Text` per paragraph, secondary; never parsed as Markdown |
| done, from disk | the same: `transcripts/think-default.jsonl` and `think-display-summarized.jsonl` carry `thinkingDurationMs` (2143, 1991; 12 transcripts do), so a reloaded row reads `Thought for 2s`; the summary shows only if the session ran summarized when recorded (probes §2.3) | | |
| done, empty text, no duration, no tokens | row hidden | | |

Expansion state lives in `ChatUiState.expanded(key): MutableState<Boolean>` (one snapshot state per row key, 5.6), one `ChatUiState` per `SessionId`, so it survives switching sessions and scrolling (jewel.md 8); collapsed by default, including while streaming **[proposal]** (an expanding row under a sticky bottom moves the viewport every delta; the token counter shows progress). `showThinking = false` makes the builder emit no thinking rows without changing grouping (a thinking block still closes a group, 4.6.4). Tokens format: `312 tokens`, `1.2k tokens`, `1.5m tokens`. Semantics: `role = Role.Button`, `contentDescription = "Claude, thinking"` while streaming (ref §3.3 VS Code screen-reader label), the label otherwise. Note: the Oct 2026 Desktop row is the first summary sentence with no label (ref §3.3); decision 2 fixes the VS Code form, which ships; swapping the label for the first sentence is a one-line builder change (open item 3).

### 4.6 Tool rows

#### 4.6.1 Anatomy

One secondary line per group (or lone call), same size as body, no leading icon, no indent, no border, trailing chevron; the whole row is the click target; click toggles the body below and rotates the chevron to `⌄` (ref §3.4). Row height 24 dp, pitch 30 dp. Paths, commands, patterns and hosts inside the line are mono spans (`RowLabel.spans`, 5.3). Diff stats `+A -B` sit before the chevron, `+A` in the added colour, `-B` in the deleted colour; **the zero side is shown** (`+146 -0`, ref §3.4, Sept/Oct 2026 builds). A `PENDING`/`RUNNING` row shows the present-participle label with no dot; it carries no chevron **[proposal]**: the Sept 2026 and April 2026 captures draw `›` on running rows (ref §3.4 `Checking for node and headless browser options ›`, `Reading weather.ts ›`) but a running call has `ToolBody.None` and nothing to open, and the Oct 2026 live row (`• Running npm test`) has none; if the owner prefers the capture, draw the chevron disabled (open item 23). A `WAITING` row the same with the label in `text.warning` **[proposal]** (no capture shows a waiting row, ref §3.4 only has VS Code's `Waiting…`; the card below is the affordance). The coral dot is not drawn on tool rows in 2a **[proposal]**: ref §3.4's `• Running npm test` dot row is the spinner-row variant (ref §3.8), and drawing a dot here plus the working row below would show the live step twice. The working row never repeats a live row's text (4.9). Hover: fill per 4.2 at 50 % alpha, radius 6 dp **[proposal]** (ref §2.3 `--cds-fill-ghost-hover` 5 %, never seen in a capture).

Expanded group: a bordered box (1 dp `borders.normal`, radius 8 dp, no fill) spanning the column with one 36 dp sub-row per call (its own label, state colour and chevron), 1 dp dividers inset 8 dp; a sub-row's click opens its body inside the box (ref §3.4, §2.5). A lone call opens its body directly under the row.

```
Reading seed.txt                                          (running: no dot, no chevron; the working row below reads ✻ Pondering… · 3s)
Read seed.txt ›
Remove fixture file · Denied ›                            (disabled colour)
Failed to use Glob ›                                      (red)

Read a file, found files, searched for a pattern ⌄
┌──────────────────────────────────────────────────────────────┐
│ Read seed.txt ›                                              │
├──────────────────────────────────────────────────────────────┤
│ Read seed.txt:10-14 ›                                        │
├──────────────────────────────────────────────────────────────┤
│ Found 1 file ⌄                                               │
│   seed.txt                                                   │
├──────────────────────────────────────────────────────────────┤
│ Searched for seven · 1 match ›                               │
└──────────────────────────────────────────────────────────────┘
```

#### 4.6.2 Labels per tool

The host builds every label from the tool name, its input and its result (ref §3.5; probes §2.4 for the result fields). `<name>` = the file path relative to the session's `cwd` (`ChatState.cwd` from `system/init.cwd`; on reload from the transcript line's `cwd`) when the path is strictly under it (a proper prefix); equal to `cwd`, or not under it → the basename (in the fixtures both `cwd` and `file_path` are the literal `<path>`, `tools-read:5`, so labels read `Read <path>`); middle-truncated at 48 characters by `middleTruncate` (ref §3.4 `Edited billing/charge.ts`). `<cmd>` = the command on one line (newlines → `; `), middle-truncated at 60. `<desc>` = Bash `description` verbatim, except that a leading `Run ` / `Running ` / `Execute ` is dropped after `Ran` **[proposal]** (`tools-bash:18` `Run echo to stdout and stderr` → `Ran echo to stdout and stderr`). The **base form** is used for `DENIED` and `INTERRUPTED` rows (a call that never ran is never described in the past tense): `<base> · Denied`, `<base> · Interrupted`. The **count noun** feeds group sentences (4.6.4).

| Tool (wire name) | Running (`PENDING` shows the verb alone: `Reading…`) | Done | Failed (whole row red) | Base form | Count noun | Body when expanded (4.7) |
|---|---|---|---|---|---|---|
| `Read` | `Reading <name>` | `Read <name>`; with `offset`/`limit`: `Read <name>:<start>-<end>` **[proposal]** (no capture shows a range) from `tool_use_result.file.startLine`/`numLines` (`tools-read:38`: `:10-14`) | `Failed to read <name>` | `Read <name>` | `read N files` / `read a file` (N = distinct names) | numbered lines from `file.content` starting at `startLine`, mono |
| `Edit`, `NotebookEdit`, `mcp__tandem__edit*` (Phase 3) | `Editing <name>` | `Edited <name> +A -B`, A/B counted from `structuredPatch[].lines` starting `+`/`-` (`tools-edit:44`: `+1 -1`) | `Failed to edit <name>` | `Edit <name>` | `edited N files` / `edited a file` | `ToolBody.Diff` from `structuredPatch` (rendered in Phase 3; 2a renders `-`/`+` lines of `old_string`/`new_string` as a mono box) |
| `Write` (create: `tool_use_result.type == "create"` or `structuredPatch` empty, `tools-edit:61`) | `Creating <name>` | `Created <name> +N -0`, N = the number of lines of `input.content` where a final newline closes the last line and adds none (`"hello\nworld\n"` → 2, `tools-edit:57`; this differs from Read's `numLines`, which counts the empty line after a trailing newline, probes §2.4) | `Failed to write <name>` | `Create <name>` | `created <name>` for one / `created N files` | the content, mono |
| `Write` (update, `plan-deny:141`) | `Writing <name>` | `Edited <name> +A -B` | `Failed to write <name>` | `Write <name>` | as Edit | as Edit |
| `Bash`, `PowerShell` | `<desc>` conjugated to the present participle when its first word is in a small verb table (`Run`→`Running`, `Create`→`Creating`, `Check`→`Checking`, `Remove`→`Removing`, `List`→`Listing`, `Install`→`Installing`, …; `Text.kt`), else the description verbatim **[proposal]**; no trailing `…` (ref §3.4 `Checking for node and headless browser options ›` shows none on the row; the `…` belongs to the working row and running groups); no description → `Running <cmd>` | `Ran <desc>` / `Ran <cmd>` (ref §3.5 `Ran View GitHub issue 2664 ›`, `Ran pnpm test ›`) | `Failed to <desc with its first letter lowercased>` (ref §3.4 `Failed to check …`; `tools-bash:34` → `Failed to run failing command with exit code 3`), else `Failed to run <cmd>` | `<desc>` verbatim when present (`Remove fixture file · Denied`), else `Run <cmd>` | `ran N commands` / `ran a command`; read-only commands count by their first word (ref §3.5 Bash, CLI 2.1.89: `ls`, `cat` as reads, `find`, `grep` as searches): `cat`, `head`, `tail` → read; `ls`, `find`, `tree` → find; `grep`, `rg`, `ag` → search (`head`, `tail`, `tree`, `rg`, `ag` are extensions **[proposal]**) | `$ <command>` box, then the output |
| `Grep` | `Searching for <pattern>` | `Searched for <pattern>` + ` · N matches` / ` · 1 match` when `numLines > 0` (`tools-read:62`; the count suffix is **[proposal]**, no capture shows it) | `Failed to search for <pattern>` | `Search for <pattern>` | `searched for N patterns` / `searched for a pattern` | `content` lines or `filenames[]`, mono |
| `Glob` | `Finding files <pattern>` | `Found N files` / `Found 1 file` / `Found no files` (`numFiles`, `tools-read:50`) | `Failed to find files` | `Find files <pattern>` | `found files` | `filenames[]`, mono |
| `WebFetch` | `Fetching <host>` | `Fetched <host>` (`host` from `input.url`) | `Failed to fetch <host>` | `Fetch <host>` | `fetched N pages` / `fetched a page` | `result` as Markdown, then `<code> <codeText>` (`misc-web:34`) |
| `WebSearch` | `Searching the web for "<query>"` | `Searched the web for "<query>"` | `Failed to search the web` | `Search the web for "<query>"` | `searched the web` | `results[].content[]` as `title` links, then the summary Markdown (`misc-web:78`) |
| `Agent`, `Task` | `Running an agent: <description>` | `Ran an agent: <description>` **[proposal]** (ref §3.4 shows `Ran an agent ⌄` with the description inside the box; the label puts it on the row so a collapsed row says what ran) + sub-line `Done (<n> tool uses · <tokens> tokens · <duration>)` from `tool_use_result.totalToolUseCount/totalTokens/totalDurationMs`, `<duration>` by `formatDuration` (5.4; `agent-sync:50`: `Done (2 tool uses · 21.8k tokens · 5.8s)`) | `Failed to run an agent: <description>` | `Run an agent: <description>` | never grouped | the nested subagent rows, then its final text as Markdown (4.8) |
| `ToolSearch` | hidden | hidden (ref §3.5: the CLI loading a deferred tool) | `Failed to load tools` | — | not counted | the error text |
| `AskUserQuestion` | `Asking a question` | `Asked a question` **[proposal]** (ref §3.5.1 proposes a `Questions ›` row) | `Failed to ask a question` | `Ask a question`; cancelled: `Question cancelled` (DENIED) | never grouped | `<question> → <answer>` per line from `tool_use_result.answers` **[proposal]** (ref §3.5.1: `<header>: <answer>`; the question text is kept because a header like `Colour` is terser than the question), or `Reply: <response>` (`ask-single:26`, `ask-response:24`) |
| `ExitPlanMode` | `Proposing a plan` **[proposal]** | `Plan approved` **[proposal]** (ref §3.5 gives no row rule for `ExitPlanMode`, only the card) | `Failed to exit plan mode` | `Exit plan mode`; sent back: `Plan sent back` **[proposal]** (DENIED) | never grouped | the plan Markdown (`tool_use_result.plan` or the card's source, 4.14); denied: `Feedback: <message>` |
| `EnterPlanMode` | hidden | hidden (ref §3.5: no row, the status line changes and the picker follows it) | `Failed to enter plan mode` | — | not counted | the error text |
| `TaskCreate/Update/Get/List` | `Updating tasks` | `Updated tasks` | `Failed to update tasks` | `Update tasks` | `used N tools` | `INPUT` / `OUTPUT` pretty JSON |
| `mcp__<server>__<tool>` (not `mcp__tandem__edit*`) | `Using <server>: <tool>` **[proposal]** | `Used <server>: <tool>` **[proposal]** (ref §3.4 seen `Using plugin playwright playwright: browser evaluate ›`: server + readable tool name, the `mcp__` prefix never shown; `<tool>` = the wire name with `_` → space) | `Failed to use <server>: <tool>` | `Use <server>: <tool>` | `used N tools` / `used a tool` | `INPUT` / `OUTPUT` pretty JSON |
| anything else (`Skill`, `LSP`, `Monitor`, `Workflow`, `TaskStop`, unknown) | `Using <Display Name>` (CamelCase split: `Notebook Edit`) | `Used <Display Name>` | `Failed to use <Display Name>` | `Use <Display Name>` | `used N tools` | `INPUT` / `OUTPUT` pretty JSON |

`ToolKinds.of(name)` maps names to a `ToolKind`; `Task` → `AGENT` (probes §2.5: `init.tools` lists `Task`, blocks say `Agent`); `mcp__tandem__*` names are routed to `EDIT`/`CREATE`/`READ` by a table in the same function so Phase 3 only extends it. A `tool_result` whose `tool_use_id` matches no block renders a `Used a tool` row with the result as its body, so nothing is dropped silently (hard rule 5).

A result whose error text starts with `No such tool available` (the CLI's registry error: `tools-read-unavailable:49` for `Glob`, `plan-disallowed:103` for `Write`) labels the call as `OTHER` whatever its name: `Failed to use <Display Name>` (`Failed to use Glob`, `Failed to use Write`), body = the error text; it stands alone as a red row (4.6.4 rule 3). The tool never ran, so a tool-specific `Failed to read/find/write …` would describe an action that did not happen. `ToolLabelsTest` `an unavailable tool reads Failed to use` pins both fixtures.

#### 4.6.3 States and the state machine

| State | Trigger | Rendering | Fixture |
|---|---|---|---|
| `PENDING` | `content_block_start(tool_use)` seen, `input` still streaming | running verb with what is known (`Running…` when the command is not complete), no dot, no chevron (4.6.1 **[proposal]**) | `permission.ndjson:11-18` |
| `RUNNING` | complete `tool_use` block, no `tool_result` yet | running label, no dot, no chevron **[proposal]** (4.6.1: the Sept/April captures draw `›`, the Oct 2026 live row does not; a running call has nothing to open) | `tools-bash:18-22` |
| `WAITING` | a `PendingPermission` with this `toolUseId` is open | running label in `text.warning`; the card (4.12–4.14) is the affordance | `permission.ndjson:21` |
| `DONE` | `tool_result` with `is_error` absent or false | done label, chevron, body on click | `tools-read:21` |
| `FAILED` | `tool_result.is_error == true` and no reject decision | whole row in `text.error`, failed label; body = the error text (`<tool_use_error>` tags stripped), first line in `text.error` | `tools-bash:39` (`Exit code 3`), `tools-read-unavailable:49` |
| `DENIED` | `tool_result_meta[].permission_decision.decision == "reject"` (`ask-deny:24`, `plan-deny:81`, `permission.ndjson:56`), or `is_error` with content `The user denied this command.` / `cancelled` / `The user denied this request.` when meta is missing (reload of an older file) | base form + ` · Denied`, `text.disabled`, not red (ref §3.6 proposal) | `Remove fixture file · Denied` (`permission.ndjson:50,56`) |
| `INTERRUPTED` | the turn ended (`result` with `aborted_streaming`, or the process ended) while `PENDING`/`RUNNING`/`WAITING` | base form + ` · Interrupted`, `text.disabled` | `interrupt.ndjson` |

| From | Event | To |
|---|---|---|
| — | `content_block_start(tool_use)` | PENDING |
| PENDING | complete `assistant` line (input known) | RUNNING |
| RUNNING | `can_use_tool` with this `tool_use_id` | WAITING |
| WAITING | host answers (allow or deny) or `control_cancel_request` | RUNNING (a deny is followed by the CLI's denied result) |
| RUNNING | `tool_result` ok | DONE |
| RUNNING | `tool_result` error with a reject decision (or a denial string without meta) | DENIED |
| RUNNING | `tool_result` error, other | FAILED |
| PENDING / RUNNING / WAITING | `result` (turn end) or process exit | INTERRUPTED (a `tool_result` that still arrives afterwards upgrades it to DONE / FAILED) |
| any | transcript reload (settled state) | DONE / FAILED / DENIED from the on-disk result; a call without a result is INTERRUPTED; never RUNNING |

`Not run` (the auto-mode skip reported through `system/permission_denied`, ref §3.4 States, §3.6) is 2b (section 2); until then such a call has no result and shows `· Interrupted` at turn end.

#### 4.6.4 Grouping rules (ref §3.7 rules 1–9, made deterministic)

Applied by `Grouping.fold` over the ordered main-thread tool calls of one turn (from a `User` entry to the next):

1. A **group** is a maximal run of ≥ 2 consecutive calls between which there is no non-empty text block, no thinking block, no user message and no notice. Text closes a group (ref §3.7 rule 4); thinking closes it too (choice: the `Show thinking` setting must never change grouping). Hidden `ToolSearch` and `EnterPlanMode` calls are skipped before counting (a failed one is a row and stands alone by rule 3).
2. `Agent`/`Task`, `AskUserQuestion`, `ExitPlanMode` never join a group; each is its own row and closes the run on both sides. An Agent row never absorbs the following main-thread calls **[proposal]** (ref §3.7 rule 8; the subagent's calls nest inside it, 4.8).
3. A `FAILED`, `DENIED` or `INTERRUPTED` call is never folded: it stands alone where it happened (its own red or dimmed row) and the calls before and after it form their own groups when ≥ 2 remain on that side (ref §3.7 rule 7). A denial is a user decision and is never hidden in a count (hard rule 5).
4. A single remaining call is a plain call row with the per-tool label (4.6.2).
5. **Grammar** of a done group: clauses in the order the families first occur, comma-separated, no `and`, the first clause capitalised, the rest lowercase; count + plural noun, or `a <noun>` for one (ref §3.7 rule 2). Family nouns are the count-noun column of 4.6.2; read/edit counts are distinct file names, run/search/web/other counts are calls. Names instead of counts: a reads-only group with ≤ 2 distinct names reads `Read <a>, <b>` / `Read <a>` (ref §3.4 `Read cart.spec.ts, checkout.ts ›`; threshold is ref §6 Q2's best guess, kept as a constant); a single created file is always named (`created launch.json`). Single-family groups fall out of the same grammar: `Read 38 files`, `Edited 3 files +71 -88`, `Ran 2 commands`, `Used 3 tools`, `Created 2 files +12 -0`.
6. Diff stats `+A -B` summed over the group's edit/create calls, after the sentence, zero side shown (`+146 -0`).
7. **Running group**: while any call of the live group is `PENDING`/`RUNNING`/`WAITING`, a clause whose family has a running call uses the present participle and counts every call of that family, finished clauses stay in the past tense, and the sentence ends with `…`: `Reading 3 files…`, `Ran a command, editing a file…`, `Read 2 files, running a command…` (ref §3.7 rule 3, §3.4 `Reading weather.ts ›`). No dot, no chevron (4.6.1 **[proposal]**); the label in `text.warning` when a call is `WAITING`; no stats until done.
8. Group state: `WAITING` if any call is `WAITING`, else `RUNNING` if any call is `PENDING`/`RUNNING`, else `DONE` (`FAILED`/`DENIED`/`INTERRUPTED` never, by rule 3).
9. The expanded box lists one sub-row per call with that call's own label, state colour and chevron (4.6.1).

#### 4.6.5 Examples from the fixtures (the goldens `ChatRowsBuilderTest` pins; basenames are `<path>`)

| Fixture | Main-thread blocks | Rows |
|---|---|---|
| `tools-read` (`:17,34,46,58`) | Read, Read `offset:10, limit:5`, Glob `*.txt`, Grep `seven`, text | `Read a file, found files, searched for a pattern ›` (two reads of one name = one file); sub-rows `Read <path> ›`, `Read <path>:10-14 ›`, `Found 1 file ›`, `Searched for seven · 1 match ›`; then the text |
| `tools-edit` (`:40,44,57,61`) | Read, Edit `golf → GOLF`, Write create, text | `Read a file, edited a file, created <path> +3 -1 ›` (`+1 -1` from the Edit patch plus `+2 -0` for the two created lines) |
| `tools-bash` (`:18` ok, `:34` exit 3) | Bash, Bash, text | two rows, no group (rule 3): `Ran echo to stdout and stderr ›` and, red, `Failed to run failing command with exit code 3 ›` |
| `tools-read-unavailable` (`:48-49`, `:73`, `:82`) | Glob (error), thinking, Bash `find`, Bash `grep -rn`, text | red `Failed to use Glob ›` (the unknown-tool error, no meta) · thinking row · `Found files, searched for a pattern ›` (Bash `find` and `grep` counted as find and search) |
| `permission.ndjson` (`:19-25`, `:50-56`) | Bash `touch tandem-fixture.txt` / `Create empty fixture file` (allowed), text, Bash `rm tandem-fixture.txt` / `Remove fixture file` (denied), text | `Ran Create empty fixture file` (no `›`: `(Bash completed with no output)` gives `ToolBody.None`, 4.7; the 4.6.2 Bash rule: `Ran <desc>` when a description exists, ref §3.5 `Ran View GitHub issue 2664 ›`), `done.`, `Remove fixture file · Denied ›` (its body is the denial text), `denied.`; the card titles use the command (4.12) |
| `plan-disallowed` (`:102-103`, `:172`) | Write (error), …, ExitPlanMode | red `Failed to use Write ›` (the `No such tool available` rule of 4.6.2; the plan file; the card's source line explains it, 4.14) · `Plan approved ›` |
| `ask-single` | AskUserQuestion, text | `Asked a question ›` alone (rule 2) with `Which colour? → Blue` in its body |
| `agent-sync` (`:35-50`) | Agent, text | `Ran an agent: Count lines in seed.txt ›` + `Done (2 tool uses · 21.8k tokens · 5.8s)`, nested rows inside |
| Read a, Read b, Grep (synthetic) | | `Read a, b, searched for a pattern ›` is **not** produced: reads use names only in a reads-only group → `Read 2 files, searched for a pattern ›` |
| Read ×4, Grep ×2 (synthetic) | | `Read 4 files, searched for 2 patterns ›` |
| Bash, Bash (fail), Bash (synthetic) | | `Ran a ›` · red `Failed to run b ›` · `Ran c ›` |
| Edit, Edit, Write create (synthetic) | | `Edited 2 files, created d.kt +12 -4 ›` |
| MCP ×3 (synthetic) | | `Used 3 tools ›` |
| Read (running) | | `Reading x`, no dot, no chevron (4.6.1 **[proposal]**) |

### 4.7 Expanded bodies

One clamp rule for every body (ref §3.6 decision): 20 lines (or 240 dp, whichever comes first) with a 3-line bottom fade (the `Modifier.bottomFade` helper of 4.3) and an inline `Show all N lines` `Link`; `Show all` expands in place up to 400 lines, beyond which the body ends with `… N more lines`. Bodies are pre-rendered strings and lists in the row model (5.3), never `JsonElement`, built once when the result arrives; opening a row composes data and parses nothing (`ToolBody.Markdown` and `Links` are parsed by the presenter on first expansion, requested by the click through `ChatUiState.onExpand` → `ChatPresenter.expandBody`, 5.6, through the same cache as text rows). `<system-reminder>…</system-reminder>` blocks are stripped from Read results; ANSI escapes are stripped from Bash output; `(Bash completed with no output)` gives `ToolBody.None`, so the row is not expandable and shows no chevron (ref §3.6: it collapses to nothing). Bodies are mono; `ToolBody.Mono` and `ToolBody.Json` sit in the box fill, a `ToolBody.Command` draws only its `$ <command>` line in the box and the output below it on the page colour (ref §2.5 expanded group box: the command box has the gray-830 fill, the raw output none); `ToolBody.Markdown` (WebFetch, WebSearch summaries, plan text, agent final text) is plain. Everything inside a body is selectable. Long bodies are plain `Text` rows inside the item, never a nested scroll area, so the outer list keeps the wheel. A Bash body starts with a `$ <command>` line in its own box, then the raw `tool_use_result.stdout` (or the `tool_result.content` string), with the `Exit code N` first line kept on failures (`tools-bash:39`). Image results (`misc-image:26`) render the line `[image 8×8 png]` from `tool_use_result.file.dimensions`; the base64 never reaches a row.

### 4.8 Subagent nesting (probes §2.5)

The `Agent` row (4.6.2) is a `ToolRow` whose single `ToolCall.children` are the rows of every entry with `parentToolUseId == <Agent tool_use id>`, built by the same builder: the echoed prompt user line (`agent-sync:38`) is hidden; the subagent's tool calls render as tool rows (ungrouped, 4.6.2 labels) indented 16 dp; its forwarded final text (`agent-forwarded:38`, requires `initialize{forwardSubagentText:true}`) renders as a text row inside the body, never at top level; when no text was forwarded the body ends with `task_notification.summary` or `tool_use_result.content[].text` (`agent-sync:49-50`). A permission request from inside the subagent (`agent-sync:43`, `agent_id`) uses the ordinary card with the line `From the agent "<task description>"` under the title **[proposal]** (no capture shows a subagent prompt), and the nested row goes `WAITING`. Live progress sub-line while running: `<tool_uses> tool uses · <total_tokens> tokens · <duration>` from the latest `task_progress.usage` (`agent-sync:41`: `1 tool use · 21.5k tokens · 2.0s`), replaced on completion by `Done (…)` from the result; both durations use `formatDuration` (5.4). The latest `task_progress.description` is also the working row's step while the agent runs (4.9). Collapsed by default (ref §3.5 Agent). `task_started` with `task_type:"local_bash"` (`agent-bash-task:21`) belongs to a plain Bash row and attaches nothing. Subagent entries exist in the state from slice A (the builder routes them into `children`); the nested rendering lands in T13; until then the Agent row shows its label and `Done (…)` sub-line and the children are not drawn.

### 4.9 Working indicator (decision 7)

The last transcript row while a turn runs (`ChatState.turn != null`), from the moment the prompt is sent until the turn's `result` (or the process ends), left-aligned at the text margin, pushed down by streaming content (ref §3.8):

```
✻  Pondering…  ·  3s  ·  1.2k tokens
```

| State | When | Glyph | Colour | Text |
|---|---|---|---|---|
| `RUNNING` | `turn != null`, no pending card, a stdout frame within the last 60 s | cycles the six glyphs `·` `✢` `✳` `✶` `✻` `✽` (ref §3.8, S18) one step every 120 ms **[decision]**, played forward then back (`· ✢ ✳ ✶ ✻ ✽ ✻ ✶ ✳ ✢`) so the spark grows and shrinks instead of snapping from `✽` to `·` **[proposal]** | spark coral; text secondary | `<Step>… · <elapsed>` + ` · <n> tokens` once `n > 0` |
| `WAITING` | `pendingPermissions` non-empty | static `✻` | glyph and text `text.warning` | `Waiting for you · <elapsed>` **[proposal]** (decision 7 fixes only the amber colour; no capture shows the waiting text) |
| `NOT_RESPONDING` | `turn != null`, no pending card, `now − lastFrameAtMillis ≥ 60_000` | static `✻` | `text.error` | `Not responding · <elapsed>` (back to `RUNNING` on the next frame) |
| `ENDED` | `turn == null` | — | — | replaced by the turn footer (4.10) |

`<Step>` **[proposal]**, chosen so that the live step is drawn once (ref §3.4: every target capture shows one live row, `Reading weather.ts ›` with the spinner row below it, and the row above never repeats on the spinner row): in priority order, the latest `task_progress.description` while a subagent runs and its Agent row is collapsed (`Running wc -l seed.txt`, middle-truncated at 60; ref §3.8 names it as a step source; the nested row is hidden, so nothing else shows it) → `Thinking` while a thinking block streams and `showThinking` is off (no row shows it) → a present-participle **verb** from `chat/Verbs.kt` (appendix A). A live main-thread tool row or a visible thinking row already names the step (`Reading seed.txt`, `Thinking… · 50 tokens`), so the working row shows the verb beneath it (ref §3.8 Desktop contract: the row "returns to `Working` between steps"; the Sept 2026 build drew `Running tools…` beside the step row). `WorkingIndicatorTest` `step text never repeats the row above` pins it. The alternative (the running label on the working row, the plain label on the tool row) is one line in `WorkingIndicator.step` (open item 20). Verb choice (decision 7 asks which): deterministic, `VERBS[abs((turn.userUuid + (turn.lastToolUseId ?: "")).hashCode()) % VERBS.size]`, i.e. one verb per turn, **re-rolled on each main-thread `tool_use` start**, no time-based re-roll (testable, no extra timer, nothing recomposes between tool calls). `<elapsed>` = host clock since `TurnProgress.startedAtMillis` (the `UserSent` time): `Ns` under a minute, `Mm Ss` under an hour (`43m 11s`), `Hh Mm` from an hour **[proposal]**. Tokens = `TurnProgress.outputTokens` (sum of `message_delta.usage.output_tokens` per request, `permission.ndjson:22`, `misc-image:24,48,64`) + the live `thinking_tokens` estimate while a thinking block streams (`think-default:11`), formatted as in 4.5. The elapsed ticker and the not-responding check run once a second inside the row's own composable (9), reading `handle.lastFrameAtMillis.value` on that tick (a `StateFlow<Long>` updated on every stdout line, keep-alives and unknown lines included, without a `ChatState` emission; 5.2); the flow is never collected as state, so a frame never recomposes the row and recovery from `Not responding` is within 1 s. Text at body − 1 (4.2). Semantics: `Row(Modifier.semantics(mergeDescendants = true) { contentDescription = view.accessibleLabel }.testTag("working-row"))` with `contentDescription = "Claude is working"` / `"Claude is waiting for you"` / `"Claude is not responding"`, so one node carries the description and the merged text (as Jewel's buttons do); the text is one `Text` node. Reduced motion: when `UISettings.getInstance().animateWindows` is false (exists on 261; whether it is the right flag is open item 5) the glyph stays `✻`. Note: ref §3.8 measured a continuously rotating starburst on Desktop and proposes a rotated `Painter`; decision 7 fixes the six-glyph cycle, which ships as the `Spark` leaf composable; the `runIde` checklist decides whether to swap the leaf for the painter (open item 4).

### 4.10 Turn footer

When a turn ends the working row is replaced by one secondary line, key `e:<TurnEnd entry id>`:

| `TurnEnd` | Text |
|---|---|
| `COMPLETED` | `Worked for <duration>` with `duration` from `result.duration_ms` (`permission.ndjson:35`), formatted by `formatElapsed` (whole seconds; ref §3.8 CLI `Worked for 5s`); text at body − 1 (4.2) |
| `INTERRUPTED` | `Interrupted · What should Claude do instead?` (ref §3.9); the reducer's `Stopped.` notice is never rendered as a notice (it carries `kind = INTERRUPTED` and becomes this footer on a reloaded transcript that has no `result` line) |
| `FAILED` | `Server error mid-response. The response above may be incomplete.` when any text block of the turn streamed (ref §3.9), else `API Error: <api_error_status> <error>` from the turn's `assistant.error` line (`invalid-model.ndjson:4`: `API Error: 404 model_not_found`; `TurnEnd.error`, 5.2), else `The turn failed (<result.error>)` (or the `Ended` detail); one secondary line keyed `e:<TurnEnd entry id>` like the others; the existing ERROR notice follows only when it carries text the footer does not |

Only the latest turn's footer is shown; older turns keep nothing (the desktop shows a hover row there, deferred 2b).

### 4.11 Notices

`ChatEntry.Notice` → `NoticeRow`: INFO plain secondary; WARNING `AllIconsKeys.General.Warning` 16 dp + text in `text.warning`; ERROR `AllIconsKeys.General.Error` 16 dp + text in `text.error` (the icons are **[proposal]**: ref §3.9 draws retries and model notices as plain `text.info` lines; an icon makes a failure findable in a long transcript, hard rule 5). The retry notice reads `<error_status> <error> · Retrying in <retry_delay_ms/1000>s · attempt <attempt>/<max_retries>` (ref §3.9, e.g. `529 Overloaded · Retrying in 20s · attempt 10/10`; the status is omitted when the line has no `error_status`), as an INFO `NoticeRow` under the working row; `ReducerTest` `an api_retry line yields the reference wording` updates the existing hand-made line (no new fixture: `api_retry` is already decoded with these fields, `Ndjson.kt`, and a string change in a typed message is presentation, not a protocol change). Other texts unchanged: model fallback, `The CLI rejected a request: …`, process exit. `The turn failed (…)` moves into the `FAILED` footer (4.10); the ERROR notice stays only when its text adds something (the exit line of a process exit). The model-switch line (`You picked X, but the CLI is using Y`) stays a WARNING notice (the reference's `〰 Switched to … 〰` is VS Code-only). No balloons for anything in the stream. The usage-limit, flagged-message and compaction cards are 2b.

### 4.12 Permission card (decision 6; ref §3.10)

Docked above the composer, column width, `panelBackground`, 1 dp `borders.normal`, radius 8 dp, 16 dp padding; `testTag("permission-card")`, `contentDescription = "Permission request"`. While it is open the transcript is dimmed **[decision]** by a scrim, not an offscreen layer: `Modifier.drawWithContent { drawContent(); drawRect(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f)) }` on the list container (`Modifier.alpha(0.5f)` would force an offscreen layer of the whole list on every frame the spark or the stream repaints it; the reference's target builds do not dim, ref §2.1; open item 1, one modifier). The composer keeps its focus-ability and its draft so the next prompt can be typed while the card waits **[proposal]** (ref §3.5.1 proposes a disabled composer); Enter does nothing until the card is answered (4.15). Contents:

1. Title, one `Text` with an `AnnotatedString` (the IDE test matches its exact text): `Allow Claude to <Verb> <object>?` with the verb in `FontWeight.SemiBold` and the object in mono: Bash/PowerShell → `Run <command>` (full command, wrapped), Read → `Read <name>`, Edit / `mcp__tandem__edit*` / NotebookEdit / Write update → `Edit <name>`, Write create → `Create <name>`, WebFetch → `Fetch <url>` (middle-truncated at 80), WebSearch → `Search the web for "<query>"` **[proposal]** (ref §3.10 proposes `search the web?`), Agent/Task → `Run an agent`, MCP → `Use <server>: <tool>`, anything else → `Use <Display Name>`. A 6 dp `text.warning` dot precedes the title (ref §3.10 yellow dot). The `session` pill ref §3.10 (S10) draws right of the title is omitted **[proposal]**: the button already says `for session`. The capitalised verb follows decision 6 (the mid-2026 build shows it lowercase; open item 2).
2. `From the agent "<task description>"` in secondary when the request carries `agent_id` (4.8) **[proposal]**.
3. Description line in secondary: `request.description` when present and different from the object (`Create empty fixture file`, `permission.ndjson:21`).
4. Payload in a mono box (fill per 4.2, radius 6 dp, 8 dp padding, horizontal scroll through `HorizontallyScrollableContainer`): Bash → the command verbatim, multi-line kept; Read/Edit/Write → the absolute `file_path`; for Edit a two-part box follows it, `old_string` lines prefixed `- ` on the deleted fill and `new_string` lines prefixed `+ ` on the inserted fill **[proposal]** (ref §3.10: the target build shows the diff in the `Edited README.md +23 -16 ⌄` tool rows above the card, which Phase 3 renders; until then the box is the only place the change is visible); Write create → the content; WebFetch → `url` then `prompt`; WebSearch → the `query`; Agent/Task → `description` then `prompt`; MCP and anything else → the first string input in the order `command`, `file_path`, `path`, `pattern`, `query`, `url`, `prompt`, `description`, `name`, `skill` (ref §3.5), pretty-printed `input` JSON only when none of them exists (so raw JSON is the last resort, never the look of a normal prompt). Every payload clamps at 8 lines / 160 dp with a `Show more` `Link` (expands in place).
5. Buttons, 8 dp apart, in a `FlowRow` (in the bundled foundation jar; `@OptIn(ExperimentalLayoutApi::class)`) so they wrap at 350 px: `Deny` (`OutlinedButton`, cap `Esc`) at the left; right-aligned `Always allow for session` (`OutlinedButton`, cap `⌘↩` on macOS / `Ctrl↩` elsewhere; shown only when `sessionPermissionUpdates(suggestions)` is non-empty, i.e. the request had `addRules`; `session/PermissionUpdates.kt`) and `Allow once` (`DefaultButton`, cap `↩`). Caps are a second `Text` in secondary inside the button content; each button carries `semantics { contentDescription = "<label>" }` so the IDE tests find it by description (ref §3.10 key caps).
6. `1 more waiting` in secondary under the buttons when several requests are pending (a subagent's and the main thread's, `agent-sync:43`) **[proposal]**; requests are answered oldest first.

Keys (`Modifier.onPreviewKeyEvent` on the card root; the card requests focus through a `FocusRequester` in `LaunchedEffect(card.requestId)` and is a `focusGroup()` so Tab cycles its buttons): Enter → `Allow once`; Cmd/Ctrl+Enter → `Always allow for session` when offered; Esc → `Deny`. Focus returns to the composer when the card closes (`promptFocusFor`, `TandemToolWindow.kt`). Digits do nothing here.

Answers go through one path: `manager.answer(id, requestId, PermissionAnswers.allowOnce(p) | allowForSession(p) | deny())` → `ClaudeSession.answer` → `HostMessages.allow/deny` (unchanged frames; `allowForSession` echoes only `addRules` with destination `session`) → `PermissionAnswered`. `control_cancel_request` closes the card (unchanged). The row behind the card shows `WAITING` (4.6.3).

```
┌──────────────────────────────────────────────────────────────────┐
│ ●  Allow Claude to Run touch tandem-fixture.txt?                 │
│ Create empty fixture file                                        │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ touch tandem-fixture.txt                                     │ │
│ └──────────────────────────────────────────────────────────────┘ │
│ [ Deny  Esc ]        [ Always allow for session  ⌘↩ ] [ Allow once  ↩ ] │
└──────────────────────────────────────────────────────────────────┘
```

### 4.13 AskUserQuestion card (decision 5; ref §3.5.1; probes §2.1)

Same chrome as 4.12, no dim (the user reads the transcript to answer), `testTag("question-card")`, `contentDescription = "Question from Claude"`, keyed on the `can_use_tool` request with `tool_name == "AskUserQuestion"` (`ask-single:22`; it arrives before `content_block_stop`, so the card never waits for the message to close).

```
┌──────────────────────────────────────────────────────────────────┐
│ [ Colour ] [ Toppings ]                                          │   SegmentedControl, one button per question header
│ Which colour?                                                    │   question text, SemiBold
│ ◉ Red                                                        1   │   RadioButtonRow (single) / CheckboxRow (multi), cap = number key
│   Red                                                            │   description, secondary
│ ○ Blue                                                       2   │
│   Blue                                                           │
│ ○ Other                                                      3   │
│   [ Something else…                                            ] │   TextField, placeholder "Something else…"
│                                   [ Cancel  Esc ] [ Submit answers  ↩ ] │
└──────────────────────────────────────────────────────────────────┘
```

- Tabs: `SegmentedControl(buttons = questions.mapIndexed { i, q -> SegmentedControlButtonData(selected = i == tab, content = { Text(q.header) }, onSelect = { tab = i }) })` **[proposal]** for the VS Code tab strip (ref §3.5.1: header chips underlined as tabs); one question → no strip. A tab whose question has no answer yet shows its header followed by ` ·` **[proposal]**. The strip scrolls horizontally at 350 px (`HorizontallyScrollableContainer`).
- Options: `RadioButtonRow(selected, onClick) { Text(label, fontWeight = FontWeight.SemiBold); Spacer; Text("<n>", secondary) }` for `multiSelect == false`, `CheckboxRow(checked, onCheckedChange) { … }` for `true` (bold label line, muted description line: ref §3.5.1); the description under each in secondary, indented to the label; each row carries `contentDescription = label`. The `Other` row is a `RadioButtonRow` (or `CheckboxRow`) labelled `Other` followed by a `TextArea(otherState, lineLimits = TextFieldLineLimits.MultiLine(1, 4), placeholder = { Text("Something else…") })` (placeholder `Something else…` **[proposal]**; the ref §3.5.1 sketch says `free text …`): Enter submits (routed through `cardKey`), Shift+Enter inserts a newline (ref §3.5.1); typing in the field selects `Other`. `option.preview` is ignored in 2a (no fixture).
- Number keys `1`–`9` (when focus is not in the `Other` field) pick or toggle the option at that index on the active tab (decision 5; ref §3.5.1); Enter submits when every question has an answer; Esc cancels; `←`/`→` switch tabs when the strip has focus (the pure `questionKey` mapping, 6). `Submit answers` is a `DefaultButton` (cap `↩`) enabled only when every question has an answer; `Cancel` an `OutlinedButton` (cap `Esc`).
- Answer value per question (`PermissionAnswers.questionAnswers`): single → the picked label; multi → the picked labels joined with `", "` in option order (`ask-multi:31`; probes §2.1 recommends the string); `Other` → the typed text, trimmed (`ask-other:26`). `Other` wins over picks when its text is non-blank. `updatedInput.response` is never sent (it would override `answers`, probes §2.1).
- Submit sends `allow` with `updatedInput = {questions: <input.questions unchanged>, answers: {<question text>: <value>}}` (5.5). Cancel (or Esc) sends `deny` with `message = "cancelled"` (`ask-deny:24`: `is_error` result, the turn continues, `result.permission_denials` lists the call).
- After the answer the tool row reads `Asked a question ›` with the pairs in its body; after a cancel `Question cancelled ›`.
- Tree order: the card sits above the composer, so the composer is still the last `@editabletext` node (the IDE test's `PROMPT_BOX`).
- A request whose `input.questions` is missing or malformed renders the generic permission card (`Allow Claude to Use AskUserQuestion?`) so nothing is silent.

### 4.14 Plan approval card (decision 4; ref §3.5.2; probes §2.2)

Keyed on `can_use_tool` with `tool_name == "ExitPlanMode"` (`plan-approve:88`, `plan-disallowed:173`). Same chrome, no dim, `testTag("plan-card")`, `contentDescription = "Plan approval"`.

```
┌──────────────────────────────────────────────────────────────────┐
│ Approve this plan?                                               │
│ From Claude's draft of add-changelog-fuzzy-dog.md (the plan file │   source line, secondary
│ was not saved)                                                   │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ # Plan: Add CHANGELOG.md                                     │ │   Markdown, clamped 240 dp, fade, Show more
│ │ ## Steps …                                                   │ │
│ └──────────────────────────────────────────────────────────────┘ │
│ Open plan file                                                   │   Link, only when a path is known
│ [ What should change?                                          ] │   feedback TextField
│ [ Keep planning  Esc ] [ Approve, keep current mode ] [ Approve and start coding  ↩ ] │
└──────────────────────────────────────────────────────────────────┘
```

Plan source, decided by `Cards.planText` in `chat/`; the card says which on its source line:

| `PlanSource` | Rule | Source line |
|---|---|---|
| `EXIT_PLAN_INPUT` | `request.input.plan` is a non-blank string (`plan-approve:88`) | `From the plan file <basename of planFilePath>` |
| `PLAN_FILE_DRAFT` | else the most recent main-thread `AssistantBlock.ToolUse` named `Write` in this turn before the `ExitPlanMode` block (in plan mode the only Write the model issues is the plan file, `~/.claude/plans/<slug>.md`; the block carries the full `input.content` although the call failed, `plan-disallowed:102-103`). When several Writes precede the block, one whose `file_path` has a `plans` segment is preferred over a later one; the segment is never required (a deviation from decision 4's "under a plans directory": the redactor rewrites every fixture path to `<path>`, `plan-disallowed:102` has `"file_path":"<path>"`, and in production plan mode the only Write is the plan file) | `From Claude's draft of <basename> (the plan file was not saved)` (`<basename>` of `<path>` is `<path>`) |
| `ASSISTANT_TEXT` | else the last `AssistantBlock.Text` of the turn before the `ExitPlanMode` block | `From Claude's last message (no plan file)` |
| `NONE` | nothing above | `Claude sent no plan text.`; the body is empty, the buttons still work |

The plan Markdown is parsed by the presenter (keyed by `requestId`), clamped at 240 dp with the `Modifier.bottomFade` helper of 4.3 and a `Show more` / `Show less` `Link`. `Open plan file` (`Link`) appears when `input.planFilePath` or the Write's `file_path` is known: a `Dispatchers.IO` VFS refresh in `TandemProjectScope`, then `OpenFileDescriptor` on the EDT.

Buttons: `Keep planning` (`OutlinedButton`, cap `Esc`) → deny with `message = "No, keep planning" + (if (feedback.isNotBlank()) ": $feedback" else "")` **[proposal]** (a deviation from decision 4's bare feedback: the prefix matches the CLI TUI's recorded frame `plan-deny:81`, so the model sees the same text as in the terminal, and avoids an empty deny message; open item 21; the mode stays `plan`, no status line follows, the model revises and asks again); `Approve, keep current mode` (`OutlinedButton`) → plain allow with `updatedInput = input`; the CLI then leaves plan mode on its own and reports `system/status permissionMode:"default"` (`plan-disallowed:177`, `plan-deny:145`), the picker follows that line and shows `Default`, and Tandem sends no mode request (the button keeps the *current* mode only in the sense that it adds no `setMode`; an implementer must not try to hold `plan`; whether the label should read `Approve` instead, since the user sees the picker flip after pressing a button that says "keep current mode", is open item 21); `Approve and start coding` (`DefaultButton`, cap `↩`) → allow with `updatedInput = input` and the host-built `updatedPermissions = [{"type":"setMode","mode":"acceptEdits","destination":"session"}]`; the CLI confirms with `system/status {status:null, permissionMode:"acceptEdits"}` before the tool result (`plan-approve:90-91`) and the permission-mode picker follows that line (the reducer already copies `SystemStatus.permissionMode`; T12 adds the fixture test). Never an always-allow rule for `ExitPlanMode`. The tool row reads `Plan approved ›` / `Plan sent back ›` afterwards. The failed plan-file Write is a `tool_use_error`, not a user rejection, so its row is `FAILED` (`Failed to use Write ›`, the `No such tool available` rule of 4.6.2), not `DENIED`: hiding it would fail silently and the card's source line explains it.

### 4.15 Composer (decision 3; ref §3.11)

- `TextArea(draft, Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 160.dp), lineLimits = TextFieldLineLimits.MultiLine(1, 8), placeholder = { Text("Describe a task or ask a question", color = secondary) })` inside a rounded box (1 dp `borders.normal`, radius 8 dp, `borders.focused` when focused), 12 dp inner padding. The placeholder is the reference's new-session string; `Type / for commands` is not used because `/` does nothing in 2a.
- Inside the box at the right: an `IconButton` showing `Text("↵", style = JewelTheme.editorTextStyle, color = secondary)` with `contentDescription = "Send"` when idle (ref §3.11 decision: `↵` idle) and `Icon(AllIconsKeys.Actions.Suspend, contentDescription = "Stop")` while `busy` (ref §3.11: the closest IDE icon to the stop glyph), 24 dp, tint secondary (no coral: decision 8 keeps the spark the only brand colour). The text buttons `Send`/`Stop` go; `ToolWindowIdeTest` switches to `iconButton("Send")` / `iconButton("Stop")` in T9, once.
- Keys (`Modifier.onPreviewKeyEvent` on the `TextArea`; pure mapping `composerKey(event, busy, cardOpen): ComposerAction?` in `ui/ComposerKeys.kt`, unit-tested like `rowKey` in `SessionSidebarTest`): Enter without Shift/Alt/Ctrl/Meta → send when the trimmed text is non-blank and `!busy`; while `busy` Enter does nothing (today's rule: the Stop icon replaces Send; no probe recorded a prompt sent mid-turn, the reducer's `UserSent` resets `turn` and would split the open group and mark every running call `· Interrupted`, and queued prompts with the `queued` caption are 2b, section 2), so `composerKey(event, busy = true, cardOpen)` returns `null` for Enter; ⌘/Ctrl+Enter → no action in 2a (queue-for-later is 2b, ref §3.11); Shift+Enter → newline; Esc while `busy` and no card open → `manager.interrupt(id)`; with a card open the card has focus and handles Esc. Esc precedence anywhere in the chat: open card → drawer (existing root handler) → running turn.
- Footer row below the box (8 dp, secondary, body − 1 per 4.2): left `PermissionModePicker` (unchanged `ListComboBox`, description `Permission mode`), right `ModelPicker` (description `Model`) and `EffortBadge` (`effort: <x>`), 8 dp apart, wrapping onto two lines at 350 px (`FlowRow`); `+`, mic and the usage ring are 2b. The pickers keep their semantics so the IDE locators hold.
- Drafts survive switching sessions: the `TextFieldState` is hoisted into `ChatUiState` per session (ui.md risk: `key(selected.id)` discards it today).

### 4.16 Autoscroll (decision 2; ref §3.12)

`following` is intent, not position: it turns off on any user wheel-up or drag-up and on `PageUp`/`Home`; it turns on when a user scroll down reaches the bottom (`!listState.canScrollForward`, 0 px threshold, so a small tick can never re-lock on a wheel-up: ref §3.12 rule 7), on `End`/`Ctrl+End`, on the `↓` button and on send. The pure `FollowState.on(ScrollEvent)` (5.4) decides; the `NestedScrollConnection` only feeds it events:

| State | Event | New state | Side effect |
|---|---|---|---|
| FOLLOWING | `WheelUp` (user wheel/drag with `available.y > 0`, `Home`, `PageUp`) | PAUSED(unseen = 0) | — |
| FOLLOWING | `Appended(n)` (rows added or the tail grew) | FOLLOWING | pin: `listState.requestScrollToItem(rows.lastIndex, Int.MAX_VALUE)` (non-suspending) |
| FOLLOWING | `CardShown` / card hidden | FOLLOWING | nothing is issued; the next content change re-pins to the shorter viewport (ref §3.12 rule 5) |
| PAUSED(u) | `Appended(n)` | PAUSED(u + n) | nothing moves |
| PAUSED | `ReachedBottom` (user wheel down and `!canScrollForward` after the scroll; decided in `onPostScroll`, never in `onPreScroll`, so the wheel notch that lands on the bottom resumes at once; events with `available.y == 0` (a horizontal wheel in a fence) are ignored) | FOLLOWING | the button hides |
| PAUSED | `Jump` (`↓` button, `End`, `Ctrl/Cmd+End`) / `Sent` | FOLLOWING | `animateScrollToItem(last)`, then pin |
| any | `Sent` | FOLLOWING | `animateScrollToItem(last)` (ref §3.12 rule 4) |
| PAUSED | `CardShown` | PAUSED | nothing (rule 5) |
| any | turn ends | unchanged | nothing (rule 6) |
| any | programmatic scroll | unchanged | never reaches `onPreScroll` as `UserInput`/`Wheel`, so no self-scroll flag is needed (jewel.md 1) |

`↓ N new` affordance **[proposal]**: an `OutlinedButton` (28 dp tall, pill) centred 12 dp above the transcript's bottom edge (ref §3.12 proposes a round icon button 44 dp above the composer; a pill carries the count as text), shown when `!following && listState.canScrollForward`; text `↓ 3 new` (rows appended while paused) or `↓` when 0; `contentDescription = "Jump to bottom"`, `testTag("jump-to-bottom")`. The transcript container carries `semantics { contentDescription = "Transcript" }`, a `FocusRequester`, `focusable()` and `onPreviewKeyEvent(::scrollKeys)`, and takes focus on a mouse press through `pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); listFocus.requestFocus() } }` (`focusable()` alone is reachable by Tab but never takes focus on a click; the Initial pass leaves row clicks working). Keyboard scrolling on the focused transcript (pure `scrollKeys` mapping unit-tested): `PageUp`/`PageDown` by `viewportSize.height − 40 dp`, `Home`/`End` to the ends; a `PageDown` that lands on the bottom emits `ReachedBottom`. The scrollbar is the IDE's: `VerticallyScrollableContainer(listState, Modifier.fillMaxSize()) { LazyColumn(state = listState, …) }`. Row expand/collapse never scrolls.

### 4.17 Settings

Settings | Tools | Tandem gains a checkbox `Show thinking rows` (`TandemSettings.showThinking: Boolean = true`, application level, roaming disabled like the rest). Expansion state is per session in memory, not persisted.

### 4.18 Keyboard map (whole chat)

| Key | Composer focused | Card open (card focused) | Transcript focused |
|---|---|---|---|
| Enter | send; nothing while `busy` (4.15) | card default: `Allow once` / `Submit answers` / `Approve and start coding` | toggle the focused row |
| Shift+Enter | newline | — | — |
| ⌘/Ctrl+Enter | no action in 2a (queue-for-later is 2b, ref §3.11) | `Always allow for session` (permission card, when offered) | — |
| Esc | stop the turn (when busy) | `Deny` / `Cancel` / `Keep planning` | focus the composer |
| 1–9 | typed | pick / toggle an option (question card, outside the `Other` field) | — |
| ←/→ | caret | switch question tabs (strip focused) | — |
| Tab / Shift+Tab | next / previous control inside the `chatFocus` group (`TandemToolWindow.kt` stays) | the card's buttons and rows (`focusGroup`) | rows with chevrons are focusable |
| PageUp / Home | caret | — | scroll and pause following |
| PageDown | caret | — | scroll; emits `ReachedBottom` when it lands on the bottom |
| End / Ctrl+End | caret | — | jump to bottom and follow |

Esc at the root (closes the drawer) keeps priority while the drawer is open. New focusable elements stay inside the `chatFocus` group so the sidebar's Tab-order assertions hold.

### 4.19 Semantics and test hooks (decision 11)

| Widget | Semantics |
|---|---|
| tool row / group row / sub-row | `semantics(mergeDescendants = true) { role = Role.Button; contentDescription = <label text without caps> }` |
| thinking row | `role = Role.Button`, `contentDescription = "Claude, thinking"` (streaming) / the label |
| working row | `semantics(mergeDescendants = true) { contentDescription = <4.9 label> }`, `testTag("working-row")`, one `Text` node merged into the same node |
| transcript container | `contentDescription = "Transcript"`, focusable, takes focus on a mouse press (4.16) |
| turn footer | one `Text` node (`starts-with(@visible_text, 'Worked for')`), `testTag("turn-footer")` |
| user bubble | one `Text` node, `testTag("user-bubble")` |
| assistant paragraphs | Jewel's `Markdown` emits one node per paragraph; rows merge nothing above it, so `@visible_text` exact matches (`pong`, `done.`, `denied.`) keep working |
| cards | `testTag` + `contentDescription` per 4.12–4.14; the title is one `Text` node |
| card buttons | `role = Role.Button` (Jewel), `contentDescription = <label>` (the caps are a second `Text`) |
| question options | Jewel `RadioButtonRow` / `CheckboxRow` with `contentDescription = <label>` |
| Send / Stop | `IconButton` with `contentDescription` `Send` / `Stop` |
| jump button | `contentDescription = "Jump to bottom"`, `testTag("jump-to-bottom")` |
| fence copy | `contentDescription = "Copy code"` |
| composer | unchanged Jewel `TextArea` (the last `@editabletext` node); the placeholder is not an editable node |
| pickers, status line, sidebar | unchanged |

The IDE Driver reports `ComposeNode`s with 0×0 bounds and clicks at `Point(10,10)` (tests.md), so every clickable row is ≥ 24 dp tall with the hit area starting at its top-left; `testTag` exposure in the Driver is open item 8 and `contentDescription` is the fallback every locator uses.

### 4.20 Every string the chat shows (new or changed; the hard-rule-8 checklist)

| Where | Strings |
|---|---|
| Removed | `You`, `Claude`, `Claude ▍`, `Tool result`, `Tandem` (row labels), `working…` (header), `Stopped.` (as a rendered notice), `**Failed:** `, `Allow <tool>?`, `Allow for this session`, `Send`, `Stop` (as text; they stay as `contentDescription`) |
| Tool rows | `Reading <name>`, `Read <name>`, `Read <name>:<a>-<b>`, `Editing <name>`, `Edited <name> +A -B`, `Creating <name>`, `Created <name> +N -0`, `Writing <name>`, `Running <cmd>`, `<desc>` (conjugated: `Creating empty fixture file`), `Ran <desc>`, `Ran <cmd>`, `Searching for <pattern>`, `Searched for <pattern>`, ` · N matches`, ` · 1 match`, `Finding files <pattern>`, `Found N files`, `Found 1 file`, `Found no files`, `Fetching <host>`, `Fetched <host>`, `Searching the web for "<q>"`, `Searched the web for "<q>"`, `Running an agent: <d>`, `Ran an agent: <d>`, `Done (N tool uses · T tokens · D)`, `N tool uses · T tokens · D`, `Asking a question`, `Asked a question`, `Question cancelled`, `Proposing a plan`, `Plan approved`, `Plan sent back`, `Failed to enter plan mode`, `Updating tasks`, `Updated tasks`, `Using <server>: <tool>`, `Used <server>: <tool>`, `Using <Name>`, `Used <Name>`, `Used a tool`, `Failed to …` (4.6.2), `Failed to load tools`, ` · Denied`, ` · Interrupted`; groups: `Read N files`, `Read <a>, <b>`, `read a file`, `reading …`, `searched for N patterns`, `searched for a pattern`, `found files`, `edited N files`, `edited a file`, `created N files`, `created <name>`, `ran N commands`, `ran a command`, `fetched N pages`, `fetched a page`, `searched the web`, `used N tools`, `used a tool`, trailing `…`; bodies: `Show all N lines`, `… N more lines`, `INPUT`, `OUTPUT`, `[image W×H png]`, `Reply: <text>`, `Feedback: <text>`, `$ <command>` |
| Thinking | `Thinking…`, `Thinking… · N tokens`, `Thought for Ns`, `Thinking` |
| Working row and footer | `<Step>… · <elapsed>`, `… · <n> tokens`, `Waiting for you · <elapsed>`, `Not responding · <elapsed>`, `Worked for <duration>`, `Interrupted · What should Claude do instead?`, `Server error mid-response. The response above may be incomplete.`, `API Error: <status> <error>`, `The turn failed (<error>)`; retry notice `<status> <error> · Retrying in Ns · attempt a/b` |
| Permission card | `Allow Claude to Run <cmd>?`, `… Read <name>?`, `… Edit <name>?`, `… Create <name>?`, `… Fetch <url>?`, `… Search the web for "<q>"?`, `… Run an agent?`, `… Use <server>: <tool>?`, `… Use <Name>?`, `From the agent "<d>"`, `Deny`, `Always allow for session`, `Allow once`, caps `Esc`, `⌘↩` / `Ctrl↩`, `↩`, `Show more`, `Show less`, `1 more waiting` / `N more waiting` |
| Question card | `Other`, placeholder `Something else…`, `Cancel`, `Submit answers`, ` ·` (unanswered tab marker), caps `1`–`9` |
| Plan card | `Approve this plan?`, `From the plan file <name>`, `From Claude's draft of <name> (the plan file was not saved)`, `From Claude's last message (no plan file)`, `Claude sent no plan text.`, `Open plan file`, placeholder `What should change?`, `Keep planning`, `Approve, keep current mode`, `Approve and start coding`, `Show more`, `Show less` |
| Composer | placeholder `Describe a task or ask a question`; `contentDescription` `Send`, `Stop` |
| Autoscroll | `↓`, `↓ N new`; `contentDescription` `Jump to bottom`, `Transcript` |
| Settings | `Show thinking rows` |
| Accessibility | `Claude, thinking`, `Claude is working`, `Claude is waiting for you`, `Claude is not responding`, `Permission request`, `Question from Claude`, `Plan approval`, `Copy code`, row `contentDescription` = the row label |
| Fake CLI (tests only) | `TANDEM_FAKE_LINE_DELAY_MS` |

None says "Claude Code"; `Claude` alone is fine (CLAUDE.md hard rule 8). T16 greps `src/main` for the phrase.

---

## 5. Data model

### 5.1 `protocol/` (T1; every field has a fixture test, 10.1)

All new fields are optional and validated (`?: null` on a type mismatch, never throw; `RobustnessTest` sweeps); existing fields keep their names; `Unknown(raw)` stays the fallback.

```kotlin
// user lines (protocol.md 6.2; probes §2.4): Ndjson.decodeUser reads BOTH the stdout and the on-disk names
data class User(
    val uuid: String?, val parentToolUseId: String?, val content: List<ContentBlock>, val isReplay: Boolean, val isSynthetic: Boolean,
    val toolUseResult: JsonElement?,            // "tool_use_result" on stdout, "toolUseResult" on disk; an object, or the string "Error: …" on failures
    val toolResultMeta: List<ToolResultMeta>,   // "tool_result_meta"[] on stdout; on disk one entry from the top-level "permissionDecision" + "toolDenialKind",
                                                // with id = the line's single tool_result block's tool_use_id (on-disk lines carry one result per line;
                                                // a line with several blocks and a permissionDecision applies it to each)
    val agentId: String?, val subagentType: String?, val taskDescription: String?,   // "agent_id" / "agentId"
    val timestampMillis: Long?,                 // "timestamp" ISO-8601
    override val raw: JsonObject,
) : CliMessage
data class ToolResultMeta(val id: String, val decision: String?, val source: String?, val reasonType: String?, val nonExecutionKind: String?)
// ContentBlock.ToolResult(toolUseId, content: JsonElement?, isError) unchanged; content stays the raw string or block array (text, image, tool_reference)

// assistant lines (probes §2.3, §2.5): decodeAssistant reads "thinking_duration_ms" / "thinkingDurationMs"
data class Assistant(…existing…, val thinkingDurationMs: Long?, val thinkingDisplay: String?, val agentId: String?, val subagentType: String?, val taskDescription: String?, val timestampMillis: Long?)

// stream events (probes §2.3, §2.6)
data class MessageStart(val messageId: String?, val model: String?, val thinkingDisplay: String?, val inputTokens: Long?)   // sum of input_tokens + cache_creation_input_tokens + cache_read_input_tokens
data class MessageDelta(val stopReason: String?, val outputTokens: Int?, val thinkingTokens: Int?)
sealed interface Delta { data class Thinking(val thinking: String, val estimatedTokens: Int?) : Delta; … }

// system subtypes (protocol.md 5.2)
data class ThinkingTokens(val estimatedTokens: Int, val estimatedTokensDelta: Int?, val userMessageUuid: String?, override val raw: JsonObject) : CliMessage
data class TaskStarted(val taskId: String, val toolUseId: String?, val description: String?, val subagentType: String?, val taskType: String?, val isBackgrounded: Boolean, override val raw: JsonObject) : CliMessage
data class TaskProgress(val taskId: String, val toolUseId: String?, val description: String?, val usage: TaskUsage?, val lastToolName: String?, override val raw: JsonObject) : CliMessage
data class TaskUpdated(val taskId: String, val status: String?, override val raw: JsonObject) : CliMessage   // status read from "patch"."status" (agent-sync:48; the line has no tool_use_id, task_type or subagent_type)
data class TaskNotification(val taskId: String, val toolUseId: String?, val status: String?, val summary: String?, val usage: TaskUsage?, override val raw: JsonObject) : CliMessage
data class TaskUsage(val totalTokens: Long?, val toolUses: Int?, val durationMs: Long?)
// SystemInit.cwd is already decoded (Ndjson.kt:45)

// result (protocol.md 7)
data class Result(…existing…, val durationMs: Long?, val durationApiMs: Long?, val outputTokens: Int?, val numTurns: Int?, val permissionDenials: List<PermissionDenial>)
data class PermissionDenial(val toolName: String, val toolUseId: String?)

// control requests (protocol.md 4.3)
data class CanUseTool(…existing…, val requiresUserInteraction: Boolean, val decisionReasonType: String?, val agentId: String?)
// control_response: "response" absent on a set_max_thinking_tokens success (think-summarized:25) -> payload null (already tolerated; a test pins it)
// rate_limit_event, command_lifecycle, session_state_changed, ui_invalidate stay Unknown (deferred; the existing `undocumented types` test lists them)

// HostMessages
fun initialize(requestId: String): JsonObject   // {"subtype":"initialize","hooks":null,"forwardSubagentText":true}
// allow(requestId, toolUseId, updatedInput, updatedPermissions) and deny(requestId, toolUseId, message, interrupt) unchanged
```

### 5.2 `session/` (T2, T3)

```kotlin
sealed interface SessionEvent {
    data class Cli(val message: CliMessage, val atMillis: Long = 0L) : SessionEvent      // host clock; TranscriptReader passes the line's timestamp
    data class UserSent(val uuid: String, val text: String, val atMillis: Long = 0L) : SessionEvent
    data class Ended(val detail: String?, val isError: Boolean, val endedByHost: Boolean, val atMillis: Long = 0L) : SessionEvent
    // ModelRequested, PermissionModeRequested, PermissionAnswered, HostNotice unchanged
}

data class ChatState(
    …existing…,
    val cwd: String? = null,                   // system/init.cwd; on reload the transcript line's cwd
    val turn: TurnProgress? = null,            // set on UserSent, cleared on result / Ended
    val tasks: Map<String, TaskInfo> = emptyMap(),   // by task_id (persistent map); local_bash tasks are not stored
)
data class TurnProgress(val userUuid: String, val startedAtMillis: Long, val outputTokens: Int, val thinkingEstimate: Int, val lastToolUseId: String?, val toolCount: Int, val lastError: String? = null)   // lastError: `API Error: <api_error_status> <error>` of the turn's latest assistant.error line (invalid-model:4), for the FAILED footer (4.10)
data class TaskInfo(val taskId: String, val toolUseId: String?, val description: String?, val subagentType: String?, val status: String?, val lastToolName: String?, val totalTokens: Long?, val toolUses: Int?, val durationMs: Long?, val summary: String?)

sealed interface ChatEntry {
    data class User(override val id: String, val text: String, val sentAtMillis: Long? = null) : ChatEntry   // defaults keep every existing constructor call in ReducerTest, TranscriptReaderTest, ChatTranscriptTest compiling
    data class Assistant(…existing…, val agentId: String?, val subagentType: String?, val taskDescription: String?) : ChatEntry
    data class ToolResult(
        override val id: String, val toolUseId: String, val text: String, val isError: Boolean,
        val content: JsonElement? = null, val result: JsonElement? = null,         // raw block content (images kept); tool_use_result (object or "Error: …" string)
        val decision: PermissionDecision? = null, val parentToolUseId: String? = null, val agentId: String? = null, val atMillis: Long? = null,
    ) : ChatEntry
    data class Notice(override val id: String, val level: NoticeLevel, val text: String, val kind: NoticeKind = NoticeKind.GENERIC) : ChatEntry
    data class TurnEnd(override val id: String, val end: dev.daarkolabs.tandem.session.TurnEnd, val durationMs: Long?, val outputTokens: Int?, val denials: List<PermissionDenial>, val error: String? = null) : ChatEntry   // id "end-<turnsEnded>"; error: FAILED only, turn.lastError else result.error / the Ended detail (4.10)
}
enum class NoticeKind { GENERIC, INTERRUPTED }
data class PermissionDecision(val decision: String, val source: String?, val reasonType: String?, val nonExecutionKind: String?)

sealed interface AssistantBlock {
    data class Thinking(val text: String, val estimatedTokens: Int? = null, val durationMs: Long? = null, val startedAtMillis: Long? = null, val completedAtMillis: Long? = null) : AssistantBlock
    // Text, ToolUse(id, name, input, partialInput), Other unchanged
}

data class PendingPermission(…existing…, val displayName: String?, val requiresUserInteraction: Boolean, val decisionReasonType: String?, val agentId: String?, val blockedPath: String?)
```

Reducer rules added (each pinned by a fixture test, 10.2); everything else is unchanged:

| Event | Rule |
|---|---|
| `UserSent` | appends `User(sentAtMillis)`; `turn = TurnProgress(uuid, atMillis, 0, 0, null, 0)` replaces any open turn (a live composer sends nothing while `busy`, 4.15, but `TranscriptReader` feeds consecutive `UserSent` with no `result` lines, session.md: the previous turn's calls without a result read `INTERRUPTED` per 5.4 and `settled()` clears the last turn); `busy = true` (as today) |
| `system/init` | `cwd` from the line (plus today's fields) |
| `content_block_start(thinking)` | draft `Thinking("", null, null, startedAtMillis = at, null)` |
| `thinking_delta` | appends text; `estimatedTokens` = the delta's `estimated_tokens` when not null |
| `system/thinking_tokens` | `turn.thinkingEstimate = estimated_tokens`; also sets `estimatedTokens` on the last `Thinking` block with `completedAtMillis == null` of the last streaming main-thread `Assistant` entry (`parentToolUseId == null`; the line carries only `user_message_uuid`, no `api_message_id`, and subagents emit no stream frames, probes §2.5); no-op on the block when none is open |
| complete `assistant` with a thinking block | the block's `durationMs` from the line's `thinking_duration_ms`, `completedAtMillis = at`; the draft's text is kept when the line's is empty |
| `assistant` line with `error` (`invalid-model:4`) | today's notice, plus `turn.lastError = "API Error: <api_error_status> <error>"` (`API Error: 404 model_not_found`; `API Error: <error>` when the status is null) |
| `content_block_start(tool_use)` (main thread) | `turn.lastToolUseId = id`, `turn.toolCount + 1` (verb re-roll seed) |
| `message_delta` | `turn.outputTokens += output_tokens`; `turn.thinkingEstimate = 0` (the final count now includes it) |
| `user` with `tool_result` blocks | one `ToolResult` per block with `content`, `result` (the line's `tool_use_result`; the same element on every block of the line), `decision` from the meta entry whose `id` matches, `parentToolUseId`, `agentId`, `atMillis`; appended even while the parent message streams (`ask-multi-array:30/31`, probes §2.1 stream order) |
| `user` text starting with `[Request interrupted` | `Notice(INFO, "Stopped.", kind = INTERRUPTED)`, `turnInterrupted = true` (text unchanged so the existing reducer test and the IDE test stay green until T7; the builder never renders an INTERRUPTED notice as a notice, 4.10) |
| `task_started` / `task_progress` / `task_updated` / `task_notification` | `task_started` creates `tasks[task_id]` only when `task_type == "local_agent"` or `subagent_type` is set (`agent-bash-task:21` is dropped); `task_progress` / `task_updated` (`patch.status`) / `task_notification` update an existing `tasks[task_id]` by `task_id` and are dropped when no entry exists (`agent-sync:48` and `:49` carry neither `task_type` nor `subagent_type`, probes §2.5, so they can never create one) |
| `can_use_tool` | `PendingPermission` with the new fields; `ExitPlanMode` with `input {}` is still a pending permission |
| `result` | appends `TurnEnd(end, duration_ms, usage output tokens, permission_denials, error = turn.lastError ?: the result's error text when FAILED)`; `turn = null`; `busy = false`; pending cleared (as today) |
| `Ended` while `turn != null` and `!endedByHost` | appends `TurnEnd(FAILED, atMillis − turn.startedAtMillis, turn.outputTokens, [], error = turn.lastError ?: detail)` before the existing ERROR notice; `turn = null` (today's `failedTurn` rule keeps its `endedByHost` guard, `Reducer.kt`) |
| `Ended` while `turn != null` and `endedByHost` | appends `TurnEnd(INTERRUPTED, same duration, turn.outputTokens, [])` and the existing INFO notice, so the open rows read `· Interrupted` and the `Interrupted · …` footer shows; `turn = null` |
| `keep_alive`, `Unknown`, `content_block_stop` | state unchanged, no emission (as today); the frame time is tracked outside the state |

`settled()` also clears `turn`. Reading the subagent lines and `tasks` stays cheap: both are persistent maps.

Session API (T3), replacing the two fixed paths with one:

```kotlin
sealed interface PermissionAnswer {
    data class Allow(val updatedInput: JsonObject, val updatedPermissions: List<JsonObject> = emptyList()) : PermissionAnswer
    data class Deny(val message: String) : PermissionAnswer
}
object PermissionAnswers {   // session/, pure; tested with the fixtures' request inputs
    fun allowOnce(p: PendingPermission) = Allow(p.input)
    fun allowForSession(p: PendingPermission) = Allow(p.input, sessionPermissionUpdates(p.suggestions))   // addRules only, destination "session" (PermissionUpdates.kt)
    fun deny() = Deny("The user denied this request.")
    fun questionAnswers(p: PendingPermission, answers: Map<String, String>): Allow   // {questions: p.input.questions (unchanged), answers}
    fun cancelQuestion() = Deny("cancelled")
    fun approvePlan(p: PendingPermission, startCoding: Boolean): Allow   // Allow(p.input, if (startCoding) listOf(setMode acceptEdits session) else emptyList())
    fun keepPlanning(feedback: String) = Deny("No, keep planning" + (if (feedback.isNotBlank()) ": ${feedback.trim()}" else ""))
}
// ClaudeSession
fun answer(permission: PendingPermission, answer: PermissionAnswer)   // outbox.trySend(HostMessages.allow/deny), then PermissionAnswered (non-blocking, hard rule 1)
val lastFrameAtMillis: StateFlow<Long>                               // updated in readLoop on EVERY stdout line (keep_alive, unknown included); no ChatState emission
// SessionManager
fun answer(id: SessionId, requestId: String, answer: PermissionAnswer)   // looks the PendingPermission up as allow/deny do today; no-op when it is gone
fun allow(id, requestId, always) = answer(id, requestId, if (always) allowForSession(p) else allowOnce(p))   // kept for the existing tests
fun deny(id, requestId) = answer(id, requestId, deny())
// SessionHandle mirrors lastFrameAtMillis
```

Exact frames the host writes (request ids and tool-use ids from the fixture lines; key order as `buildJsonObject` emits):

```json
// AskUserQuestion, single (ask-single:22)
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-27>","response":{"behavior":"allow","updatedInput":{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"Red"},{"label":"Blue","description":"Blue"}],"multiSelect":false}],"answers":{"Which colour?":"Blue"}},"toolUseID":"<id-12>"}}}
// AskUserQuestion, multi (ask-multi:27, request <request_id-32>): "answers":{"Which toppings would you like?":"Cheese, Ham"}   // Other (ask-other:26): "answers":{"Which colour?":"Green"}
// AskUserQuestion, cancel (ask-deny)
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-27>","response":{"behavior":"deny","message":"cancelled","toolUseID":"<id-12>"}}}
// ExitPlanMode, approve and start coding (plan-approve:88 -> :90 status acceptEdits)
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-97>","response":{"behavior":"allow","updatedInput":{"plan":"<as received>","planFilePath":"<path>"},"updatedPermissions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}],"toolUseID":"<id-92>"}}}
// ExitPlanMode, approve keeping the mode (plan-disallowed:173 -> :177 status default): the same without "updatedPermissions"; "updatedInput":{}
// ExitPlanMode, keep planning (plan-deny:79 -> :81)
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-88>","response":{"behavior":"deny","message":"No, keep planning: also add a date","toolUseID":"<id-83>"}}}
// Permission allow once / always for session / deny: unchanged shapes (HostMessagesTest); updatedPermissions = addRules only, destination "session"
// initialize: {"type":"control_request","request_id":"tandem-1","request":{"subtype":"initialize","hooks":null,"forwardSubagentText":true}}
```

Spawn: `SessionConfig.argv()` adds `--thinking-display summarized` after `--include-partial-messages` (`think-display-summarized:8`; hidden flag accepted by 2.1.302). No `set_max_thinking_tokens` request is sent (the fake CLI answers host control requests in recorded order, tests.md, so a second request at start would take the next recorded ack, e.g. the `set_permission_mode` ack in `permission-mode.ndjson`, and every later request in that fixture would be off by one). `ClaudeSession` takes `clock: () -> Long = System::currentTimeMillis` for the event stamps. Nothing else in the environment changes.

### 5.3 `chat/`: the row model

Package `dev.daarkolabs.tandem.chat` (`src/main/kotlin/dev/daarkolabs/tandem/chat/`), pure Kotlin. Imports allowed: the JDK, kotlinx.collections.immutable, kotlinx.serialization.json (to read `tool_use_result`), `session/` and `protocol/` types. No Compose, no Jewel, no IntelliJ. Added to the Kover report filter and the 90 % rule (`packages("dev.daarkolabs.tandem.protocol", "dev.daarkolabs.tandem.session", "dev.daarkolabs.tandem.chat")`), to `CLAUDE.md`'s layout list and to `docs/design.md`'s diagram. Row types hold only `String`, `Int`, `Long`, `Boolean`, enums and `ImmutableList`s (no `JsonElement`). The Compose compiler does **not** infer the sealed interfaces (`ChatRow`, `ToolBody`, `PendingCard`, `Payload`) stable, and `chat/` is Compose-free, so `@Immutable`/`@Stable` are unavailable; with strong skipping (Kotlin 2.4.20) unstable parameters are compared by instance identity. Skipping therefore relies on identity: the builder keeps the previous turn's rows by key and, when a rebuilt row equals its predecessor (data-class equality; tool bodies are built once per result entry and cached by the entry's identity, so the comparison is identity-fast), returns the previous instance; the presenter reuses the cached `RenderedRow` whenever `row === cached.row`. `ChatRowsBuilderTest` `unchanged rows of the live turn keep their instances across a text delta` pins it (9.4). If open item 11 shows unchanged rows recomposing, declare the row types stable through the compiler, not annotations: `composeCompiler { stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf")) }` in `build.gradle.kts` with the line `dev.daarkolabs.tandem.chat.**`; rows then compare by `equals`, so a rebuilt-but-equal row skips too.

```kotlin
sealed interface ChatRow { val key: String; val gap: RowGap }     // key: unique in the list, stable across deltas of the same thing
enum class RowGap(val dp: Int) { NONE(0), ROW(6), SECTION(16), AFTER_BUBBLE(18), BEFORE_TEXT(20), TURN(24) }   // 4.1: 30 dp pitch = 24 dp row + ROW
// The builder picks a row's gap from the previous row: tool/thinking after tool/thinking -> ROW; the first row after a UserRow -> AFTER_BUBBLE;
// a TextRow after a tool/thinking row -> BEFORE_TEXT; WorkingRow, NoticeRow, TurnEndRow -> SECTION (16 ~ ref 17); UserRow -> TURN; the first row -> NONE.

data class UserRow(override val key: String, val text: String, val mono: Boolean, override val gap: RowGap = RowGap.TURN) : ChatRow            // "u:<entry id>"
data class TextRow(override val key: String, val markdown: String, val streaming: Boolean, override val gap: RowGap) : ChatRow                 // "t:<entry id>:<block index>"
data class ThinkingRow(override val key: String, val label: String, val summary: String, val streaming: Boolean, override val gap: RowGap) : ChatRow   // "th:<entry id>:<block index>"; expandable = summary.isNotBlank()
data class ToolRow(override val key: String, val calls: ImmutableList<ToolCall>, val label: RowLabel, val state: ToolState, val stats: DiffStats?, override val gap: RowGap) : ChatRow   // "tool:<first call id>"; 1 call = lone row, >= 2 = group
data class NoticeRow(override val key: String, val level: NoticeLevel, val text: String, override val gap: RowGap = RowGap.SECTION) : ChatRow  // "n:<entry id>"
data class TurnEndRow(override val key: String, val end: TurnEnd, val text: String, override val gap: RowGap = RowGap.SECTION) : ChatRow        // "e:<entry id>" (or "e:<notice id>" for an interrupt marker without a result)
data class WorkingRow(override val key: String = "working", val turnUuid: String, val startedAtMillis: Long, val step: String, val outputTokens: Int, val waiting: Boolean, override val gap: RowGap = RowGap.SECTION) : ChatRow   // at most one

data class RowLabel(val text: String, val spans: ImmutableList<IntRange>)   // spans render in mono; the state suffix (" · Denied") is part of text
data class ToolCall(
    val id: String, val name: String, val kind: ToolKind, val state: ToolState,
    val label: RowLabel,                      // this call's own label in its own tense (4.6.2)
    val stats: DiffStats?, val body: ToolBody,            // ToolBody.None while PENDING/RUNNING
    val children: ImmutableList<ChatRow>,     // Agent only: the subagent's rows (4.8)
    val progress: String?,                    // Agent only: live sub-line or "Done (…)"
)
enum class ToolKind { READ, FIND, SEARCH, SHELL, EDIT, CREATE, WEB_FETCH, WEB_SEARCH, AGENT, QUESTION, PLAN, PLAN_ENTER, TASKS, TOOL_SEARCH, MCP, OTHER }
enum class ToolState { PENDING, RUNNING, WAITING, DONE, FAILED, DENIED, INTERRUPTED }
data class DiffStats(val added: Int, val removed: Int)

sealed interface ToolBody {
    data object None : ToolBody
    data class Mono(val lines: ImmutableList<String>, val totalLines: Int, val firstLineError: Boolean = false) : ToolBody        // Read, Glob, Grep, Write content, errors
    data class Command(val command: String, val output: ImmutableList<String>, val totalLines: Int, val firstLineError: Boolean) : ToolBody   // Bash
    data class Markdown(val markdown: String, val title: String?) : ToolBody           // WebFetch result, plan text, agent final text; parsed on first expansion
    data class Links(val links: ImmutableList<Link>, val markdown: String) : ToolBody  // WebSearch
    data class Pairs(val pairs: ImmutableList<Pair<String, String>>) : ToolBody        // AskUserQuestion answers
    data class Json(val input: String, val output: String?) : ToolBody                 // MCP, tasks, unknown tools
    data class Diff(val path: String, val hunks: ImmutableList<DiffHunk>, val truncated: Boolean) : ToolBody   // from structuredPatch; Phase 3 renders it
    data class Image(val width: Int?, val height: Int?, val mediaType: String) : ToolBody   // rendered as "[image W×H png]" in 2a
}
data class DiffHunk(val oldStart: Int, val newStart: Int, val lines: ImmutableList<DiffLine>)
data class DiffLine(val kind: DiffKind, val text: String); enum class DiffKind { CONTEXT, ADDED, REMOVED }
data class Link(val title: String, val url: String)
```

Bodies hold at most 400 lines and carry `totalLines` for the `… N more lines` tail; a body over 50,000 characters is cut with a final line `… (cut, N more characters)`.

Key stability: a row's key never changes while the thing it shows exists, with one documented exception: when a call's result marks it `FAILED` / `DENIED` (or the turn end marks it `INTERRUPTED`) it leaves its group (4.6.4 rule 3), so the calls after it move to a new `tool:<their first id>` row; the list diff is one removal and up to two insertions, once per such call. A lone call that gains a second call keeps its key (`tool:<first id>`), only its label changes. Subagent rows inside a `ToolCall.children` are part of that one lazy item and need no list-level keys (they get keys anyway for the in-item `key()`).

### 5.4 `chat/`: builder, labels, grouping, cards, indicator, follow state

```kotlin
data class RowOptions(val showThinking: Boolean)
data class ChatRows(val rows: ImmutableList<ChatRow>, val card: PendingCard?, val moreWaiting: Int)

class ChatRowsBuilder(private val options: RowOptions) {
    fun build(state: ChatState): ChatRows
    // Cache: rows per turn (a turn = from one main-thread User entry to the next; subagent entries belong to the turn of their Agent call).
    // A cached turn is reused when its entry count and the identity of its last entry are unchanged and entries.size never shrank since
    // the cache was filled (a retraction drops the whole cache); only the current turn is rebuilt per delta, and the WorkingRow / TurnEndRow
    // are appended after it. The builder remembers cachedSize and the entry at cachedSize - 1; a build validates the cache with
    // entries.size >= cachedSize && entries[cachedSize - 1] === cachedLast (persistent-list get is O(log n)) and scans only entries from
    // cachedSize; it never rescans earlier entries, which is what the 32,000-event test pins. Rows of the live turn that equal their
    // predecessor keep the previous instance (5.3). Not thread-safe: one builder per presenter coroutine.
    // A new RowOptions value replaces the builder (and so its per-turn cache); the presenter rebuilds every turn once and keeps its
    // Markdown cache, keyed by row key (the 10.5 showThinking test relies on this).
}

object ToolKinds { fun of(name: String): ToolKind }
object ToolLabels {   // 4.6.2, pure string rules
    fun label(kind: ToolKind, name: String, input: JsonObject?, state: ToolState, result: JsonElement?, cwd: String?): RowLabel
    fun middleTruncate(s: String, max: Int): String; fun relativePath(path: String, cwd: String?): String; fun hostOf(url: String): String
    fun stripAnsi(s: String): String; fun stripSystemReminders(s: String): String
}
object ToolStates { fun of(block: AssistantBlock.ToolUse, result: ChatEntry.ToolResult?, pending: Boolean, turnOpen: Boolean): ToolState }
object ToolBodies { fun body(kind: ToolKind, input: JsonObject?, content: JsonElement?, result: JsonElement?, isError: Boolean): ToolBody; fun stats(kind: ToolKind, input: JsonObject?, result: JsonElement?): DiffStats? }
object Grouping {     // 4.6.4
    fun fold(calls: List<ToolCall>): List<List<ToolCall>>
    fun sentence(calls: List<ToolCall>): RowLabel   // past tense, or the running form with "…"
    const val MAX_NAMES = 2
}
sealed interface PendingCard { val requestId: String; val toolUseId: String?; val agentDescription: String? }
data class PermissionCard(…, val verb: String, val objectText: String, val description: String?, val payload: Payload, val canAlwaysAllow: Boolean) : PendingCard
sealed interface Payload { data class Mono(val text: String) : Payload; data class OldNew(val path: String, val old: String, val new: String) : Payload; data class Json(val text: String) : Payload; data object None : Payload }
data class QuestionCard(…, val questions: ImmutableList<Question>) : PendingCard
data class Question(val text: String, val header: String, val multiSelect: Boolean, val options: ImmutableList<Option>); data class Option(val label: String, val description: String?)
data class PlanCard(…, val plan: String?, val source: PlanSource, val planFilePath: String?) : PendingCard
enum class PlanSource { EXIT_PLAN_INPUT, PLAN_FILE_DRAFT, ASSISTANT_TEXT, NONE }
object Cards {
    fun pending(state: ChatState): Pair<PendingCard?, Int>                       // first pending permission typed by toolName; moreWaiting = count - 1
    fun permission(p: PendingPermission, cwd: String?): PermissionCard
    fun question(p: PendingPermission): QuestionCard?                            // null when input.questions is missing or malformed -> permission card
    fun plan(p: PendingPermission, state: ChatState): PlanCard
    fun planText(state: ChatState, p: PendingPermission): Pair<String?, PlanSource>
    fun answers(card: QuestionCard, picks: Map<Int, Pick>): Map<String, String>  // Pick = Options(indices) | Other(text); ", "-join in option order
}

object WorkingIndicator {
    val VERBS: List<String>                                            // appendix A (Verbs.kt)
    fun verb(turnUuid: String, lastToolUseId: String?): String
    fun step(state: ChatState, rows: List<ChatRow>, options: RowOptions): String   // 4.9: agent task_progress.description | "Thinking" (rows hidden) | verb; never equal to the last live row's label
    enum class Mode { RUNNING, WAITING, NOT_RESPONDING }
    data class View(val mode: Mode, val animated: Boolean, val text: String, val accessibleLabel: String)
    fun view(row: WorkingRow, nowMillis: Long, lastFrameAtMillis: Long, notRespondingAfterMs: Long = 60_000): View
    fun formatElapsed(ms: Long): String; fun formatTokens(n: Long): String
    fun formatDuration(ms: Long): String                               // one decimal under 10 s ("5.8s", "2.0s"), then as formatElapsed; the Agent sub-lines (4.6.2, 4.8)
    fun footer(end: TurnEnd, durationMs: Long?, error: String?, textStreamed: Boolean): String   // 4.10; FAILED: the mid-response wording when textStreamed, else error (API Error: … / The turn failed (…))
}

data class FollowState(val following: Boolean = true, val unseen: Int = 0) {
    fun on(event: ScrollEvent): FollowState
    val buttonText: String? get() = if (following) null else if (unseen > 0) "↓ $unseen new" else "↓"
}
sealed interface ScrollEvent { data object WheelUp : ScrollEvent; data object ReachedBottom : ScrollEvent; data class Appended(val rows: Int) : ScrollEvent; data object Sent : ScrollEvent; data object Jump : ScrollEvent; data object CardShown : ScrollEvent }

data class ComposerModel(val busy: Boolean, val ended: Boolean, val cardOpen: Boolean, val models: ImmutableList<ModelOption>, val requestedModel: String?, val activeModel: String?, val modelWarning: String?, val permissionMode: PermissionMode?, val effort: String?, val claudeCodeVersion: String?)
fun composerModel(state: ChatState): ComposerModel
```

Per-call state (`ToolStates.of`): `PENDING` when `block.input == null`; `WAITING` when a pending permission names the call; `RUNNING` when there is no result, `state.turn != null`, and the call lies after the last main-thread `User` entry (`turnOpen` in `ToolStates.of(block, result, pending, turnOpen)` means exactly that); otherwise (an earlier turn, or a settled state such as a reloaded transcript, which has no `TurnEnd` lines but also no `turn`) a call without a result is `INTERRUPTED`; `DONE` on a result without `isError`; `DENIED` on `decision.decision == "reject"` or, without meta, on the denial strings of 4.6.3; `FAILED` on any other error result. `ToolStatesTest` `a settled state never yields RUNNING` pins the reload case, matching 4.6.3's last row.

### 5.5 Reload parity (T15): the same rows from disk

`TranscriptReader` keeps feeding `reduce` with `Cli(User)` / `Cli(Assistant)` / `UserSent`, now stamped with the line's `timestamp` and setting `cwd` from the line's `cwd` field; the decoder reads the on-disk names (5.1), so no second code path exists:

| Row element | Live source | On disk (`fixtures/transcripts/*.jsonl`) | On reload |
|---|---|---|---|
| tool bodies, stats | `tool_use_result` | `toolUseResult` (`tools-edit.jsonl` has `structuredPatch`) | identical bodies |
| `DONE` / `FAILED` / `DENIED` | `tool_result_meta` / `is_error` | `permissionDecision` + `toolDenialKind` (`ask-deny.jsonl:20`), `is_error` in the block | identical states |
| thinking summary, duration | deltas, `thinking_duration_ms` | the block text as recorded, `thinkingDurationMs` (`think-default.jsonl`: 2143) | `Thought for 2s`; the summary only if the session ran summarized (`think-display-summarized.jsonl:18`) |
| thinking tokens | `system/thinking_tokens` | not on disk | no ` · n tokens` (the row is complete anyway) |
| `Worked for …` footer | `result` | no `result` lines | no footer; `Interrupted · …` from the marker notice only |
| working row | `turn` | settled state | none; every call without a result is `INTERRUPTED` |
| subagent children | sidechain lines with `parent_tool_use_id` | not in `<id>.jsonl` (they live in `<id>/subagents/agent-*.jsonl`, not read) | Agent row with `Done (…)` from `toolUseResult.totalToolUseCount/totalTokens/totalDurationMs` (present on disk, `agent-sync.jsonl`) and no children |
| user send time, elapsed | `sentAtMillis` | `timestamp` on the user line | unused on a settled state |
| notices (retries, fallbacks) | `system/*` lines | not on disk | absent, as today |
| `cwd` for relative names | `system/init.cwd` | every line's `cwd` | identical names |

### 5.6 `ui/`: rendered rows and per-session UI state

```kotlin
internal data class RenderedRow(val row: ChatRow, val blocks: ImmutableList<MarkdownBlock>?, val bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>> = persistentMapOf())   // blocks: TextRow; bodies: parsed ToolBody.Markdown / Links by row key or sub-row key "c:<call id>", filled by expandBody
internal data class RenderedChat(val rows: ImmutableList<RenderedRow>, val card: PendingCard?, val moreWaiting: Int, val planBlocks: ImmutableList<MarkdownBlock>?)
internal class ChatPresenter(scope: CoroutineScope, handle: SessionHandle, options: StateFlow<RowOptions>) {
    val chat: StateFlow<RenderedChat>          // built on Dispatchers.Default from handle.state.conflate(); Markdown parsed once per (row key, markdown) and cached; cache pruned when a key disappears
    val composer: StateFlow<ComposerModel>     // handle.state.map(::composerModel).distinctUntilChanged().flowOn(Dispatchers.Default): the map (models.toImmutableList()) never runs on the EDT per token
    fun expandBody(key: String)                // non-blocking: expandRequests.trySend(key) on a Channel<String>(UNLIMITED); key = a row key or a sub-row key "c:<call id>"
    // The Dispatchers.Default collector merges expandRequests.receiveAsFlow() with handle.state; a request parses that body's markdown into the same
    // (key, markdown) cache and re-emits RenderedChat with the row's bodies[key] filled; until the blocks arrive the body view draws nothing (sub-frame).
}
internal class ChatUiState {                   // one per SessionId, held in a map at the tool-window root so a switch keeps it
    private val expanded = HashMap<String, MutableState<Boolean>>()   // row key / "c:<call id>" -> one snapshot state per key
    fun expanded(key: String): MutableState<Boolean> = expanded.getOrPut(key) { mutableStateOf(false) }   // only the item that read this state recomposes on a flip
    // (a SnapshotStateMap records reads against the map object, not the key: one write would recompose every visible tool and thinking row)
    var onExpand: (String) -> Unit = {}                 // set by ChatPresenters to presenter::expandBody; the click handler never parses (hard rule 1)
    val follow: MutableState<FollowState>
    val draft: TextFieldState
    val listState: LazyListState
}
```

`ChatRowsPresenters` (today) becomes `ChatPresenters`: one `ChatPresenter` and one `ChatUiState` per open session, same lifecycle (retained across switches, dropped on close). `RowOptions` comes from `TandemSettings.showThinking` through a `StateFlow` the settings page's `apply` updates; `TandemSessions.settingsChanged()` bumps it (no message bus).

---

## 6. Components per layer

### 6.0 Data flow

```
 claude stdout line
   │ Ndjson.decode (protocol/)                       pure; unknown types -> Unknown(raw); both stdout and on-disk names
   ▼
 SessionEvent.Cli(message, atMillis)                 ClaudeSession.lastFrameAtMillis <- every line (keep_alive, unknown too), no state emission
   │ reduce(state, event) (session/)                 pure; one ChatState per line that changes something
   ▼
 ClaudeSession.state ─► SessionHandle.state : StateFlow<ChatState>
   │                      SessionHandle.lastFrameAtMillis : StateFlow<Long>
   │ ChatRowsBuilder.build(state) (chat/)            pure Kotlin, no Compose, no IntelliJ; per-turn cache
   ▼
 ChatRows(rows, card, moreWaiting) + composerModel(state)
   │ ChatPresenter (ui/)                             Dispatchers.Default, conflate(); Markdown parse cache per row key
   ▼
 StateFlow<RenderedChat>  ─► ChatPanel: VerticallyScrollableContainer(listState) { LazyColumn(items keyed by row.key) } + the ↓ button
 StateFlow<ComposerModel> ─► PromptBar, SessionHeader (distinctUntilChanged: a text delta never reaches them)
 RenderedChat.card        ─► PendingCardHost (permission / question / plan)
```

Threads: decoding and reducing run in `ClaudeSession`'s reader coroutine (as today); the builder and the Markdown parse run on `Dispatchers.Default` inside the presenter's collector; the EDT only collects ready values and lays out. No composable collects `handle.state` directly any more.

- **`protocol/`** (T1): `Ndjson` (new fields and system subtypes, both name sets), `CliMessage` (5.1), `HostMessages.initialize` with `forwardSubagentText`. Fixture tests in `FixtureDecodeTest` (10.1); `RobustnessTest` sweeps the new types.
- **`session/`** (T2, T3, T15): `ChatState` / `ChatEntry` / `AssistantBlock` / `PendingPermission` (5.2), `Reducer` rules, `SessionEvent` stamps, `PermissionAnswer` + `PermissionAnswers` (`session/PermissionAnswers.kt`), `ClaudeSession.answer` / `lastFrameAtMillis` / `clock`, `SessionConfig.argv` (`--thinking-display summarized`), `SessionHandle.lastFrameAtMillis`, `SessionManager.answer`; `TranscriptReader` passes timestamps and `cwd` through. `SessionHandle.status` unchanged (`WAITING` already covers the question and plan cards).
- **`chat/`** (new, T4, T5, T6): `ChatRow.kt` (5.3), `ChatRowsBuilder.kt`, `ToolKinds.kt`, `ToolLabels.kt`, `ToolStates.kt`, `ToolBodies.kt`, `Grouping.kt`, `Cards.kt`, `WorkingIndicator.kt` + `Verbs.kt`, `FollowState.kt`, `ComposerModel.kt`, `Text.kt` (truncation, ANSI, system-reminder stripping). No Compose.
- **`ide/`** (T8): `TandemSettings.showThinking`, the `Show thinking rows` checkbox (Kotlin UI DSL v2, next to the cap), `TandemSessions.settingsChanged()` feeding the presenters' `RowOptions`.
- **`ui/`** (T7–T14): `ChatPresenter.kt` + `ChatPresenters.kt` (replace `ChatRows.kt`, `ChatRowsPresenters.kt`, `ChatTranscript.kt`), `ChatPanel.kt` (list, nested-scroll connection, pin effect, `↓` button, scroll keys), `rows/` (`UserBubble.kt`, `TextRowItem.kt`, `ThinkingRowItem.kt`, `ToolRowItem.kt` + `ToolGroupBox.kt` + `ToolBodyView.kt`, `AgentBody.kt`, `WorkingRowItem.kt` + `Spark.kt`, `TurnEndItem.kt`, `NoticeItem.kt`), `cards/` (`CardFrame.kt`, `PermissionCardView.kt`, `QuestionCardView.kt`, `PlanCardView.kt`, `CardKeys.kt`), `PromptBar.kt` (composer keys, placeholder, icons, hoisted draft; `ComposerKeys.kt`), `SessionHeader.kt` (banner, no `working…`), `TandemColors.kt` (the spark constant) + `ChatPalette.kt` (diff colours in `remember(JewelTheme.instanceUuid)`), `Modifiers.kt` (`bottomFade`, 4.3), `ChatUiState.kt`, `TandemMarkdown.kt` (processor with GFM extensions, the `TandemBlockRenderer` subclass of `DefaultMarkdownBlockRenderer` with the copy button, 4.4). `build.gradle.kts` + `plugin.xml`: the two GFM modules.
- Jewel components used (all in 0.37.0 on 2026.1.5, jewel.md): `VerticallyScrollableContainer`, `HorizontallyScrollableContainer`, `Icon`/`IconButton`/`IconActionButton`, `DefaultButton`/`OutlinedButton`, `RadioButtonRow`, `CheckboxRow`, `TextField`/`TextArea`, `SegmentedControl`, `Link`, `Divider`, `InlineWarningBanner`, `Markdown`/`ProvideMarkdownStyling`, `Tooltip` through `WithTooltip`; Compose `FlowRow`, `animateFloatAsState`, `Modifier.rotate`, `NestedScrollConnection`.

Composable sketches the tasks implement (every API named exists in the bundled jars):

```kotlin
@Composable
internal fun ToolRowItem(rendered: RenderedRow, ui: ChatUiState) {
    val row = rendered.row as ToolRow
    val colors = JewelTheme.globalColors
    val tone = when (row.state) { ToolState.FAILED -> colors.text.error; ToolState.DENIED, ToolState.INTERRUPTED -> colors.text.disabled; ToolState.WAITING -> colors.text.warning; else -> colors.text.info }
    val expandedState = ui.expanded(row.key); val expanded by expandedState   // per-key state: only this item recomposes on a flip (5.6)
    val canExpand = row.calls.size > 1 || row.calls.single().body !is ToolBody.None
    val label = remember(row.label) { annotated(row.label) }                     // not rebuilt on every recomposition
    val angle by animateFloatAsState(if (expanded && canExpand) 90f else 0f, tween(150))
    Column(Modifier.fillMaxWidth().padding(top = row.gap.dp.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp)
                .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = row.label.text }
                .thenIf(canExpand) { clickable {
                    val open = !expanded; expandedState.value = open
                    val body = row.calls.singleOrNull()?.body
                    if (open && (body is ToolBody.Markdown || body is ToolBody.Links) && rendered.bodies[row.key] == null) ui.onExpand(row.key)   // the parse runs in the presenter (5.6), never here
                } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // no leading dot in any state (4.6.1)
            Text(label, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            row.stats?.let { DiffStatsText(it) }
            if (canExpand) Icon(AllIconsKeys.General.ChevronRight, null, Modifier.padding(start = 8.dp).size(16.dp).rotate(angle), tint = colors.text.info)
        }
        if (expanded && canExpand) { if (row.calls.size > 1) ToolGroupBox(row.calls, rendered.bodies, ui) else ToolBodyView(row.calls.single(), rendered.bodies[row.key], ui) }   // pre-rendered data, no parsing here; a sub-row's click does the same with key "c:<call id>"
    }
}

@Composable
internal fun WorkingRowItem(row: WorkingRow, lastFrameAt: StateFlow<Long>) {
    var now by remember(row.key) { mutableLongStateOf(System.currentTimeMillis()) }
    var lastFrame by remember(row.key) { mutableLongStateOf(lastFrameAt.value) }   // a plain read on the tick: the flow is never collected as state (9.6)
    LaunchedEffect(row.key) { while (true) { delay(1_000); now = System.currentTimeMillis(); lastFrame = lastFrameAt.value } }
    val view = WorkingIndicator.view(row, now, lastFrame)             // pure: mode, animated, text, accessible label
    val color = when (view.mode) { RUNNING -> JewelTheme.globalColors.text.info; WAITING -> JewelTheme.globalColors.text.warning; NOT_RESPONDING -> JewelTheme.globalColors.text.error }
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = view.accessibleLabel }.testTag("working-row"), verticalAlignment = Alignment.CenterVertically) {   // one node: description + merged text (4.19)
        Spark(animated = view.animated && !reducedMotion(), tint = if (view.mode == RUNNING) TandemColors.spark else color)
        Spacer(Modifier.width(8.dp)); Text(view.text, color = color)
    }
}
private val GLYPHS = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
@Composable private fun Spark(animated: Boolean, tint: Color) {        // leaf: only this Text recomposes per step
    var i by remember { mutableIntStateOf(4) }
    LaunchedEffect(animated) { if (animated) while (true) { delay(120); i = (i + 1) % GLYPHS.size } else i = 4 }
    Text(GLYPHS[i], color = tint, style = JewelTheme.editorTextStyle, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
}

// autoscroll wiring (T9)
fun isUser(source: NestedScrollSource) = source == NestedScrollSource.UserInput || source == NestedScrollSource.Wheel
val connection = remember { object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (isUser(source) && available.y > 0f) ui.follow.value = ui.follow.value.on(ScrollEvent.WheelUp)   // sign verified in runIde (open item 6)
        return Offset.Zero
    }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {   // after the delta is applied (4.16)
        if (isUser(source) && (consumed.y < 0f || available.y < 0f) && !listState.canScrollForward) ui.follow.value = ui.follow.value.on(ScrollEvent.ReachedBottom)
        return Offset.Zero                                                                             // available.y == 0 (horizontal wheel) never reaches here
    }
} }
val chat by presenter.chat.collectAsState()                     // RenderedChat: one new instance per presenter emission, conflated (9.9)
val previousKeys = remember { HashSet<String>() }
var previousLast by remember { mutableStateOf<RenderedRow?>(null) }
LaunchedEffect(chat) {                                          // keyed on the emission, not on rows.size: a text delta changes the tail row, not the count; the body is synchronous, so SideEffect is equivalent
    val added = chat.rows.count { it.row.key !in previousKeys }
    val tailChanged = chat.rows.lastOrNull() !== previousLast
    previousKeys.clear(); chat.rows.forEach { previousKeys += it.row.key }; previousLast = chat.rows.lastOrNull()
    if (ui.follow.value.following && chat.rows.isNotEmpty()) listState.requestScrollToItem(chat.rows.lastIndex, Int.MAX_VALUE)
    else if (added > 0 || tailChanged) ui.follow.value = ui.follow.value.on(ScrollEvent.Appended(added))   // counted by key, so grouping (a lone call joining a group shrinks the list) never miscounts
}
val listFocus = remember { FocusRequester() }
VerticallyScrollableContainer(listState, Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(connection)
            .semantics { contentDescription = "Transcript" }.focusRequester(listFocus).focusable().onPreviewKeyEvent(::scrollKeys)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); listFocus.requestFocus() } },   // a click focuses the transcript (4.16)
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        items(chat.rows, key = { it.row.key }, contentType = { it.row::class }) { RowItem(it, ui) }
    }
    ui.follow.value.buttonText?.takeIf { listState.canScrollForward }?.let { text -> JumpToBottom(text, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
        scope.launch { listState.animateScrollToItem(chat.rows.lastIndex); ui.follow.value = ui.follow.value.on(ScrollEvent.Jump) } } }
}

// card chrome, keys and focus (T10; the question and plan cards reuse it)
@Composable
internal fun CardFrame(requestId: String, tag: String, description: String, onEnter: () -> Unit, onCmdEnter: (() -> Unit)?, onEscape: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestId) { focus.requestFocus() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Column(   // the same 768 dp cap as the list and the composer (4.1)
        Modifier.widthIn(max = 768.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(8.dp))
            .background(JewelTheme.globalColors.panelBackground, RoundedCornerShape(8.dp)).padding(16.dp)
            .focusRequester(focus).focusGroup().testTag(tag).semantics { contentDescription = description }
            .onPreviewKeyEvent { e -> cardKey(e, hasCmdEnter = onCmdEnter != null)?.let { when (it) { CardKey.ENTER -> onEnter(); CardKey.CMD_ENTER -> onCmdEnter?.invoke(); CardKey.ESCAPE -> onEscape() }; true } ?: false },
        content = content,
    ) }
}
```

`cardKey(event, hasCmdEnter): CardKey?` is a pure function in `ui/cards/CardKeys.kt`, tested next to `composerKey`. `questionKey(event, otherFieldFocused: Boolean, stripFocused: Boolean): QuestionKey?` (`Pick(n)` for `1`–`9` unless the `Other` field has focus, `PrevTab` / `NextTab` for `←`/`→` only when the strip has focus) in the same file is the question card's mapping, run before `cardKey` so digits and arrows never reach `CardFrame`; both are tested in `CardKeysTest` (10.5).

---

## 7. Flows

### 7.1 A turn, start to end (slice A)

1. Send: `PromptBar` calls `manager.send(id, text)` → `UserSent(uuid, text, now)` → `User` entry, `turn` set, `busy`. The builder emits `UserRow` + `WorkingRow(step = verb, tokens 0)`; `FollowState.on(Sent)` plus one animated scroll.
2. Frames: every stdout line updates `lastFrameAtMillis`; each line that changes state yields a new `ChatState`; the presenter (conflated) rebuilds the current turn's rows only; the EDT recomposes the rows whose instances changed.
3. Thinking: `content_block_start(thinking)` → `ThinkingRow("Thinking…")` (if shown; when hidden the working row's step reads `Thinking`); `thinking_tokens` → the row's count and the working row's tokens; `thinking_delta` text appends; the complete line brings `thinking_duration_ms` → `Thought for 2s`.
4. Text: `content_block_start(text)` → `TextRow(streaming)`; each `text_delta` re-parses that block only.
5. Tool call: `content_block_start(tool_use)` → `ToolRow(PENDING)` keyed `tool:<id>` (or it joins the open group) showing its running label, no dot; the working row keeps a verb (re-rolled on this call, 4.9); the complete `assistant` line → `RUNNING` with the full label; `can_use_tool` → `WAITING` + the card + `WorkingRow(waiting)`; the answer closes the card; the `user` tool result → `DONE` / `FAILED` / `DENIED` with a body; `message_delta.usage` → tokens.
6. End: `result` → `TurnEnd` entry; the builder drops `WorkingRow` and emits `TurnEndRow("Worked for 4s")`; `busy` off.

### 7.2 Permission (`permission.ndjson:11-25`, `:56`)

Row `Running…` (`PENDING` at `:11`) → `Creating empty fixture file` (`RUNNING` at `:19`, the description conjugated, no `…`, 4.6.2), card at `:21` titled `Allow Claude to Run touch tandem-fixture.txt?` with `Create empty fixture file`; `Allow once` → `manager.answer(id, requestId, allowOnce(p))` → the frame → `PermissionAnswered` → card gone, dim lifted, focus back to the composer → `:25` result `(Bash completed with no output)` → `Ran Create empty fixture file` with `ToolBody.None` (no chevron, 4.7). The second request (`:50-52`, `rm tandem-fixture.txt` / `Remove fixture file`, card `Allow Claude to Run rm tandem-fixture.txt?`) denied (`:56`) → `Remove fixture file · Denied ›`. `control_cancel_request` removes the card the same way (an interrupt while a prompt is open).

### 7.3 Question (`ask-single:9-26`)

The row `Asking a question` appears at `:9`; the card at `:22` (before `content_block_stop :23`), rendered from the request's own input while the Assistant entry still streams. Picks → `Cards.answers` → `PermissionAnswers.questionAnswers` → the frame (5.2) → `:26` result (possibly before `message_delta`, `ask-multi-array:30/31`: the reducer appends it regardless) → `Asked a question ›` with `Which colour? → Blue`; the model's `Blue` text follows. Cancel → `cancelQuestion()` → `is_error` result with `decision reject` (`ask-deny:24`) → `Question cancelled ›`; the model carries on (`:36`).

### 7.4 Plan approval (`plan-approve:87-91`, `plan-disallowed:102-178`, `plan-deny:78-143`)

`ExitPlanMode` request → `PlanCard` with `Cards.planText`. `Approve and start coding` → `approvePlan(p, true)` → `system/status {permissionMode:"acceptEdits"}` (`:90`) → `ChatState.permissionMode` → the picker shows `Accept edits` → `:91` result → `Plan approved ›`. `Keep planning` → `keepPlanning(feedback)` → `is_error` result → `Plan sent back ›`, no status line, the picker stays `Plan`; the model writes a revised plan (`plan-deny:137`, Write `type:"update"`) and asks again (`:142-143`) → a second card. Under Tandem's argv the plan Write fails first (`plan-disallowed:102-103`): a `FAILED` row `Failed to use Write ›` whose body shows the CLI's `No such tool available` error; the card still shows the plan from that block's `input.content` with the `PLAN_FILE_DRAFT` source line (the most recent Write before the `ExitPlanMode` block; its path is `<path>` in the fixture). After `Approve, keep current mode` the CLI leaves plan mode on its own (`:177` `permissionMode:"default"`) and the picker shows `Default`; Tandem sends no mode request.

### 7.5 Subagent (`agent-sync:35-50`, `agent-forwarded:38`)

`Agent` tool_use (`:35`) → `ToolRow` with `Running an agent: Count lines in seed.txt`; `task_started` (`:37`) → `tasks[task_id]`; the subagent's lines (`parent_tool_use_id` = the call id) → entries the builder routes into `children`; `task_progress` (`:41`) → `1 tool use · 21.5k tokens · 2.0s` sub-line (`formatDuration`) and the working row's step `Running wc -l seed.txt && tail -c1 seed.txt | xxd` (4.9); the subagent's `can_use_tool` (`:43`) → the card with `From the agent "Count lines in seed.txt"`; `task_notification` (`:49`), the forwarded text, the Agent result (`:50`) → `Ran an agent: … ›` + `Done (2 tool uses · 21.8k tokens · 5.8s)`; expanded shows the nested rows and the final text.

### 7.6 Interrupt (`interrupt.ndjson`)

Esc in the composer (or the Stop icon) → `interrupt` → the `[Request interrupted by user]` marker → `Notice(kind = INTERRUPTED)`, `turnInterrupted`; `result aborted_streaming` → `TurnEnd(INTERRUPTED)`; open rows → `INTERRUPTED`; a streaming `TextRow` keeps what it has; the footer reads `Interrupted · What should Claude do instead?` (one footer: the marker notice is skipped when the `TurnEnd` exists).

### 7.7 Autoscroll

The user wheels up during a stream → `WheelUp` → paused; rows keep appending and `↓ 3 new` appears; a wheel down that reaches the bottom → following; click on `↓`, `End` or a send → animated scroll and following. A card appearing issues no scroll: the list shrinks and, while following, re-pins on the next content change.

### 7.8 Switch session, reopen, reload

`ChatUiState` per session keeps expansion, follow state, draft and list position across switches; closing a session drops its presenter and state. A reloaded transcript shows rows with reload states (5.5), no working row, no footer, no notices.

### 7.9 LaF change

`SwingBridgeTheme` re-reads the LaF and bumps `JewelTheme.instanceUuid`; every token is read in composition or under `remember(JewelTheme.instanceUuid)`; `ProvideMarkdownStyling` already keys on it; cached `MarkdownBlock`s and rows hold no colours, so nothing is re-parsed or rebuilt.

---

## 8. Errors and edge cases

| Case | Behaviour |
|---|---|
| tool result before the message closes (`ask-multi-array:30/31`, `ask-response:24/25`) | the reducer appends the `ToolResult` while the Assistant entry streams; the builder joins by `toolUseId`; the row is `DONE`; `message_stop` later only clears `streaming` |
| `control_request` before `content_block_stop` (`ask-single:21-23`, `permission.ndjson:19-21`) | the card keys on `pendingPermissions`, never on the message closing; the row is `WAITING` from the complete `tool_use` line; if the request ever arrives before the complete line (not recorded) the row is `PENDING` in `text.warning` and the card still renders from the request's own `input` |
| interrupted turn (`interrupt.ndjson`) | 7.6; a `tool_result` that still arrives after the marker (the CLI finishing a tool) upgrades the row to `DONE` / `FAILED` |
| process exit mid-turn (`Ended`) | `TurnEnd(FAILED, host-clock duration, error = detail)` → the 4.10 footer (`Server error mid-response …` when text streamed, else `The turn failed (<detail>)`) + the existing ERROR notice `The claude CLI exited with code N. The turn ended without its result.`; when the host ended the process (Stop, close) `TurnEnd(INTERRUPTED)` and the `Interrupted · …` footer instead (5.2); open rows `INTERRUPTED`; cards cleared (pending permissions are cleared today); the composer and empty-state text unchanged |
| `--thinking-display summarized` rejected by an older CLI at spawn | the process exits with an unknown-flag error → the existing error notice (hard rule 5); T8 adds a version gate if the owner's CLI needs one (open item 12) |
| `thinking_display` reads `updates` despite the flag | rows show tokens only, no text, no chevron, no error (`think-default` behaviour) |
| model fallback (`model_refusal_fallback`) / mismatch | unchanged WARNING notices as `NoticeRow`s + the header banner; `retracted_message_uuids` remove entries → their rows vanish (keys disappear; the builder drops its cache, the presenter evicts theirs) |
| very long outputs | bodies clamp at 20 lines and cap at 400 (4.7); a body over 50,000 characters is cut; labels ellipsise on one line; commands are middle-truncated at 60 by the builder; the permission payload clamps at 8 lines; a user prompt over 8 lines clamps (T14); a text block of any length is one Markdown parse on `Default` |
| 1000+ rows | only visible items compose; the builder rebuilds the current turn only; cached turns return the same row instances; the `ReducerTest` linear-time check gains a builder twin (32,000 events under 2 s); measured in the spike with a 2000-row `FakeTranscript` (10.7) |
| transcript reload of an old session | 5.5 |
| resumed session recorded in `updates` mode (`think-default.jsonl`) | `Thought for 2s` without a chevron; live turns of the same session stream summaries (the spawn flag applies to the new process; open item 13) |
| unknown tool names (`tools-read-unavailable:48-49`, `plan-disallowed:102-103`, a new CLI tool) | a known name runs under its own rule until the result arrives (`Finding files *.txt`); a `No such tool available` error relabels the call `Failed to use Glob ›` / `Failed to use Write ›` red (4.6.2), body = the error text with `<tool_use_error>` tags stripped; a name no rule knows is `Using <Name>` → `Used <Name>`; `DENIED` only from meta or the denial strings; a `FAILED` row stands alone (4.6.4 rule 3), and only a done `Used <Name>` call counts as `used a tool` in a group |
| a `tool_result` for an unknown `tool_use_id` | a `Used a tool` row with the result as body |
| several questions in one call (no fixture; probes §2.1 Unknown) | tabs; `Submit answers` waits for every tab; one `answers` map with every question; the CLI's joining of several pairs in its text is not Tandem's concern |
| malformed `AskUserQuestion` input | the generic permission card (`Allow Claude to Use AskUserQuestion?`); nothing is silent |
| `ExitPlanMode` with `{}` and no plan Write and no text (`plan-disallowed:173` variant) | card with `Claude sent no plan text.`; the buttons still work |
| deny with feedback | plan card only; the permission card's `Deny` sends the fixed message and the user types what to do instead in the composer |
| two pending requests (subagent + main thread, `agent-sync:43`) | the first shows with `1 more waiting`; answering shows the next; keys route to the shown one |
| `Not responding` during a legitimately silent long Bash (the CLI emits nothing for a 6 s Bash, `agent-bash-task`) | the row turns red after 60 s without any frame although the tool may still run; Stop stays available; `task_started` for `local_bash` counts as a frame; threshold is a constant (open item 14) |
| LaF change mid-session | 7.9; the spark keeps its coral; the working row's ticker survives (its state is in the item keyed `working`) |
| narrow tool window (350 px) | column 318 dp; bubble ≤ 270 dp; rows one line + ellipsis; card buttons wrap (`FlowRow`); question tabs scroll; the composer footer wraps the pickers; the sidebar auto-collapses below 500 (Phase 1) |
| wide window (≥ 800 px) | column 768 dp centred; nothing stretches beyond it |
| `tool_result.content` with image blocks (`misc-image:26`) | `ToolBody.Image(8, 8, "image/png")` → `[image 8×8 png]`; base64 never reaches a row; thumbnails 2b |
| an `Edit` result without `structuredPatch` (older CLI) | stats null; body `Mono(content text)` |
| `keep_alive` / unknown lines | update `lastFrameAtMillis` only; no state emission |
| card focus while typing | the card takes focus on appearance; the composer keeps its draft; verify the IME path on macOS (open item 10) |

---

## 9. Performance

What recomposes per streaming delta, and why only that:

1. `ClaudeSession.state` emits one `ChatState` (persistent list, structural sharing). `keep_alive` and unknown lines emit nothing; they only set `lastFrameAtMillis`.
2. The presenter's collector is `conflate()`d on `Dispatchers.Default`: under load it skips intermediate states. `ChatRowsBuilder.build` touches the current turn only (5.4 cache); previous turns' rows are the same instances.
3. For the streaming text row the presenter re-parses that block's Markdown (one block, not the message; today it is the whole message) and emits a new `RenderedRow` for that row only. Every other `RenderedRow` is the cached instance. Thinking summaries are plain text, never parsed. Tool bodies are built once when the result arrives; `ToolBody.Markdown` parses on first expansion.
4. `LazyColumn.items(key)`: items whose `RenderedRow` instance is unchanged skip. Identity, not stability inference, is what makes them skip: the sealed row types are unstable to the Compose compiler, so strong skipping compares them by instance, and the builder returns the previous instance for every unchanged row of the live turn (5.3); open item 11 checks it with the debug counter. Per delta exactly one item recomposes: the tail `TextRowItem`, or the row whose state changed; the `WorkingRowItem` recomposes once a second on its own tick and when its `WorkingRow` data changes (step, tokens, waiting), never per frame and never per tick of other rows. A debug-only recomposition counter (`SideEffect` behind `-Dtandem.spike=true`) in the row composable verifies this in the spike; it is not a test.
5. `PromptBar`, `SessionHeader` and `PendingCardHost` collect `presenter.composer` / `chat.card` with `distinctUntilChanged`, so a text delta does not reach them (today three composables recompose per token, ui.md).
6. Timers: the elapsed ticker is a `LaunchedEffect(row.key)` with `delay(1000)` inside the `WorkingRowItem`, writing a `mutableLongStateOf` only that item reads; the not-responding check reads `handle.lastFrameAtMillis.value` on that one-second tick; the flow is never collected as state, so a frame never recomposes the row (recovery from `Not responding` is within 1 s); the glyph cycle is the leaf `Spark` composable with its own `delay(120)` loop, cancelled when the row leaves the composition; the chevron rotation is `animateFloatAsState` on the row. None of these touch the list state, the presenter, the reducer or the session.
7. Markdown parse cache: `HashMap<String, Pair<String, ImmutableList<MarkdownBlock>>>` keyed by row key, hit when the markdown string is equal, pruned when the key leaves the list. Parse cost of a 5 KB block is well under a frame on a worker thread (jewel.md 4, unmeasured); the EDT never calls `MarkdownProcessor`.
8. Expanding a row writes one per-key `MutableState` (`ChatUiState.expanded(key)`, 5.6); only the item that reads it recomposes (a `SnapshotStateMap` would invalidate every reader of the map); no parsing, no IO in the click handler (`Show all` flips a flag; a Markdown body's first expansion goes through `expandBody`, off the EDT, 5.6).
9. Autoscroll pins with the non-suspending `requestScrollToItem` per row-list emission while following; no effect keyed on markdown length.
10. Memory: bodies ≤ 400 lines; the `ChatState` keeps the `JsonElement` results (already in memory as `raw` today); the builder holds one row list per turn.
11. The hard rule stays measurable: `EdtStallMonitor` in the spike run (`xvfb-run ./gradlew runIde -Ptandem.spike=true`); target as in Milestone 0: p99 EDT wait ≤ 6 ms while 2000 rows stream at 50 tokens/s, no stall over 100 ms after the first Markdown composition.

Hard-rule checks: no parsing, IO or JSON in composition or click handlers; theme reads in composition only (rule 1); no new process, no Node (2); credentials untouched (3); `Edit`/`Write` stay in `--disallowedTools` (4); every CLI surprise is visible: `Not responding`, `Failed to …`, `· Denied`, the footer, the notices, `Used a tool` (5); every decoded field has a fixture test and unknown lines stay `Unknown` (6); nothing bundled (7); `Claude` and `Tandem`, never "Claude Code" (8).

---

## 10. Testing (decision 11)

Every new decoder field and reducer rule names a fixture; every pure function has a unit test; the host's frames are asserted from `TANDEM_FAKE_LOG`; the IDE Starter tests cover the four behaviours the owner can see. Test names are exact (backtick names, JUnit 5; `ReducerTest` style). Nothing here needs a real `claude`.

### 10.1 Protocol fixture tests (`src/test/kotlin/…/protocol/FixtureDecodeTest.kt`, T1)

| Test | Fixture:line | Asserts |
|---|---|---|
| `ask-single: the question request decodes with requires_user_interaction and the answer result carries tool_use_result` | `ask-single:22,26` | `CanUseTool.requiresUserInteraction`, no suggestions, `input.questions[0].options.size == 2`; `User.toolUseResult` object with `answers`; `toolResultMeta[0]` = `accept` / `user_temporary` |
| `ask-deny: a denied question result is an error string with a reject decision` | `ask-deny:24` | `toolUseResult` is a `JsonPrimitive` `"Error: cancelled"`; `nonExecutionKind == "permission-rule"`; decision `reject` / `user_reject` |
| `ask-deny: result lists the denied call` | `ask-deny:40` | `permissionDenials == [AskUserQuestion]` |
| `plan-approve: ExitPlanMode carries the plan and the status line names the new mode` | `plan-approve:88,90` | `input.plan` non-blank, no suggestions; `SystemStatus.permissionMode == ACCEPT_EDITS` |
| `plan-disallowed: ExitPlanMode with an empty input still decodes as a permission request` | `plan-disallowed:173` | `input` empty, `requiresUserInteraction` |
| `think-display-summarized: thinking deltas carry text and thinking_tokens lines decode` | `think-display-summarized:10,11,34` | `Delta.Thinking.thinking == "47"`, `estimatedTokens == null`; `ThinkingTokens(1, 1)`; `Assistant.thinkingDurationMs == 1991`, `thinkingDisplay == "summarized"` |
| `think-display-summarized: message_start names the thinking display` | `think-display-summarized:8` | `MessageStart.thinkingDisplay == "summarized"` |
| `misc-state-emit: message_start input tokens sum the three input fields` | `misc-state-emit:9,16` | `MessageStart.inputTokens == 31260` (2 + 4720 + 26538) `== result.first_request_input_tokens` at `:16` |
| `misc-image: message_delta carries thinking tokens` | `misc-image:48` | `MessageDelta.thinkingTokens == 62`, `outputTokens == 135` |
| `agent-sync: subagent user lines carry subagent_type, task_description and a timestamp` | `agent-sync:38` | `User.subagentType == "general-purpose"`, `taskDescription == "Count lines in seed.txt"`, `timestampMillis` parsed from `2026-10-18T20:12:55.307Z` |
| `agent-forwarded: the forwarded assistant line carries a timestamp` | `agent-forwarded:38` | `Assistant.timestampMillis != null` |
| `permission: result carries duration_api_ms and num_turns` | `permission.ndjson:35` | `Result.durationApiMs == 3384`, `numTurns == 2`, `durationMs == 5223` |
| `misc-state-action: a default-mode request carries blocked_path and three suggestions` | `misc-state-action:21` | `CanUseTool.blockedPath == "<path>"`, `suggestions.size == 3` (`addRules`, `addDirectories`, `setMode`) → `PendingPermission.blockedPath` |
| `think-default: empty thinking deltas carry estimated_tokens` | `think-default:10,16` | `estimatedTokens == 50`; block text empty; `thinkingDurationMs == 2143` |
| `think-summarized: a set_max_thinking_tokens success has no response and the full value is an error` | `think-summarized:25,73` | success with null payload; error text starts with `set_max_thinking_tokens:` |
| `tools-read: Read results carry the file view` | `tools-read:21,38` | `toolUseResult.file.numLines == 31`; offset run `startLine == 10`; `toolResultMeta[0].source == "config"`, `reasonType == "mode"` (and `plan-approve:73` → `"other"`; `tools-bash:23` → `source == "user_temporary"`, `reasonType == null`) |
| `tools-edit: Edit and Write results carry structuredPatch` | `tools-edit:44,61` | one hunk with `-line 07 golf` / `+line 07 GOLF`; Write `type == "create"` |
| `tools-bash: a Bash failure is an error string and the request names its reason type` | `tools-bash:20,39` | `decisionReasonType == "subcommandResults"`; `isError`, `toolUseResult` primitive starting `Error: Exit code 3` |
| `tools-read-unavailable: an unknown tool error has no meta` | `tools-read-unavailable:49` | `isError`, `toolResultMeta` empty |
| `agent-sync: task lines decode and subagent lines carry agent ids` | `agent-sync:37,41,48,49,43,47` | `TaskStarted(taskType == "local_agent")`, `TaskProgress(lastToolName == "Bash", usage == TaskUsage(21510, 1, 2006))`, `TaskUpdated(status == "completed")` (from `patch.status`), `TaskNotification(summary, usage == TaskUsage(21826, 2, 5826))`; `TaskStarted.description == "Count lines in seed.txt"`, `isBackgrounded == false`; `CanUseTool.agentId`; `User.agentId == taskId`; the subagent's Bash success (`:47`) has `toolUseResult == null` (no `tool_use_result` on the line) and `toolResultMeta[0].reasonType == "subcommandResults"` |
| `agent-bash-task: a Bash task line decodes with task_type local_bash` | `agent-bash-task:21,22` | `TaskStarted(taskType == "local_bash", subagentType == null)`, `TaskNotification(usage == null)` |
| `agent-forwarded: the forwarded subagent text is an assistant line with agent_id` | `agent-forwarded:38` | `Assistant.agentId`, `parentToolUseId`, one text block `7`, `subagentType == "general-purpose"`, `taskDescription == "Count lines in seed.txt"` |
| `misc-image: an image result is a block array and an image tool_use_result` | `misc-image:26` | `content` is an array with an `image` block; `toolUseResult.type == "image"`, dimensions 8×8 |
| `misc-web: ToolSearch, WebFetch and WebSearch results decode` | `misc-web:17,34,78` | `tool_reference` content; `result` string; `results` list with a `content` list |
| `permission: message_delta usage and result durations decode` | `permission.ndjson:22,35` | `MessageDelta.outputTokens`, `Result.durationMs`, `Result.outputTokens == 90`, `permissionDenials` empty |
| `misc-state-emit: session_state_changed and rate_limit_event stay Unknown` | `misc-state-emit:3,5` | `Unknown(subtype == "session_state_changed")`; `Unknown(type == "rate_limit_event")` |
| `transcripts: on-disk tool results and thinking fields decode with their camelCase names` | `transcripts/ask-deny.jsonl:20`, `tools-edit.jsonl`, `think-default.jsonl` | `toolUseResult`, decision `reject`, `nonExecutionKind`, `structuredPatch`, `thinkingDurationMs == 2143` |
| `HostMessagesTest`: `initialize forwards subagent text` | — | exact JSON |
| `RobustnessTest`: the existing mutation sweep covers the new fields; the generic sweep also loads the 29 `.jsonl` twins (`Fixtures.transcript`) | all | never throws |

### 10.2 Reducer, session and reader tests (T2, T3, T15)

| Test | Fixture | Asserts |
|---|---|---|
| `ReducerTest` `ask-single: the question is a pending permission before the block stops and the answer links to the call` | `ask-single` | after line 22 one `PendingPermission(toolName == AskUserQuestion, requiresUserInteraction)` while the Assistant entry streams; after 26 a `ToolResult(toolUseId == <id-12>, result.answers)` |
| `ask-deny: a cancelled question is a rejected result and the turn still completes` | `ask-deny` | `ToolResult(isError, decision reject)`; final `TurnEnd(COMPLETED, denials.size == 1)` |
| `a tool result before message_delta lands on the streaming message` | `ask-multi-array` | after line 30 the `ToolResult` exists and the Assistant entry still streams; after 31/32 it does not |
| `plan-approve: the status line after approval switches the mode before the tool result` | `plan-approve` | after line 90 `permissionMode == ACCEPT_EDITS`; after 91 `ToolResult(result.plan)` |
| `plan-deny: a deny keeps plan mode and the second request is pending again` | `plan-deny` | no mode change until line 145; two pending permissions over the file, one at a time |
| `think-display-summarized: thinking text accumulates then the complete block brings the duration` | `think-display-summarized` | draft text grows per delta; after 34 `Thinking(text startsWith "47×83", durationMs == 1991)` |
| `think-default: the thinking estimate rises with thinking_tokens and clears on message_delta` | `think-default` | `turn.thinkingEstimate` 50 → 150 → 285; after `message_delta` 0 and `turn.outputTokens` = the usage |
| `tools-bash: results keep content, result and decision` | `tools-bash` | first result `decision accept/user_temporary`, `result.stdout`; second `isError`, result primitive; after line 18 `turn.lastToolUseId` == the Bash block id and `turn.toolCount == 1`, after line 34 `toolCount == 2` (the verb re-roll seed, 4.9) |
| `permission: a denied call records the reject decision` | `permission.ndjson:56` | `ToolResult(decision reject)` |
| `agent-sync: task lines fill tasks and subagent entries carry the parent id` | `agent-sync` | `tasks[<task_id>].status == completed`, `summary`, `toolUses == 2`; entries with `parentToolUseId == <id-12>` and `agentId`; after line 43 `pendingPermissions.single().agentId == "<task_id-42>"` |
| `agent-bash-task: a bash task is not stored` | `agent-bash-task` | `tasks` empty |
| `agent-forwarded: the forwarded text is a subagent assistant entry` | `agent-forwarded` | an Assistant entry with `agentId` and text `7` |
| `misc-image: an image result keeps its content array` | `misc-image` | `ToolResult.content` is a `JsonArray` |
| `UserSent records the send time and result appends a TurnEnd entry` | `plain-reply` | `turn.startedAtMillis == 1234`, `TurnEnd(COMPLETED, durationMs == <fixture>)`, `turn == null` |
| `interrupt shows Stopped and no error` (existing, extended) | `interrupt` | `Notice(kind == INTERRUPTED)`, `TurnEnd(INTERRUPTED)` |
| `Ended during a turn appends a FAILED turn end with the host duration` | hand-made | `TurnEnd(FAILED, durationMs == atMillis − startedAt, error == detail)` |
| `Ended by the host during a turn appends an INTERRUPTED turn end` | hand-made | `TurnEnd(INTERRUPTED, durationMs == atMillis − startedAt)`, an INFO notice, `turn == null` |
| `invalid-model: the assistant error line sets the turn error and the failed result carries it` | `invalid-model` | after line 4 `turn.lastError == "API Error: 404 model_not_found"`; the final `TurnEnd(FAILED, error == the same)` |
| `an api_retry line yields the reference wording` | hand-made (the existing `api_retry` line, plus `"error_status":529`) | `Notice(INFO, "529 overloaded · Retrying in 4s · attempt 2/10")`; without `error_status`: `overloaded · Retrying in 4s · attempt 2/10` |
| `init stores cwd` | `tools-read:5` | `cwd == "<path>"` |
| `the entry index stays in step through every fixture` (existing) | all 40 | unchanged |
| `PermissionAnswersTest`: `questionAnswers keeps the questions and adds the answers`, `approvePlan adds setMode only when starting to code`, `keepPlanning joins the feedback after the fixed prefix`, `allowForSession echoes addRules only` (moves from `PermissionUpdatesTest`) | request inputs from `ask-single:22`, `plan-approve:88`, `misc-state-action:21` | the exact `JsonObject`s of 5.2 |
| `TranscriptReaderTest`: `a recorded question session reloads with its answers and decision`, `a recorded edit reloads with its patch`, `a recorded thinking block reloads with its duration`, `a recorded agent session reloads the agent result without children`, `timestamps and cwd are read from the lines` | `transcripts/ask-single.jsonl`, `ask-deny.jsonl`, `tools-edit.jsonl`, `think-default.jsonl`, `agent-sync.jsonl` | the entries of 5.5 (`durationMs == 2143`) |

### 10.3 Pure presentation tests (`src/test/kotlin/…/chat/`, T4–T6)

| Class | Tests |
|---|---|
| `ToolLabelsTest` | `read rows name the file and the range` (`tools-read:17/34`), `names are relative to cwd and middle-truncated`, `bash rows show the description and fall back to the command` (`Create empty fixture file` → running `Creating empty fixture file` with no `…`, done `Ran Create empty fixture file`; no description → `Running <cmd>` / `Ran <cmd>`), `a leading Run is dropped after Ran`, `bash failure uses the description lowercased`, `write create counts the content lines` (2 for `tools-edit:57`'s `"hello\nworld\n"`, 1 for `"x"`), `write update is an edit`, `edit stats come from structuredPatch` (`tools-edit:44` → `+1 -1`), `grep and glob rows count matches and files`, `web rows name host and query` (`misc-web:29/73`), `agent rows carry the Done sub-line` (`agent-sync:50`), `tool search is hidden unless it failed`, `mcp rows name server and tool`, `unknown tools split camel case`, `denied rows use the base form` (`Remove fixture file · Denied`, `permission.ndjson:50/56`; no description → `Run <cmd> · Denied`), `interrupted rows use the base form`, `hostOf strips scheme and path`, `commands are middle-truncated at 60`, `an unavailable tool reads Failed to use` (`tools-read-unavailable:49` → `Failed to use Glob`, `plan-disallowed:103` → `Failed to use Write`), `a path equal to cwd keeps its basename` (`<path>` with `cwd == "<path>"` → `Read <path>`), `enter plan mode is hidden unless it failed` |
| `ToolStatesTest` | one test per transition of the 4.6.3 machine, named by the transition; `reload without meta still detects the denial text`; `a settled state never yields RUNNING` (no result, `turn == null` → `INTERRUPTED`) |
| `ToolBodiesTest` | `read body has line numbers from startLine` (`tools-read:38`), `bash body starts with the command and keeps the exit code line` (`tools-bash:39`), `bash no output gives no body` (`misc-state-action:25` → `ToolBody.None`), `system reminders are stripped`, `ansi is stripped`, `bodies clamp at 20 and cap at 400 with the total`, `image result reads image WxH` (`misc-image:26`), `web search body lists links` (`misc-web:78`), `answers body pairs questions with picks` (`ask-single:26`), `cancelled question body` (`ask-deny:24`), `diff body from structuredPatch` |
| `GroupingTest` | one test per row of 4.6.5, named by its sentence, plus `a text block closes a group`, `a thinking block closes a group`, `a failed call stands alone`, `a denied call stands alone`, `an interrupted call stands alone`, `agents never fold or absorb`, `hidden tool search and enter plan mode are not counted`, `a running group uses the participle and ends with an ellipsis`, `stats sum across a group`, `bash cat, find and grep count as read, find and search`, `a single call is not a group`, `reads use names only in a reads-only group` |
| `ChatRowsBuilderTest` | per fixture (`plain-reply`, `permission`, `interrupt`, `invalid-model`, `tools-read`, `tools-edit`, `tools-bash`, `tools-read-unavailable`, `think-display-summarized`, `think-default`, `ask-single`, `ask-deny`, `plan-approve`, `plan-deny`, `plan-disallowed`, `agent-sync`, `agent-forwarded`, `misc-image`, `misc-web`): replay through `reduce` with a fake clock, build after every event, assert the final row kinds, labels, states and stats (goldens in the test) and that `build` never throws; `keys are unique and stable across every state of every fixture` (replays all 40 fixtures, asserts uniqueness after every event and that a key that still refers to the same thing is unchanged, allowing the 5.3 exception); `previous turns return the same row instances` (identity over `history-reply` + a live turn); `unchanged rows of the live turn keep their instances across a text delta` (replay `tools-read`; the Read/Glob rows are the same instances before and after each `text_delta`); `showThinking false drops thinking rows without changing groups`; `the working row is last while busy and the footer replaces it`; `a failed turn ends with the failure footer` (`invalid-model`: `TurnEndRow(FAILED, "API Error: 404 model_not_found")`, an ERROR notice row only when its text differs); `a reloaded transcript has neither working row nor footer` (`history-reply.jsonl`); `a reloaded interrupted transcript shows the Interrupted footer` (`history-interrupt.jsonl`: `TurnEndRow(INTERRUPTED)` from the `NoticeKind.INTERRUPTED` notice, no notice row); `gaps follow the previous row` (one assertion per `RowGap` rule of 5.3); `a reloaded transcript builds the same tool rows as its stream` (for each of the 29 probe fixtures with a `.jsonl` twin: labels, states and stats equal, allowing the footer and children differences of 5.5); `building 32000 events stays linear` |
| `CardsTest` | `pending picks the first request and counts the rest`, `permission titles per tool` (Bash `tools-bash:20`, WebFetch `misc-web:31`, WebSearch `:74`, Read, Edit, Write create, Agent, MCP, unknown), `always allow only with addRules` (`permission.ndjson:21` yes; `ask-single:22` no), `payload per tool`, `agent description from agent_id` (`agent-sync:43`), `question card from ask-single`, `question card from ask-multi is multi-select`, `question card with two questions keeps both`, `a malformed question input falls back to the permission card`, `answers join multi-select labels in option order`, `answers use the Other text`, `plan from the request input` (`plan-approve:88`), `plan from the failed plan-file write` (`plan-disallowed:102/173`: the fixture's path is `<path>`; asserts `PLAN_FILE_DRAFT` and `plan == input.content` of line 102), `plan from the last assistant text`, `plan none`, `plan card says where the plan came from` |
| `WorkingIndicatorTest` | `verb is stable per turn and re-rolls per tool call`, `step prefers the agent progress then hidden thinking then the verb`, `step text never repeats the row above` (over every state of `tools-read`, `agent-sync` and `think-default` the step differs from the last live row's label), `formatDuration one decimal under ten seconds` (5826 → `5.8s`, 2006 → `2.0s`, 12_000 → `12s`), `text omits tokens until known`, `waiting while a card is pending`, `not responding after 60 s without a frame and back on the next`, `not animated under reduced motion`, `formatElapsed seconds minutes hours`, `formatTokens k and m`, `footer strings` (`Worked for 4s`, `Interrupted · What should Claude do instead?`), `FAILED mid-response` (`textStreamed = true` → `Server error mid-response. The response above may be incomplete.`), `FAILED before text` (`invalid-model:4` → `API Error: 404 model_not_found`; no error line → `The turn failed (boom)`) |
| `FollowStateTest` | one test per row of the 4.16 table; `a wheel down that lands on the bottom resumes` (`PAUSED` + `ReachedBottom` → `FOLLOWING`, the post-scroll case); `button text counts unseen` |
| `ComposerModelTest` | `composer model changes only on its own fields` (two states differing by a text delta give equal models) |

### 10.4 Fake-CLI tests (`session/SessionManagerTest.kt`, `ClaudeSessionTest.kt`; frames read from `TANDEM_FAKE_LOG`, T3, T11, T12)

| Test | Fixture | Asserts |
|---|---|---|
| `spawn passes thinking display summarized and initialize forwards subagent text` | `plain-reply` | `__spawn__.argv` contains `--thinking-display`, `summarized`; the initialize frame has `forwardSubagentText: true` |
| `answering a question sends the picked label` | `ask-single` | the `control_response` of 5.2 (`updatedInput.answers["Which colour?"] == "Blue"`, `toolUseID`), then `lastTurnEnd == COMPLETED` |
| `answering a multi-select joins the labels` | `ask-multi` | `"Cheese, Ham"` |
| `cancelling a question denies with cancelled and the turn goes on` | `ask-deny` | `behavior deny`, `message cancelled`; the turn completes |
| `approving a plan to start coding sends setMode and the mode follows the status line` | `plan-approve` | `updatedPermissions[0] == {setMode, acceptEdits, session}`; `state.permissionMode == ACCEPT_EDITS` |
| `approving a plan keeps the mode without permissions` | `plan-disallowed` | no `updatedPermissions`; `permissionMode == DEFAULT` after `:177` |
| `keeping planning denies with the feedback` | `plan-deny` | `No, keep planning: also add a date`; a second pending permission arrives |
| `lastFrameAt advances on every line` | `plain-reply` | monotonic, updated after the fixture's last line |
| existing `allow(always)` / `deny` tests | `permission` | unchanged |

Fake CLI addition (T16): `TANDEM_FAKE_LINE_DELAY_MS` (sleep before each `stream_event` line) for the autoscroll IDE test, documented in its docstring and `CLAUDE.md`. No other fake change: every fixture replays as is (tests.md).

### 10.5 Platform and pure `ui/` tests (T8, T9, T10)

- `ide/TandemSettingsTest.showThinkingRoundTrips` (default true, persisted); `ide/TandemSessionsTest.aPresenterRebuildsWhenShowThinkingChanges` (rows lose thinking rows after the flip); `ide/TandemSessionsTest.aQuestionAnsweredFromTheServiceReachesTheFake` (`ask-single`, asserts the frame) so the manager wiring is covered without the IDE; `ide/TandemPluginTest` asserts the two GFM modules load (T14).
- `ui/ComposerKeysTest`: `Enter sends, Enter does nothing while busy, Cmd-Enter does nothing, Shift+Enter is a newline, Esc stops while busy, Esc does nothing with a card open`; `ui/ScrollKeysTest`: `Home and PageUp pause, End resumes`; `ui/CardKeysTest`: `Enter, Cmd-Enter when offered, Esc`, `digits pick options unless the Other field has focus`, `arrows switch tabs only when the strip has focus` (`questionKey`, 6).
- `ui/ChatPresenterTest`: `expanding a markdown body parses it once off the EDT and re-emits the row` (`misc-web` replayed through a fake handle: `expandBody("tool:<id>")` → the next `chat` emission carries `bodies[key]` for the WebFetch row, parsed on `Dispatchers.Default`; a second call parses nothing).

### 10.6 IDE Starter tests (`src/integrationTest/…/ToolWindowIdeTest.kt`; `xvfb-run ./gradlew integrationTest`; each 1.5–3 min)

Locators (XPath on `//div[@class='ComposeNode' …]`, as today's helpers; the table is what 4.19 must produce):

| Widget | Locator |
|---|---|
| tool row | `@contentdescription='<label>'` (`Ran Create empty fixture file`, `Remove fixture file · Denied`; the labels the 4.6.2 Bash rule builds from `permission.ndjson:19/50`); add `@role='Button'` only once the Driver is seen to print it for a custom semantics role (open item 8); the description alone is unique |
| transcript | `@contentdescription='Transcript'` |
| thinking row | `starts-with(@contentdescription,'Thought for')` (same role caveat) |
| working row | `@contentdescription='Claude is working'` / `'Claude is waiting for you'`; its merged text via `@text` (as `button(text)` reads merged Jewel buttons), `@visible_text` as the fallback |
| turn footer | `starts-with(@visible_text,'Worked for')`, `@visible_text='Interrupted · What should Claude do instead?'` |
| card title | exact `@visible_text='Allow Claude to Run touch tandem-fixture.txt?'`, `'Allow Claude to Run rm tandem-fixture.txt?'`, `'Which colour?'`, `'Approve this plan?'` |
| card buttons | `@role='Button' and @contentdescription='Allow once'` / `'Deny'` / `'Always allow for session'` / `'Submit answers'` / `'Cancel'` / `'Approve and start coding'` / `'Keep planning'` |
| question option | `@role='RadioButton' and @contentdescription='Blue'` (or `@role='Checkbox'`; open item 8) |
| send / stop | `@role='Button' and @contentdescription='Send'` / `'Stop'` (from T9) |
| jump button | `@role='Button' and @contentdescription='Jump to bottom'` |
| prompt box | unchanged: the last `@editabletext` node (cards sit above it in tree order) |
| pickers, status line, sidebar rows | unchanged |

Tests (the four of decision 11, plus the existing six re-pointed):

- `a typed prompt is sent and the streamed reply is rendered` (existing): `iconButton("Send")`, waits for `pong` and a node starting with `Worked for`.
- `the permission card names the command and answers from the keyboard` (rewrites the permission test, T10): waits for the title node `Allow Claude to Run touch tandem-fixture.txt?`, presses Enter (allow once; Enter is sent to the focused card node: the test first asserts `@focused='true'` inside the `Permission request` node, and if the Driver cannot focus Compose cards it clicks `Allow once` by description instead and the keyboard path stays on `runIde` checklist item 6), waits for the row `Ran Create empty fixture file`; on the second card (`Allow Claude to Run rm tandem-fixture.txt?`) clicks `Deny` by description; asserts behaviors `[allow, deny]` and request ids `<request_id-26>`, `<request_id-63>` as today; asserts the row `Remove fixture file · Denied`.
- `a question card answers with the picked option` (T11, `ask-single`): waits for `Which colour?`, clicks the `Blue` option, clicks `Submit answers`; asserts the log's `updatedInput.answers["Which colour?"] == "Blue"`; waits for the reply `Blue` and the row `Asked a question`.
- `the working indicator shows while a turn runs and a footer after it` (T16, `permission` with `TANDEM_FAKE_GATE_FILE`): after Send waits for `@contentdescription='Claude is working'` and asserts the same node's `@text` (fallback `@visible_text`) contains ` · ` and ends with `s` (one merged node, 4.19); creates the gate; waits for the first card and `'Claude is waiting for you'`; answers both cards (Enter on the first, `Deny` on the second: the fixture gates two requests, `:21` and `:52`, and the result only arrives after both); after the turn waits for `starts-with(@visible_text,'Worked for')` and that the working node is gone.
- `scrolling up pauses following and the jump button catches up` (T16): seeds `history-reply.jsonl` 30× with fresh uuids into `CLAUDE_CONFIG_DIR` so the list overflows the viewport, opens it, then sends a prompt replayed from `plain-reply` with `TANDEM_FAKE_LINE_DELAY_MS=200`; clicks `@contentdescription='Transcript'` at `Point(10,10)` (inside the 16 dp content padding, so no row takes the click; the press focuses the list, 4.16), presses `Home`; waits for `Jump to bottom`; asserts the first user bubble's text node stays present (only visible items exist in the tree) and that `pong` is absent for 3 s; clicks the button; waits for `pong` and the button's absence. (If the seeded transcript does not overflow under Xvfb, a generated `synthetic-long-reply.ndjson` of 60 one-paragraph `text_delta` frames replaces `plain-reply`.)
- `Stop interrupts a running turn` (existing): waits for `Interrupted · What should Claude do instead?` instead of `Stopped.`; `iconButton("Stop")`.
- `the pickers send set_permission_mode and set_model`, `several sessions live side by side in the sidebar`, `a wide tool window docks the sidebar…` (existing): locator updates only (the status line keeps `claude-sonnet-5-5` and `mode: plan` in single nodes).

Expected wall-clock under xvfb: nine tests at 2–3 min each, 10–15 min total (tests.md measures the current six at 5m47–6m31, 2m07–2m55 per typical test); CI's ide-test job has no timeout. The plan card has no IDE test in 2a: its frames and the picker follow-through are pinned by the fake-CLI and reducer tests (10.2, 10.4); T16 adds `the plan card approves and the picker follows` only if the three new tests average under 2.5 min, otherwise it stays pinned by 10.2/10.4.

### 10.7 Manual `runIde` checklist (feel: only a human can judge; once per slice and before the PR)

1. Dark and light LaF, switched mid-stream: bubble, rows, cards, fences, diff fills follow; nothing stays the old colour; the spark stays coral.
2. The spark cycle at 120 ms next to the Desktop video (ref §3.8): if it does not read as "working", swap `Spark` for the rotated painter (open item 4).
3. Streaming a long reply (`-Ptandem.spike=true`): no visible jank; the working row stays last and is pushed down smoothly; elapsed ticks once a second without flicker; `EdtStallMonitor` reports no stall over 100 ms after the first composition; the debug counter shows only the tail row recomposing per delta; the working row once per second and when its data changes.
4. Wheel up with a trackpad during a stream: the view must not creep; `↓ N new` appears; a small wheel-down tick does not re-lock; End, the button and a send resume.
5. Tool rows: grey, quiet; chevron rotates; a group with a 500-line Bash body opens and `Show all` renders without a stall; a failed Bash is red; `· Denied` after a deny; `· Interrupted` after Stop.
6. Permission card: dims the transcript; Enter / ⌘Enter / Esc work from the card; Tab cycles the buttons; focus returns to the composer; typing in the composer when the card appears keeps the draft (IME on macOS).
7. Question card at 350 px and 900 px: tabs, radios, number keys, `Other` field, Submit enables correctly.
8. Plan mode end to end against the real CLI (`--permission-mode plan`): the source line, `Show more`, `Open plan file`, `Keep planning` with feedback, `Approve and start coding` flips the picker.
9. Thinking rows with a real Sonnet session: `Thinking… · N tokens` → `Thought for Ns`; expansion survives scrolling and switching sessions.
10. Markdown tables and strikethrough render; the fence copy button copies.
11. 350 px and 1200 px widths: no horizontal scroll, labels truncate, buttons wrap, the column is centred at 768 dp.

---

## 11. Gap table coverage (ref §5, every row)

| Ref §5 row | Specified in | Slice / phase |
|---|---|---|
| Row model | 5.3, 5.4 | A |
| User message | 4.3 | A; clamp C (T14); thumbnails, queued caption 2b |
| Assistant text | 4.4 | A; GFM + copy button C (T14); fade-in, hover row 2b |
| Thinking | 4.5, 4.17 | A |
| Tool call row | 4.6 | A |
| Tool result | 4.7, 5.3 | A; image thumbnails 2b |
| Grouping | 4.6.4 | A; verbose toggle 2b |
| Edit/Write diff | 5.3 `ToolBody.Diff`, 4.12 payload | model A; rendering Phase 3 |
| Working indicator | 4.9, 9 | A |
| Notices | 4.11 | A; banner C; usage-limit / compaction cards 2b |
| Permission prompt | 4.12 | B |
| AskUserQuestion | 4.13, 5.2 | B |
| ExitPlanMode | 4.14, 5.2 | B |
| Todo list | — | 2b |
| Subagents | 4.8, 5.2 `tasks` | A (data), C (nesting); background 2b |
| Composer | 4.15 | A; `/` menu, queue list, ring 2b |
| Autoscroll | 4.16, 5.4 | A |
| Scrollbar | 4.16 | A |
| Protocol decoding | 5.1, 10.1 | A; `rate_limit_event`, `session_state_changed`, `tool_progress`, `compact_boundary`, `informational` stay `Unknown` (2b) |

---

## 12. Task slices (SDD; each with its own tests; dependencies in brackets)

Slice A = T1–T9, B = T10–T12, C = T13–T16.

1. **T1 protocol-fields** — `Ndjson`/`CliMessage` additions of 5.1 (both name sets), `HostMessages.initialize` with `forwardSubagentText`; the 10.1 fixture tests; the `RobustnessTest` sweep over the 29 fixtures and their `.jsonl` twins (the first `./gradlew test` over them; fix a fixture, never the sweep). [none]
2. **T2 reducer-state** — `ChatState.cwd/turn/tasks`, `TurnEnd` entry, `ToolResult` fields, thinking fields, `PendingPermission` fields, `SessionEvent` stamps, the 5.2 rules; `ReducerTest` additions of 10.2. [T1]
3. **T3 session-answers-and-spawn** — `PermissionAnswer` + `PermissionAnswers`, `ClaudeSession.answer` / `clock` / `lastFrameAtMillis`, `SessionHandle.lastFrameAtMillis`, `SessionManager.answer` (+ wrappers), `--thinking-display summarized`; `PermissionAnswersTest`, the 10.4 fake-CLI tests (spawn, question, plan, lastFrameAt). [T1]
4. **T4 chat-rows** — package `chat/`, `ChatRow` model, `ToolKinds`, `ToolLabels`, `ToolStates`, `ToolBodies`, `Text` helpers, `ChatRowsBuilder` with the per-turn cache (flat rows, subagent entries routed into `children`), Kover include, `CLAUDE.md` / `design.md` layout lines; `ToolLabelsTest`, `ToolStatesTest`, `ToolBodiesTest`, `ChatRowsBuilderTest` (ungrouped goldens, key stability, instances, linear time). [T2]
5. **T5 chat-grouping** — `Grouping.fold/sentence`, the running form, stats; `GroupingTest`, grouped goldens in `ChatRowsBuilderTest`. [T4]
6. **T6 chat-working-cards-follow** — `WorkingIndicator` + `Verbs` (the appendix A list with its provenance line), `formatDuration`, formats, footer, `Cards` (permission / question / plan presenters, `planText`, `answers`), `FollowState`, `ComposerModel`; `WorkingIndicatorTest`, `CardsTest`, `FollowStateTest`, `ComposerModelTest`. [T4]
7. **T7 ui-list-and-rows** — `ChatPresenter(s)`, `RenderedRow`, `ChatUiState`, `ChatPanel` with `VerticallyScrollableContainer` and keyed items, `UserBubble`, `TextRowItem`, `ToolRowItem` + `ToolGroupBox` + `ToolBodyView` (every body kind, the 20-line clamp, `Show all`), `NoticeItem`, `TurnEndItem`, `TandemColors` / `ChatPalette`; delete `ChatTranscript.kt` and `ChatTranscriptTest`; the header drops `working…`; existing IDE tests re-pointed (`Stopped.` → the `Interrupted` footer, row descriptions). [T4, T5, T6]
8. **T8 ui-working-thinking** — `WorkingRowItem` + `Spark`, the ticker, not-responding, reduced motion; `ThinkingRowItem`, expansion state; `TandemSettings.showThinking` + the checkbox + `settingsChanged()`; platform tests 10.5. [T7, T3]
9. **T9 ui-composer-autoscroll** — composer keys, placeholder, icon Send/Stop, footer order, hoisted draft; nested-scroll connection, pin effect, `↓ N new`, scroll keys, scroll on send; `ComposerKeysTest`, `ScrollKeysTest`; IDE locators `iconButton("Send"/"Stop")`. [T7, T6]
10. **T10 ui-permission-card** — `CardFrame`, `CardKeys`, `PermissionCardView`, dim, focus, keys, `1 more waiting`, `From the agent`; `CardKeysTest`; the IDE test `the permission card names the command and answers from the keyboard`; today's `PermissionPrompt` deleted. [T7, T3, T6]
11. **T11 ui-question-card** — `QuestionCardView` with tabs, option rows, caps, `Other`, number keys, Submit/Cancel, the malformed-input fallback; `TandemSessionsTest.aQuestionAnsweredFromTheServiceReachesTheFake`; the IDE test `a question card answers with the picked option`. [T10]
12. **T12 ui-plan-card** — `PlanCardView`, source line, Markdown clamp, `Open plan file`, feedback field, the three answers, picker follow (reducer test for `plan-approve:90`). [T10]
13. **T13 ui-subagents** — nested children rendering, progress sub-line, `agent-sync`/`agent-forwarded` goldens incl. children, `(agent)` card line verified end to end. [T7, T10]
14. **T14 markdown-theme-polish** — GFM modules in `build.gradle.kts` + `plugin.xml`, `TandemMarkdown` processor and the `TandemBlockRenderer` subclass with the copy button (or defer, 4.4), `InlineWarningBanner`, hard-coded colours out, the user-prompt clamp; `TandemPluginTest` asserts the modules load. [T7]
15. **T15 reload-parity** — `TranscriptReader` timestamps and `cwd`; `TranscriptReaderTest` additions; `a reloaded transcript builds the same tool rows as its stream` over the 29 probe fixtures. [T4]
16. **T16 ide-tests-and-docs** — fake-CLI `TANDEM_FAKE_LINE_DELAY_MS`, the IDE tests for the working indicator and autoscroll, `docs/protocol.md` field tables, `docs/design.md` milestone note, `CLAUDE.md` (fake knob, `chat/` layout, Kover packages), the "Claude Code" grep over `src/main`, the `runIde` checklist run by the owner, `verifyPlugin`, the PR `feat/chat-redesign → feat/sessions-and-pickers`. [all]

Order that ships the four complaints first: T1 → (T2 ‖ T3) → T4 → (T5 ‖ T6) → T7 → (T8 ‖ T9) → T10 → (T11 ‖ T12 ‖ T13 ‖ T14 ‖ T15) → T16. T15 may start right after T4.

---

## 13. Open items to verify during implementation

1. **Dim behind the permission card.** Decision 6 says dim; the reference's target builds do not (ref §2.1, §3.10). Shipped as the decision says (a panel-colour scrim at 50 %, 4.12, not `alpha(0.5f)`: no offscreen layer); ask the owner at the slice-B review; removing it is one modifier.
2. **Permission title verb case.** Shipped capitalised (`Allow Claude to Run …?`, decision 6; the early-2026 capture); the mid-2026 build shows lowercase (ref §3.10). One-word change in `Cards.permission`.
3. **Thinking row label.** Shipped as `Thinking…` / `Thought for Ns` (decision 2); the Oct 2026 Desktop shows the first summary sentence with no label (ref §3.3). Decide after checklist 9.
4. **Spark motion.** Six-glyph 120 ms cycle (decision 7) versus the rotating starburst painter the reference proposes (ref §3.8). Decide at checklist 2; both are leaf composables.
5. **Reduced-motion flag.** `com.intellij.ide.ui.UISettings.getAnimateWindows()` exists on 261 (verified in `app.jar`); whether the IDE's animation toggle writes it is open; gate behind `reducedMotion()` so the flag is one line.
6. **`NestedScrollConnection` sign and sources.** `available.y > 0` for wheel-up on desktop is assumed (jewel.md); whether scrollbar-thumb drags through `VerticallyScrollableContainer` arrive as `UserInput` is unknown. The `ReachedBottom` decision sits in `onPostScroll` (`consumed.y < 0 || available.y < 0`, after the delta is applied, 4.16); the same sign assumption applies. Verify in `runIde`; the policy test is sign-agnostic; if the connection sees nothing for thumb drags, fall back to `listState.isScrollInProgress && lastScrolledBackward`.
7. **Markdown bridge and the block-renderer subclass.** `TandemBlockRenderer` subclasses `DefaultMarkdownBlockRenderer` (open class, open `RenderFencedCodeBlock` / `createCopy` / `plus`, verified on 0.37.0; interface delegation never reaches the override, 4.4); `verifyPlugin` reports binary incompatibilities of the subclass against 0.41.0 (2026.2.3), not compilation. Unverified: whether the bridge's IDE code highlighter (`LocalCodeHighlighter`) still reaches `super.RenderFencedCodeBlock` through a custom `markdownBlockRenderer` passed to `ProvideMarkdownStyling`. Verification is checklist 10's manual click (the clipboard equals the fence text).
8. **Driver attributes.** Jewel's `RadioButtonRow` / `CheckboxRow` set `Role.RadioButton` / `Role.Checkbox` (verified in the 0.37.0 `RadioButtonKt` / `CheckboxKt` bytecode: `selectable(role = Role.RadioButton)`, `triStateToggleable(role = Role.Checkbox)`); open is only whether the Driver prints them as `@role='RadioButton'` / `'Checkbox'` and whether `testTag` surfaces as `@testtag`; `contentDescription` stays the fallback every locator in 10.6 already uses. Also open: whether a custom `Modifier.semantics { role = Role.Button }` on a row prints as `@role='Button'` (10.6 adds that predicate only when seen) and whether a `mergeDescendants = true` node exposes its merged text as `@text` (as Jewel's buttons do) or only as `@visible_text` (10.6 reads `@text` first).
9. **User bubble tint.** `colorPalette.gray(3 / 12)` versus the panel in both LaFs; pick by eye in `runIde`.
10. **Card focus and IME.** A card taking focus on appearance must not steal a composition in progress in the composer (checklist 6, macOS).
11. **Strong skipping.** Confirm with the debug counter that unchanged keyed items skip when `RenderedRow` holds a `List<MarkdownBlock>`; if not, add the Compose compiler stability configuration file of 5.3 (`dev.daarkolabs.tandem.chat.**`), which makes equal-but-new rows skip too; a `@Stable` holder for the blocks would not change how the row types are compared.
12. **`--thinking-display summarized` on the owner's CLI version.** Recorded on 2.1.302; an older CLI may reject the flag at spawn (8); T8 adds a version gate if needed.
13. **Thinking on `--resume`.** The spawn flag applies to the new process; verify a resumed session streams summaries (not recorded); `set_max_thinking_tokens{thinking_display:"summarized"}` after `initialize` is the fallback, with the fake-CLI ordering caveat of 5.2.
14. **`Not responding` during a silent long Bash.** The CLI emits no frame for a 6 s Bash (`agent-bash-task`); confirm nothing fires during a legitimately silent 70 s Bash; if it does, raise the threshold while a tool row is `RUNNING` to 120 s (a constant in `WorkingIndicator.view`).
15. **Grouping thresholds and the `TURN` gap** (names vs counts at 2; a failed call splitting the group; `RowGap.TURN` = 24 dp with no Oct 2026 measurement, 4.1) are the reference's best guesses (ref §6 Q2, §2.5); keep them as constants in `Grouping.kt` / `RowGap` so an owner screenshot can retune them.
16. **Two-question `tool_result` wording** and the bundle's `questions[].kind` text/number fields (probes §2.1 Unknown): the card treats unknown kinds as single-select; record `ask-two-questions` when the owner's model emits one.
17. **`DiffColors` attribute backgrounds** may be null in some schemes; exercise the fallback chain of 4.2 in a light scheme.
18. **Kover filter for `chat/`.** Confirm `koverLog` reports the new package and that `Verbs.kt` (a list) does not drag the percentage.
19. **Agent row stats on reload.** `toolUseResult.totalToolUseCount/totalTokens/totalDurationMs` are present in `transcripts/agent-sync.jsonl` (verified); confirm the parity test accepts the missing children.
20. **Step word on the working row.** 2a shows the verb beneath a live tool row (4.9, one live step); if the owner prefers Desktop's step-specific spinner text (`✻ Reading seed.txt… · 3s`) while the tool row carries the same label, change `WorkingIndicator.step` to return the running label and relax `step text never repeats the row above`; decide at checklist 3.
21. **Plan card deviations from decision 4** (4.14). (a) `keepPlanning` prefix: decision 4 says the feedback alone; the `No, keep planning: <feedback>` prefix matches the recorded `plan-deny:81` message and avoids an empty deny message; drop it if the owner prefers the bare feedback (one line in `PermissionAnswers.keepPlanning` and its test). (b) `Approve, keep current mode` ends in Default (the CLI's own exit, `plan-disallowed:177`); tell the owner at the slice-B review whether the label should read `Approve` instead.
22. **Header status line.** `<model>  ·  mode: <wire>  ·  claude <version>` duplicates the footer pickers (4.1); kept in 2a because `ToolWindowIdeTest` reads it. Decide at the slice-C review whether to reduce it to `claude <version>` and re-point the pickers test at the pickers' own text.
23. **Chevron on running rows.** 2a draws none on a `PENDING`/`RUNNING` row (4.6.1 **[proposal]**: nothing to open); the Sept/April captures draw `›`. If the owner prefers the capture, draw it disabled (`text.disabled`, no click): one branch in `ToolRowItem`.

---

## Appendix A. Spinner verbs (`chat/Verbs.kt`)

Provenance line at the top of `Verbs.kt`: `// The CLI's static spinner words (90, v1.0.29 onward), from S18 levindixon/tengu_spinner_words (README, fetched 2026-10-20); the CLI merges more from its tengu_spinner_words config at runtime, which Tandem does not fetch. Extend from the gist in S18 when reachable.` Decision 7 asks for a present participle from this list; the choice rule is in 4.9. Kept alphabetical so a diff against the source is one read:

```
Accomplishing, Actioning, Actualizing, Baking, Booping, Brewing, Calculating, Cerebrating, Channelling, Churning,
Clauding, Coalescing, Cogitating, Combobulating, Computing, Concocting, Conjuring, Considering, Contemplating, Cooking,
Crafting, Creating, Crunching, Deciphering, Deliberating, Determining, Discombobulating, Divining, Doing, Effecting,
Elucidating, Enchanting, Envisioning, Finagling, Flibbertigibbeting, Forging, Forming, Frolicking, Generating, Germinating,
Hatching, Herding, Honking, Hustling, Ideating, Imagining, Incubating, Inferring, Jiving, Manifesting,
Marinating, Meandering, Moseying, Mulling, Mustering, Musing, Noodling, Percolating, Perusing, Philosophising,
Pondering, Pontificating, Processing, Puttering, Puzzling, Reticulating, Ruminating, Scheming, Schlepping, Shimmying,
Shucking, Simmering, Smooshing, Spelunking, Spinning, Stewing, Sussing, Synthesizing, Thinking, Tinkering,
Transmuting, Unfurling, Unravelling, Vibing, Wandering, Whirring, Wibbling, Wizarding, Working, Wrangling
```

`Thinking` is in the list; it is also 4.9's hidden-thinking step, which is harmless (the two never show together). `WorkingIndicatorTest` `verbs are the appendix list` asserts the count (90) and alphabetical order so an edit to one place is caught.
