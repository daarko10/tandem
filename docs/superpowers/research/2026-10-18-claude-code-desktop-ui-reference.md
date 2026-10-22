# Claude Code desktop chat UI reference (for Tandem's chat redesign)

Status: research reference, written 2026-10-18, revised 2026-10-19 with the second research round (typography, colour tokens, spacing, user message, Markdown, thinking, tool rows, asset verification) and the protocol probes in `2026-10-18-protocol-probes.md`. Read with `docs/design.md` (hard rules) and `docs/protocol.md` (wire protocol). The Phase 2a spec and plan are built from this file.

Audience: the engineer and the reviewer who reproduce the Claude Code desktop app's chat feel in Tandem (Kotlin, Jewel/Compose) without having seen the app.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

How to read this file:

- **Desktop** = the Claude desktop app's Code tab. Builds differ visibly across 2026, so every Desktop claim names the build it was seen on: *early 2026 light* (S9, chevron before the row text, two-row composer), *April 2026* (S7, S8, per-call rows, blue bubble), *mid 2026* (S4, S10), *Sept 2026 Windows* (S6), *Oct 2026* (S3 videos, S11, S28 issues). **The Sept/Oct 2026 builds are the target.** Where builds disagree, the newest seen build wins and the older one is recorded as history.
- **VS Code** = the Claude Code VS Code extension. Used where Desktop evidence is missing (Bash IN/OUT body, AskUserQuestion card, VS Code-only strings). Its chat is the same component family, docked in a narrow side panel like Tandem's tool window.
- **CLI** = the terminal UI. Cited only for strings and behaviour the GUIs inherit (grouping tense rules, retry lines, plan-approval options).
- Every claim carries a confidence tag. **[seen]** = visible in a captured frame or screenshot listed in section 1.1; a file name after the source number (`S3 desktop-pop-out-01`) is that file under `docs/superpowers/research/assets/`; conflicts between research agents were settled by opening the file, and the file wins. **[stated]** = written in official docs, a changelog, an issue, a stylesheet or type declaration, or read from code (including Tandem's). **[inferred]** = estimated from a capture (sampled colours, pixel sizes on scaled media) or deduced. Measurements come from compressed captures of several builds at 1x and 2x, so expect ±2 px and ±a few colour units; S3 video frames are scaled screen recordings, so S3 sizes are ratios, not absolutes. Where nothing was measured the text says **unknown** and gives a **proposal**, labelled as such.
- Tandem line references (`ChatPanel.kt:41`) were checked against the working tree on 2026-10-18; the fixture references in section 4 were checked on 2026-10-18 while writing `2026-10-18-protocol-probes.md`.

---

## 1. Sources

| # | URL | What it is | Claims that rest on it |
|---|-----|------------|------------------------|
| S1 | https://code.claude.com/docs/en/vs-code | Official VS Code extension docs with three screenshots (copies in `assets/s1-vscode-docs/`) | VS Code row style (dot rail, `Read` / `Write` rows, `└ Read 256 lines`), `✻ Pondering...`, composer strings, permission card `Make this edit to utils.py?` with `1 Yes / 2 Yes, and don't ask again / 3 No`, Focus view, screen-reader labels, shortcuts, `spinnerVerbs`, paste handling, hover actions `Copy response` / `Bookmark response` / rewind |
| S2 | https://code.claude.com/docs/en/desktop and https://code.claude.com/docs/en/desktop-quickstart | Official Desktop docs (no chat screenshots) | View modes Normal/Thinking/Verbose, permission-mode labels, keyboard shortcuts, composer features, panes, usage ring, `Allow once / Always allow / Deny` wording, queued corrections |
| S3 | https://code.claude.com/docs/en/whats-new/2026-w28, -w32, -w33, -w37 | Official "what's new" pages with mp4 demos; frames in `assets/s3-whats-new/`, extraction in 1.1 | Desktop Oct 2026 dark and light chat: right-aligned user bubble, grouped summary rows, `Edited 3 files` card, verbose MCP rows, Edit card, animated spark + step word + elapsed (`Testing… · 3s`), usage-limit card, composer footer, the streaming tail fade, dock stack above the composer |
| S4 | https://github.com/anthropics/claude-code/issues/72458 | Issue with Desktop (June 2026, Windows) and VS Code screenshots | `Used 2 tools ›`, red `Failed to … ›`, `Ran a command, edited a file ›`, spark `6m 25s · 35.4k tokens · 2 running tasks`, VS Code Bash IN/OUT box, dot rail with connecting line, the "every paragraph semibold" regression |
| S5 | https://github.com/anthropics/claude-code/issues/73000 | Issue with VS Code screenshots (2.1.195–197) | `Thought for 9s` rows, `● Bash <description>` + IN/OUT box, `● AskUserQuestion` row and the question card (`Render test` tab, options, `Other`, `1 Submit answers`, `Esc to cancel`) |
| S6 | https://www.xda-developers.com/claude-code-desktop-gui-has-superpower-the-terminal-version-will-never-match/ | Review with seven Desktop screenshots (Sept 2026, Windows, dark, 1x, 1920×1080; `assets/s6-xda/`) | Measured dark colours and spacing, summary rows with diff stats, expanded `Ran 2 commands ⌄` box, file cards, working row (dot + `13s · 109 tokens · Running tools...`), turn footer icons + `just now`, code fence with run/copy icons, inline code, table, link colours, composer footer |
| S7 | https://venturebeat.com/orchestration/we-tested-anthropics-redesigned-claude-code-desktop-app-and-routines-heres-what-enterprises-should-know | Review with Desktop screenshots (April 2026, macOS, dark) | `Reading weather.ts ›`, spark `7s · ↓ 201 tokens`, blue left-aligned user bubble of that build, `Used 2 tools ›`, Tasks/Plan panes, usage-ring tooltip, sampled page `#0f0f0f` and muted text `#9c9b91` |
| S8 | https://miraflow-cms.s3.us-west-2.amazonaws.com/claude-code-redesign-interface.png | Marketing-style Desktop shot (April 2026 redesign, dark; `assets/s8-miraflow/`) | Per-call rows `Edit <path> +142 -38 ›`, `Lint ›`, `Typecheck ›`, `Build ›`, `Write <path> +62 ›`, footer `25s · ↓ 35.7k tokens`, branch bar, row pitch by group |
| S9 | https://ruben.substack.com/p/claude-code | Walkthrough with Desktop light-theme screenshots (early 2026, macOS 2x) | Light colours, `› <natural-language description>` rows with a leading chevron, expanded tree (`└ Bash …`, `Edit <path> +6 -6`), `· Clauding...` / `✱ Forging...` spinner with right-aligned elapsed/tokens, permission card `Allow Claude to Edit launch.json?` with `Deny [Esc] / Always allow for session [⌘↩] / Allow once [↩]` replacing the composer, dimmed transcript (that build only), VS Code `/` menu and model-switch notice |
| S10 | https://claudecodeformarketers.com/_astro/claude-code-desktop-diff-approval.CWy_kjyo_gvDkq.webp and …/claude-code-desktop-tabs.DIVN9aUH_Z1GN2Vp.webp | Desktop dark screenshots (mid 2026, macOS; `assets/s10-ccfm/`) | Expanded `Edited README.md +23 -16 ⌄` diff cards (clamp, fade, expander), dark permission card (yellow dot, `session` pill, `Deny / Always allow / Allow once ⌘↩`, no dimming), dock stack card → branch bar → composer, sidebar status glyphs |
| S11 | https://aitoolsreview.co.uk/insights/claude-code-desktop-browser | Frames of Anthropic's Oct 2026 browser demo (same video as S3 w28) | `mcp__browser__*  <description>` rows, `✛ Applying edit` / `✱ Reading page` spinner rows, `Auto mode` yellow pill |
| S12 | https://verygood.ventures/blog/claude-code-desktop-hands-on-review (images under `/assets/images/blog/claude-code-desktop-hands-on-review/`) | Review screenshots (Desktop dark; `assets/s12-vgv/`) | Tasks pane cards (`Running Bash · 2m 8s · 170.0k tokens · 53 tool uses`), side chat, PR/CI bar, context-usage popover |
| S13 | https://code.claude.com/docs/en/changelog and https://raw.githubusercontent.com/anthropics/claude-code/main/CHANGELOG.md | Official CLI/extension changelog | Thinking pill strings, Focus view, scroll fixes (2.1.275, `scrollToBottomOnSend`), `Not run` rows, `Compacted chat` card, retry wording, plan approval strings, spinner colour changes, 60 s `Not responding`, paste marking (2.1.280), timestamps default (2.1.290), `Copy response` (2.1.277), bookmarks (2.1.286), grouping tense rules (2.1.20, 2.1.89, 2.1.246) |
| S14 | https://code.claude.com/docs/en/permissions, …/permission-modes, …/interactive-mode, …/fullscreen, …/costs, …/statusline, …/sub-agents, …/tools-reference, …/errors, …/how-claude-code-works | Official CLI docs (one Bash prompt screenshot) | CLI permission prompt strings, plan approval options, Ctrl+T task list, auto-follow rule and `Jump to bottom`, `(<elapsed> · ↓ <n> tokens)` format, Agent row `<agent>(<task>)`, tool list and which tools prompt, queued-message colour rule |
| S15 | https://code.claude.com/docs/en/agent-sdk/typescript, …/agent-sdk/user-input, …/agent-sdk/permissions, …/agent-sdk/todo-tracking, …/agent-sdk/hooks, …/hooks | Official SDK and hooks reference | Tool input/output field names, `can_use_tool` answer shapes, AskUserQuestion `updatedInput.answers`, ExitPlanMode plan injection, task_* and thinking_tokens events, `ThinkingDisplay = "summarized" \| "omitted"`. Two third-party host PRs (a VS Code extension PR and a "Station" host) were cited in the first draft for the plan-approval answer shape; their URLs were not captured, so those claims are now carried by the recordings in S34 instead |
| S16 | https://github.com/anthropics/claude-code/issues/58767, /66833, /72117, /99338, /99139, /55120, /78593, /96497, /4058, /81327, /52517, /55888, /71467 | Issues (first round) | Broken 50 px scroll threshold and the expected rule, AskUserQuestion card opens expanded over the composer, spinner node-graph complaint, thinking chevron regression, retry line format, Mermaid not rendered (#52517) |
| S17 | https://gist.github.com/phuryn/478581dc5e09d9a6324c6a9623163c7a | Community CSS patch for the VS Code webview | DOM facts: `toolUse_*` / `toolSummary*` / `toolBody_*` classes, 60 px clamp with fade, 0.25 s collapse, Edit subtitles `Modified` / `Added N lines`; its own additions (carets, `Called N tools`, `+N −M` chip) are **not** stock |
| S18 | https://gist.github.com/krishnakanthb13/c43f4851bebcca9358ee2be9f3074919 ; https://github.com/levindixon/tengu_spinner_words ; https://github.com/Piebald-AI/tweakcc ; https://github.com/anthropics/claude-code/issues/99902 | Community spinner research (CLI and VS Code) | Verb list, CLI/VS Code glyph cycle `· ✢ ✳ ✶ ✻ ✽` at 120 ms, shimmer timing |
| S19 | https://plugins.jetbrains.com/plugin/27310-claude-code-beta- | Anthropic's JetBrains plugin page | It has no chat panel (terminal-based, v0.1.14-beta) |
| S20 | Open-source hosts: https://github.com/siteboon/claudecodeui, https://github.com/wbopan/cui, https://github.com/zhukunpenglinyutong/jetbrains-cc-gui, https://github.com/slopus/happy, https://github.com/winfunc/opcode, https://github.com/sugyan/claude-code-webui, https://github.com/croll83/companion | Community reimplementations (MIT/Apache/AGPL; read, not copied) | Stick-to-bottom algorithm, per-tool label fallbacks, denial strings, tool-row state machine; cited only as implementation precedent, never as the look |
| S21 | `src/test/resources/fixtures/*.ndjson`, `src/main/kotlin/dev/daarkolabs/tandem/**` | Tandem's recorded CLI transcripts (first round) and current code | Section 4 (what the stream gives) and section 5 (Tandem today) |
| S22 | https://assets-proxy.anthropic.com/claude-ai/v2/assets/v1/c6a992d55-CGuUmOei.css | claude.ai's public web stylesheet (the Desktop Code tab is web-loaded from claude.ai and shares its tokens, S28 #102723) | Font families (`Anthropic Sans`, `Anthropic Serif`, `Anthropic Mono`), UI type scale and weights, prose scale, heading sizes, inline-code rules, letter spacing, line heights, dark-mode weight thinning |
| S23 | https://github.com/patrickjaja/claude-desktop-extra/blob/fc82c551d06b6b0794f11b08fe8fe6ec21985717/baseline/THEME_TOKEN_MAP.md ; https://github.com/XBOT98df/Claude-ui/blob/b39962efda25c045161d3508aaba9e1296987b5c/src/styles/tokens.css ; https://github.com/taoweiy431-boop/ClaudedesktopDom/blob/afa113e3927903f97032f3491c16072e6abf4292/chat%20cowork%20code.md ; https://github.com/Caho1/open-claude-ui/blob/638800b8a4cee4513c8763c3df0ecf8a77b32379/code-examples/cds-theme.css ; https://github.com/catppuccin/userstyles/blob/main/styles/claude/catppuccin.user.less ; https://github.com/KyongSik-Yoon/cc-desktop-mod/blob/a2f2b49b17cf32031347ca46fb0efed6867f2622/desk-look/hooks/theme.ts | Captures of the Desktop app's CSS custom properties (a live DOM capture from Desktop v1.15962.0 re-validated on v1.49585.0, a token sheet, a DOM dump, a userstyle) | The `--cds-*` gray ramp and semantic tokens (dark and light), the legacy `--bg-*` / `--text-*` HSL tokens, the `--df-*` frame tokens, user-bubble tokens, scroll-fade sizes. Three independent copies agree on every ramp value; the open-claude-ui sheet contradicts the live capture in places and is only used where the others agree |
| S24 | https://github.com/Harsh1210/openclaude-vscode/blob/8678984eeb4bd107ed8d35d2ac93814f10f63359/webview/src/styles/index.css ; https://github.com/kzahel/yepanywhere/blob/970079562bf9d908b2cd076187d98d3cd73d0880/docs/archive/claude-vscode-style-guide.md ; https://github.com/Haleclipse/Claudix/blob/c238b3e57c55e95220a8e2837cd4cbe6350a6b65/src/webview/src/styles/claude-theme.css ; https://github.com/Bhavik7880wegod/boringspinner/blob/184c28253d22573420a79f828f4a481aac49f3ad/src/adapters/claude-code/block.asset.ts | Copies of the VS Code extension's webview CSS (v2.1.90) made by three unrelated projects, and a spinner adapter | `--app-*` → `--vscode-*` token map, hard-coded brand constants, composer focus ring per permission mode, send button, thinking block, code block, mention chip, attachment pill, timeline-dot colours, spinner glyph sequence |
| S25 | https://github.com/microsoft/vscode/blob/main/extensions/theme-defaults/themes/dark_modern.json (and `light_modern.json`); https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/workbench/contrib/chat/browser/widget/chatLayoutService.ts | VS Code theme defaults and chat font source | Resolved hex values of the `--vscode-*` tokens for Dark Modern / Light Modern; `chat.fontSize` default 13 |
| S26 | https://github.com/anthropics/claude-code/blob/main/mods/types/claude-code.d.ts ; https://code.claude.com/docs/en/plugins/mods/interface | Anthropic's public mods type declarations (2.1.277) and mods interface docs | `ToolUse`, `ToolGroup`, `ToolResult`, `Spinner` render-site props (`isRunning`, `isActive`, `isExpanded`, `mode`), the group label example `Read 3 files, ran 2 shell commands`, which sites render on Desktop |
| S27 | https://claude.com/docs/cowork/changelog | Desktop app changelog | Default transcript view setting (v1.52386.0), Summary view removed (v1.46388.1) |
| S28 | https://github.com/anthropics/claude-code/issues/102604, /92535, /78593, /79021, /94032, /100521, /81979, /76577, /49739, /77460, /94354, /102406, /98661, /74185, /97209, /92988, /102342, /101909, /93402, /96608, /71726, /98493, /48158, /94102, /99089, /86916, /102315, /98829, /60161, /96390, /39416, /96921, /73827, /65668, /57060, /99483, /100876, /99327, /99762, /92623, /48237, /48805, /11651, /45841, /89808, /88027, /95971, /58512, /86191, /102723, /61679, /64714, /73736, /77574, /58689, /26078, /95473, /64776, /96147 | Issues (second round); retrievable screenshots in `assets/s28-issues/` | Desktop thinking row and expanded block, Transcript view menu, default-view setting, VS Code thinking rows and `/` menu, Desktop font settings, relative-time strings, queued/steered prompts, hover toolbar, `Ran N commands ›` header, `Edited N files` card, click target of rows, spinner step words, VS Code CSS quotes (inline code `.9em`, diff `fontSize: 12`) |
| S29 | https://www.kiloloco.com/articles/claude-code-desktop-first-impressions (video episode: https://aiwithkyle.podbean.com/e/174-complete-beginner-s-guide-to-anthropic-s-claude-code-app/) | Desktop April 2026 walkthrough frames; `assets/s29-kiloloco/` | `Found files, searched code ›` and `Ran View GitHub issue 2664 ›` row anatomy (measured), `Ran an agent ⌄` / `Ran agent Haiku 4.5 Explore MCP server codebase ⌄` subagent rows, Transcript view popover, Summary view pills `19.5k tokens` |
| S30 | https://note.com/tandago/n/nb74f8cde6a79 | Desktop Oct 2026 light-theme walkthrough (Japanese locale); `assets/s30-note/` | `Edited 7 files` card with `Revert` / `Show changes` buttons and `Show 2 more ›`, hover icons + `5 min ago`, collapsed rows `Read page text ›` / `Used 2 tools ›`, Transcript view submenu |
| S31 | `~/.gradle/caches/…/idea-2026.1.5/lib/intellij.platform.jewel.*.jar` (the platform Tandem compiles against) and https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/theme/IntUiBridgeText.kt | Jewel API surface read from the bundled jars (`javap`) and the bridge text-style source | Which Jewel composables and tokens exist on 2026.1.5 (section 2.3, 5); IDE default fonts (Inter 13 / JetBrains Mono 13) |
| S32 | https://simonwillison.net/2026/Jan/12/claude-cowork/ | Cowork tab screenshots (Jan 2026, light) | The floating `↓` scroll-to-bottom button of the shared desktop chat components |
| S33 | https://aicatchup.com/news/claude-code-desktop-redesign | Coverage of the Oct 14 2026 Desktop update | Live to-do list, recap card, suggested-task chip (text only) |
| S34 | `docs/superpowers/research/2026-10-18-protocol-probes.md` and the 29 fixtures it names (`src/test/resources/fixtures/{ask-*,plan-*,think-*,tools-*,agent-*,misc-*}.ndjson`, recorded against `claude` 2.1.302 on 2026-10-18) | Tandem's second recording round | Everything in section 4 that cites those fixtures: AskUserQuestion round trip, ExitPlanMode input and `setMode`, thinking text via `summarized`, per-tool result shapes, subagent and task lines, image results, session-state env vars, usage lines |

Hosts that refused during research (so no claim rests on them): `api.github.com` (search and GraphQL), `github.com` raw HTML, `user-images.githubusercontent.com`, `claude.ai` (Cloudflare challenge; its stylesheet S22 was reachable on the assets proxy), YouTube watch pages and transcripts, `x.com`. Issue screenshots behind signed GitHub URLs were retrievable only through `private-user-images.githubusercontent.com` links that expire; the ones that were fetched are stored under `assets/s28-issues/`. The Desktop app binary and asar were not downloaded or unpacked; CSS facts come from public copies (S22–S24).

### 1.1 Captured frames and screenshots (`docs/superpowers/research/assets/`)

Every `[seen]` tag that names a file points into this folder. **The folder is gitignored** (`.gitignore:26`), so the files exist on the machine where the research ran and are reproduced elsewhere from the URLs, hashes and commands below; nothing in the text depends on a file that cannot be re-fetched. Files under `s*/` are unedited copies of public media; files under `derived/` are crops, zooms, montages and one frame-difference sheet made from them with the commands given here. Sizes read on S3 frames were taken on the 1600 px-wide frames (the 1280 px / 1440 px videos scaled by 1.25× / 1.11×); the videos are themselves scaled screen recordings, so treat S3 measurements as ratios.

**S3 videos** (downloaded 2026-10-18 from the `<video src>` of each page and re-checked 2026-10-19: the same URLs with the same `?s=` signatures are still embedded and still return HTTP 200; sha256 prefixes below):

| File | Page | URL | Format |
|------|------|-----|--------|
| `desktop-browser.mp4` | 2026-w28 | https://mintcdn.com/claude-code/x358isu_VzLnyTEN/images/whats-new/desktop-browser.mp4?fit=max&auto=format&n=x358isu_VzLnyTEN&q=85&s=8033e85a1cb0a37870a79e702c18f4e4 | 1280×720, 24 fps, 47.25 s, 1195804 B, sha256 `83739a2a3aab…` |
| `cross-session-messaging.mp4` | 2026-w32 | https://mintcdn.com/claude-code/N3yEaTYPXMXFrF6k/images/whats-new/cross-session-messaging.mp4?fit=max&auto=format&n=N3yEaTYPXMXFrF6k&q=85&s=8f33c3390f78660a4a26dc980f46159f | 1440×756, 30 fps, 16.07 s, 856596 B, sha256 `eec32f56fb8a…` |
| `desktop-auto-continue.mp4` | 2026-w33 | https://mintcdn.com/claude-code/2SnAdpL4dJ18nKb3/images/whats-new/desktop-auto-continue.mp4?fit=max&auto=format&n=2SnAdpL4dJ18nKb3&q=85&s=1937f489695feaea715e48ecfd7e62cd | 1440×810, 30 fps, 12.03 s, 696809 B, sha256 `572d56646910…` |
| `desktop-pop-out-panes.mp4` | 2026-w37 | https://mintcdn.com/claude-code/f9HTZGyMtxIFOUgt/images/whats-new/desktop-pop-out-panes.mp4?fit=max&auto=format&n=f9HTZGyMtxIFOUgt&q=85&s=ff3770dd09bb15ed9cf17a460f3d1e23 | 1280×800, 30 fps, 22.5 s, 880486 B, sha256 `702d32d079bb…` |

Extraction, one frame every 3 s, upscaled to 1600 px wide (frame `NN` is at t ≈ 3·(NN−1) s). Re-running this on 2026-10-19 against the hash-verified downloads reproduced the checked-in `desktop-pop-out-01/03/04/07` and `desktop-auto-continue-01..04` pixel for pixel (ImageMagick `compare -metric AE` = 0); the browser and cross-session frames were not re-extracted but come from the same hash-verified inputs:

```
ffmpeg -i desktop-pop-out-panes.mp4   -vf "fps=1/3,scale=1600:-1" desktop-pop-out-%02d.png        # 8 frames
ffmpeg -i desktop-auto-continue.mp4   -vf "fps=1/3,scale=1600:-1" desktop-auto-continue-%02d.png  # 4 frames
ffmpeg -i desktop-browser.mp4         -vf "fps=1/3,scale=1600:-1" desktop-browser-%02d.png        # 16 frames
ffmpeg -i cross-session-messaging.mp4 -vf "fps=1/3,scale=1600:-1" cross-session-%02d.png          # 5 frames
```

Stored under `assets/s3-whats-new/`: the twelve frames cited in the text and one labelled contact sheet per video (`<video>-sheet.jpg`, every frame at 400 px wide, 4 columns) so the uncited frames can be found without re-extracting. The mp4s are not stored (3.6 MB; the hashes above identify them).

| Frame | t ≈ | What it shows |
|-------|-----|---------------|
| `desktop-pop-out-01` | 0 s | Dark Desktop, macOS: sidebar; right-aligned grey user bubble; `Read cart.spec.ts, checkout.ts ›`; bullets with inline-code chips (`/api/cart`, `retry()`); `Edited 3 files` card with `View changes` and rows `cart.spec.ts +12 -4 ›`, `checkout.ts +3 -1 ›`, `utils/retry.ts +18 -0 ›`; streaming reply whose last word is faded (`now wraps`); composer `Type / for commands` with `↵`; footer `+  🎤 ⌄  Auto … Opus 5  High  ◯`; file pane with coloured diff gutter |
| `desktop-pop-out-03` | 6 s | Same session; `• Running npm test · 0/340 passed` row (coral dot) under the reply; Terminal pane popped out as a floating window |
| `desktop-pop-out-04` | 9 s | `✻ Running npm test · 11/340 passed`; two popped-out panes |
| `desktop-pop-out-07` | 18 s | Two ~400 px sessions side by side. Left: `• Running npm test · 46/340 passed`, footer `Auto … Opus 5  High`. Right: `Ran npx @tailwindcss/upgrade ›`, `Edited 14 files ›`, reply with bullets, `✻ Writing summary…`, PR bar `#128  claude/tailwind-v4  +42 -7  ● CI ⌄  ×` above the composer, footer `Accept edits … Fable 5.1  High  ◯` |
| `desktop-auto-continue-01` | 0 s | Light Desktop: `Read 38 files, searched for 6 patterns ›`; reply with `PaymentError → V3Error` chips; `Edited billing/charge.ts +31 -40 ›`, `Edited billing/refund.ts +22 -27 ›`, `Edited payouts/schedule.ts +18 -21 ›`; `✻ Migrating… · 43m 11s` |
| `desktop-auto-continue-02` | 3 s | Close-up (2:12 PM): the same rows, then the `Usage limit reached` card: icon + title, `Auto-resuming at 3:40 PM`, `View details` button, checked box `Auto-continue when limits reset`, right-aligned `Try again`; composer; footer `Auto  +  🎤 ⌄ … Opus 5  Max  ◯` |
| `desktop-auto-continue-03` | 6 s | 3:40 PM: the card dimmed (settled), `✻ Resuming… · 0s` |
| `desktop-auto-continue-04` | 9 s | Close-up: dimmed card, `Edited payouts/retry.ts +26 -30 ›`, `Edited 61 files +904 -1130 ›`, `Ran pnpm test ›` (mono command), streaming reply with faded tail (`deleted,`), `✻ Testing… · 3s`, composer, footer |
| `desktop-browser-03` | 6 s | Close-up, dark, no sidebar: user bubble; assistant sentence `Starting the dev server to look at it live.`; grey sub-row `• Opening in Browser` with a small coral dot; empty browser pane |
| `desktop-browser-10` | 27 s | Close-up, verbose view: rows `mcp__browser__click  Select Express shipping`, `mcp__browser__reload  Reload localhost:3000/checkout` (grey mono id + light description, no box); Edit card header `src/app/checkout/ShippingStep.tsx` with right-aligned `Edit +6 -2`, unified diff lines (one `-` red, four `+` green); `✻ Applying edit`; branch bar `checkout-shipping-cards  +6 -2  Create PR ⌄`; composer with a stop square; footer yellow `Auto mode` pill, `+`, `🎤 ⌄`, `Opus`, usage ring |
| `desktop-browser-11` | 30 s | Full window: two-column research rows, short user bubble `Apply the pattern.`, six `mcp__browser__*` rows, the Edit card, `Reloaded.`, `✻ Filling form`, branch bar, composer, footer; browser pane with the ACME checkout |
| `cross-session-01` | 0 s | CLI, not Desktop: two terminal windows; `● Update(db/migrations/031_display_name.sql)` + `⎿ Added 9 lines, removed 2 lines`; `● Write(src/jobs/weeklyDigest.ts)` + `⎿ Wrote 96 lines to src/jobs/weeklyDigest.ts` |

**Still images** (sizes are what the server returned; re-checked on 2026-10-19 against the live URLs: S1, S8, S10 and two of the seven S6 files by `Content-Length`, S12 by a byte compare, all unchanged; the other five S6 files were not re-checked):

| Folder | File | URL |
|--------|------|-----|
| `s1-vscode-docs/` | `vs-code-extension-interface.jpg` 2500×1155 (JPEG) | https://mintcdn.com/claude-code/-YhHHmtSxwr7W8gy/images/vs-code-extension-interface.jpg?fit=max&auto=format&n=-YhHHmtSxwr7W8gy&q=85&s=300652d5678c63905e6b0ea9e50835f8 |
| | `vs-code-send-prompt.png` 3288×1876 (lossless, 2x) | https://mintcdn.com/claude-code/FVYz38sRY-VuoGHA/images/vs-code-send-prompt.png?fit=max&auto=format&n=FVYz38sRY-VuoGHA&q=85&s=ede3ed8d8d5f940e01c5de636d009cfd |
| | `vs-code-edits.png` 3292×1876 (lossless, 2x) | https://mintcdn.com/claude-code/FVYz38sRY-VuoGHA/images/vs-code-edits.png?fit=max&auto=format&n=FVYz38sRY-VuoGHA&q=85&s=e005f9b41c541c5c7c59c082f7c4841c |
| | `vs-code-spark-icon.svg` (the spark glyph, 24×24 viewBox, title `Claude`) | https://mintcdn.com/claude-code/c5r9_6tjPMzFdDDT/images/vs-code-spark-icon.svg |
| `s6-xda/` | `claude-code-<name>.png` 1920×1080, seven files: `assembling-starter-pricing-wireframe`, `branding-pro-card-as-popular`, `coupling-toggle-to-real-prices`, `discovering-built-in-browser-pane`, `exporting-launch-json-for-dev-server`, `framing-hero-low-fi-wireframe`, `getting-responsive-widths-for-hero` | `https://static0.xdaimages.com/wordpress/wp-content/uploads/wm/2026/09/claude-code-<name>.png?q=100&fit=contain&w=1920&h=1080&dpr=1` (the page embeds `?q=49&fit=contain&w=750&h=422&dpr=2`) |
| `s8-miraflow/` | `claude-code-redesign-interface.png` 2440×1242 | https://miraflow-cms.s3.us-west-2.amazonaws.com/claude-code-redesign-interface.png |
| `s10-ccfm/` | `claude-code-desktop-diff-approval.webp` 1600×1140 | https://claudecodeformarketers.com/_astro/claude-code-desktop-diff-approval.CWy_kjyo_gvDkq.webp |
| | `claude-code-desktop-tabs.webp` 1600×919 | https://claudecodeformarketers.com/_astro/claude-code-desktop-tabs.DIVN9aUH_Z1GN2Vp.webp |
| `s12-vgv/` | `parallel-agents-running.png` 1152×532, `side-chat.png` 798×548, `pr-controls.png` 492×376, `session-sidebar.png` 612×134, `context-usage-panel.png` 732×658 | `https://verygood.ventures/assets/images/blog/claude-code-desktop-hands-on-review/<file>` |
| `s28-issues/` | `102604-desktop-thinking-summary-row.png`, `102604-desktop-flagged-card.png` (Desktop 2.42304.0, macOS, dark, Oct 16 2026); `92535-desktop-expanded-thinking-summaries.png` (Desktop, macOS, dark, Sept 6 2026); `78593-desktop-transcript-view-menu.jpg`, `81979-desktop-transcript-view-dropdown.png` (Desktop light, Jul 2026); `76577-desktop-set-default-view.png` (Desktop Settings, dark, Sept 2026); `49739-may28-vscode-toggle.png`, `49739-jun16-vscode-thinking-fixed.png`, `55120-vscode-no-chevron.png`, `77460-vscode-thought-for-ticking-tokens.png` (VS Code, dark, Apr–Jul 2026) | Attachments of the issues named in the file names (signed `private-user-images.githubusercontent.com` URLs that expire; the issue page is the stable reference) |
| `s29-kiloloco/` | `kiloloco-transcript-output-levels.png` 2640×1706, `kiloloco-split-screen.png` 3094×1878, `kiloloco-haiku-subagent.png` 1012×240 | Frames from the S29 walkthrough (Desktop April 2026, dark, macOS 2x) |
| `s30-note/` | `note08-todo-test.png`, `note09-todo-result.png`, `note17-transcript-view.png` | Images embedded in the S30 article (Desktop Oct 2026, light, Japanese locale) |

**Derived files** (`assets/derived/`, made with ImageMagick/PIL from the files above; the source and the operation are in the name):

| File | Made from | Shows |
|------|-----------|-------|
| `desktop-auto-continue-tail-fade-montage.png` | `desktop-auto-continue.mp4`, 8 frames 0.2 s apart (t = 9.7–11.1 s) | The streaming tail: the newest chunk dim, older chunks opaque |
| `desktop-pop-out-tail-fade-montage.png` | `desktop-pop-out-panes.mp4`, 7 frames 0.4 s apart (t = 0.5–2.9 s) | Same, dark theme |
| `desktop-auto-continue-04-tail-x3.png`, `desktop-pop-out-01-tail-x3.png` | 3× crops of the cited frames | The faded last word, no caret |
| `desktop-auto-continue-spark-10fps.png` | `ffmpeg -ss 9.0 -t 2.0 -i desktop-auto-continue.mp4 -vf fps=10 spark-%02d.png`, then a 166×32 px crop around the spark at (317, 385) in each native 1440×810 frame, scaled 4× | 20 consecutive 100 ms frames of the working row (`✻ Migrating… · 1s` → `✻ Testing… · 3s`); the spark differs in every frame (RMS 11–15 between neighbours) while the text is identical except when the counter ticks |
| `s6-hover-icons-x3.png`, `s6-fence-x3.png`, `s6-table-x2.png` | Crops of `s6-xda/…getting-responsive-widths-for-hero`, `…exporting-launch-json-for-dev-server`, `…coupling-toggle-to-real-prices` | The four hover icons + `just now`; the single-command fence with run/copy icons; the Markdown table |
| `kiloloco-group-row-x4.png`, `kiloloco-ran-row-x4.png` | 4× crops of the S29 frames | `Found files, searched code ›` and `Ran View GitHub issue 2664 ›` glyph shapes |

Not captured as files: S4/S5/S16 issue screenshots (signed URLs; the earlier scratch copies are not reproducible), S7 (VentureBeat; its URLs are in the first-round notes and were sampled, not stored), S9 (Substack CDN URLs are not stable), S11 (same video as S3 w28). Claims that rest only on those keep a bare source number.

---
## 2. Overall layout and visual language

### 2.1 Layout

- The chat is **one centred column of flat content**: no avatars, no names, no persistent timestamps in the stream [seen S3 desktop-pop-out-01, desktop-auto-continue-01; S6; S28 102604-desktop-thinking-summary-row].
- **Column width rule** [seen S6 coupling-toggle, assembling; S3 desktop-pop-out-01, desktop-pop-out-07]: `column = min(768 px, pane − 2·inset)`, centred when the pane is wider. With no side pane the column is exactly 768 px at x 576–1343 of 1920 [seen S6 coupling-toggle: composer and the expanded `Ran 2 commands` box both span 576–1343]. With a 776 px chat pane the composer is 696 px (inset 40) and the transcript content (bubble right edge, cards, text) 676 px (inset 50) [seen S6 assembling]. In ~400 px panes (two sessions side by side) the inset is 24–25 px on both sides and content and composer share one width [seen S3 desktop-pop-out-07; desktop-pop-out-01 at a ~480 px pane: 24/26]. The pane width at which the inset grows from 24 to 40/50 was not captured. The light Aug 2026 video shows a 640 px column centred in a 1016 px pane (188 px each side); whether that is a 640 px cap in that build or a scaled 768 px column cannot be settled from the video [inferred S3 desktop-auto-continue-01]. Users of the wide April 2026 build complained of 160-character lines, i.e. no cap at all in that build [stated S28 #48158]; the cap is a later addition.
- Vertical order inside the column: transcript (scrolls) → optional pending card (permission, question, usage limit) → optional branch/PR bar (`draw-a-video  main  +354 -16  Create PR` [seen S10]; `#128  claude/tailwind-v4  +42 -7  ● CI ⌄  ×` [seen S3 desktop-pop-out-07]; `checkout-shipping-cards  +6 -2  Create PR ⌄` [seen S3 desktop-browser-10]) → composer box → composer footer row [seen S3 desktop-pop-out-07, desktop-auto-continue-02; S6; S10]. The bar and composer are anchored to the bottom of the pane; when the transcript is short, empty space sits between it and the bar [seen S3 desktop-pop-out-07].
- **Pending cards dock above the composer; the composer stays visible** in every mid/late-2026 capture [seen S10 (permission card → branch bar → composer), S3 desktop-auto-continue-02 (usage card → composer)]. Only the early-2026 light build replaced the composer with the card [seen S9]; that is history. Cards are not modal; the transcript keeps scrolling behind them (VS Code adds a fold button) [stated S13]. Dimming of the transcript behind a permission card was seen only in the early-2026 light build [seen S9]; the mid-2026 dark build does **not** dim [seen S10]; VS Code removed its dim in 2.1.47 [stated S13]. **Target: no dim.**
- VS Code adds a **dot rail**: every assistant step starts with a 7.5 pt dot at 12 pt from the panel's left edge with a 1 pt vertical line joining the dots, content at 27 pt [seen S1 vs-code-extension-interface, measured at 2x]. Desktop has no rail [seen S3, S6].
- The Desktop sidebar and side panes are out of scope (Tandem's sidebar exists); only the chat column is specified.

**Sketch (column and dock, Desktop Oct 2026):**

```
│← inset →│                      column ≤ 768                         │← inset →│
│         │                                             ┌──────────────┐        │
│         │                                             │ user bubble  │        │
│         │                                             └──────────────┘        │
│         │ Read a.ts, b.ts ›                                                   │
│         │ Reply text …                                                        │
│         │ ┌───────────────────────────────────────────────────────────┐       │
│         │ │ Edited 3 files                             [View changes] │       │
│         │ ├───────────────────────────────────────────────────────────┤       │
│         │ │ </> cart.spec.ts                                +12 -4  › │       │
│         │ └───────────────────────────────────────────────────────────┘       │
│         │ ✻ Testing… · 3s                                                     │
│         │                                 (empty space when the chat is short)│
│         │ ┌ pending card (permission / question / usage limit) ──────────┐    │
│         │ └─────────────────────────────────────────────────────────────┘    │
│         │ ┌ branch bar: main  +354 -16  Create PR ───────────────────────┐    │
│         │ ┌ composer: Type / for commands                             ↵ ┐    │
│         │   +  🎤 ⌄  Auto                              Opus 5  High  ◯       │
```

### 2.2 Surfaces, borders, radius

Page and surface colours per build, as sampled on the checked-in files (flat areas, exact):

| Build | Page | User bubble | Composer | Cards / boxes | Confidence |
|-------|------|-------------|----------|---------------|------------|
| Desktop Sept 2026 Windows (S6) | `#151515` | `#212121`, no border | fill `#20201f`, 1 px border `#373736` | code/command box fill `#1a1a19`; card and group-box border `#2d2d2d`, no fill | [seen S6 assembling, coupling-toggle, sampled 2026-10-19] |
| Desktop Oct 2026 macOS dark (S3 pop-out, scaled video) | `#121212`–`#131313` | grey, one step lighter | box with 1 px border | `Edited 3 files` card: 1–2 px border, no fill | [inferred S3 desktop-pop-out-01, -07] |
| Desktop mid 2026 macOS dark (S10) | `#1f1f1f` | — | fill `#2c2c2c` | permission card fill `#262626` with a 1 px lighter edge | [inferred S10] |
| Desktop April 2026 macOS dark (S7) | `#0f0f0f` | blue `#084d7f` | `#1c1c1c`, ring `#2e2e2e` | side panes `#131313`, table header `#222222` | [inferred S7] |
| Desktop Oct 2026 light (S3 auto-continue) | `#fafbf9` | light grey | white, 1 px border | white, 1 px border | [inferred S3 desktop-auto-continue-04] |
| Desktop early 2026 light (S9) | `#ffffff` (chrome `#faf9f6`) | `~#f0eee6` | white card, border, shadow | white, `#e2e2e2` border | [inferred S9] |
| VS Code Dark Modern (S1) | `#181818` (`sideBar.background`) | `#313131` (`input.background`), 1 px `#454545` | `#313131`, 1 px `#454545`/`#464646` | tool diff box `#1f1f1f` (`editor.background`) with `#343434`–`#3d3d3d` border | [seen S1, sampled on the lossless PNGs] |

The spread across Desktop dark builds (`#0d0d0d`–`#151515`–`#1f1f1f`) is the CDS neutral ramp moving between `gray-890`, `gray-850` and `gray-800` (2.3); it is a build/theme difference, not measurement noise. **For Tandem the page is the IDE's panel background, so the absolute value is moot; what matters is the one-step relations** (bubble, composer and code box each one small step lighter than the page; borders hairline at ~10 % white).

| Role | Desktop dark (Sept–Oct 2026) | Desktop light (Oct 2026) | Confidence |
|------|------------------------------|--------------------------|------------|
| Corner radius: user bubble | 8 px (corner curve spans 8 px) | ~8–12 px (not measured; S9 light used ~16) | [seen S6; inferred S3] |
| Corner radius: file card, `Edited N files` card, expanded group box | 6–8 px (curve spans 7–8 px) | ~8 px | [seen S6; inferred S3 desktop-auto-continue-02] |
| Corner radius: composer | 8 px | ~8 px | [seen S6; inferred S3] |
| Corner radius: permission card | ~10 px (S10) | 12 px (S9, early build) | [inferred S10, S9] |
| Corner radius: code fence / command box | 6–8 px | — | [seen S6] |
| Corner radius: inline code chip | 3–4 px | — | [seen S6] |
| Borders | 1 px, `#2d2d2d` on `#151515` (≈ 10 % white) | 1 px, ≈ 10 % black | [seen S6; stated S23 `--cds-border` = alpha-2 = 10 %] |
| Shadows | none on cards or composer | soft shadow on the composer card (early build only) | [seen S6, S3; S9] |

Hairline borders only; nothing is outlined unless it is a card, a box or the composer [seen S3 desktop-pop-out-01, desktop-auto-continue-04; S6].

### 2.3 Colour tokens

The Desktop Code tab is web code loaded from claude.ai [stated S28 #102723, #92988] and uses claude.ai's design tokens. Three independent public captures of those tokens agree on every value below [stated S23]; the sampled screenshots match them within compression noise. Hex values marked "derived" are conversions of the authored HSL triplets.

**CDS gray ramp (mode-independent, `--cds-gray-N`)** [stated S23]: 0 `#ffffff`, 10 `#fcfcfb`, 20 `#f9f9f7`, 30 `#f6f6f4`, 40 `#f3f3f0`, 50 `#f0efec`, 60 `#edece8`, 70 `#eae9e4`, 80 `#e7e6e1`, 90 `#e4e3dd`, 100 `#e1e0d9`, 150 `#d2d1c7`, 200 `#c3c2b7`, 250 `#b4b3a8`, 300 `#a5a49a`, 350 `#97958d`, 400 `#898781`, 450 `#7b7974`, 500 `#6d6b67`, 550 `#5f5e5a`, 600 `#52514e`, 650 `#454442`, 700 `#383835`, 750 `#2c2c2a`, 800 `#20201f`, 810 `#1e1e1d`, 820 `#1c1c1b`, 830 `#1a1a19`, 840 `#181817`, 850 `#151515`, 860 `#131313`, 870 `#111111`, 880 `#0f0f0f`, 890 `#0d0d0d`, 900 `#0b0b0b`. Brand: `--cds-clay #d97757`, `--cds-clay-emphasized #c6613f`.

Note how the sampled Desktop surfaces land on this ramp: Sept 2026 page `#151515` = gray-850, composer `#20201f` = gray-800, code box `#1a1a19` = gray-830 [seen S6]; April 2026 page `#0f0f0f` = gray-880, panes `#131313` = gray-860 [inferred S7]; `--cds-surface-0` (page) is gray-890 `#0d0d0d` in the token sheet and the `[data-darker-default]` sidebar is gray-860 [stated S23].

**Semantic tokens, dark and light** [stated S23; light values also in S22's legacy tokens]:

| Role | Dark | Light | Notes |
|------|------|-------|-------|
| Page (`--cds-surface-0`) | gray-890 `#0d0d0d` | gray-20 `#f9f9f7` | Sept 2026 Windows renders the page at gray-850 `#151515` [seen S6]; Oct 2026 light video page `#fafbf9` [inferred S3] |
| Panel / card (`--cds-surface-1`, `-2`, `-3`) | `#1a1a19`, `#2c2c2a`, `#383835` | `#fcfcfb`, `#ffffff`, `#ffffff` | tooltip bg = surface-3 dark / gray-900 light |
| Primary text (`--cds-text-primary`) | `#ffffff` (legacy `--text-100` `#faf9f5`) | `#0b0b0b` (legacy `#141413`) | sampled body text `#cbcbc8`–`#dedcd5` on 14 px anti-aliased text understates the token [inferred S6, S7] |
| Secondary text (`--cds-text-secondary`) | gray-200 `#c3c2b7` | gray-600 `#52514e` | bullet dots sampled `#c3c2b7` [seen S6] |
| Muted text (`--cds-text-muted`) | gray-400 `#898781` | gray-400 `#898781` | hover-row icons and relative time sampled `#757470` [seen S6]; summary rows and chevrons sampled `#9c9b91` = legacy `--text-400` dark `#9c9a92` [inferred S7], `~#9a9a9a` [inferred S6], `#a6a69c` [seen S29], light `#73726d`/`#8a8880` = legacy `--text-400` light `#73726c` [seen S9] |
| Disabled text | 35 % white (`--cds-alpha-4`) | 35 % black | footer `· Medium` sampled `#666666` [inferred S7] |
| Border (`--cds-border`) | 10 % white | 10 % black | sampled `#2d2d2d` on `#151515` [seen S6] |
| Hover fill (`--cds-fill-ghost-hover`) | 5 % white | 5 % black | row hover not seen in any capture; this is the token the app uses for ghost hovers |
| Selected row (`--df-selected`) | 10 % white (darker variant 15 %) | — | sidebar; sampled `#303030` on `#131313` [inferred S7] |
| User bubble (`--cds-bg-user-message` = `--cds-bg-neutral` = alpha-1) | 5 % white (sampled `#212121` on `#151515` [seen S6]) | 5 % black | the April 2026 blue "personal" tint (`--cds-bg-tint-blue`, sampled `#084d7f`/`#0f161b` with `#0a8df1` text [inferred S7]) was dropped by Sept 2026 [seen S6; stated S28 #94102] |
| Accent text / links (`--cds-text-accent`) | blue-300 `#6da7ec` | blue-600 `#184f95` | links sampled `#6092cd` underlined [seen S6]; Oct 2026 light links black underlined [seen S9] |
| Accent fill (`--cds-fill-accent`, usage ring, `Submit` buttons) | blue-450 `#2a78d6` | blue-450 `#2a78d6` | ring arc sampled `#0982de` on a `#202020` track [inferred S7]; `--dot-ready` `#5aa6f2` dark / `#308ce8` light |
| Danger text (`--cds-text-danger`, failed rows, diff `-N`) | red-300 `#ec7e7e` | red-600 `#8e2626` | `-N` sampled `#b32541`–`#e06060` [inferred S6, S8]; git-status variant `--cds-text-git-removed #ff2c56` |
| Success text (diff `+N`) | green-400 `#0ca30c` | green-600 `#006300` | `+N` sampled `~#5fb865` [inferred S6]; git-status variant `--cds-text-git-added #32d74b` |
| Warning (needs-input dot, `session` pill, `Auto mode` pill) | yellow-300 `#db9300` on `--cds-bg-warning` `#311a00` | yellow-600 `#734500` | sampled `~#e3b341` [inferred S4, S10, S11] |
| Brand coral (spark, send button, VS Code focus ring) | `#d97757`; pressed/emphasized `#c6613f` | same | sampled `#d36b50`, `#da7857`, `#cc7c5e` (anti-aliased) [inferred S7, S9]; identical constants in the VS Code CSS [stated S24] |
| Inline code chip | bg `#212121`, 1 px `#383838`, text salmon `~#d47373`–`#e8846b` | bg light grey, coral text | [seen S6]; claude.ai: bg `hsl(--text-200)/5%`, border `.5px hsl(--border-300)/25%`, colour `hsl(--danger-000)` [stated S22] |
| Code fence / command box | fill `#1a1a19` (gray-830), 1 px `#2d2d2d` | — | [seen S6] |
| Table header row | `#212121` (Sept) / `#222222` (April) fill; body rows page colour; header divider `#383838`, row dividers `#2d2d2d` | — | [seen S6; inferred S7] |
| Bash `$ <command>` colour inside the expanded box | syntax colours: command word `#64a1de`, arguments `#87c857`, numbers `#51c5c5` | — | [seen S6 exporting-launch-json (fence), coupling-toggle (command box)] |
| Added / removed diff rows | dark red row `~#4a1f22` / dark green row `~#1f3a24` (S10); `--cds-bg-danger #3c0e0e` / `--cds-bg-success #11260f` | pale red `#f9edec` / pale green `#e7f1da`, gutter `#f5f4ed` (S9); `--cds-bg-danger #fad6d6` / `--cds-bg-success #caeac7` | [inferred S10, S9; stated S23] |
| Dim overlay behind a modal | `--cds-backdrop #00000080` (dark) / `#0006` (light) | — | not used behind pending cards in the target builds |
| Scroll fade | 28 px (`--cds-scroll-fade-size`), strip colour = surface-1 | same | [stated S23 open-claude-ui only; unverified] |

**VS Code Dark Modern resolution** (for parity checks against the S1 screenshots) [stated S24, S25; seen S1]: foreground `#cccccc`, `descriptionForeground` `#9d9d9d` (sub-rows, context line, grey dot), `errorForeground` `#f85149`, `focusBorder` `#0078d4` (permission card outline, `1 Yes` fill `#0078d5`), `textLink.foreground` `#4daafc`, tool-done dot `#73c991`, diff added `#81b88b` / removed `#c74e39`, composer focus ring `#d97757` (acceptEdits: white; plan: `focusBorder`; bypass/auto: `errorForeground`), send button `#c6613f` with `#faf9f5` icon, modal dim `#000000bf`, and the 2025 permission prompt dimmed everything above it to opacity 0.40.

**Proposal for Tandem (theme-correct, not hex; every API below exists in the bundled Jewel 2026.1.5 jars [stated S31]):** page = `JewelTheme.globalColors.panelBackground`; primary text = `globalColors.text.normal`; secondary/muted = `globalColors.text.info`; failed = `globalColors.text.error`; warning = `globalColors.text.warning`; one-step surfaces (bubble, composer, code box, cards) = `JewelTheme.colorPalette.gray(n)` picked by `JewelTheme.isDark` so the step is ~5 % toward the text colour, like `--cds-alpha-1`; borders = `globalColors.borders.normal`; diff rows = the editor scheme's `DIFF_INSERTED` / `DIFF_DELETED` so they match the IDE diff viewer; `+N` / `-N` = the same scheme's foreground variants or `colorPalette.green(n)` / `red(n)`; links = `JewelTheme.linkStyle`; inline code and fences = the bridge `MarkdownStyling`; accent = `colorPalette.blue(n)`. The coral `#d97757` is kept **only** for the spark glyph (the one brand element every capture shares). This mapping was not yet checked in a running IDE.

### 2.4 Typography

**Faces.** claude.ai defines `--font-ui: "Anthropic Sans", system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif`, `--font-claude-response: "Anthropic Serif", Georgia, …` (assistant prose on claude.ai), `--font-mono: "Anthropic Mono", ui-monospace, monospace`; Anthropic Sans is a variable font (weights 300–800, roman + italic), Anthropic Mono has weight 400 only; ligatures are disabled on all mono (`calt 0, liga 0`) [stated S22]. The Desktop app's Settings › Claude Code offers *Interface font: Anthropic Sans or System*, *Code font* (free text, "for code and terminal"), *Transcript text size: Small / Medium / Large* and *Transcript width: Narrow / Medium / Wide* [stated S28 #48237]; no pixel values for those presets are published. **The Code tab's prose is sans, not serif**, in every Desktop capture [seen S3, S6, S7, S9, S28]; the serif is the Chat/Cowork tab. VS Code uses the editor's `chat.fontFamily` / `chat.fontSize` (default 13 px, workbench UI font) for everything and the editor mono font for code [stated S13, S25].

**Sizes and weights (claude.ai UI scale, the Code tab's basis)** [stated S22]:

| Class | Size | Weight | Line height |
|-------|------|--------|-------------|
| UI small (`.font-small`) | 12 px | 430 | 1.4 |
| UI base (`.font-base`, composer `.tiptap`) | 14 px | 430; bold 460 | 1.4 |
| UI large | 16 px | 430; bold 560 | 1.4 |
| Prose body (`.font-claude-response-body`) | 16 px | 400; bold 530 | 1.5 |
| Prose small | 14 px | 400; bold 530 | 1.5 |
| Prose heading / title | inherit / 28 px | 530 | 1.3 |
| Code small | 11 px mono | 400 (360 in dark mode) | 1.5 |
| Markdown h1 / h3 (full scale) | 25.6 px / 16 px | 600 / 500 | — |
| Markdown h1 / h2 / h3 (compact scale, used on narrow surfaces) | 15 / 14 / 13 px | — | — |
| Inline code in Markdown | `.9rem` ≈ 14.4 px of a 16 px body (0.9 em) | 400 | padding 1 px 4 px, radius `.3rem` |

Letter spacing is 0 on every UI and prose class (tracking utilities exist but are opt-in for eyebrows) [stated S22]. Dark mode thins the variable font: wght 360 regular / 460 medium / 540 semibold / 560 bold against 400/500/600/700 in light [stated S23].

**As measured in the Code tab:** body ≈ 14 px with 20 px line pitch at 1x on Windows (ink 15–16 px) [seen S6]; ≈ 15–16 px on macOS [inferred S7, S28 102604]; summary rows the same size as body, in the muted colour, not smaller [seen S6, S29]; `Edited`/`Ran` verbs are regular weight in Oct 2026 rows (the bold verb was the April 2026 per-call row) [seen S3 desktop-auto-continue-04 vs S8]; file names and commands inside rows are monospace at ~12.5–13 px [seen S3, S6, S8]; bold inline labels (`Layout:`) are the same size, heavier [seen S6]; headings in replies could not be measured (no capture contains an H1–H3). A June 2026 Windows build rendered every paragraph semibold, which users filed as a regression [stated S4]; the target is regular body weight with bold reserved for emphasis.

**VS Code:** body 13 px (`chat.fontSize`), inline code `.9em` with `2px 4px` padding and 3 px radius, diff editors `fontSize: 12` mono, composer max-height 200 px (~8 lines) [stated S25, S28 #45841, #89808, #95971]; a third-party override extension implies native sizes of ~14 px chat text, ~11 px fences, 12 px diffs [stated S28 #11651].

**Decision for Tandem (proposal):** do not bundle Anthropic's proprietary faces; use the IDE fonts through Jewel: `JewelTheme.defaultTextStyle` (Settings › Appearance font, default Inter 13) for UI and prose, `JewelTheme.editorTextStyle` (editor font, default JetBrains Mono 13, `liga 0, calt 0` unless the scheme enables ligatures) for paths, commands, code [stated S31]. Scale relative to the base size *b*: muted rows and sub-rows = *b* (same size, `text.info` colour); footer/meta = *b* − 1; verbs/labels = *b* at `FontWeight.SemiBold` (Anthropic uses 460–560 on a variable font, never 700); mono inside rows and chips = editor style at *b* − 1; h1/h2/h3 = *b* + 2 / *b* + 1 / *b* at SemiBold/SemiBold/Medium (mirrors the compact 15/14/13 scale); line height 1.4 for UI rows, 1.5 for prose; letter spacing 0.

### 2.5 Spacing

All values at 1x unless noted; "ink" = glyph extent, "line pitch" = baseline to baseline.

**Desktop Sept 2026 Windows, 1x (S6)** [seen S6 assembling, coupling-toggle, exporting-launch-json; measured 2026-10-19]:

| Element | Measure |
|---------|---------|
| Body text | ink 15–16 px, line pitch 20–21 px (14 px font, lh ≈ 1.45) |
| Paragraph → paragraph | +10–11 px over the line pitch |
| Paragraph → list, list → paragraph | +8–9 px before, +20–21 px after the last item |
| List item → item | +4 px |
| Bullet | 5 px filled dot at column +12 px; item text at column +35 px |
| User bubble | padding 12 px horizontal, ~10 px vertical; 3 lines = 76 px tall, 1 line = 36 px; right edge flush with the content column; width fits content |
| Bubble → first summary row (ink) | 56 px (Sept Windows); 17–19 px in Oct 2026 ~400 px panes [seen S3 desktop-pop-out-01, -07] |
| Summary row | one 20 px line box; no leading glyph; chevron `›` 5–6 × 10 px, 6–7 px after the last glyph, vertically centred on the text |
| Summary row → reply text (ink) | 22 px |
| Consecutive summary rows | pitch 30 px = 1.5 lines [seen S3 desktop-pop-out-07]; 34 px (1.7 lines) in the light Aug 2026 video [seen S3 desktop-auto-continue-01]; April 2026 per-call rows ~25 px inside a group and ~37 px between groups [inferred S8] |
| Reply last line → file card | 16 px; card → next user bubble 48 px; card → footer icon row 15 px |
| File card | 36 px tall, 1 px border, no fill; icon at +9, name at +30 from the left border; `+N` `-N` then `›` 12 px from the right border, 15 px after `-N`; text vertically centred (10 px above/below) |
| `Edited 3 files` card (Oct 2026, ~400 px column) | header 38 px with a `View changes` button 81 × 24 px (10 px from the right); 1 px divider; file rows 27 px each: `</>` icon at +12, name at +26, `+12` `-4` `›` with the chevron 12 px from the right border [seen S3 desktop-pop-out-07] |
| Expanded group box | header `Ran 2 commands ⌄` (13 px grey text, chevron-down 10 × 6 px, 4 px after the text); box top border 13 px below the header ink; 1 px border, radius ~8 px, no fill, full column width; inner rows 36 px tall, 1 px dividers inset 7 px, row text at +11 from the border, each with its own `›` (5 × 10 px, 8 px after) or `⌄` (10 × 6, 6 px after); an opened command: box inset 10 px, fill gray-830, 12 px mono, 17 px line pitch, starting 7 px below the row text; raw output below with no fill, 17 px pitch; box bottom → next reply 14 px |
| Fenced command block | sized to content (442 of 676 px), 33 px tall, text at +17, run icon 10 px and copy icon 13 px at the right with 12 px padding; 12 px above, 14 px below |
| Working row | 6 × 6 px coral dot at column +7, text 12–13 px starting 21 px after the dot; 24 px (ink) below the preceding row |
| Turn footer | four 12 px icons at 24 px pitch, `just now` 15 px after the last; 15 px below the card; lone `✻` 18 × 18 px 21 px below the icons |
| Composer | box 40 px tall, 1 px border, radius 8 px; placeholder at +12, ink 14 px; send/stop glyph 14 px at 13 px from the right border; footer text ink 10 px below the box: `+` at +12, mic, chevron, mode label 12 px; right: model, effort, 12 px usage ring aligned with the box's inner right; footer → window bottom 14 px; last transcript ink → composer top 17 px when scrolled to the end |

**Desktop Oct 2026, ~1x, ~400 px panes (S3 pop-out)** [seen S3 desktop-pop-out-07, -01]: bubble 2 lines = 56 px with ~11 px side and 9–11 px vertical padding; summary row pitch 30 px; chevron 4–5 × 6–7 px, 7–8 px after the text; row → reply 28 px (ink gap 19); reply → working row 17 px; `Edited 3 files` card 121 px tall for 3 rows; last bullet → card 14 px; card → reply 18 px; dock from the bottom: footer ink 14 px above the window edge, composer 41 px tall 9 px above the footer ink, PR bar 35 px tall 10 px above the composer; verbose MCP rows pitch ≈ 30 px [seen S3 desktop-browser-10, scaled].

**Desktop Oct 2026 light (S3 auto-continue, scaled; divide by 1.3 for the close-ups)** [seen S3 desktop-auto-continue-01, -02, -04]: `Edited` rows pitch 34 px, chevron 11–12 px after the last digit; bubble → row 18; row → reply 22; reply → first `Edited` row 20; last row → working row 23; usage card ≈ 425 px wide (two thirds of the column, **left-aligned**, not full width), 1 px border, ~21 px padding, 29 px buttons, 13 px bottom padding; last row → card ~16; card → composer ~27; composer ~45 px; composer → footer ~9; footer → bottom ~15–19.

**Desktop mid 2026 dark permission dock (S10, scale unknown, body line pitch 28 px; ratios)** [inferred S10]: edit-preview card → permission card 2 px; permission card 202 px tall (7 lines) with 16 px inner padding, 11 px yellow dot, heading 21 px below the top, `session` pill 52 × 29 px 22 px from the right, file name 20 px below the heading, mono path box 47 px tall 15 px below, buttons 33 px tall 16 px below the box and 16 px above the bottom (`Deny` 65 px wide at the left; `Always allow` 126 px and `Allow once ⌘↩` 145 px right-aligned, 8 px apart); card → branch bar 5 px; bar 57 px tall; bar → composer 7 px; composer 62 px; composer → footer 17 px.

**VS Code, 352 pt panel, 2x (S1 interface shot; pt = px/2)** [seen S1]: everything starts 12 pt from the panel's left edge; dot 7.5 pt with a 1 pt rail line; row text at 27 pt; sub-row `└` at 29.5 pt, its text at 40.5 pt, smaller and grey; user bubble 180 × 28 pt, text inset 9.5 pt, radius ~3–4 pt (`#313131` on `#181818`); bubble → context line 11 pt; context → first text row 19 pt; text row → tool row 30.5 pt; tool row → `└` sub-row 16 pt; sub-row → next tool row 26 pt; Write row → code preview box 11 pt (box 82 pt tall, clamped); box → `✻ Pondering…` 15.5 pt; composer 65 pt tall (two rows), radius ~8 pt, 7 pt above the status bar. Zoomed lossless shots (`vs-code-send-prompt.png`, `vs-code-edits.png`; line pitch 34 pt): composer inset 25.5 pt = 0.75 line, 155 pt tall with three input lines; bullets at text +15.5 pt with item text at +34.5 pt (one line pitch); paragraph gap 0.65 line; permission card = composer width, option rows 46.5 pt tall, inset 0.38 and gap 0.3 of the row height, free-text input 52 pt, bottom padding 0.35 row.

**Compose defaults for Tandem (proposal, synthesised from the above; the tool window is usually 350–500 dp wide, closest to the ~400 px panes and the VS Code panel):** column = full width − 2 × 16 dp, capped at 768 dp and centred beyond that; composer the same width. Body = IDE font, line height 1.4 (≈ 20 dp at 14 sp). Consecutive tool rows at 1.5 × line height pitch (30 dp); row text same size as body in `text.info`; trailing chevron glyph ~6 × 10 dp, 8 dp after the text; no leading glyph. Group box: 1 dp border, 8 dp radius, 36 dp inner rows with 1 dp dividers, 10–12 dp inner padding. File card 36 dp tall, 8–10 dp inner padding, chevron 12 dp from the right edge. Bubble: 12 dp × 10 dp padding, 8 dp radius, width wraps content, right-aligned, max 85 % of the column. Lists: bullet at +12 dp, text at +35 dp; paragraph gap 10 dp; item gap 4 dp. Dock: content → pending card 16 dp; card → composer 8–10 dp; composer 40 dp tall with 12 dp inner padding; footer row 10 dp below; 14 dp bottom margin.

### 2.6 Dark and light themes

Both themes are the same layout with the neutral ramp inverted (2.3): in light, surfaces go white → `#fcfcfb` → `#f9f9f7`, text `#0b0b0b` / `#52514e` / `#898781`, borders 10 % black, hover 5 % black, bubble 5 % black; in dark, page `#0d0d0d`–`#151515`, text white / `#c3c2b7` / `#898781`, borders 10 % white, bubble 5 % white [stated S23; seen S3 desktop-auto-continue-04 (light) vs desktop-pop-out-01 (dark); S6]. Diff rows: pale red/green in light (`#f9edec` / `#e7f1da` with a `#f5f4ed` gutter), deep red/green in dark (`~#4a1f22` / `~#1f3a24`) [inferred S9, S10]. The coral spark, the blue accent and the green/red diff numbers are the same hue in both. No light-theme capture at tool-window width exists for the Oct 2026 build (the Oct light evidence is the 1016 px-pane video and the S30 Japanese screenshots at ~870 px).

### 2.7 How it feels

Calm, flat, low contrast: the reply is the only full-contrast text; everything the model *did* is a quiet muted line you can open; the only saturated colours are the coral spark, the green/red diff numbers, the blue usage ring and a red failure row [seen S3 desktop-pop-out-01, desktop-auto-continue-04; S4, S6]. The only motion is the spark (which animates continuously while working, 3.8) and the streaming text (which fades each new chunk in over ~200 ms, 3.2); nothing else moves. Rows have no icons (Desktop) or one small dot (VS Code). Cards are rare and reserved for things that need the user (permission, question, usage limit) or that summarise many files (`Edited 3 files`). Users who dislike the Desktop say the tool affordances are "dim grey on dark grey" and that their own prompts "blend into everything else" [stated S4, S28 #94102], which tells you how far it leans toward quiet; Tandem can keep the bubble one step more visible than 5 % without leaving the family.

---
## 3. Elements

### 3.1 User message

**Appearance (Desktop, Sept–Oct 2026).** A filled rounded rectangle, no avatar, no label, no timestamp [seen S3 desktop-pop-out-01, desktop-auto-continue-01, desktop-browser-11; S6; S28 102604-desktop-thinking-summary-row]. **Right-aligned**: right edge flush with the content column; width wraps the text up to ~85 % of the column (S6: a 3-line prompt is 574 of 676 px; S3: a 2-line prompt spans the ~350 px column) [seen S6 assembling; S3 desktop-pop-out-01, -07]. Fill one small step lighter than the page (`#212121` on `#151515`, the `--cds-bg-user-message` 5 % white token), no border, radius 8 px, padding 12 px horizontal / ~10 px vertical, text the same size and colour as the reply [seen S6; stated S23]. History: the April 2026 macOS build drew a left-aligned, full-width, blue-tinted bubble with light-blue text [seen S7], which users then missed when it went [stated S28 #48158, #94102]; the early-2026 light build drew a light-grey bubble [seen S9]. **Target: right-aligned, subtle grey, 8 dp radius.** The low contrast is deliberate in the app; for a tool window Tandem may use a slightly larger step (proposal: `colorPalette.gray` one step, ~8 % toward the text colour).

**VS Code** [seen S1 vs-code-send-prompt (lossless): bubble 421 × 50 pt for one line, radius ≈ 8 pt, side padding ≈ 13 pt, fill `#313131` with a 1 pt `#454545` border on `#181818`, text `#cccccc` at 13 pt; vs-code-extension-interface: a short prompt hugs its content at 52 % of the column]: **left-aligned**, width fits content, and a small muted context line sits ~8 pt under the bubble listing what was attached (`utils.py#2-3`, `localizationUtils.ts`), which newer builds expand into rows for terminal output, browser tab and selected code [stated S13 2.1.286]; file pills show the project-relative path on hover [stated S13 2.1.290].

**Markdown and code in the prompt.** Unknown for the sent message in both hosts: no capture contains a prompt with a fence. The Desktop composer formats Markdown live while typing; the VS Code composer shows raw syntax [stated S28 #96921]. **Proposal:** render the sent prompt as plain text with line breaks preserved (no Markdown parsing); that cannot misrender code and matches every capture, which show plain sentences.

**Long prompts.** Desktop inserts long pastes inline in the composer with no collapse chip [stated S28 #97209, #96147]; VS Code keeps pasted text visible in the box and marks a paste over 800 characters or over 2 line breaks so Claude can tell it from typing, hiding the marker in the shown message [stated S1, S13 2.1.280]; the CLI's `[Pasted text #1 +N lines]` placeholder is CLI-only. Whether the sent long prompt is clamped in the transcript is unknown in both hosts. Community precedent clamps at 160 px with a gradient and a chevron, and previews 10 k of a > 20 k paste [stated S20 jetbrains-cc-gui]. **Proposal:** clamp a prompt taller than 8 lines to ~160 dp with a bottom fade and a `Show more` link (the same 3 rem fade mask the app uses on tall bubbles, `mask-image: linear-gradient(to bottom, black calc(100% - 3rem), transparent)` [stated S23]).

**Attachments.** One image attached to the prompt shows as a ~480 × 270 px thumbnail with a 1 px border **above** the bubble, right-aligned with it [seen S6 framing-hero-low-fi-wireframe]. In the composer, pasted images show as a thumbnail or an `[Image #N]` chip [stated S28 #96390, #39416]. The layout of several images in one sent message is unknown. **Proposal:** thumbnails in a wrapping row above the bubble, each ≤ 240 dp wide, 8 dp apart, 1 dp border.

**Queued and steered prompts.** Desktop, Oct 2026: a prompt sent with Enter while Claude works is a *steer*: it is appended to the transcript at once, styled like a sent message, with a small `unread` hint beside it, and is read at the next tool boundary (the message is delivered as a `queued_command` attachment on the tool result); it moves below the reply when the turn ends [stated S28 #92988, #71726, #98493]. `Cmd/Ctrl+Enter` = *queue for later*: the composer shows a collapsible `N messages queued` list (`1 message queued`, `4 messages queued`) with per-item removal and `Clear all`; the send button's tooltip lists `Send (Enter)`, `Interrupt (Ctrl+Enter)`, `Send in a forked session (Ctrl+Alt+Enter)`; the button relabels to `Queue` while busy [stated S28 #102342, #101909, #66310, #93402, #96608]. VS Code: queued messages wait at the bottom in grey until Claude starts on them, screen reader says `Message queued.` [stated S1, S13 2.1.275]; CLI: queued messages show in grey, `Ctrl+Enter` sends them now, and prompts sent while idle show in the normal colour right away [stated S14, S13 2.1.286]. No capture shows the queued row. **Proposal for Tandem:** Enter while busy = steer (append the bubble at once at 60 % opacity with a `queued` caption under it in `text.info`; restore to full opacity when the CLI's `command_lifecycle started` names its uuid, section 4.1); Shift+Enter = newline; no separate "queue for later" list in Phase 2a.

**Hover actions.** Desktop: hovering a message shows a per-message toolbar with `Revert to here` (rewind) and the timestamp (`showMessageTimestamps`) [stated S28 #86916, Aug 2026]; Oct 2026 adds a pin icon `Pin as chapter` / `Unpin chapter` whose pins form a Chapters list in the right panel [stated S28 #102315]; selecting reply text offers `Reply`; `Send in a forked session` exists [stated S28 #98829, #92988]. VS Code: hover reveals the rewind button with `Fork conversation from here`, `Rewind code to here`, `Fork conversation and rewind code`; `Edit prompt` restores a flagged prompt to the composer [stated S1, S13 2.1.300]. VS Code pins the latest user prompt at the top of the panel while its response scrolls (a sticky header); Desktop had it and lost it in May 2026 [stated S28 #60161]. Timestamps: Desktop shows none in the stream (users ask for them, #102406) and a relative time in the reply's hover row (3.2); VS Code shows a time above each prompt and response with a date line when the day changes, default on since 2.1.290 [stated S13].

**Sketch (Desktop):**

```
                              ┌──────────────────────────────────────────┐
                              │ cart.spec.ts is flaky on CI — passes     │
                              │ locally, fails about 1 in 5 runs.        │
                              │ Find out why and fix it.                 │
                              └──────────────────────────────────────────┘
                                                        (steered while busy:)
                                                ┌──────────────────────────┐
                                                │ Also bump the timeout.   │   60 % opacity
                                                └──────────────────────────┘
                                                                    queued
```

### 3.2 Assistant text and Markdown

**Appearance.** Plain Markdown flush with the column's left edge; no bubble, no label [seen S3 desktop-pop-out-01, desktop-pop-out-07; S6]. VS Code prefixes each text step with a grey dot on the rail [seen S1].

**Streaming.** New text does **not** appear with a caret: in 39 consecutive frames of two videos, nothing follows the last glyph [seen S3 derived/desktop-auto-continue-04-tail-x3, desktop-pop-out-01-tail-x3]. The "tail fade" is a **per-chunk opacity fade-in**, not a mask: each appended token chunk (from a 2-letter word to a multi-word run, 36–160 px wide) appears at alpha ≈ 0.1–0.4 across its whole width and rises ≈ +0.15 per 33 ms frame to opaque in 5–6 frames (≈ 170–200 ms, roughly linear); chunks arrive every 130–170 ms, so at any instant the last one to three chunks are dim (newest ≈ 0.2, previous ≈ 0.6, older ≈ 0.9); when the message completes the paragraph is fully opaque with no residue [seen S3 derived/desktop-auto-continue-tail-fade-montage, desktop-pop-out-tail-fade-montage, measured on 33 ms frames]. Whether a new block (list item, table row, fence) fades in as a block was not observed; the mechanism implies yes [inferred]. The working row below the text carries the "busy" meaning (3.8). **Proposal:** on each `text_delta`, render the appended span with an `animateFloatAsState` alpha from 0.2 → 1 over 200 ms (linear); no caret; nothing when reduced motion is on.

**Body and lists** [seen S6 coupling-toggle, assembling]: 14 px / 20 px pitch (2.5); bullets `•` as a 5 px filled dot (`#c3c2b7`) at column +12 with item text at +35; bold inline labels (`Layout:`); numbered lists; horizontal rules between sections [seen S6, S3 desktop-browser-11]. Nested-list markers, blockquote and heading sizes (H1–H3) were not captured in any Desktop screenshot; the only statement is "headers look like headers, lists format as lists" (third-party blog). The earlier note that "headings are bold body text, not larger" [first draft, S6] cannot be confirmed because no S6 reply contains a heading; it is withdrawn. **Proposal:** the compact claude.ai scale, h1/h2/h3 = *b* + 2 / *b* + 1 / *b* (2.4).

**Inline code** [seen S6 exporting-launch-json, derived/s6-fence-x3]: chip with fill `#212121`, 1 px border `#383838`, radius 3–4 px, ~5 px horizontal padding, box 19 px on a 20 px line, mono ~12–13 px, salmon text `~#d47373`–`#e8846b`; file paths and commands in prose use it (`package.json`). claude.ai's rule: `.9rem`, border `.5px` at 25 %, bg `text-200` at 5 %, colour `--danger-000`, `white-space: pre-wrap` [stated S22].

**Code fences** [seen S6 exporting-launch-json, derived/s6-fence-x3]: a one-line shell fence renders as a rounded box **sized to its content** (442 of 676 px), 1 px `#2d2d2d` border, fill `#1a1a19`, 33 px tall, text at +17 px, mono 12–13 px with syntax colours (command `#64a1de`, arguments/flags `#87c857`, numbers `#51c5c5`); inside the box at the right end a **run** icon (outline play triangle, 10 × 10 px) 24 px after the text and a **copy** icon (two overlapping squares, 13 × 12 px) 16 px further, 12 px from the right border, icon ink `#cccccc` at full coverage; no language label, no header strip, no line numbers. The run icon was seen only on a single-line shell command the reply offered to execute; no Desktop capture shows a multi-line fence, so language label, copy placement and whether `run` appears on multi-line shell blocks are **unknown**; a feature request implies the copy control sits at the top of long blocks [stated S28 #74185]. VS Code: `pre` with 4 px radius and 8 px padding, copy button on code blocks and bash inputs [stated S24, S13 2.0.46]. Mermaid is not rendered in Desktop or VS Code (raw source in a plain block) [stated S16 #52517]; LaTeX is typeset (2.1.301) [stated S13]; GFM task-list checkboxes render (2.1.149) [stated S13]. **Proposal:** fences full-width with a 6–8 dp radius, the IDE editor font and highlighter (the bridge `MarkdownStyling.Code.Fenced` with `infoPosition = Hide`), a copy icon top-right that appears on hover (`IconActionButton`, transparent style), and a run icon only for one-line fences whose language is `bash`/`sh`/`shell` (runs in the IDE terminal).

**Tables** [seen S6 coupling-toggle, derived/s6-table-x2]: span the full column width; outer 1 px `#2d2d2d` border with ~6 px rounded corners; header row fill `#212121`, 32 px tall, bold 13–14 px text, separated from the body by a 1 px `#383838` line; body rows 32 px tall on the page colour with 1 px `#2d2d2d` horizontal dividers and **no vertical borders**; cell text 12 px from the cell's left edge (~12 × 9 px padding); columns near-equal and filling the width; in a narrow pane a 5-column table keeps full width and wraps cell text [seen S6 assembling].

**Links** [seen S6 coupling-toggle, getting-responsive-widths]: blue `#6092cd`, always underlined (1 px, 4 px below the baseline), used for file paths (`wireframes/pricing.html`) which open the file pane; hover state not captured; right-click on a path offers `Attach as context`, `Open in`, `Show in Finder`, `Copy path` [stated S2]. Light theme early 2026: black underlined [seen S9].

**Interstitial sentences.** A one-line sentence between tool groups is plain body text at the row margin [seen S8; S3 desktop-browser-03].

**Hover row and relative time** [seen S6 getting-responsive-widths, framing-hero, exporting-launch-json; derived/s6-hover-icons-x3; S30 note09-todo-result]: under the latest reply a single row of four glyphs, each 11–12 px of ink at a 24 px pitch, colour `#757470` on `#151515`: **copy** (two overlapping rounded squares, back one offset up-left), **share** (three dots joined by two lines, like a `<` node graph), **pin** (outline push-pin tilted ~45°, needle to the lower left), **read aloud** (speaker cone with two arcs); then the relative time in the same colour, ~13 px, 15 px after the speaker. The row appears under the latest reply in three of four S6 shots and is absent in the fourth, so it is hover-revealed per message [inferred S6]. Strings seen: `just now`, `9 minutes ago` [seen S6], `5 hours ago` [stated S28 #98661], `5分前` (Japanese `5 min ago`) [seen S30]; hovering shows an absolute tooltip like `Oct 1, 2026, 1:40 PM` [stated S28 #98661]. Thresholds (when `just now` becomes `1 minute ago`, when hours become days or a date) are unknown. VS Code: `Copy response` and `Bookmark response` / `Remove bookmark` on hover, plus the rewind button [stated S1, S13]; `Copy response` returns the raw Markdown [stated S1]. **Proposal:** copy + relative time only (share, pin and read-aloud have no Tandem meaning); `just now` under 60 s, `N minutes ago`, `N hours ago`, then the IDE's `DateFormatUtil.formatPrettyDateTime`; tooltip = absolute time.

**Sketch:**

```
The spec clicks “Place order” before the cart request settles, so it races the
network. Plan:

• Wait on the `/api/cart` response instead of a fixed 500 ms sleep
• Add a small `retry()` helper around the payment stub so timing is deterministic
• Re-run the suite 20× to confirm the flake is gone

┌ python -m http.server 8000 --directory wireframes          ▷  ⧉ ┐
└────────────────────────────────────────────────────────────────┘

┌───────────────┬───────────────┬───────────────┬───────────────┐
│ Plan          │ Monthly       │ Yearly        │ Billed        │   header fill
├───────────────┼───────────────┼───────────────┼───────────────┤
│ Starter       │ $0            │ $0            │ Free forever  │   no vertical rules
│ Pro           │ $19           │ $15           │ $180 yearly   │
└───────────────┴───────────────┴───────────────┴───────────────┘

⧉   ⋖   ⊼   🔈   just now                                            (hover row)
```

### 3.3 Thinking

**Desktop (current, Oct 2026) [seen S28 102604-desktop-thinking-summary-row, 102604-desktop-flagged-card; 92535-desktop-expanded-thinking-summaries].** The thinking row is **one line of the thinking summary text itself**, in the muted colour, truncated with `…` at the column width, followed by a `›` chevron after the text: `This person wants to report a genuine bug about missing thinking blocks in the UI, which is legitimately h… ›`. No bullet, no `Thinking` label, no token count, no duration. It sits directly above the reply at the body's left edge, same size as body, with roughly one line of space above and below, and looks exactly like a collapsed tool row (`Claude Code notice ›` is drawn the same way beneath it). **Expanded** [seen S28 92535, macOS, Sept 2026]: the header line is the latest summary sentence in muted text with a `⌄` chevron after it; below it every summary sentence on its own line in primary text, same size as body, no indentation, no bullets, no border, no box. Summaries are one sentence per reasoning phase in first person (`Working out the best move in a chess position.`, `I recall that the number of compositions…`) [seen S28 92535; stated S34 `think-display-summarized:34`]. A copy button on an expanded block copies a Markdown blockquote `> **Thinking** (~36 tok)` + a blank quoted line + the text [stated S28 #100521]. The row only appears in the **Thinking** or **Verbose** view mode (session menu ▸ Transcript view, or Ctrl+O cycles; the Thinking entry appears in the menu only after the session produced thinking) [stated S2; seen S28 78593-desktop-transcript-view-menu, 81979, S30 note17]; a `Default transcript view` setting (`Normal | Thinking | Verbose` segmented control) exists since Desktop v1.52386.0 [seen S28 76577-desktop-set-default-view; stated S27]. Desktop launches the CLI with `--thinking-display summarized`; `showThinkingSummaries` only toggles summarized/omitted [stated S28 #94032, #102604]. While streaming: July 2026 reports show the block fully expanded while it streams and a clickable `thinking...` text; an Oct 15 2026 report says the text streams in, disappears when the block completes and leaves a `Thinking` label in Thinking view; the Oct 16 2026 screenshot shows the collapsed summary row after the turn [stated S28 #78593, #79021, #94032; seen S28 102604]. The live row's exact form in the current build is therefore **unknown**; after the turn it is the collapsed summary row [seen]. After an app restart older builds hid thinking entirely [stated S28 #78593, #79021].

**VS Code [seen S28 49739-may28-vscode-toggle, 49739-jun16-vscode-thinking-fixed, 77460-vscode-thought-for-ticking-tokens, 55120-vscode-no-chevron; S5]:** a row with the grey rail dot and muted text:

- while streaming: `Thinking… · 3.7k tokens` / `Thinking… · 128 tokens` (label, ` · `, count with one decimal and `k` ≥ 1000, `tokens`); the live count ticks even when no text arrives;
- after the block completes: `Thought for 1s` / `Thought for 0s` / `Thought for 7s` (whole seconds, so `0s` happens; 2.1.79 fixed the pill staying on `Thinking`);
- a `⌄` chevron after the label when there is text to show; expanded body = the summary paragraphs in muted text, normal weight, at the reply's left edge, no box; when the text is empty the row is a static non-clickable label with no chevron (the "missing chevron" issues #55120, #61679, #64714 were this) [stated S28 #77460 reconstruction];
- the rotating verb (`Imagining…`, `Tinkering…`, `Clauding…`) is the **separate working row** with the coral `✻`, not the thinking label (one unverified report of `Churned for 1s`);
- Ctrl+O expands/collapses every thinking block; Focus view folds thinking-only turns to `Thought for Ns`; screen reader label `Claude, thinking`; the Thinking toggle lives in the `/` menu's Model section and is hidden for models that always think [stated S1, S13].

**Over stdio (recorded, S34).** With Tandem's argv the thinking text is always empty (`thinking_display:"updates"`, `estimated_tokens` only). With `--thinking-display summarized` on the argv, or `set_max_thinking_tokens {thinking_display:"summarized"}` after `initialize` (effective next turn), every `thinking_delta` carries a text chunk and the assistant block holds the whole summary (`think-display-summarized:11-34`, `think-summarized:33-62`); the assistant line carries `thinking_duration_ms` (`think-display-summarized:34`: 1991 ms). A trivial prompt produces no thinking block at all. Section 4.3 has the frames.

**Sketch (Desktop target):**

```
Working out the best move in a chess position. Testing knight moves for a forc… ›     ← muted, one line, click to expand

Working out the best move in a chess position. ⌄                                     ← expanded: header = latest sentence
Working out the best move in a chess position.                                        (primary text)
Testing knight moves for a forced checkmate pattern.
Rechecking piece placement and probing queen sacrifice lines.
```

**Target for Tandem (proposal):** Desktop's collapsed summary row, drawn like a tool row (muted, `›`), shown only when a "Show thinking" toggle is on (session menu, default off, remembered per project, matching Desktop's Normal/Thinking modes). While streaming with summaries on: the row shows the text received so far on one line (newest chunk at the end), ellipsised; with summaries off the row is not shown at all (nothing to show; the working row covers "thinking"). After the block: the collapsed summary row; expanding lists the sentences (split on `\n\n`). Spawn with `--thinking-display summarized` so the text exists; duration from `thinking_duration_ms` (or the host clock between `content_block_start` and the block's completion) and the final `thinking_tokens` from `message_delta.usage` are available for a tooltip (`Thought for 2s · 166 tokens`) but Desktop shows neither in the row. Copy (hover) = the blockquote format above.

### 3.4 Tool call row (generic)

**Desktop Normal view (Sept–Oct 2026, the target) [seen S3 desktop-auto-continue-01, -04, desktop-pop-out-01, -07; S6; S29; S30].** One line of muted text per *group* of tool activity (3.7), same size as body, no icon, no indent (flush with the prose), no border, ending in a small `›` chevron glyph drawn in the same muted colour, centred on the text, 6–8 px after the last character (measured: text `#a6a69c` on `#121212`, chevron 3 × 6 pt at 2x on S29; 5–6 × 10 px at 1x on S6; 4–5 × 7 px on S3). Diff stats, when any file changed in the group, sit before the chevron as `+N` green and `-N` red; **the zero side is shown** (`+146 -0`, `utils/retry.ts +18 -0`) in the Sept and Oct 2026 builds [seen S6; S3 desktop-pop-out-01; S30], and was omitted only in the April 2026 build (`Write <path> +62`) [seen S8]. File names and commands inside the line are monospace. Row box = one text line (20 px) with 1.5-line pitch between consecutive rows (2.5). The whole row is the click target (users click "its header" to collapse) [stated S28 #73827, #65668]; clicking expands the body below and turns the chevron into `⌄` [seen S6 coupling-toggle; S10]. Hover highlight: not captured in any screenshot; the app's ghost-hover token is 5 % (`--cds-fill-ghost-hover`) [stated S23].

Exact strings seen on Desktop:

- `Read cart.spec.ts, checkout.ts ›` ; `Read 38 files, searched for 6 patterns ›` ; `Read page text ›` [seen S3; S30]
- `Found files, searched code ›` (Glob + Grep, April 2026) [seen S29]
- `Edited billing/charge.ts +31 -40 ›` ; `Edited 61 files +904 -1130 ›` ; `Edited 14 files ›` ; `Edited http.ts +10 -3 ›` ; `Edited README.md +23 -16 ⌄` (expanded) [seen S3, S4, S10]
- `Ran pnpm test ›` (command in mono) ; `Ran npx @tailwindcss/upgrade ›` ; `Ran 2 commands ›` ; `Ran View GitHub issue 2664 ›` (one Bash: `Ran ` + its `description`) [seen S3, S6, S29]
- `Used 2 tools ›` ; `Used 3 tools ›` ; `Ran a command, edited a file ›` ; `Ran a command, used 4 tools ›` ; `Created pricing.html, ran a command +146 -0 ›` ; `Found files, read 2 files, created a file, edited a file, used 4 tools +73 -1 ›` ; `Ran a command, created launch.json, used a tool +11 -0 ›` [seen S4, S6, S7]
- `Ran an agent ⌄` (Agent) [seen S29]
- `Using plugin playwright playwright: browser evaluate ›` [seen S4]
- `Failed to check businesses/branch/causegroup OData queries ›` (whole row red) [seen S4]
- Running: `Checking for node and headless browser options ›` [seen S6] ; `Reading weather.ts ›` [seen S7] ; `• Opening in Browser` / `• Running npm test · 0/340 passed` (a small coral dot before the text, no chevron, for a live step) [seen S3 desktop-browser-03, desktop-pop-out-03, -07]

**States** (`ToolUse` and `ToolGroup` render-site props, which Desktop uses) [stated S26]: `isRunning` ("True while the call is still running"), `isErrored` ("a refusal at the dialog is one"), `isInterrupted` ("The row draws `Interrupted` for it"), `output` ("undefined while it runs"); a group has `isActive` ("True while the group is the live one: a call in it may still be running and the model's next call may join it") and `isExpanded` ("true under `--verbose` and in the ctrl+o transcript"). So **rows exist from the moment the call starts**, not when it completes [stated S26; seen S3 desktop-browser-11: six MCP rows listed above a still-running spinner row; stated S28 #100876: the spinner mounts/unmounts once per tool call]. Visually: *running* = present participle (`Reading`, `Checking…`) or the `• <step>` dot row, no stats; the busy signal is the spinner row below it (3.8), **not** a spinner on the row; *done* = past tense + stats; *failed* = whole row red (`Failed to …`); *interrupted* = `Interrupted`; *not run* (auto-mode skip) = dim `Not run` [stated S13]; *expanded* = `⌄` + body.

**Expanded body** [seen S6 coupling-toggle]: a bordered box (1 px, radius ~8 px, no fill) spanning the column with one 36 px sub-row per call, each a natural-language description with its own `›`/`⌄` (`Updated prices and suffix logic ›`, `Verified toggle with new prices ⌄`), 1 px dividers between sub-rows; an opened command shows the command box then the raw output (3.5 Bash). Expanded Agent [seen S29 kiloloco-haiku-subagent]: `Ran an agent ⌄` then an inner box with `Ran agent Haiku 4.5 Explore MCP server codebase ⌄` and key/value lines `subagent_type: Explore`, `description: …`, `prompt: …`.

**History, not target.** April 2026 build: one line per *call*, bold verb + mono path chip + stats + chevron (`Edit  src/components/AlignmentGrid.tsx  +142 -38 ›`; Bash shows its description: `Lint ›`, `Build ›`) [seen S8]. Early-2026 light build: `› <natural-language description>` with the chevron **before** the text hanging in the left gutter (7 × 11 pt, 23 pt left of the text column), expanding to a tree with a 1 pt vertical line and `└` sub-rows (`└ Bash cd "…"`, `Edit <path> +6 -6`) [seen S9]. June 2026: single shell cards labelled `Used Bash` / `Used PowerShell` [stated S28 #65668].

**VS Code (Normal view) [seen S1, S4, S5]:** a row per call: rail dot (green `#73c991` when finished, grey otherwise) + **bold tool name** + argument in mono at regular weight: `● Read  localizationUtils.ts`, `● Write  (src/test/localization.ts)`, `● Bash  Commit staged changes + push to origin`. A result sub-row with an `L`/`└` glyph, smaller and grey: `└ Read 256 lines`. Long paths truncate in the middle [stated S13]. Focus view folds the turn to `1 tool call` / `3 tool calls · 1 failed` [stated S28 #92623]. Rows carry `Waiting…` / `Running…` while input streams (2.1.300) [stated S13].

**Sketch (Desktop, target):**

```
Read cart.spec.ts, checkout.ts ›
Reply text follows at the same margin …
Edited billing/charge.ts +31 -40 ›
Ran pnpm test ›
Failed to check businesses/branch/causegroup OData queries ›        (whole row red)
• Running npm test · 46/340 passed                                   (live step, coral dot)

Ran 2 commands ⌄                                                     (expanded group)
┌────────────────────────────────────────────────────────────────────┐
│ Updated prices and suffix logic ›                                  │
├────────────────────────────────────────────────────────────────────┤
│ Verified toggle with new prices ⌄                                  │
│   ┌──────────────────────────────────────────────────────────────┐ │
│   │ $ node -e "…"                                                │ │   command box
│   └──────────────────────────────────────────────────────────────┘ │
│   raw stdout line 1                                                │
│   raw stdout line 2                                                │
└────────────────────────────────────────────────────────────────────┘
```

**Sketch (VS Code):**

```
●  Read  localizationUtils.ts
│  └ Read 256 lines
●  Write  (src/test/localization.ts)
   ┌────────────────────────────────────────────┐
   │ 1 │ import { formatDate, … }     (green bg)│
   └────────────────────────────────────────────┘
```

### 3.5 Per-tool summaries and bodies

The host builds the summary from the tool input and its result; Desktop's sentence rows are not sent over the wire (section 4). Label rule per tool, then the body when expanded. Wire tool names and the planned `mcp__tandem__*` edit tools must map onto the same rows (hard rule 4).

| Tool | Summary, running → done (Desktop, target) | VS Code row | Body when expanded |
|------|--------------------------------------------|-------------|--------------------|
| **Read** | `Reading <file> ›` → `Read <file> ›`; several: `Read <a>, <b> ›` (names) or `Read N files ›` (count); folds with searches: `Read 38 files, searched for 6 patterns ›` [seen S7; S3 desktop-pop-out-01, desktop-auto-continue-01]. Image read: same row (`Read <file>`); result body = the image | `● Read  <file>` + `└ Read 256 lines` [seen S1] | Desktop verbose rendering of Read is unknown (only MCP and Edit rows were seen in verbose frames). **Proposal:** numbered lines from `tool_use_result.file.content` with `startLine`, mono, clamped to 12 lines with `Show all`; an image result as a thumbnail (3.6) |
| **Edit** (and `mcp__tandem__*` edits) | `Editing <file>` → `Edited <file> +A -B ›`; several in a group: `Edited N files +A -B ›` [seen S3 desktop-auto-continue-04, desktop-pop-out-07, S10]; a turn that touched files also gets the **`Edited N files` card** (3.5.3) [seen S3 desktop-pop-out-01; S30 note09] | `● Edit (path)` + inline diff box, added lines green; subtitle `Modified` / `Added N lines` / `Added N lines, removed M lines` [seen S1; stated S17] | Desktop: `Edited README.md +23 -16 ⌄` header (blue focus outline when focused), then **one card per hunk**: muted absolute-path header, line-number gutter, `-` rows on a dark-red row fill with a red `-` marker, `+` rows on dark-green with `+`, context rows plain; a tall hunk clamps (≈ 300 px in S10's scale ≈ 140 px at 1x) with a bottom fade and a centred `⌄` expander [seen S10]. Verbose variant: card header `src/app/checkout/ShippingStep.tsx` with right-aligned `Edit +6 -2`, body = unified diff lines `-`/`+` in red/green **text** (no row fill) [seen S3 desktop-browser-10, -11]. Stats from `structuredPatch` (section 4.5) |
| **Write** | `Creating <file>` → `Created <file> +N -0 ›` [seen S6]; April build `Write  <path>  +62 ›` [seen S8]; counts as "created a file" in mixed labels [seen S6] | `● Write  (path)` + green-highlighted new-file box [seen S1] | Same hunk card with only `+` rows; CLI: `Wrote 96 lines to <path>` [seen S3 cross-session-01] |
| **Bash** | one call: `Ran <command> ›` (mono) when the call has no `description`, else `Ran <description> ›` (`Ran View GitHub issue 2664 ›`) [seen S3 desktop-auto-continue-04 (`Ran pnpm test ›`), S29]; several: `Ran N commands ›` [seen S6]; read-only commands (`ls`, `cat`, `find`, `grep`) count as reads/searches in the group label [stated S13 2.1.89]; live: `• Running npm test · 46/340 passed` (the step row) [seen S3] | `● Bash  <description>` [seen S4, S5] | Desktop: sub-row per command (its description) → command box `$ <command>` with syntax colours (2.3), fill gray-830, then the raw stdout in muted mono, shown **in full** (hundreds of lines) with no "show more" [seen S6 coupling-toggle]; stderr is merged into stdout by the CLI and the exit code exists only as a first text line `Exit code 3` on failure (section 4.5), so there is nothing separate to colour. VS Code: a rounded box with grey uppercase gutter labels `IN` (command) and `OUT` (stdout), copy button, body clamped at 60 px with a fade, openable in an editor tab [seen S4, S5; stated S17]. Background (`run_in_background`): Desktop lists it in the Background tasks pane as `Bash  39m 43s` with a stop square [seen S4]; its transcript row form is unknown. **Decision for Tandem:** the Desktop form (command box + output), not IN/OUT; clamp output at 20 lines with `Show all N lines` (3.6) |
| **Grep** | folded into `searched for N patterns` / `searched code` [seen S3 desktop-auto-continue-01; S29]; lone call: `Searching for <pattern>` → `Searched for <pattern> ›` (CLI tense rule) [stated S13 2.1.20]; in 2.1.302 Grep is absent from the default tool set and the model runs Bash `grep -rn`, which should be labelled as a search [stated S34] | `● Grep <pattern>`; no screenshot | Community: `Found N files` / `Found N lines` + the list, collapsed [stated S20]. **Proposal:** the matched lines (`content` mode) or file list, mono, clamped |
| **Glob** | folded into `Found files` [seen S6, S29]; lone: `Finding files` → `Found N files ›` (proposal); Glob is also absent by default in 2.1.302 (Bash `find` instead) [stated S34] | same as Grep | `filenames[]` as a list [stated S34 `tools-read:50`] |
| **WebSearch** | unknown on Desktop. **Proposal:** `Searching the web for "<query>"` → `Searched the web for "<query>" ›`; prompts for permission; preceded by a `ToolSearch` round-trip (hide it) [stated S34 `misc-web`] | unknown | `results[].content[]` as `title` links + the summary Markdown [stated S34 `misc-web:78`] |
| **WebFetch** | unknown. **Proposal:** `Fetching <host>` → `Fetched <host> ›`; prompts (`description` = the URL) [stated S34 `misc-web:31`] | unknown | `result` Markdown + `code codeText` [stated S34 `misc-web:34`] |
| **Agent** (wire `Agent`; `init.tools` lists `Task`) | `Ran an agent ⌄` [seen S29]; **proposal** while running `Running an agent`, with the CLI's `<agent-name>(<task>)` as the inner label; Desktop runs subagents in the background and shows `N running tasks` as a blue link in the spark row [seen S4] and cards in the Tasks pane (`Running Bash · 2m 8s · 170.0k tokens · 53 tool uses`, `Agent` pill, `×`) [seen S12]; VS Code: `N agents` footer pill → agent map; live progress rows under the group in Focus view [stated S1, S13] | `● Task …` row; Focus view progress rows | Inner box `Ran agent <model> <description> ⌄` with `subagent_type:`, `description:`, `prompt:` lines [seen S29]; the subagent's own tool rows are not shown in the main transcript (its lines carry `parent_tool_use_id`, section 4.6); its final text is the Agent result (`tool_use_result.content[].text`) and `task_notification.summary`; **proposal:** nest the subagent's forwarded tool rows under the Agent row, collapsed, with a `Done (N tool uses · tokens · time)` sub-line from `AgentOutput` |
| **TaskCreate / TaskUpdate / TaskList / TaskGet** (todos; `TodoWrite` no longer exists in 2.1.302) | Desktop: a separate **Tasks** pane (`No tasks.`) [seen S7]; Oct 2026 adds a live to-do list "that follows Claude's plan", a recap card and a `3 suggested tasks  3 started` chip card [stated S33]; VS Code: the latest to-do list stays visible in Focus view [stated S1]; CLI: Ctrl+T list under the spinner, up to 5 items, pending / in-progress / completed marks, collapsed to the `activeForm` as spinner text + `└ Next: <task>` [stated S14]. No GUI screenshot of the inline list exists. The tools exist only with `CLAUDE_CODE_ENABLE_TODO_TOOLS=1` and are loaded via `ToolSearch` [stated S34 `agent-todos`] | — | **Proposal:** one inline checklist card per turn built from TaskCreate/TaskUpdate results (`subject`, `status`, `activeForm` while in progress); newest expanded, older ones collapse to `N tasks · k done ›`; hide the `ToolSearch` call that precedes them |
| **AskUserQuestion** | VS Code: a `● AskUserQuestion` row, then the card docked at the bottom [seen S5]; after answering a `Questions` row lists each question with the picks [stated S13 2.1.286]; Desktop: the card opens expanded over the composer with a collapse chevron (`View question options`) [stated S16 #99338] | same | 3.5.1 |
| **ExitPlanMode** | plan approval card (3.5.2) | same | plan Markdown |
| **EnterPlanMode** | no row (mode change; the status line changes) | — | — |
| **MCP tools** (`mcp__<server>__<tool>`) | Desktop verbose: one row per call, `mcp__browser__click  Select Express shipping` = tool id in muted mono + the call's description in primary text, no box, no chevron, ellipsised at the column [seen S3 desktop-browser-10, -11; S11]; Normal view folds them into `Used N tools ›` or `Using plugin <plugin> <server>: <tool> ›` [seen S4]; CLI collapses repeats to `Called slack 3 times` [stated S14]; permission prompts and the sidebar name the tool as server + readable name, not `mcp__server__tool` [stated S2]; `mcp__tandem__*` edit tools render as **Edit/Write rows** (above), never as MCP rows | `mcp__server__tool` row | request JSON and result, collapsed; `tool_use_result {content, structuredContent?}` |
| **ToolSearch** | hidden: it is the CLI loading a deferred tool (`select:WebFetch`), one round-trip before the first use of WebFetch/WebSearch/Task*/ExitPlanMode [stated S34 `misc-web:15/17`, `agent-todos:16/18`, `plan-disallowed`]; slopus/happy also hides it [stated S20] | — | — |
| **Skill, LSP, NotebookEdit, Monitor, Workflow, TaskStop, PowerShell** | no Desktop row rule found. **Rule (proposal):** count them in the mixed label as `used N tools` (that is what Desktop does for anything outside read/search/edit/run, e.g. `used 4 tools` [seen S6]); a lone call renders `Used <display name> ›` where the display name splits CamelCase (`Notebook Edit`) and the body is the first string input (`command`, `file_path`, `path`, `pattern`, `query`, `url`, `prompt`, `description`, `name`, `skill`) [stated S20 for the field order]; PowerShell = Bash rules | tool name bold + first input | `INPUT` / `OUTPUT` sections with pretty JSON (community) [stated S20] |
| **Unknown tool** | `Used N tools ›` [seen S4]; lone: `Used <name> ›` | same | same |

#### 3.5.1 AskUserQuestion card

**Appearance (VS Code, [seen S5]):** a dark rounded card (radius ≈ 8–12 px, padding ≈ 16 px) docked at the bottom of the transcript in the composer's place. Top row: the question's `header` as a tab label underlined in blue (`Render test`) and a close `×` at the right; several questions = several tabs. Body: question text in semibold; options stacked, each a full-width row with a **bold label** line and a muted **description** line; the highlighted/selected row has a lighter background; an `Other` row with a free-text field (Shift+Enter for new lines) [stated S13]; a full-width button `1 Submit answers` (outlined until an answer is chosen, then filled blue `#0078d4`; the `1` is the number key) and a footer `Esc to cancel`. Multi-select questions take several picks [stated S15]. Option `preview` (markdown/HTML mockup) renders beside or under the options in newer builds [stated S13 2.1.286]. A fold button lets the transcript behind be read [stated S13]. VS Code no longer dims the conversation behind it (2.1.47) [stated S28 #26078].

**Desktop [stated S16 #99338; S28 #64776 via search only]:** the same card "always opens expanded over the composer" and "takes over the bottom of the window"; a chevron collapses it to a title row whose accessible label is `View question options`; the same card style carries a `Next` header with 2–3 suggested next prompts. Optional auto-continue with a 20 s countdown when `askUserQuestionTimeout` is set; otherwise the question stays open until answered [stated S15]. **No Desktop screenshot exists**; tab style, checkbox look, `Other` field, button style and the post-answer `Questions` row are unknown for Desktop.

**Sketch (target: VS Code's structure, Desktop's button style):**

```
┌────────────────────────────────────────────────────────────────┐
│ Format      Sections                                         ⌄ │   header chips as tabs; ⌄ collapses
│ ‾‾‾‾‾‾                                                         │
│ How should I format the output?                                │
│ ◉ Summary                                                      │   RadioButtonRow (single) / CheckboxRow (multi)
│   Brief overview                                               │   description in text.info
│ ○ Detailed                                                     │
│   Full explanation                                             │
│ ○ Other                                                        │
│   [ free text …                                              ] │
│                                   [ Esc  Cancel ] [ Submit ↩ ] │   Submit = DefaultButton (primary)
└────────────────────────────────────────────────────────────────┘
```

**Keyboard:** number keys pick options (VS Code), Enter submits, Esc cancels = deny [seen S5; stated S1]. Docked above the composer; the composer stays visible but disabled (proposal, following 2.1 for pending cards).

**What is sent back:** section 4.8. Short form: allow the `can_use_tool` request with `updatedInput = { questions: <original array unchanged>, answers: { "<question text>": "<label>" } }`; multi-select labels joined with `", "` (what the CLI's own TUI sends; an array is also accepted); free text = the typed text itself, not `Other`; or `updatedInput.response` for a free-form reply [stated S34 `ask-*`]. Afterwards render a collapsed `Questions ›` row (`<header>: <answer>` per line when expanded).

#### 3.5.2 ExitPlanMode (plan approval)

No GUI screenshot exists in any host; strings are [stated S14, S13, S1], the wire shape is recorded [stated S34].

- VS Code opens the plan as a full Markdown document in an editor tab (title = the plan's heading) where the user adds inline comments; the chat shows a **plan review card** with the comment list (scrollable), a reason field and a `Send feedback` button [stated S1, S13 2.1.274–2.1.275].
- Options, exact: `Yes, and use auto mode` (first when auto mode is available; `Yes, auto-accept edits` when it is not; `Yes, and switch to BYPASS PERMISSIONS (no further prompts) for this session` when started with bypass), `Yes, manually approve edits`, `No, keep planning`; `showClearContextOnPlanAccept` adds a first option that approves and clears context [stated S14, S13].
- Approving exits plan mode into the mode the option names; `No, keep planning` with feedback sends the feedback, Claude revises and stays in plan mode (recorded: the deny carries the feedback as `message`, no status line follows, the model rewrites the plan file and asks again) [stated S34 `plan-deny:81-143`].
- Desktop: the approval UI covers the composer while it waits [stated S28 #95473]; a `Plan` pane shows the plan with empty state `No plan yet. Claude writes the plan here as it explores. Keep chatting.` [seen S7].
- Keyboard: Shift+Tab jumps to the auto-accept option; Ctrl+G opens the plan in an editor (CLI) [stated S14].

**Proposal for Tandem:** a card in the permission-card style, title `Approve this plan?`, body = the plan Markdown from `input.plan` (clamped at ~240 dp with a fade and `Open plan` which opens `input.planFilePath` in the editor), buttons `No, keep planning` (outlined, with a feedback field that becomes the deny message), `Yes, manually approve edits` (outlined), `Yes, auto-accept edits` (primary, Enter); wire shape in 4.9. Because Tandem disallows `Write`, the plan file is not written and `input` arrives empty (4.9): either allow `Write` for `~/.claude/plans/` or render the plan from the preceding assistant text; this is a Phase 2a spec decision.

#### 3.5.3 `Edited N files` card

**Appearance [seen S3 desktop-pop-out-01, -07; S30 note09-todo-result].** After the reply of a turn that changed files: a full-column card, 1 px border, no fill, radius ~6–8 px. Header row (38 px): `Edited 3 files` / `Edited 7 files` in primary text at the left; at the right a `Revert` link/button and a `Show changes` / `View changes` secondary button (81 × 24 px; tooltip `Show this turn's changes`). A 1 px divider. One row per file (27 px): a `</>` file-type icon (globe for `.html`, `</>` for code/json) at +12, the file name at +26 in primary text, right-aligned `+12` green `-4` red (zero side shown: `+17 -0`) and a `›` chevron 12 px from the border that opens the diff pane for that file. More than five files: a footer row `Show 2 more ›` [seen S30]. Single-file turns in the Sept 2026 Windows build use a one-row **file card** instead: `⊕ pricing.html   +146 -0 ›` (36 px, icon, name, optional `Undo` link in muted text, stats, chevron) [seen S6 assembling, exporting-launch-json]. Clicking opens the diff viewer (file list left, per-file diff right, line comments with Enter, `Cmd+Enter` submits all) [stated S2].

**Sketch:**

```
┌──────────────────────────────────────────────────────────────────┐
│ Edited 7 files                             Revert  [Show changes] │
├──────────────────────────────────────────────────────────────────┤
│ </> TodoItem.cs                                        +17 -0  › │
│ </> TodoDbContext.cs                                   +14 -0  › │
│ </> Program.cs                                         +11 -0  › │
│ </> appsettings.json                                    +3 -0  › │
│ </> Index.cshtml.cs                                    +75 -0  › │
│ Show 2 more  ›                                                   │
└──────────────────────────────────────────────────────────────────┘
```

**Proposal for Tandem:** the same card from the turn's Edit/Write/`mcp__tandem__*` results, `›` opens the IDE diff viewer for that file (`DiffManager`), `Show changes` opens a multi-file diff, `Revert` only if Tandem keeps pre-edit snapshots (Phase 3).
### 3.6 Tool results

- **Collapsed by default** in Desktop: the summary row *is* the result; the chevron opens it [seen S6]. VS Code shows a one-line result sub-row (`└ Read 256 lines`) and, for Bash/Write/Edit, a body clamped to ≈ 60 px with a fade mask; click to expand (0.25 s max-height animation) [seen S1; stated S17].
- **Expanded bodies** (Desktop): Bash = command box then raw output in full [seen S6]; Edit = hunk cards clamped at ≈ 140 px with a fade and a centred `⌄` expander [seen S10]; MCP = request/response (not captured); Read/Grep/Glob = unknown (3.5). The app therefore has **no single clamp rule**: shell output is unclamped, diffs are clamped. **Decision for Tandem (proposal):** every expanded body clamps at 20 lines (or 240 dp) with a 3-line bottom fade and a `Show all N lines` link inline at the fade; `Open in editor` for anything over 200 lines. One rule is easier to test and matches the Edit card's look.
- **Error:** Desktop paints the whole summary row red: `Failed to check … ›` [seen S4]; what the expanded red row contains is not captured. **Proposal:** the body shows the error text (the `tool_result.content` string, or the `Exit code N` first line plus the output for Bash) in mono on the same box, with `text.error` for the first line only. VS Code/community: a red-bordered row labelled `Error` with a one-line preview, expandable [stated S20]. Recorded error shapes: `is_error:true` with `tool_use_result` = the string `"Error: " + content` (`tools-bash:39`, `tools-read-unavailable:49`) [stated S34].
- **Denied:** the result is `The user denied this command.` / the deny `message` with `is_error:true` and `tool_result_meta[].permission_decision {decision:"reject", source:"user_reject"}` plus `non_execution_kind:"permission-rule"` (`ask-deny:24`, `plan-deny:81`) [stated S34]; no Desktop capture shows a denied row. Community hosts show an orange `Denied` badge by matching `user denied tool use`, `tool disallowed by settings`, `permission request timed out`, `permission request cancelled` [stated S20]; auto-mode skips show a dim `Not run` row [stated S13]. **Proposal:** key denial on `tool_result_meta` (not on text), render the row in the muted colour with a trailing `Denied` word (`Edit cart.spec.ts · Denied ›`), not red; `Not run` likewise.
- **Images:** a Read of an image returns an image block in `tool_result.content` and `tool_use_result {type:"image", file:{base64, type, originalSize, dimensions:{originalWidth, originalHeight, displayWidth, displayHeight}}}` (`misc-image:26`) [stated S34]. No GUI screenshot of the rendered thumbnail. **Proposal:** render inline as a thumbnail ≤ 480 dp wide with a 1 dp border, like the user-attachment thumbnail [seen S6], click opens the image in the IDE; PDFs and notebooks as a one-line `Read <file> (N pages)` row only.
- Hidden: `<system-reminder>` text inside Read results is stripped by every host [stated S20]; `(Bash completed with no output)` collapses to nothing; the `ToolSearch` round-trip is hidden (3.5).

### 3.7 Grouping of consecutive tool calls

What Desktop does, from its own type declarations and the captures [stated S26; seen S3, S4, S6, S29]:

1. A **group** is a run of consecutive tool calls folded into one count line; the public type says "reads, searches, listings" and gives the example `Read 3 files, ran 2 shell commands` [stated S26]. The captures show every family folding: reads, searches, edits, creates, runs and "other" tools [seen S6 `Found files, read 2 files, created a file, edited a file, used 4 tools +73 -1 ›`].
2. **Grammar** (from the examples; no rule text exists): clauses in the order the families first occur; comma-separated; no `and`; the first clause capitalised, the rest lowercase; count + plural noun (`read 2 files`, `ran 2 commands`, `searched for 6 patterns`) or `a <noun>` for one (`ran a command`, `edited a file`, `created a file`, `used a tool`); names instead of counts when few: `Read cart.spec.ts, checkout.ts ›`, `created launch.json`; anything outside read/search/edit/create/run is `used N tools`; group diff stats `+A -B` after the sentence, summed over the group's edits [seen S3, S4, S6, S29]. **Threshold for names vs counts is unknown**; best guess: names while the row fits one line with ≤ 2 names, else counts (open question 2).
3. The group row is **live**: `isActive` is true while a call may still join it, and the CLI engine shows present tense while running (`Reading 3 files`, `Searching for …`) and past tense when done, with the current file or pattern on a line beneath the summary while active [stated S26; S13 2.1.20]. On Desktop the live step is shown as the spinner row (`• Running npm test · 46/340 passed`) [seen S3]; the group label re-renders as calls join [inferred].
4. A **text block closes a group** [seen S8: an interstitial sentence sits between groups] — except that in some builds the model's follow-up text got folded into the `Ran N commands ›` row, which was filed as a bug and fixed (2.1.300 "Fixed cloud sessions sometimes showing the model's follow-up text after a tool group's one-line label") [stated S28 #99327; S13]. `desktop-browser-03`'s `• Opening in Browser` under a sentence is the live step row, not a folded row.
5. **Edits**: one edit = `Edited <file> +a -b ›`; several = `Edited N files +A -B ›`; plus the `Edited N files` card at the end of the turn when files changed (3.5.3) [seen S3, S10, S30]. Thresholds unknown; best guess per-file row for one, count for ≥ 2.
6. **Runs**: one = `Ran <command>` or `Ran <description>`; several = `Ran N commands ›` [seen S3, S6, S29]; the CLI says `Ran 1 shell command` [stated S14]. Todo/task updates between commands do not split the group (CLI 2.1.234) [stated S13]; no Desktop evidence either way (proposal: same).
7. **Failures** are not merged silently: the failing call becomes its own red row (`Failed to …`) [seen S4]. Whether the rest of the group stays one row or splits in two is not captured; proposal: the red row sits where the call happened and the group continues after it (`Read 2 files ›` / `Failed to … ›` / `Ran a command ›`).
8. **Agent**: `Ran an agent ⌄` is its own row with its own expansion [seen S29]; community precedent lets an agent row absorb following calls [stated S20]; proposal: do not absorb, the subagent's calls are nested inside it (4.6).
9. Expanding a group shows the bordered box with one sub-row per call (3.4) [seen S6]; Verbose view renders every call as its own row (`isExpanded`) [stated S26, S2].
10. VS Code's Normal view does **not** group (each call its own row), which users file as noise (#55888, #71467); its Focus view folds the turn's activity into one row [stated S1, S16].

**Proposal for Tandem:** implement 1–9 deterministically from tool names, inputs and results (names, counts, `+a -b` from `structuredPatch`); a "Verbose" toggle renders one row per call.

### 3.8 Working indicator

**Where.** The last row of the transcript, under the latest content, left-aligned at the text margin [seen S3 desktop-auto-continue-04, desktop-browser-10, desktop-pop-out-07; S1, S6]. The early-2026 light build put elapsed/tokens right-aligned on the same row [seen S9]; history.

**Form on the target build (Oct 2026, dark and light) [seen S3 derived/desktop-auto-continue-spark-10fps; desktop-auto-continue-01, -03, -04; desktop-pop-out-07; desktop-browser-10, -11]:** the coral **spark** (a many-spoked starburst, ≈ 16–20 px, `#d97757`) followed by the **step word** as a present participle with `…`, then ` · ` and the **elapsed time** in muted text: `✻ Migrating… · 1s`, `✻ Testing… · 3s`, `✻ Resuming… · 0s`, `✻ Migrating… · 43m 11s`, `✻ Writing summary…` (no time yet), `✻ Filling form`, `✻ Applying edit`. **No token count** on this build. The elapsed counter ticks every second (`· 1s` → `· 2s` → `· 3s`) [seen derived/…spark-10fps]. The word is **step-specific, not a random verb**: it changed from `Migrating…` to `Testing…` the moment the task resumed and reads as the current step everywhere (`Filling form`, `Applying edit`, `Writing summary…`) [seen S3]; the public Spinner contract on Desktop says the word is "what the row says its step is doing (`Creating notes.md`), or `Working` while the row shows no words", `mode` is `requesting` for `Working` and `tool-use` for step words, and the row "returns to `Working` between steps" [stated S28 #99483; S26]. Live progress replaces the verb: `• Running npm test · 46/340 passed` (a plain coral dot instead of the spark on that row) [seen S3 desktop-pop-out-03, -07].

**Animation [seen derived/desktop-auto-continue-spark-10fps].** The spark is **animated continuously**: in 20 consecutive 100 ms frames the glyph differs from the previous one every time (RMS 11–15 on a 32 × 32 px crop) while the text is pixel-identical except when the counter ticks; the shape stays a many-spoked starburst (it rotates/morphs), it does **not** cycle through the CLI's six glyphs `· ✢ ✳ ✶ ✻ ✽` and it is **not** a static `✻`. The exact rotation rate and easing cannot be read from 10 fps. The six-glyph 120 ms cycle and the text shimmer belong to the CLI and the VS Code webview [stated S18, S24]. Reduced motion stops the animation and screen readers get a text label [stated S1, S13]. A newer Desktop build morphs the spark into a dot and then a rotating node graph; users dislike it (#99139) [stated S16]; do not copy that.

**Other builds (history):**

| Build | Row | Confidence |
|-------|-----|------------|
| Desktop Sept 2026 Windows | small coral dot (6 px) + `13s · 109 tokens · Running tools...` in muted 12–13 px text; after the turn, a lone static `✻` (18 px) under the hover-icon row | [seen S6 assembling, branding-pro-card] |
| Desktop June 2026 | `✻ 6m 25s · 35.4k tokens · 2 running tasks` (last item a blue link) | [seen S4] |
| Desktop April 2026 | `✻ 7s · ↓ 201 tokens`, `✻ 25s · ↓ 35.7k tokens`, `✻ 3m 22s · ↓160 tokens` (stays as the turn's footer) | [seen S7, S8] |
| Desktop early 2026 light | `· Clauding...` / `✱ Forging...` coral at left, `11m 16s · ↓ 22.6k tokens` muted at right | [seen S9] |
| VS Code | `✻ Pondering...` (random verb from the CLI list; the glyph cycles `·✢*✶✻✽` at 120 ms, colour `#d97757`, `#c6613f` in light themes) | [seen S1; stated S18, S24] |
| CLI | `✻ Deciphering… (10m 27s · ↓ 5.5k tokens)`; with todos `(esc to interrupt · ctrl+t to show todos · 8s · ↓ 215 tokens)` + `└ Next: <task>` | [stated S14] |

**Target string (Tandem):** `✻ <Step>… · <elapsed>` exactly as the Oct 2026 build, where `<Step>` = the current activity when known, else `Working`. Elapsed: `Ns` under a minute, `Mm Ss` above (`3s`, `43m 11s`) [seen]. **Proposal:** append ` · <n> tokens` (`201 tokens`, `35.4k tokens`: one decimal and `k` ≥ 1000, `1.5m` ≥ 1M) once the first token arrives, as the Sept 2026 and June 2026 builds did; it is cheap (section 4.1) and the owner asked for it; drop it if the `runIde` check finds it noisy. When live progress exists (`Running npm test · 46/340 passed`) it replaces `<Step>… · <elapsed>` on the row with a plain dot.

**Where the step word comes from (Tandem):** the `description` of the running tool call (`Creating empty fixture file` → `Creating empty fixture file…`), `task_progress.description` for subagents, the CLI's `system/task_summary.detail` when present (older CLIs only; none in 29 sessions on 2.1.302, section 4.1), else `Working`; between a prompt and the first frame `Working` (Desktop) or a random verb from the CLI list (VS Code). The random-verb list (`spinnerVerbs`, ~190 words: Accomplishing, Architecting, Baking, …, Pondering, …, Zigzagging [stated S18, S1]) is optional flavour for the no-step state; its rotation interval is unknown (old reverse-engineering: random every 1000 ms) [stated S18]; proposal: pick one verb per turn, do not rotate.

**Colour states.** Coral normally; CLI warms to amber after 10 s of thinking and turns red when an auto-mode check stalls; VS Code turns red with `Not responding` after 60 s without a backend response [stated S13, S18]; neither seen on Desktop. Long-thinking phases (CLI, inline): `thinking` → `still thinking` → `thinking more` → `almost done thinking`; `deep in thought` after 45 s [stated S13].

**When it shows and hides.** Shows from the moment the prompt is sent (before any token; `Working`) until the turn's `result`; it is the last row and is pushed down by streaming content; it stays during a permission wait (VS Code) [inferred S1]. On completion the Oct 2026 Desktop replaces it with the hover-icon row + relative time and a lone static `✻` under the reply [seen S6, S30]; the April 2026 build kept `✻ 7s · ↓ 201 tokens` as the footer [seen S7]; the CLI prints `Worked for 5s` [stated S13]. On interrupt the CLI shows an `Interrupted` row with the hint `What should Claude do instead?` [stated S13].

**Sketch:**

```
Ran pnpm test ›
All 214 call sites are on the v3 client, the shim is deleted,     (streaming, newest chunk faded)

✻  Testing…  ·  3s                                                 (spark animates; Tandem adds · 1.2k tokens)
•  Running npm test · 46/340 passed                                (live progress variant)
```

**Compose (proposal):** draw the spark as a `Painter` of the docs spark SVG (`assets/s1-vscode-docs/vs-code-spark-icon.svg`, 24 × 24 viewBox) rotated by `rememberInfiniteTransition().animateFloat(0f, 360f, tween(2000, easing = LinearEasing))`, 16 dp, tinted `#d97757`; stop the rotation under reduced motion (`UISettings.animateWindows` is the IDE's nearest flag) and expose the row's text as the accessible label; elapsed from a host `withFrameMillis` loop. Jewel's `CircularProgressIndicator` is the fallback if the custom painter looks wrong in `runIde`.

### 3.9 Status and notice lines

Inline text rows in the transcript, muted colour, no card unless noted [stated S13, S14; seen S3 desktop-auto-continue-02, -03; S9]:

- Errors: `API Error: 500`, `529 Overloaded`, `Prompt is too long`, `Not logged in · Please run /login`, `API Error: No response from API` [stated S14]; VS Code shows them as a rail row (`● API Error: 400 output_config.effort xhigh is not supported when thinking is disabled…`) [stated S28 #49739].
- Retries, under the running row, one line per attempt: `529 Overloaded · Retrying in 20s · attempt 10/10`; older `API Error (529 {…}) · Retrying in 1 seconds… (attempt 1/10)`; since 2.1.198 the reason shows only from the second attempt and a status-page hint replaces the spinner tip while overloaded; a stalled stream after 20 s: `Waiting for API response · will retry in … · check your network` [stated S13, S14]. Desktop placement not captured; proposal: a muted line directly under the working row.
- Mid-response failure keeps the partial text and adds `Server error mid-response. The response above may be incomplete.` (variants `Connection lost mid-response`, `Response stalled mid-stream`) [stated S14].
- Model switch: a centred muted line with wavy rules, `〰 Switched to claude-opus-4-6[1m] 〰` (VS Code) [seen S9]; model fallback shows a notice [stated S1]; Desktop form unknown.
- Flagged message (Desktop): a bordered card `Message flagged` with a warning icon, `Learn more` link, `Details: [reasoning_extraction]` in mono, `View details ›` and a white `Rewind` button, preceded by a collapsed `Claude Code notice ›` row [seen S28 102604-desktop-flagged-card].
- Usage limit: a bordered card (left-aligned, ~two thirds of the column) `Usage limit reached` / `Auto-resuming at 3:40 PM` with `View details`, a checked `Auto-continue when limits reset`, right-aligned `Try again`; once resolved it stays in the transcript dimmed [seen S3 desktop-auto-continue-02, -03, -04]; CLI two-liner `Usage limit reached · continuing automatically at 3:45pm · esc to cancel` [stated S14]; VS Code banner with `Get more usage` [stated S13].
- Compaction: collapsible `Compacted chat` card with the summary inside (VS Code) [stated S13]; Desktop form unknown; community: 6 px dot + `Compacted` + `full summary` details [stated S20].
- Interrupt: `Interrupted` + `What should Claude do instead?` (CLI) [stated S13]; Desktop form unknown.
- Auto-mode skip: dim `Not run` row [stated S13]. Hook block reasons appear as text rows [stated S14].

**Proposal for Tandem:** transcript rows (not balloons); Jewel `InlineWarningBanner` / `InlineErrorBanner` (both exist on 2026.1.5 [stated S31]) only for the usage-limit card, the flagged-message card and a mid-response failure; plain `text.info` lines for retries, model notices, compaction and interrupts.

### 3.10 Permission prompt

**Desktop, dark, mid 2026 (the newest Desktop capture) [seen S10 claude-code-desktop-diff-approval].** A card docked above the branch bar and composer, full column width, fill one step lighter than the page (`#262626` on `#1f1f1f`), 1 px lighter edge, radius ~10 px, 16 px padding; **the transcript above is not dimmed**. Contents, top to bottom:

1. Title row: a small yellow dot, then bold `Allow Claude to edit README.md?` (verb lowercase in this build; the early-2026 light build bolded `Edit`/`Run` as `Allow Claude to **Edit** launch.json?` / `Allow Claude to **Run** <full command>?` [seen S9]); right-aligned a small yellow-on-dark pill `session`.
2. The file name in muted text (`README.md`), or for Bash the tool's `description` in muted text (`Build and deploy to GitHub Pages`) [seen S9].
3. The payload: a mono box with the absolute path (edits; the diff itself is the expanded `Edited README.md +23 -16 ⌄` rows **above** the card, not inside it) [seen S10]; for Bash a mono command box with horizontal scroll [seen S9]. The early-2026 light build put the diff box inside the card [seen S9].
4. Buttons, 33 px tall (≈ 1.2 lines), radius 8 px: `Deny` outlined at the **left**; `Always allow` outlined and `Allow once ⌘↩` filled (white on dark / black on light) right-aligned, 8 px apart. The key cap is drawn as small muted text after the label.

**Key caps differ between the two Desktop captures:** the mid-2026 dark build shows `⌘↩` on `Allow once` and nothing on `Always allow` [seen S10]; the early-2026 light build showed `Deny [Esc]`, `Always allow for session [⌘ ⏎]`, `Allow once [⏎]` [seen S9]. The newer capture wins for the label (`Always allow`, no `for session`), but it leaves Enter unexplained. **Decision for Tandem (proposal):** Enter = `Allow once` (the primary; every host confirms the focused default with Enter), Cmd/Ctrl+Enter = `Always allow` (session scope), Esc = `Deny`; draw the caps `↩`, `⌘↩` / `Ctrl↩`, `Esc` after the labels.

Docs name the choices `Allow once`, `Always allow`, `Deny`; computer-use shows `Allow for this session` / `Deny` [stated S2]. A denied edit makes Claude ask how to proceed [stated S2]. Titles for tools other than Edit/Bash are not captured on Desktop; the sidebar and prompts name MCP tools by server + readable name [stated S2]. **Proposal:** `Allow Claude to run <command>?` (Bash, mono command, `description` beneath), `Allow Claude to edit <file>?` / `create <file>?`, `Allow Claude to fetch <host>?` (WebFetch), `Allow Claude to search the web?` (WebSearch), `Allow Claude to read <path>?` (Read outside the project), `Allow Claude to use <server>: <tool>?` (MCP, with the readable name), `Allow Claude to run an agent?` (Agent).

**VS Code [seen S1 vs-code-edits (lossless)].** Card = composer width, docked in the composer's place, panel fill with a 1 px `focusBorder` blue outline, radius ≈ 8 px, padding ≈ 12 px. Title bold `Make this edit to utils.py?` (file name mono). Full-width stacked option rows ≈ 40 px tall, 8 px apart, radius 6 px, each prefixed with its number: `1 Yes` (filled `#0078d4`, white text = focused/default), `2 Yes, and don't ask again`, `3 No` (fill `#313131`, 1 px `#464646`), then a text field `Tell Claude what to do instead`. The proposed edit opens in an editor diff tab `✻ [Claude Code] utils.py` with per-hunk `Accept this change` / `Reject this change` (≤ 100 changes) [stated S13]. Newer labels are descriptive (`Yes, allow npm for this project`) with a save-destination selector (`this project` / `all projects` / `shared with team` / `session only`; Left/Right arrows change it); Bash prompts add `Yes, and switch to auto mode` when auto mode is available; a fold button collapses the card [stated S1, S13]. The 2025 build dimmed everything above the card to 40 % opacity [seen S1]; removed in 2.1.47 [stated S13].

**CLI (strings the GUIs mirror) [seen S14 screenshot]:** `Bash command`, description, dashed rules around the command, `This command requires approval`, `Do you want to proceed?`, `1. Yes`, `2. Yes, and don't ask again for: npm test *`, `3. Yes, and switch to auto mode · auto mode handles these prompts for you`, `4. No`, footer `Esc to cancel · Tab to amend`. WebFetch: `Yes`, `Yes, and don't ask again for <domain>`, `No, and tell Claude what to do differently`.

**Keyboard:** digits pick, Enter confirms the focused option, Esc denies, Tab opens a comment field (VS Code/CLI) [stated S1, S14]; Desktop caps as decided above.

**"Always allow" scope.** The CLI offers an `addRules` suggestion with `destination:"localSettings"` (writes `.claude/settings.local.json`) and the Desktop pill says `session`; the Desktop's actual destination is not stated. **Decision for Tandem:** `Always allow` = echo only the `addRules` suggestion with destination `session` (never `setMode`, never `addDirectories`; see 4.7), so nothing is written to disk without a settings page.

**Auto-accept.** Permission mode `acceptEdits` auto-approves Edit/Write and safe file commands inside the working directory (`reason_type:"mode"`), read-only Bash sub-commands auto-approve with `reason_type:"subcommandResults"` [stated S34]; `auto` and `bypassPermissions` approve more; the mode is picked in the composer footer, not in the card [stated S14, S2].

**Sketch (Desktop target, dark):**

```
┌──────────────────────────────────────────────────────────────────────┐
│ ●  Allow Claude to edit launch.json?                       [session] │
│ launch.json                                                          │
│ ┌──────────────────────────────────────────────────────────────────┐ │
│ │ /Users/me/code/app/.claude/launch.json                           │ │   mono
│ └──────────────────────────────────────────────────────────────────┘ │
│ [ Deny  Esc ]                       [ Always allow  ⌘↩ ] [ Allow once  ↩ ] │
└──────────────────────────────────────────────────────────────────────┘
   (the diff is shown as the expanded `Edited launch.json +6 -6 ⌄` rows above the card)
```

### 3.11 Composer

**Desktop [seen S3 desktop-pop-out-01, desktop-auto-continue-02, desktop-browser-10; S6; S10; S7; S11].** A single rounded text field spanning the column: radius 8 px, 1 px border, **40–41 px tall when empty** at 1x (Sept/Oct 2026 dark; the light Aug 2026 video shows ≈ 45; the mid-2026 dark build 62 px at its unknown scale), 12 px inner padding, grows with text. Placeholder `Type / for commands` inside a session (`Describe a task or ask a question` on the new-session screen; `Reply...` in the early-2026 light build). At the right end inside the field a 14 px glyph: `↵` when idle (even with an empty box in Oct 2026 [seen S3 desktop-pop-out-01, desktop-auto-continue-04]); while a turn runs a stop glyph: a filled circle with a square `⦿` [seen S6 assembling], a plain square `□` [seen S10; S3 desktop-browser-10]; the Oct 2026 light frame still showed `↵` while running [seen S3 desktop-auto-continue-04], so the glyph is not consistent across builds. **Decision:** `↵` idle, `■` in a circle while running (Jewel `AllIconsKeys.Actions.Suspend` is the closest IDE icon). Suggested next prompt appears as muted ghost text; Tab or Right accepts it [stated S2]. Long pastes stay inline (3.1).

Footer row **below** the field, outside it, 12–13 px muted text, baseline 10 px under the box [seen S6; S3]:

- Left: `+` (attachments, skills, connectors, plugins, slash commands), mic icon with a `⌄`, then the **permission-mode label**: `Auto` (plain text, Oct 2026), `Accept edits`, `Manual`, `Plan`, `Bypass permissions`; mid-2026 builds show a yellow-outlined pill `Auto mode`; older builds `Ask permissions`, `Auto accept edits` with an icon [seen S3 desktop-pop-out-01 (`Auto`), desktop-pop-out-07 (`Accept edits`), desktop-browser-10 (`Auto mode` pill); S6, S10, S11, S8, S9; stated S2]. The light Aug 2026 video puts the mode label **before** `+` (`Auto  +  🎤 ⌄`) [seen S3 desktop-auto-continue-02]; the dark builds put it after (`+  🎤 ⌄  Accept edits`) [seen S6, S10].
- Right: model name (`Opus 5`, `Sonnet 5.5`, `Fable 5.1`, `Opus 4.7 1M · Extra high`), effort (`High`, `Max`, `Medium`), optional `Ultracode`, then a 12–14 px circular **usage ring** (blue arc `#2a78d6`/`#0982de` on a dark track) aligned with the box's inner right; hover tooltip `Context 20.1k / 200.0k (10%) · Plan 24%`; click opens the context/plan-usage popover (`Context window 120.3k / 1.0M (12%)` segmented bar, category rows, `Plan usage →` with `5-hour limit 68% · resets 2h`, `Weekly · all models 11% · resets 5d`) [seen S3; S4, S6, S7, S10, S12].

The docs' "model dropdown and permission-mode selector next to the send button" [stated S2] is loose wording for this footer; every capture shows the mode at the footer's left and the model at its right.

**Pickers.** Desktop: the mode label opens the mode menu (`Manual`, `Accept edits`, `Plan`, `Auto`, `Bypass permissions` when enabled; `⌘⇧M`), the model label opens the model menu (`⌘⇧I`), effort has its own menu (`⌘⇧E`), `1–9` pick an item [stated S2]; menus not captured. VS Code: click the model pill or `/model`: a flat list with prices, an `Effort` row (5-dot slider low/medium/high/xhigh/max), a `Thinking` toggle, `Switch models when a message is flagged`, `Account & usage…` [seen S28 49739-jun16]; `default` was renamed `Manual` everywhere; `Auto` is the starting mode since 2.1.283 [stated S14]. Desktop remembers the mode per folder; `Plan` applies to the current session only [stated S14].

**Queue list (Desktop, Oct 2026)** [stated S28 #102342, #101909]: when messages were queued for later, a collapsible row above the field reads `1 message queued` / `4 messages queued` with per-item removal and `Clear all`; see 3.1 for Enter vs Cmd+Enter semantics.

**VS Code [seen S1].** The footer is **inside** the box: left = mode pill with a pencil icon (`Ask before editing`; current labels `Auto` / `Manual` / `Plan` / `Edit automatically` / `Bypass permissions`), current-file chip `📎 localizationUtils.ts` or `2 lines sel…` with an eye-slash/× control; right = `+`, `/`, and the action button (26 × 26 px, radius 5): coral `#c6613f` circle with an ivory `↑` (send) that becomes a grey `#767676` square-in-a-box Stop while running. Placeholder `⌘ Esc to focus or unfocus Claude` (Mac) / `ctrl esc to focus or unfocus Claude` (Windows) when idle, `Queue another message...` while Claude works. Box: `menu.background`/`input.background` fill, 1 px `input.border`, radius 8, max-width 680 px, focus ring `#d97757` (3 px at 12 % + border) or white/blue/red by permission mode (2.3) [stated S24]. Model pill, context indicator, prompt-cache clock (red when expired), `N agents` pill also live in the footer [stated S1].

**Keys.** Enter sends; Shift+Enter newline; Esc stops the current turn (background agents keep running); Enter while busy steers / Cmd+Enter queues (3.1); `/` opens the command menu; `@file` mentions; drag-and-drop attachments; Option/Alt+K inserts `@file#5-10` (VS Code) [stated S2, S1, S28].

**Sketch (Desktop):**

```
 1 message queued                                                    Clear all     (only when queued)
┌────────────────────────────────────────────────────────────────────┐
│ Type / for commands                                              ↵ │   40 dp, radius 8
└────────────────────────────────────────────────────────────────────┘
 +   🎤 ⌄   Auto                                 Opus 5   High   ◯
```

**Proposal for Tandem:** Jewel `TextArea` with `placeholder`, `undecorated` inside our own rounded box, `heightIn(min = 40.dp)`; `↵`/stop as an `IconActionButton` inside the box; footer `DropdownLink`s (exist on 2026.1.5 [stated S31]) for mode and model · effort, a 12 dp ring drawn with `Canvas` from `rate_limit_event` and the last `message_start` input sum (4.11); keep the `Send` text button out.

### 3.12 Autoscroll

Documented rule (CLI fullscreen, which the GUIs follow) [stated S14, S13, S16]:

1. **Auto-follow** keeps the view at the bottom while output streams **only if the user is already at the bottom**.
2. Scrolling up pauses auto-follow; new output must not pull the view back. VS Code's 2.1.275 fix is titled exactly that.
3. A floating **`Jump to bottom`** affordance appears over the bottom edge while scrolled up and shows a count like `3 new messages` when output arrives; click it, press Ctrl+End or scroll to the bottom to resume (CLI). The shared desktop chat components show it as a white circular `↓` button (≈ 48 px, shadow) centred over the bottom edge [seen S32 Cowork tab, Jan 2026]; whether the Oct 2026 Code tab still shows it is unknown (open question 10); community hosts use a 32 px round button ≈ 44 px above the composer [stated S20].
4. **On send**, scroll to the bottom once (`scrollToBottomOnSend`, default true) and do not stop short when the reply starts arriving during the scroll [stated S13].
5. Pending cards do **not** force-scroll and must not make the transcript creep down while content keeps arriving (2.1.275) [stated S13]; users ask for an `input required` badge instead [stated S16 #72117].
6. When auto-follow is paused the view also stays put when the response finishes [stated S14].
7. The anti-pattern that was shipped and fixed: a 50 px "near bottom" test re-locked after a 10–40 px trackpad tick because scroll position alone was the signal (#58767) [stated S16]. Desktop's own algorithm is not public.

**Proposal for Tandem (Compose):** *following* is a boolean. It turns **off** on any user wheel-up or drag-up (intent, not position) and on `PageUp`/`Home`; it turns **on** when the user scrolls down **and** the list is at the bottom (last item fully visible, `!canScrollForward`), on `End`/`Ctrl+End`, on the `↓` button, and on send. A pixel threshold is used only for the "at bottom" test after a wheel-down (≤ 100 px), so it can never re-lock on a wheel-up. Programmatic scrolls (`scrollToItem`) set a flag for one frame so they are not read as intent. While following, re-pin on every content/size change (`LaunchedEffect` keyed on row count, tail length and composer height) with `scrollToItem(last, Int.MAX_VALUE)`; on send `animateScrollToItem(last)`. Show the `↓` button (Jewel `IconActionButton`, `AllIconsKeys.General.ArrowDown`, round surface, 1 dp border) centred 44 dp above the composer when `!following && canScrollForward`, with a `N new` badge counting rows appended while paused; hide it 1.5 s after the wheel stops when at bottom. Pending cards never scroll the list. The IDE scrollbar comes from `VerticallyScrollableContainer(listState)` [stated S31].

---
## 4. What the stream gives us, and what the host sends back

Evidence is `file:line` in `src/test/resources/fixtures/`; the 29 fixtures from the second round (`ask-*`, `plan-*`, `think-*`, `tools-*`, `agent-*`, `misc-*`, recorded against `claude` 2.1.302 on 2026-10-18) are described line by line in `2026-10-18-protocol-probes.md` [S34]; shapes without a fixture are marked so and come from the SDK reference [stated S15]. Hard rule 6: every shape the UI relies on needs a fixture test. Tandem's decoder today (`Ndjson.kt:41-54`) types only `system/init`, `status`, `api_retry`, `model_refusal_*`, the assistant/user/stream/result/control lines and `can_use_tool`; everything else is `CliMessage.Unknown` and `Reducer.kt:140` drops it.

### 4.1 Turn lifecycle (working indicator, footer)

- Host sends `{type:"user", message:{role:"user", content:[{type:"text", text}]}, parent_tool_use_id:null, uuid}` (`HostMessages.kt:200-214`); images as `{type:"image", source:{type:"base64", media_type, data}}` blocks in the same array (`docs/protocol.md` §3).
- CLI echoes the uuid: `command_lifecycle {command_uuid, state:"queued"}` then `"started"` (`permission.ndjson:5,7`; `tools-bash:3`), `system/init` (`:8`), `system/status {status:"requesting"}` before each API call (`:9`, again after each tool result `:26`), `stream_event message_start` with wrapper `ttft_ms` and `message.usage {input_tokens, cache_creation_input_tokens, cache_read_input_tokens}` whose sum is the request's context size (`misc-state-emit:9`: 31260 = `result.first_request_input_tokens`).
- **Steered prompts:** a prompt sent while busy is queued by the CLI and may be folded into the running turn; `result.user_message_uuids` lists which sends a turn consumed and the interrupt receipt lists `still_queued` (`interrupt.ndjson:13`); the UI shows the bubble at once and un-dims it when `command_lifecycle started` names its uuid (3.1).
- End: `result {duration_ms, duration_api_ms, ttft_ms, ttft_stream_ms, num_turns, usage{output_tokens, output_tokens_details:{thinking_tokens}, …}, modelUsage{<model>:{inputTokens, outputTokens, cacheReadInputTokens, costUSD, contextWindow, …}} (cumulative per process), total_cost_usd, terminal_reason:"completed", permission_denials:[], subagent_stats, result_index}` (`permission.ndjson:35`; `misc-image:66`), then `command_lifecycle "completed"` (`:36`). `system/task_summary {detail}` appeared only in the older `permission.ndjson:24,37` and in **none** of the 29 sessions on 2.1.302, so the step word cannot rely on it [S34].
- Per API request: `message_delta.usage {output_tokens (final), output_tokens_details:{thinking_tokens}}` (`permission.ndjson:22`; `misc-image:24,48,64`). **There is no server-side elapsed clock mid-turn**: the host runs its own timer from send to `result`, and the live token count = accumulated `message_delta.usage.output_tokens` per request plus `system/thinking_tokens` while a thinking block streams (the estimate overshoots: 186 estimated vs 62 final, `misc-image:33` vs `:48`).
- Step word sources: the running tool's `description` from its `tool_use` input; `task_progress.description` (`Running wc -l …`) for subagent steps (`agent-sync:41`); `tool_progress {tool_use_id, tool_name, elapsed_time_seconds, heartbeat:true}` is documented every 30 s but was never seen, even for a 6 s Bash (`agent-bash-task`) [S34]. A plain Bash also emits `task_started {task_type:"local_bash", description}` / `task_notification` (`agent-bash-task:21-22`), usable as the step's start/end.
- Session state: with `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` or `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` (the latter adds `sdk_host_only:true`) the CLI emits `system/session_state_changed {state:"running"|"requires_action"|"idle"}`: `running` before `system/init`, `requires_action` immediately before each `can_use_tool` and `running` right after the answer, `idle` after `result` as the turn's last line (`misc-state-emit:3,18`; `misc-state-action:20,24,37`); neither variable set → no such line (`misc-state-none`) [S34]. Tandem sets neither (`CliEnvironment`).
- Today: `decodeResult` (`Ndjson.kt:165`) keeps no durations or usage; `MessageDelta` is ignored (`Reducer.kt:260`); `command_lifecycle`, `thinking_tokens`, `task_*`, `session_state_changed`, `rate_limit_event` are Unknown.

### 4.2 Assistant text

Order for one block: `message_start` → `content_block_start {type:"text"}` → `text_delta` frames → complete `assistant` line (same `message.id`) → `content_block_stop` (after the assistant line) → `message_delta {stop_reason:"end_turn"}` → `message_stop` (`plain-reply.ndjson:7-13`). Text deltas carry no counts. Reducer builds a draft on `message_start`, appends deltas, replaces block N with the Nth complete line (`Reducer.kt:184-196, 224-258`); the UI re-parses the whole message per delta (`ChatRows.kt`, cached per entry id). A cut tool call gets no `content_block_stop`; treat its input as incomplete when `message_stop` arrives with the block open [stated S15]. The tail fade (3.2) keys on the span appended by each `text_delta`.

### 4.3 Thinking

- **Default (Tandem's argv): no text.** `message_start` carries wrapper `thinking_display:"updates"` (`think-default:7`); `content_block_start {type:"thinking", thinking:"", signature:""}` (`:8`); `thinking_delta` frames have `thinking:""` and a non-standard `estimated_tokens` (int, `null` on the last before `signature_delta`; `:10,12,13`); `system/thinking_tokens {estimated_tokens, estimated_tokens_delta, user_message_uuid}` interleave (`:9,11,14`: 50, 150, 285); the assistant line's block is `{type:"thinking", thinking:"", signature:"…"}` and the line carries `thinking_duration_ms:2143` and `thinking_display:"updates"` (`:16`); `result.usage.output_tokens_details.thinking_tokens:216` (`:45`). `--thinking=adaptive` and Opus 5.5 behave the same (`think-adaptive`, `think-opus`).
- **Summarized: the one that carries text.** Spawn flag `--thinking-display summarized` (hidden from `--help`; `think-display-summarized`): `message_start.thinking_display:"summarized"` (`:8`), each `thinking_delta` carries a text chunk with `estimated_tokens:null` (`:11` `"47"` … `:31` `"\n\n"`), each preceded by a `system/thinking_tokens` line (`:10` = 1), one jump before `signature_delta` (`:32`: 166), the assistant block holds the whole summary with `thinking_duration_ms:1991` (`:34`). Or the control request after `initialize`: `{"type":"control_request","request_id":"host-29","request":{"subtype":"set_max_thinking_tokens","thinking_display":"summarized"}}` → `{"type":"control_response","response":{"subtype":"success","request_id":"…"}}` with **no `response` field** (`think-summarized:25`), effective next turn (`:30-62`). Accepted values `summarized | omitted | highlights | null`; `"full"` → `control_response` error `set_max_thinking_tokens: max_thinking_tokens must be an integer or null and thinking_display must be "summarized", "omitted", "highlights", or null` (`:73`); `highlights` yields no text on this account (`think-display-highlights`); `omitted` reads back as `updates` (`:100,105`). A trivial prompt produces no thinking block at all.
- Transcripts keep whatever was streamed (`transcripts/think-display-summarized.jsonl:18` has the text), so history shows summaries only if the session was summarized when recorded.
- Today the reducer keeps `AssistantBlock.Thinking(text)` (`ChatState.kt:104`, `Reducer.kt:249`) and the UI drops it (`ChatTranscript.kt:40`); `thinking_tokens` and `thinking_duration_ms` are not decoded. **Decision:** spawn with `--thinking-display summarized` (3.3).

### 4.4 Tool call rows

`content_block_start {type:"tool_use", id, name:"Bash", input:{}, caller:{type:"direct"}}` (`permission.ndjson:11`; `ask-single:9`) → `input_json_delta` fragments (first fragment `""`, `ask-single:10`) → complete assistant line with the full `input`, top-level `wire_tool_inputs {<id>: input}`, `user_message_uuid(s)`, `request_id` (`permission.ndjson:19`) → **`control_request can_use_tool` (if any) before** `content_block_stop` (`ask-single:21-23`; `permission.ndjson:19-21`) → `message_delta(stop_reason:"tool_use")` and the user `tool_result` **in either order** (the result landed first in 3 of 6 AskUserQuestion runs) → `message_stop` → `system/status` [S34]. The row is drawn from `content_block_start` (name known) and filled as JSON streams; a running row needs no result; the reducer must accept a tool result for a message whose `message_delta`/`message_stop` has not arrived. Today `Reducer.kt:250` accumulates `partialInput`, `Reducer.kt:339` sets `input`, and the UI shows only `**Name** \`command\` \`file_path\`` (`ChatTranscript.kt:41-45`).

Tool names on the wire: `Agent` (older `Task`; `init.tools` still lists `Task`: match both, `agent-sync:6,35`), `AskUserQuestion`, `Bash`, `Edit`, `EnterPlanMode`, `ExitPlanMode`, `Glob`, `Grep`, `LSP`, `Monitor`, `NotebookEdit`, `PowerShell`, `Read`, `Skill`, `TaskCreate/Get/List/Update`, `TaskStop`, `ToolSearch`, `WebFetch`, `WebSearch`, `Write`, `Workflow`, `mcp__<server>__<tool>` [stated S15, S34]. The 2.1.302 **default tool set has no Glob, Grep, MultiEdit, TodoWrite or LS** (`tools-edit:6`); the model still calls `Glob`, gets `<tool_use_error>Error: No such tool available: Glob …</tool_use_error>` with no `tool_result_meta` (`tools-read-unavailable:49`), and falls back to Bash `find`/`grep -rn` which auto-approve with `reason_type:"subcommandResults"` (`:73-86`); WebFetch/WebSearch/Task*/ExitPlanMode are **deferred** and preceded by a `ToolSearch {query:"select:WebFetch", max_results:1}` call whose result is `[{type:"tool_reference", tool_name:"WebFetch"}]` (`misc-web:15/17`). Tandem's `--disallowedTools Edit,Write,MultiEdit,NotebookEdit` (`ClaudeSession.kt:76`) names `MultiEdit`, which no longer exists (harmless).

Input fields seen (verbatim): Bash `{command, description?, timeout?, run_in_background?}`; Read `{file_path, offset?, limit?}` (`tools-read:17,34`); Edit `{replace_all, file_path, old_string, new_string}` (`tools-edit:40`); Write `{file_path, content}`; Glob `{pattern, path?}`; Grep `{pattern, path?, glob?, type?, output_mode?, -n?, -A?, -B?, -C?, head_limit?}` (`tools-read:58`); WebFetch `{url, prompt}`; WebSearch `{query, mode:"standard"}` (`misc-web:73`); Agent `{description, subagent_type, prompt, run_in_background}` (`agent-sync:35`); TaskCreate `{subject, description, activeForm?}`, TaskUpdate `{taskId, status, activeForm?, …}` (`agent-todos:31,61`); ExitPlanMode `{plan, planFilePath}` or `{}` (4.9); AskUserQuestion `{questions:[{question, header, options:[{label, description, preview?}], multiSelect}]}` (`ask-single:21`; the model's key order differs from the request's: compare by value) [S34]. Edit rows will mostly come from the planned `mcp__tandem__*` tools (hard rule 4); the row renderer keys on both names.

### 4.5 Tool results and bodies

`user` line `{message:{content:[{tool_use_id, type:"tool_result", content, is_error?}]}, parent_tool_use_id, session_id, uuid, timestamp, tool_use_result?, tool_result_meta?}`. `is_error` is **absent** on most successes, `false` on Bash success, `true` on errors; on any error `tool_use_result` is the **string** `"Error: " + content` (`tools-bash:39`, `tools-read-unavailable:49`, `ask-deny:24`): decode it as a `JsonElement`. `tool_result_meta` = `[{id, permission_decision:{decision, source, reason_type?}, non_execution_kind?}]` with `source:"config"` + `reason_type:"mode"` (auto-accept by mode), `"subcommandResults"` (read-only Bash), `"user_temporary"` (host allow), `{reject, user_reject}` + `non_execution_kind:"permission-rule"` (host deny); missing on the unknown-tool error and on subagent Bash results [S34]. Transcript files mirror this with camelCase `toolUseResult`, `permissionDecision`, `sourceToolAssistantUUID` (`transcripts/history-permission.jsonl:19`, `transcripts/ask-single.jsonl:20`).

Structured outputs recorded (`tool_use_result`) [S34, section 2.4 of the probes]:

| Tool | `tool_result.content` | `tool_use_result` | Fixture |
|------|-----------------------|-------------------|---------|
| Read | `cat -n` style `N\tline` text | `{type:"text", file:{filePath, content (raw), numLines, startLine, totalLines}}` | `tools-read:21`, `:38` (offset) |
| Read (image) | array `[{type:"image", source:{type:"base64", data, media_type:"image/png"}}]` | `{type:"image", file:{base64, type, originalSize, dimensions:{originalWidth, originalHeight, displayWidth, displayHeight}}}` | `misc-image:26` |
| Glob | newline-joined names | `{filenames[], durationMs, numFiles, truncated, totalMatches, countIsComplete}` | `tools-read:50` |
| Grep | `file:line:text` | `{mode:"content", numFiles, filenames[], content, numLines, totalLines}` | `tools-read:62` |
| Edit | `The file <path> has been updated successfully. …` | `{filePath, oldString, newString, originalFile, structuredPatch:[{oldStart, oldLines, newStart, newLines, lines[]}], userModified, replaceAll}`; `gitDiff` absent outside a git repo | `tools-edit:44` |
| Write | `File created successfully at: <path> …` / `… updated successfully` | `{type:"create"\|"update", filePath, content, structuredPatch, originalFile, userModified}` | `tools-edit:61`, `plan-deny:141` |
| Bash ok | merged stdout+stderr, `is_error:false` | `{stdout, stderr:"", interrupted, isImage, noOutputExpected}` (stderr merged into stdout; no exit code) | `tools-bash:23` |
| Bash exit 3 | `Exit code 3\nfailing`, `is_error:true` | string `"Error: Exit code 3\nfailing"` | `tools-bash:39` |
| Bash no output | `(Bash completed with no output)` | `{…, noOutputExpected:true}` | `misc-state-action:25` |
| ToolSearch | array `[{type:"tool_reference", tool_name}]` | `{matches[], query, total_deferred_tools}` | `misc-web:17` |
| WebFetch | the model's answer string | `{bytes, code, codeText, result, durationMs, url}` | `misc-web:34` |
| WebSearch | `Web search results for query: …\n\nLinks: [{title,url},…]\n\n<summary>` | `{query, results:[{tool_use_id, content:[{title,url}]}, "<summary>"], durationSeconds, searchCount}` | `misc-web:78` |
| Agent | `[Subagent hand-back] …` frame | `{status:"completed", agentId, agentType, content:[{type:"text", text}], totalDurationMs, totalTokens, totalToolUseCount, usage, toolStats:{readCount, searchCount, bashCount, editFileCount, linesAdded, linesRemoved, otherToolCount}, canContinueAgent}` | `agent-sync:50` |
| TaskCreate / TaskUpdate | `Task #1 created successfully: …` / `Updated task #1 status` | `{task:{id, subject}}` / `{success, taskId, updatedFields[], statusChange:{from,to}}` | `agent-todos:35`, `:63` |
| ExitPlanMode | `User has approved your plan. …` | `{plan, isAgent, filePath}` | `plan-approve:91` |
| AskUserQuestion | `Your questions have been answered: "Q"="A". …` | `{questions, answers, response?}` | `ask-single:26` |

Not recorded: `Edit.gitDiff` in a git repo, Grep `files_with_matches`/`count`, PDF/notebook reads, Bash `run_in_background`/`timedOutAfterMs`, WebFetch/WebSearch errors, MCP tool results (`{content, structuredContent?}` per SDK). Today `Ndjson.decodeUser` (`Ndjson.kt:97`) keeps only `content`, and `toolResultText` (`Reducer.kt:344`) joins the text members, so image blocks and every structured field are lost; `ChatEntry.ToolResult` holds `(toolUseId, text, isError)`.

### 4.6 Grouping, subagents, todos

- Grouping needs only the ordered `tool_use` blocks and their results; the host computes the sentence (3.7).
- **Subagents** (`agent-sync`, `agent-forwarded`): the parent block is `Agent`; `system/task_started {task_id, run_id, tool_use_id, description, subagent_type, is_backgrounded, spawn_depth, task_type:"local_agent", prompt}` arrives before the parent message closes (`:37`); `task_progress {task_id, tool_use_id, description:"Running <command>", subagent_type, usage:{total_tokens, tool_uses, duration_ms}, last_tool_name}` once per subagent tool call, before that call's line (`:41`); `task_updated {task_id, patch:{status:"completed", end_time}}` (no `tool_use_id`; correlate by `task_id`) (`:48`); `task_notification {task_id, tool_use_id, status, output_file, summary, usage}` (`:49`). Every subagent `assistant`/`user` line carries `parent_tool_use_id` (= the Agent block id), `agent_id` (= `task_id`), `subagent_type`, `task_description` (`:38,42,44,46,47`); subagent `tool_use`/`tool_result` lines are always forwarded, no `stream_event` frames come from a subagent, and the subagent's final **text** is forwarded only with `initialize {hooks:null, forwardSubagentText:true}` (`agent-forwarded:38`); otherwise it is in `task_notification.summary` and `tool_use_result.content[].text`. A permission prompt from inside a subagent is a normal `can_use_tool` plus `agent_id` (`agent-sync:43`). Background subagents (`run_in_background:true`, `background_tasks_changed`, `TaskStop`) were not recorded. Today the reducer keys subagent lines as separate `ChatEntry.Assistant` rows with `parentToolUseId` (`Reducer.kt:350`), rendered flat; `task_*` are Unknown; `initialize` sends `{hooks:null}` (`HostMessages.kt:216`).
- **Todos** (`agent-todos`): without `CLAUDE_CODE_ENABLE_TODO_TOOLS=1` neither Sonnet 5.5 nor Opus 5.5 lists todo tools (`agent-sync:6`); with it, `init.tools` gains `TaskCreate, TaskGet, TaskList, TaskUpdate` (deferred, loaded via `ToolSearch`; `:5,16,18`); `TodoWrite` does not exist in 2.1.302. The list is reconstructed from TaskCreate/TaskUpdate results (4.5); no system lines accompany them.

### 4.7 Permission prompt

Request: `control_request {request_id, request:{subtype:"can_use_tool", tool_name, display_name, input, description?, permission_suggestions?, blocked_path?, tool_use_id, decision_reason_type?, requires_user_interaction?, agent_id?}}`. In `default` mode a Bash call carries `description` (the tool's description), three suggestions `[{type:"addRules", rules:[{toolName:"Bash", ruleContent:"touch tandem-fixture.txt"}], behavior:"allow", destination:"localSettings"}, {type:"addDirectories", directories:[…], destination:"session"}, {type:"setMode", mode:"acceptEdits", destination:"session"}]` and `blocked_path` (`permission.ndjson:21`; `misc-state-action:21`); in `acceptEdits` a Bash with a non-auto-allowed sub-command carries `decision_reason_type:"subcommandResults"` and a rule naming only that sub-command (`tools-bash:20`); WebFetch's suggestion is `addRules [{toolName:"WebFetch", ruleContent:"domain:example.com"}]` with `description` = the URL, WebSearch's `addRules [{toolName:"WebSearch"}]` with `description` = the query (`misc-web:31,74`); AskUserQuestion and ExitPlanMode carry **no** suggestions and `requires_user_interaction:true` (`ask-single:22`, `plan-approve:88`). The request arrives **after** the complete `tool_use` line and **before** `content_block_stop` (4.4), so the card can be attached to the already-drawn row. `Ndjson.kt:188-206` keeps `tool_name, input, tool_use_id, permission_suggestions, blocked_path, title, display_name, description`.

Answers (`HostMessages.kt:233-247`): allow `{type:"control_response", response:{subtype:"success", request_id, response:{behavior:"allow", updatedInput:<input>, updatedPermissions?:[…], toolUseID}}}`; deny `{… response:{behavior:"deny", message, interrupt?:true, toolUseID}}` (a deny without `interrupt` lets the turn continue; `ask-deny:24-40`). "Always allow" = echo the `addRules` suggestion in `updatedPermissions` (destination `localSettings` writes `.claude/settings.local.json`; `session` keeps it in memory). **Fixed on 2026-10-18 (commit `a9675de`):** `ClaudeSession.allow(always=true)` (`ClaudeSession.kt:229-234`) now echoes only the `addRules` suggestions with destination `session`; the recorded `plan-approve:90` confirms that an echoed `setMode` would have switched the live mode. Cancel: `control_cancel_request` removes the pending card (`Reducer.kt:130`). Mode switch acknowledgements: `control_response {response:{mode:"plan"}}` + `system/status {status:null, permissionMode:"plan"}` (`permission-mode.ndjson:17-18`); rejection `control_response subtype:"error"` with `error_code` (`permission-mode-rejected.ndjson:17`). Auto-denials without a prompt: `system/permission_denied {tool_name, tool_use_id, message}` (SDK; not recorded).

### 4.8 AskUserQuestion

Arrives as a `can_use_tool` request with `tool_name:"AskUserQuestion"`, `display_name`, `input.questions`, `tool_use_id`, `requires_user_interaction:true` and nothing else (`ask-single:22`); it reaches the host even in `default`/`acceptEdits` (it is how the CLI asks the host to render the question) and is denied only in `dontAsk` [stated S15, S34]. Today it lands in the generic `PendingPermission` path (`Reducer.kt:118`) and the UI shows `Allow AskUserQuestion?` with 300 chars of raw JSON (`ChatScreen.kt:68`), which is the owner's complaint.

Exact answer (recorded round trip, `ask-single`), keyed by question **text**, value = option **label**:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id>","response":{
  "behavior":"allow",
  "updatedInput":{
    "questions":[ …the original questions array, unchanged… ],
    "answers":{ "Which colour?":"Blue" }
  },
  "toolUseID":"<tool_use_id>"
}}}
```

- Multi-select: the `", "`-joined string `"Cheese, Ham"` (what the CLI's own TUI sends; `ask-multi:31`) or an array `["Cheese","Ham"]` (also accepted; the model-visible text then joins with a bare comma, `ask-multi-array:30`). Send the string.
- Free text ("Other"): a non-label value is accepted with the cautionary wording `The user answered: "Q"="Green". Read the answers carefully …` (`ask-other:26`); or `updatedInput.response:"free text reply"` instead of `answers` → `The user responded: …` (`ask-response:24`); `response` wins if both are sent.
- Deny: `{behavior:"deny", message:"cancelled"}` → `tool_result {content:"cancelled", is_error:true}`, `tool_use_result:"Error: cancelled"`, the turn continues and `result.permission_denials` lists the call (`ask-deny:24-40`).
- After an answer: `tool_result.content` = `Your questions have been answered: "Which colour?"="Blue". You can now continue with these answers in mind.` and `tool_use_result` = exactly the `updatedInput` sent (`ask-single:26`); it may arrive **before** the parent message's `message_delta` (`ask-multi-array:30/31`), so the card must key on the `control_request`, not on message close.
- Unknown: how several questions' answers are joined in the text; `interrupt:true` denies; the bundle's newer `questions[].kind` (`text` / `number`) and notes fields (never emitted by the model in the probes).

### 4.9 ExitPlanMode

A `tool_use` named `ExitPlanMode` (loaded via `ToolSearch` first), then a `can_use_tool` request with **exactly** `subtype, tool_name, display_name, input, tool_use_id, requires_user_interaction:true`, never `permission_suggestions` (`plan-approve:88`, `plan-deny:79,143`, `plan-disallowed:173`) [S34]:

- With `Write` allowed (`--permission-mode plan`), the plan-file Write (`~/.claude/plans/<slug>.md`) is auto-approved (`source:"config", reason_type:"other"`, `plan-approve:73`) and the request's `input` is `{plan:"# Plan: …", planFilePath:"<path>"}`; `plan` equals the file the model wrote (`:69` vs `:87`).
- With Tandem's argv (`Write` disallowed, `plan-disallowed`), the Write fails with `<tool_use_error>Error: No such tool available: Write …</tool_use_error>` (no prompt, no `permission_denied`, `:102-103`), the model still calls `ExitPlanMode` with `input:{}` (`:172-173`), and the host has no plan to show (the text exists only in the failed Write's `input.content` and the preceding assistant text).
- Approve and start coding = allow with `updatedInput:<input>` and a **host-built** `updatedPermissions:[{type:"setMode", mode:"acceptEdits", destination:"session"}]`; the CLI answers `system/status {status:null, permissionMode:"acceptEdits"}` **before** the tool result (`plan-approve:90-91`), the next turn's `init` agrees (`:160`), and a later project Write is silent (`:121`). A plain allow leaves plan mode to `default` (`plan-disallowed:177`). `No, keep planning` with feedback = deny with `message:"No, keep planning: also add a date"`: `is_error` result, **no status line**, the session stays in plan mode, the model rewrites the plan file (Write `type:"update"` with one `structuredPatch` hunk, `plan-deny:137-141`) and calls `ExitPlanMode` again (`:142-143`). Never persist an always-allow rule for `ExitPlanMode`.
- The tool result after approval: `User has approved your plan. You can now start coding. …\n\nYour plan has been saved to: <path>\n…## Approved Plan:\n<plan>` with `tool_use_result:{plan, isAgent:false, filePath}` (`plan-approve:91`).
- Open: `EnterPlanMode` from a non-plan session; `isAgent:true` plans; a rule allowing `Write` only under the plans directory (untested alternative to dropping `Write` from `--disallowedTools`).

### 4.10 Notices and errors already typed

`system/api_retry {attempt, max_retries, retry_delay_ms, error_status, error}` → warning notice (`Reducer.kt:94`); `model_refusal_fallback` → retraction + notice (`:105`); `model_refusal_no_fallback` (`:111`); `assistant.error` with `model:"<synthetic>"`, `error:"model_not_found"`, `api_error_status:404` (`invalid-model.ndjson:4`) → error notice; `result is_error` with `terminal_reason:"api_error"` (`:5`); interrupt = `control_response {still_queued:[]}`, `assistant aborted:true`, user text `[Request interrupted by user]` → `Stopped.` (`interrupt.ndjson:13-19`, `Reducer.kt:214`). Not yet surfaced: `rate_limit_event {rate_limit_info:{status, resetsAt, rateLimitType, overageStatus, unifiedWindows:{five_hour:{utilization, resetsAt}, seven_day:{…}}}}` which arrives **anywhere** in the stream, including between `stream_event` frames (`misc-state-emit:5`, `misc-state-action:8,34`); `system/informational {content, level}` and `system/notification {text, priority}` (SDK; never seen in 29 sessions); `system/compact_boundary`; `conversation_reset`; `tool_progress.subagent_retry`; `result.errors[]`; the replayed `<local-command-stdout>Set model to …</local-command-stdout>` user line (`model-switch.ndjson:20`, skipped by `Reducer.kt:207`).

### 4.11 Composer data

`initialize` response (`fork.ndjson:2`; `agent-sync:2`) lists `models:[{value:"default", resolvedModel:"claude-sonnet-5-5", displayName:"Default (recommended)", description:"Sonnet 5.5 · Efficient for routine tasks", supportsEffort:true, supportedEffortLevels:["low","medium","high","xhigh","max"], supportsAdaptiveThinking, supportsAutoMode, supportsThinkingOff, supportsFastMode?}, …]` plus `claude_code_version`, `current_permission_mode`, `capabilities`, `session_state`, and the outer envelope's `pending_permission_requests:[]` / `pending_user_dialog_requests:[]`. Reducer keeps `value/displayName/description` only (`Reducer.kt:353-362`). Usage ring: context = the last `message_start.message.usage` input sum / `result.modelUsage[init.model].contextWindow` (1000000 on Sonnet 5.5, `misc-image:66`); plan usage = `rate_limit_event.unifiedWindows.{five_hour, seven_day}.utilization` (0..1) with `resetsAt` (epoch seconds) [S34].

---

## 5. Gap table: element → Tandem today → target → size

| Element | Tandem today (verified in code) | Target | Size |
|---------|-------------------------------|--------|------|
| Row model | One flat `List<TranscriptEntry(role, markdown)>`; everything flattened to Markdown (`ChatTranscript.kt`); labels `You` / `Claude ▍` / `Tool result` / `Tandem` (`ChatPanel.kt:70-73`) | Structured rows: `User`, `Text`, `Thinking`, `ToolGroup(calls, sentence, expanded)`, `ToolCall`, `EditedFilesCard`, `Working`, `Notice`, `PendingCard`; no labels; column = min(768 dp, width − 32 dp) | **L** |
| User message | Full-width grey box, 6 dp radius, label `You` (`ChatPanel.kt:66-70`) | Right-aligned bubble ≤ 85 % width, 8 dp radius, 12 × 10 dp padding, no label; plain text; thumbnails above; steered prompt at 60 % with a `queued` caption; clamp > 8 lines | S |
| Assistant text | Markdown via Jewel bridge, CommonMark + autolink only; whole message re-parsed per delta (`ChatRows.kt`) | Same renderer plus the bundled `gfmTables` (and strikethrough/alerts) extensions (declare the modules in `build.gradle.kts` and `plugin.xml`); fences with hover copy and one-line `run`; table styling of 3.2; file-path links open the editor; per-chunk 200 ms fade-in while streaming, no caret; hover row (copy + relative time) | M |
| Thinking | Dropped (`ChatTranscript.kt:40`); no text requested | Spawn with `--thinking-display summarized`; collapsed summary row like a tool row, behind a "Show thinking" toggle; expand lists the sentences; `thinking_duration_ms` + `thinking_tokens` decoded for a tooltip | M |
| Tool call row | `**Name** \`command\` \`file_path\`` text only; no state, no partial input (`ChatTranscript.kt:41-45`) | Muted summary row with trailing `›`, drawn at `content_block_start`, present/past tense, red `Failed to …`, `Denied` / `Not run`, per-tool label rules (3.5), fills while JSON streams; whole row clickable | **L** |
| Tool result | Separate `Tool result` row, 12-line fence (`ChatTranscript.kt:20,36`) | Joined to its call by `toolUseId`; collapsed by default; typed bodies (Bash command box + output, Read with line numbers, hunk cards from `structuredPatch`, image thumbnails, Agent summary); one 20-line clamp rule; `tool_use_result` decoded as `JsonElement` | **L** |
| Grouping | None | Rules 1–9 of 3.7 with the expandable box; Verbose toggle; `ToolSearch` hidden | M |
| Edit/Write diff | Not rendered (tools disabled; MCP tools pending) | Hunk cards (gutter, red/green rows, 140 dp clamp + expander) for `Edit`/`Write`/`mcp__tandem__*`; `Edited N files` card with `›` → IDE diff | M (depends on Phase 3) |
| Working indicator | `working…` in the header (`SessionHeader.kt:180`), `Claude ▍` label, Stop button | Last-row `✻ <Step>… · <elapsed>` (+ ` · N tokens`, proposal) with a continuously animated spark; live progress variant; step word from the running tool's `description`; replaced by the hover row + lone `✻` on completion; host timer + usage sums; `session_state_changed` optional | M |
| Notices | Markdown rows labelled `Tandem` with `⚠`/`**Error:**` (`ChatTranscript.kt:28-29`); hard-coded red bar `Color(0xFFB3261E)` (`SessionHeader.kt:173`) | Muted-text rows; Jewel inline banners for usage limit / flagged message / mid-response failure; theme colours; retry string format; `rate_limit_event` decoded | S |
| Permission prompt | `Allow <tool>?` + 300 chars raw JSON + `Allow once` / `Allow for this session` / `Deny`, below the list (`ChatScreen.kt:60-72`); "always" now echoes only `addRules` (`ClaudeSession.kt:229-234`) | Card docked above the composer (composer stays): yellow dot + `Allow Claude to edit <file>?` / `run <command>?`, muted description, mono payload, buttons `Deny [Esc]` left, `Always allow [⌘↩]` + `Allow once [↩]` right; no dim; per-tool titles | M |
| AskUserQuestion | Generic permission prompt with JSON; allow does not answer | Question card (3.5.1): header tabs, `RadioButtonRow`/`CheckboxRow` options with descriptions, `Other` field, `Submit`, `Esc`; sends `updatedInput.answers` (fixture `ask-single`); `Questions ›` row afterwards | M |
| ExitPlanMode | Generic permission prompt | Plan approval card with the three options and feedback field; `setMode acceptEdits` in `updatedPermissions` (fixture `plan-approve`); plan from `input.plan` or, under Tandem's argv, decide on `Write` for the plans dir | M |
| Todo list | Nothing | Inline checklist card from TaskCreate/TaskUpdate results, newest expanded; only when `init.tools` lists them (`CLAUDE_CODE_ENABLE_TODO_TOOLS=1`) | S–M |
| Subagents | Flat `Claude` rows with `parentToolUseId` (`Reducer.kt:350`) | `Ran an agent ⌄` row; nested subagent rows collapsed inside; `Done (N tool uses · tokens · time)` sub-line from `AgentOutput`; `task_*` decoded; `forwardSubagentText:true` in `initialize` | M |
| Composer | `TextArea` + `Send`/`Stop` buttons; pickers row with `ListComboBox`es and `effort: x` badge (`PromptBar.kt`); no placeholder, no Enter-to-send | 40 dp rounded field with placeholder `Type / for commands`, `↵`/stop glyph inside, Enter sends / Shift+Enter newline / Esc stops / Enter-while-busy steers; footer row: mode label + model · effort as `DropdownLink`s + usage ring | M |
| Autoscroll | Unconditional `scrollToItem(last)` on every delta (`ChatPanel.kt:41-42`) | Intent-based following (3.12), scroll on send, `↓ N new` button, cards never force-scroll | S–M |
| Scrollbar | Plain `LazyColumn`, no IDE scrollbar | `VerticallyScrollableContainer(listState)` | S |
| Protocol decoding | `Unknown` for `thinking_tokens`, `task_*`, `tool_progress`, `rate_limit_event`, `permission_denied`, `informational`, `compact_boundary`, `session_state_changed`, `command_lifecycle`; `tool_use_result`, `tool_result_meta`, `message_delta.usage`, `message_start.usage`, `result` durations/usage, `thinking_duration_ms`, `agent_id` dropped | Type each with a fixture test (hard rule 6); the 29 S34 fixtures already hold every shape except background subagents, MCP results and `tool_progress` | M |

---

## 6. Open questions

Closed since the first draft (answers in sections 3–4): thinking text over stdio (`summarized`); `ExitPlanMode` input and `setMode` behaviour; the `session_state_changed` env vars (both work); todo-tool availability; the spark's motion (animated, not a glyph cycle or a dot); the working-row composition of the Oct 2026 build; the Desktop thinking row and expanded block; the `Edited N files` card; key caps on the dark permission card; dimming (none in the target builds); the dock order (cards above a visible composer); `-0` stats (shown); chevron side (trailing); the Agent row (`Ran an agent ⌄`); the "Allow for this session" `setMode` risk (fixed in code).

Still open, each with the best guess and how to verify:

1. **Desktop light theme at tool-window width and exact Desktop fonts/metrics.** Best guess: the token tables in 2.3/2.4 with the IDE fonts. Verify: ask the owner for 2x screenshots of one dark and one light session at ≈ 400 px and at full width, with a tool group expanded, a diff card, a failed row and the composer; sample them; then compare with `runIde` side by side.
2. **Grouping thresholds** (names vs counts; per-file vs `Edited N files`; whether a failed call splits a group). Best guess: names while ≤ 2 names fit one line, else counts; per-file for one edit; the red row sits in place. Verify: owner screenshots of turns with 1/2/3/6 reads, one and two edits, a mixed turn, and a failure in the middle, collapsed and expanded.
3. **Token count on the working row.** The Oct 2026 build shows none; the Sept 2026 Windows build shows `109 tokens`. Best guess: append it (proposal in 3.8). Verify: `runIde` under a human eye; drop it if noisy.
4. **Step-word source and the `Working` fallback.** No `task_summary` on 2.1.302; `tool_progress` never seen. Best guess: the running tool's `description`, else `Working` (Desktop) or one random verb per turn. Verify: a 15 s screen recording from the owner of a Desktop turn from send to completion; check whether the current CLI ever emits `task_summary`/`tool_progress` with longer tools.
5. **The live thinking row in the current Desktop build** (streaming text vs a `Thinking` label vs nothing) and whether Desktop ever shows `Thought for Ns` or a token count. Best guess: the streaming summary text on one line, then the collapsed row. Verify: owner screenshot mid-turn in Thinking view.
6. **Desktop AskUserQuestion and plan-approval card visuals.** Best guess: VS Code's structure with Desktop's button style (3.5.1, 3.5.2). Verify: owner screenshots of a two-question card (one multi-select) before and after answering, and of a plan approval.
7. **Plan mode under Tandem's argv.** The plan file is not written and the request's `input` is `{}`. Best guess: allow `Write` for `~/.claude/plans/` (or serve it through the IDE MCP tool). Verify: a `plan-*` recording with a rule allowing `Write` under the plans directory; check `tool_result_meta`.
8. **Desktop to-do widget look** (inline card vs pane, item glyphs, where `activeForm` shows). Best guess: inline checklist card (3.5). Verify: owner screenshot of the Oct 2026 live to-do list.
9. **Jump-to-bottom button in the Oct 2026 Code tab** (whether it still exists and its look). Best guess: the Cowork `↓` button (3.12). Verify: owner scrolls up during a stream and reports; screenshot.
10. **Image tool results in the GUI** (thumbnail size, click behaviour). Best guess: ≤ 480 dp inline, click opens in the IDE. Verify: owner screenshot of a Read of a PNG.
11. **Verbose-view rendering of Read/Grep on Desktop** and the expanded body of a red `Failed to …` row and of a denied call. Best guess: `Read  <path>` / `Grep  <pattern>` in the MCP-row style; the error text in mono. Verify: owner screenshots in Verbose mode and of a failed/denied call expanded.
12. **Multi-line code fences on Desktop** (language label, copy placement, `run` on multi-line shell blocks) and heading sizes H1–H3, blockquotes, nested lists, link hover. Best guess: 3.2 proposals. Verify: owner screenshot of a reply containing H1–H3, a blockquote, a nested list, a table, a one-line fence and a ten-line fence, hovered.
13. **Relative-time thresholds and the hover row's visibility rule.** Best guess: `just now` < 60 s, then minutes/hours/date; hover-only. Verify: owner reports after 1 min / 2 h / next day; a screenshot with the mouse away from the latest reply.
14. **Spark rotation rate and easing; whether a rotating painter in Compose looks like the app.** Best guess: one revolution per 2 s, linear. Verify: `runIde` under a human eye next to the S3 video; agents cannot judge motion.
15. **Background subagents, MCP tool results and `tool_progress`** have no fixture. Verify: `scripts/record-fixture.sh` with `run_in_background:true`, with an MCP server configured, and with a > 30 s Bash.
16. **Queued-prompt visuals on Desktop** (the `unread` hint, the `N messages queued` list). Best guess: 3.1 proposal. Verify: owner screenshot of a prompt sent while a turn runs and of a Cmd+Enter queue.
