# Claude Code desktop chat UI reference (for Tandem's chat redesign)

Status: research reference, 2026-10-18. Read with `docs/design.md` (hard rules) and `docs/protocol.md` (wire protocol). Phase 2a spec and plan are built from this file.

Audience: the engineer and the reviewer who reproduce the Claude Code desktop app's chat feel in Tandem (Kotlin, Jewel/Compose) without having seen the app.

How to read this file:

- **Desktop** = the Claude desktop app's Code tab (Sept–Oct 2026 builds unless a build date is given). This is the primary target.
- **VS Code** = the Claude Code VS Code extension. Used where Desktop evidence is missing (thinking row, AskUserQuestion card, Bash body). Its chat is the same component family, docked in a narrow side panel like Tandem's tool window.
- **CLI** = the terminal UI. Only cited for strings and behaviour the GUIs inherit (spinner verbs, grouping rules, retry lines).
- Every claim carries a confidence tag: **[seen]** visible in a captured screenshot or video frame listed in section 1 (conflicts between agents were settled by viewing the frame; the frame wins; a file name after the source number, such as `S3 desktop-pop-out-01`, is the frame or screenshot in `docs/superpowers/research/assets/`, section 1.1); **[stated]** written in official docs, the changelog, an issue, or read from source code; **[inferred]** estimated from screenshots (colours, pixel sizes) or deduced. Measurements are from compressed captures of several builds at 1x and 2x, so expect ±2 px and ±a few colour units. Where nothing was measured the text says **unknown** and gives a **proposal**, clearly labelled.
- Tandem line references (`ChatPanel.kt:41`) were checked against the working tree on 2026-10-18.

---

## 1. Sources

| # | URL | What it is | Claims that rest on it |
|---|-----|------------|------------------------|
| S1 | https://code.claude.com/docs/en/vs-code | Official VS Code extension docs with three screenshots (`vs-code-extension-interface.jpg`, `vs-code-send-prompt.png`, `vs-code-edits.png`; copies in `assets/s1-vscode-docs/`) | VS Code row style (dot rail, `Read` / `Write` rows, `└ Read 256 lines`), `✻ Pondering...`, composer strings, permission card `Make this edit to utils.py?` with `1 Yes / 2 Yes, and don't ask again / 3 No`, Focus view, screen-reader labels, shortcuts, `spinnerVerbs` |
| S2 | https://code.claude.com/docs/en/desktop and https://code.claude.com/docs/en/desktop-quickstart | Official Desktop docs (no chat screenshots) | View modes Normal/Thinking/Verbose, permission-mode labels, keyboard shortcuts, composer features, panes, usage ring, `Allow once / Always allow / Deny` wording |
| S3 | https://code.claude.com/docs/en/whats-new/2026-w28, -w32, -w33, -w37 | Official "what's new" pages with mp4 demos (frames extracted with the command in 1.1; cited frames and contact sheets in `assets/s3-whats-new/`) | Desktop dark/light chat: right-aligned user bubble, grouped summary rows (`Read 38 files, searched for 6 patterns ›`, `Edited billing/charge.ts +31 -40 ›`, `Ran pnpm test ›`), `Edited 3 files` card, verbose MCP rows, Edit card, spark + verb + elapsed (`Testing… · 3s`), usage-limit card, composer footer |
| S4 | https://github.com/anthropics/claude-code/issues/72458 | Issue with Desktop (June 2026, Windows) and VS Code screenshots | `Used 2 tools ›`, red `Failed to … ›`, `Ran a command, edited a file ›`, spark `6m 25s · 35.4k tokens · 2 running tasks`, VS Code Bash IN/OUT box, dot rail with connecting line |
| S5 | https://github.com/anthropics/claude-code/issues/73000 | Issue with VS Code screenshots (2.1.195–197) | `Thought for 9s` rows, `● Bash <description>` + IN/OUT box, `● AskUserQuestion` row and the question card (`Render test` tab, options, `Other`, `1 Submit answers`, `Esc to cancel`) |
| S6 | https://www.xda-developers.com/claude-code-desktop-gui-has-superpower-the-terminal-version-will-never-match/ | Review with seven Desktop screenshots (Sept 2026, Windows, dark, 1x; 1920×1080 copies in `assets/s6-xda/`; an eighth image on the page, `claude-code-cli-commands.png`, shows the CLI and was not kept) | Measured dark colours and spacing, summary rows with diff stats, expanded `Ran 2 commands ⌄` box, file cards (`pricing.html +146 -0 ›`, `Undo`), working row `13s · 109 tokens · Running tools...`, turn footer icons + `just now`, composer footer `Sonnet 5.5 Medium ◯` |
| S7 | https://venturebeat.com/orchestration/we-tested-anthropics-redesigned-claude-code-desktop-app-and-routines-heres-what-enterprises-should-know | Review with Desktop screenshots (April 2026, macOS, dark) | `Reading weather.ts ›`, spark `7s · ↓ 201 tokens`, blue left-aligned user bubble of that build, Tasks/Plan panes (`No tasks.`, `No plan yet…`), usage-ring tooltip `Context 20.1k / 200.0k (10%) · Plan 24%` |
| S8 | https://miraflow-cms.s3.us-west-2.amazonaws.com/claude-code-redesign-interface.png | Marketing-style Desktop shot (April 2026 redesign, dark; `assets/s8-miraflow/claude-code-redesign-interface.png`, 2440×1242) | Per-call rows `Edit <path> +142 -38 ›`, `Lint ›`, `Typecheck ›`, `Build ›`, `Write <path> +62 ›`, footer `25s · ↓ 35.7k tokens`, branch bar `main ← claude/alignment-grid +966 -55 Create PR` |
| S9 | https://ruben.substack.com/p/claude-code | Walkthrough with Desktop light-theme screenshots (early 2026) | Light colours, `› <natural-language description>` rows, expanded tree (`Preview Stop`, `Edit <path> +6 -6`), `· Clauding...` spinner with right-aligned `11m 16s · ↓ 22.6k tokens`, permission card `Allow Claude to Edit launch.json?` with `Deny [Esc] / Always allow for session [⌘↩] / Allow once [↩]`, dimmed transcript behind the card, VS Code `/` menu and model-switch notice |
| S10 | https://claudecodeformarketers.com/_astro/claude-code-desktop-diff-approval.CWy_kjyo_gvDkq.webp and …/claude-code-desktop-tabs.DIVN9aUH_Z1GN2Vp.webp | Desktop dark screenshots (mid 2026; `assets/s10-ccfm/claude-code-desktop-diff-approval.webp` 1600×1140 and `claude-code-desktop-tabs.webp` 1600×919) | Expanded `Edited README.md +23 -16 ⌄` diff cards (clamped, fade, expander), dark permission card (yellow dot, `session` pill, `Deny / Always allow / Allow once ⌘↩`), sidebar status glyphs |
| S11 | https://aitoolsreview.co.uk/insights/claude-code-desktop-browser | Frames of Anthropic's Oct 2026 browser demo | `mcp__browser__*  <description>` rows, `✛ Applying edit` / `✱ Reading page` spinner rows, `Auto mode` yellow pill |
| S12 | https://verygood.ventures/assets/images/blog/claude-code-desktop-hands-on-review/*.png | Review screenshots (Desktop dark; `assets/s12-vgv/`: `parallel-agents-running.png`, `side-chat.png`, `pr-controls.png`, `session-sidebar.png`, `context-usage-panel.png`) | Tasks pane cards (`Running Bash · 2m 8s · 170.0k tokens · 53 tool uses`), side chat, PR/CI bar, context-usage popover |
| S13 | https://code.claude.com/docs/en/changelog and https://raw.githubusercontent.com/anthropics/claude-code/main/CHANGELOG.md | Official changelog | Thinking pill strings, Focus view, scroll fixes (2.1.275, `scrollToBottomOnSend`), `Not run` rows, `Compacted chat` card, retry wording, plan approval strings, spinner colour changes, 60 s `Not responding` |
| S14 | https://code.claude.com/docs/en/permissions, …/permission-modes, …/interactive-mode, …/fullscreen, …/costs, …/statusline, …/sub-agents, …/tools-reference, …/errors | Official CLI docs (one Bash prompt screenshot) | CLI permission prompt strings, plan approval options, Ctrl+T task list, auto-follow rule and `Jump to bottom`, `(<elapsed> · ↓ <n> tokens)` format, Agent row `<agent>(<task>)`, tool list and which tools prompt |
| S15 | https://code.claude.com/docs/en/agent-sdk/typescript, …/agent-sdk/user-input, …/agent-sdk/permissions, …/agent-sdk/todo-tracking, …/agent-sdk/hooks, …/hooks | Official SDK and hooks reference | Tool input/output field names, `can_use_tool` answer shapes, AskUserQuestion `updatedInput.answers`, ExitPlanMode input/plan injection, task_* and thinking_tokens events, session_state_changed |
| S16 | https://github.com/anthropics/claude-code/issues/58767, /66833, /72117, /99338, /99139, /55120, /78593, /96497, /4058, /81327 | Issues | Broken 50 px scroll threshold and the expected rule, AskUserQuestion card opens expanded over the composer, spinner node-graph complaint, thinking chevron regression, retry line format |
| S17 | https://gist.github.com/phuryn/478581dc5e09d9a6324c6a9623163c7a | Community CSS patch for the VS Code webview | DOM/token facts: 13 px / 1.6 line height, secondary `#8b8b8b`, charts green `#89d185` / red `#f14c4c`, 60 px clamp with fade, 0.25 s collapse |
| S18 | https://gist.github.com/krishnakanthb13/c43f4851bebcca9358ee2be9f3074919 ; https://github.com/levindixon/tengu_spinner_words ; https://github.com/Piebald-AI/tweakcc ; https://github.com/anthropics/claude-code/issues/99902 | Community spinner research | Verb list, glyph cycle `· ✢ ✳ ✶ ✻ ✽` at 120 ms, shimmer timing |
| S19 | https://plugins.jetbrains.com/plugin/27310-claude-code-beta- | Anthropic's JetBrains plugin page | It has no chat panel (terminal-based, v0.1.14-beta) |
| S20 | Open-source hosts: https://github.com/siteboon/claudecodeui, https://github.com/wbopan/cui, https://github.com/zhukunpenglinyutong/jetbrains-cc-gui, https://github.com/slopus/happy, https://github.com/winfunc/opcode, https://github.com/sugyan/claude-code-webui, https://github.com/croll83/companion | Community reimplementations (MIT/Apache/AGPL; read, not copied) | Stick-to-bottom algorithm, per-tool label rules, denial strings, tool-row state machine; cited only as implementation precedent, never as the look |
| S21 | `src/test/resources/fixtures/*.ndjson`, `src/main/kotlin/dev/daarkolabs/tandem/**` | Tandem's recorded CLI transcripts and current code | Section 4 (what the stream gives) and section 5 (Tandem today) |

Hosts that refused during research (so no claim rests on them): `api.github.com`, `github.com` raw HTML, `user-images.githubusercontent.com`, YouTube watch pages and transcripts. Issue screenshots behind signed GitHub URLs were only partly retrievable; where an issue is cited without a frame the claim is [stated].

### 1.1 Captured frames and screenshots (`docs/superpowers/research/assets/`)

Every `[seen]` tag that names a file points into this folder. Files are unedited copies of public media; nothing was cropped or recoloured. Sizes in this document that come from S3 frames were read on the 1600 px-wide frames, which are the 1280 px / 1440 px videos scaled up by 1.25× / 1.11×, so divide by that factor to get video pixels (the videos are themselves scaled screen recordings, so treat every S3 measurement as a ratio, not an absolute).

**S3 videos** (downloaded 2026-10-18 from the `<video src>` of each page; the `?s=` signature is what the page carried that day, the path part is stable):

| File | Page | URL | Format |
|------|------|-----|--------|
| `desktop-browser.mp4` | 2026-w28 | https://mintcdn.com/claude-code/x358isu_VzLnyTEN/images/whats-new/desktop-browser.mp4?fit=max&auto=format&n=x358isu_VzLnyTEN&q=85&s=8033e85a1cb0a37870a79e702c18f4e4 | 1280×720, 24 fps, 47.25 s, sha256 `83739a2a3aab…` |
| `cross-session-messaging.mp4` | 2026-w32 | https://mintcdn.com/claude-code/N3yEaTYPXMXFrF6k/images/whats-new/cross-session-messaging.mp4?fit=max&auto=format&n=N3yEaTYPXMXFrF6k&q=85&s=8f33c3390f78660a4a26dc980f46159f | 1440×756, 30 fps, 16.07 s, sha256 `eec32f56fb8a…` |
| `desktop-auto-continue.mp4` | 2026-w33 | https://mintcdn.com/claude-code/2SnAdpL4dJ18nKb3/images/whats-new/desktop-auto-continue.mp4?fit=max&auto=format&n=2SnAdpL4dJ18nKb3&q=85&s=1937f489695feaea715e48ecfd7e62cd | 1440×810, 30 fps, 12.03 s, sha256 `572d56646910…` |
| `desktop-pop-out-panes.mp4` | 2026-w37 | https://mintcdn.com/claude-code/f9HTZGyMtxIFOUgt/images/whats-new/desktop-pop-out-panes.mp4?fit=max&auto=format&n=f9HTZGyMtxIFOUgt&q=85&s=ff3770dd09bb15ed9cf17a460f3d1e23 | 1280×800, 30 fps, 22.5 s, sha256 `702d32d079bb…` |

Extraction, one frame every 3 s, upscaled to 1600 px wide (frame `NN` is at t ≈ 3·(NN−1) s; this reproduces the frames pixel for pixel, verified with ImageMagick `compare -metric AE` = 0):

```
ffmpeg -i desktop-pop-out-panes.mp4   -vf "fps=1/3,scale=1600:-1" desktop-pop-out-%02d.png        # 8 frames
ffmpeg -i desktop-auto-continue.mp4   -vf "fps=1/3,scale=1600:-1" desktop-auto-continue-%02d.png  # 4 frames
ffmpeg -i desktop-browser.mp4         -vf "fps=1/3,scale=1600:-1" desktop-browser-%02d.png        # 16 frames
ffmpeg -i cross-session-messaging.mp4 -vf "fps=1/3,scale=1600:-1" cross-session-%02d.png          # 5 frames
```

Checked in under `assets/s3-whats-new/`: the twelve frames cited in the text and one labelled contact sheet per video (`<video>-sheet.jpg`, every frame at 400 px wide) so the uncited frames can be found without re-extracting. The mp4s are not checked in.

| Frame | t ≈ | What it shows |
|-------|-----|---------------|
| `desktop-pop-out-01` | 0 s | Dark Desktop, macOS: sidebar; right-aligned user bubble; `Read cart.spec.ts, checkout.ts ›`; bullets with inline-code chips (`/api/cart`, `retry()`); `Edited 3 files` card with `View changes` and rows `cart.spec.ts +12 -4 ›`, `checkout.ts +3 -1 ›`, `utils/retry.ts +18 -0 ›`; streaming reply whose last word is faded (`now wraps`); composer `Type / for commands` with `↵`; footer `+  🎤 ⌄  Auto … Opus 5  High  ◯`; file pane with coloured diff gutter |
| `desktop-pop-out-03` | 6 s | Same session; `• Running npm test · 0/340 passed` row (coral dot) under the reply; Terminal pane popped out as a floating window |
| `desktop-pop-out-04` | 9 s | `✻ Running npm test · 11/340 passed`; two popped-out panes |
| `desktop-pop-out-07` | 18 s | Two sessions side by side. Left: `• Running npm test · 46/340 passed`, footer `Auto … Opus 5  High`. Right: `Ran npx @tailwindcss/upgrade ›`, `Edited 14 files ›`, reply with bullets, `✻ Writing summary…`, PR bar `#128  claude/tailwind-v4  +42 -7  ● CI ⌄  ×` above the composer, footer `Accept edits … Fable 5.1  High  ◯` |
| `desktop-auto-continue-01` | 0 s | Light Desktop: `Read 38 files, searched for 6 patterns ›`; reply with `PaymentError → V3Error` chips; `Edited billing/charge.ts +31 -40 ›`, `Edited billing/refund.ts +22 -27 ›`, `Edited payouts/schedule.ts +18 -21 ›`; `✻ Migrating… · 43m 11s` |
| `desktop-auto-continue-02` | 3 s | Close-up (2:12 PM): the same rows, then the `Usage limit reached` card: icon + title, `Auto-resuming at 3:40 PM`, `View details` button, checked box `Auto-continue when limits reset`, right-aligned `Try again`; composer; footer `Auto  +  🎤 ⌄ … Opus 5  Max  ◯` |
| `desktop-auto-continue-03` | 6 s | 3:40 PM: the card dimmed (settled), `✻ Resuming… · 0s` |
| `desktop-auto-continue-04` | 9 s | Close-up: dimmed card, `Edited payouts/retry.ts +26 -30 ›`, `Edited 61 files +904 -1130 ›`, `Ran pnpm test ›` (mono command), streaming reply with faded tail (`deleted,`), `✻ Testing… · 3s`, composer, footer |
| `desktop-browser-03` | 6 s | Close-up, dark, no sidebar: user bubble; assistant sentence `Starting the dev server to look at it live.`; grey sub-row `• Opening in Browser` with a small coral dot; empty browser pane |
| `desktop-browser-10` | 27 s | Close-up, verbose view: rows `mcp__browser__click  Select Express shipping`, `mcp__browser__reload  Reload localhost:3000/checkout` (grey mono id + light description, no box); Edit card header `src/app/checkout/ShippingStep.tsx` with right-aligned `Edit +6 -2`, unified diff lines (one `-` red, four `+` green); `✻ Applying edit`; branch bar `checkout-shipping-cards  +6 -2  Create PR ⌄`; composer with a stop square; footer yellow `Auto mode` pill, `+`, `🎤 ⌄`, `Opus`, usage ring |
| `desktop-browser-11` | 30 s | Full window: two-column research rows (`boxline.test | filled the form, clicked Express → total reconciled instantly` …), short user bubble `Apply the pattern.`, six `mcp__browser__*` rows, the Edit card, `Reloaded.`, `✻ Filling form`, branch bar, composer, footer; browser pane with the ACME checkout |
| `cross-session-01` | 0 s | CLI, not Desktop: two terminal windows; `● Update(db/migrations/031_display_name.sql)` + `⎿ Added 9 lines, removed 2 lines`; `● Write(src/jobs/weeklyDigest.ts)` + `⎿ Wrote 96 lines to src/jobs/weeklyDigest.ts`; prompt line with a coloured session tag |

**Still images** (same download date; sizes are what the server returned, not necessarily the capture's native size):

| Folder | File | URL |
|--------|------|-----|
| `s1-vscode-docs/` | `vs-code-extension-interface.jpg` 2500×1155 | https://mintcdn.com/claude-code/-YhHHmtSxwr7W8gy/images/vs-code-extension-interface.jpg?fit=max&auto=format&n=-YhHHmtSxwr7W8gy&q=85&s=300652d5678c63905e6b0ea9e50835f8 |
| | `vs-code-send-prompt.png` 3288×1876 | https://mintcdn.com/claude-code/FVYz38sRY-VuoGHA/images/vs-code-send-prompt.png?fit=max&auto=format&n=FVYz38sRY-VuoGHA&q=85&s=ede3ed8d8d5f940e01c5de636d009cfd |
| | `vs-code-edits.png` 3292×1876 | https://mintcdn.com/claude-code/FVYz38sRY-VuoGHA/images/vs-code-edits.png?fit=max&auto=format&n=FVYz38sRY-VuoGHA&q=85&s=e005f9b41c541c5c7c59c082f7c4841c |
| `s6-xda/` | `claude-code-<name>.png` 1920×1080, seven files: `assembling-starter-pricing-wireframe` (first turn: `Created pricing.html, ran a command +146 -0 ›`, file card `pricing.html +146 -0 ›`, `Checking for node and headless browser options ›`, working row `13s · 109 tokens · Running tools...`), `branding-pro-card-as-popular` (`Edited a file, ran a command, read a file +6 -5 ›`, file card with `Undo`, footer icons + `just now`, lone `✻`), `coupling-toggle-to-real-prices` (expanded `Ran 2 commands ⌄` box with `$` command and raw output, a Markdown table), `discovering-built-in-browser-pane` (`Browse with Claude` empty pane), `exporting-launch-json-for-dev-server` (`Ran a command, created launch.json, used a tool +11 -0 ›`, fenced command block with run/copy icons, file card `launch.json +11 -0 ›`), `framing-hero-low-fi-wireframe` (`Found files, read 2 files, created a file, edited a file, used 4 tools +73 -1 ›`, `9 minutes ago`), `getting-responsive-widths-for-hero` (`Ran a command, used 4 tools ›`, `hero.html +73 -1 ›`) | `https://static0.xdaimages.com/wordpress/wp-content/uploads/wm/2026/09/claude-code-<name>.png?q=100&fit=contain&w=1920&h=1080&dpr=1` (the page itself embeds `?q=49&fit=contain&w=750&h=422&dpr=2`) |
| `s8-miraflow/` | `claude-code-redesign-interface.png` 2440×1242 | https://miraflow-cms.s3.us-west-2.amazonaws.com/claude-code-redesign-interface.png |
| `s10-ccfm/` | `claude-code-desktop-diff-approval.webp` 1600×1140 | https://claudecodeformarketers.com/_astro/claude-code-desktop-diff-approval.CWy_kjyo_gvDkq.webp |
| | `claude-code-desktop-tabs.webp` 1600×919 | https://claudecodeformarketers.com/_astro/claude-code-desktop-tabs.DIVN9aUH_Z1GN2Vp.webp |
| `s12-vgv/` | `parallel-agents-running.png` 1152×532, `side-chat.png` 798×548, `pr-controls.png` 492×376, `session-sidebar.png` 612×134, `context-usage-panel.png` 732×658 | `https://verygood.ventures/assets/images/blog/claude-code-desktop-hands-on-review/<file>` (page: https://verygood.ventures/blog/claude-code-desktop-hands-on-review) |

Not captured as files: S4/S5/S16 issue screenshots (signed GitHub URLs; the earlier scratch copies are not reproducible from a stable URL), S7 (VentureBeat), S9 (Substack) and S11 (aitoolsreview) images. Claims that rest only on those keep a bare source number.

---

## 2. Overall layout and visual language

### 2.1 Layout

- The chat is **one centred column of flat content**, no avatars, no names, no timestamps in the stream (Desktop) [seen S3 desktop-pop-out-01, desktop-auto-continue-01; S6]. Column width ≈ 700–800 px when a side pane is open, ≈ 770 px centred when none is [seen S6]. Side gutter ≈ 40 px from the window edge at 1x [seen S6]; in the narrow VS Code panel the gutter is ≈ 12–16 px [seen S1].
- Vertical order inside the column: transcript (scrolls) → optional branch/PR bar (`main ← claude/alignment-grid  +966 -55  Create PR`; Oct 2026 build: `#128  claude/tailwind-v4  +42 -7  ● CI ⌄  ×`, or `checkout-shipping-cards  +6 -2  Create PR ⌄`) [seen S8; S3 desktop-pop-out-07, desktop-browser-10] → composer box → composer footer row [seen S3 desktop-pop-out-01, desktop-auto-continue-04; S6].
- Pending cards (permission, question, plan approval, usage limit) are **docked at the bottom of the transcript, above the composer**, full column width; they are not modal [seen S9, S1, S5]. Desktop dims the transcript behind a pending permission card [seen S9]; VS Code does not dim since 2.1.47 [stated S13].
- VS Code adds a **dot rail**: every assistant step starts with an 8 px dot in a left gutter and content is indented ≈ 24 px [seen S1, S4]. Desktop has no rail [seen S3 desktop-pop-out-01, desktop-browser-11; S6].
- Desktop sidebar and panes are out of scope here (Tandem's sidebar exists already); only the chat column is specified.

### 2.2 Surfaces, borders, radius

| Role | Desktop dark | Desktop light | VS Code dark | Confidence |
|------|--------------|---------------|--------------|------------|
| Page / transcript background | `#1a1a19` (Sept 2026), `#0d0d0d` (April 2026) | `#ffffff` main, `#faf9f6` chrome | `#181818` (editor theme) | [inferred S6, S7, S9, S1] |
| User bubble | `#212121`, text `#d3d2d0` | light grey `~#f0eee6` | `#222222` | [inferred S6, S9, S1] |
| Cards (file card, Edited N files, permission, question) | `#151515`–`#1e1e1e`, 1 px border `#2d2d2d`, radius 6–8 px | white, 1 px `#e2e2e2`, radius 12 px, soft shadow | panel bg, 1 px border, radius 6–8 px | [inferred S6, S9, S5] |
| Composer box | `#20201f`, 1 px `#2d2d2d`, radius 8 px, ≈ 40–52 px tall | white card, radius 16 px, shadow | `#414141`-ish, radius 8 px, coral border when focused | [inferred S6, S9, S1] |
| Code block / command box | `#141414`, radius 6 px | `#f5f4ed` gutter, 1 px border | editor theme | [inferred S6, S9] |
| Inline code chip | `~#252323` bg, salmon text `~#e8846b`, radius 4 px | light grey bg, coral text | `#262626` bg, radius 3 px | [inferred S6; S3 desktop-pop-out-01; S1] |

Hairline borders only; no shadows in dark mode; nothing is outlined unless it is a card or the composer [seen S3 desktop-pop-out-01, desktop-auto-continue-04; S6].

### 2.3 Colour roles

| Role | Value | Confidence |
|------|-------|------------|
| Brand accent (spark glyph, send button, focused composer border, working-row dot) | coral `#d97757` (docs spark SVG renders this; sampled `#da7857`, `#cc7c5e`) | [inferred S1, S7, S9] |
| Primary text | dark: `#cbcbc8`–`#e8e6e3`; light: `#1f1f1f` | [inferred S6, S9] |
| Secondary text (summary rows, sub-rows, footer, timestamps, thinking rows) | dark: `#8a8a8a`–`#9a9a9a`; light: `#8a8880`; VS Code `#8b8b8b` | [inferred S6, S9; stated S17] |
| Diff added `+N` | green `~#5fb865` / `#7bc47f` (VS Code `#89d185`) | [inferred S6, S8; stated S17] |
| Diff removed `-N` | red `~#e5484d` / `#b32541` (VS Code `#f14c4c`) | [inferred S6, S8; stated S17] |
| Added-line row background | dark `~#1f3a24`, light `#e7f1da` | [inferred S10, S9] |
| Removed-line row background | dark `~#4a1f22`, light `#f9edec` | [inferred S10, S9] |
| Failed row text | red (same red as `-N`) | [seen S4] |
| Links | blue underlined `~#5a9bff` (dark); black underlined (light) | [seen S6, S9] |
| Permission card default button | VS Code: editor blue `#3376ce`; Desktop light: black `#1a1a1a` filled with white text; Desktop dark: white filled with dark text | [seen S1, S9; inferred S10] |
| Needs-input / session pill | yellow `~#e3b341` (dot, `session` pill, `Auto mode` pill) | [seen S4, S11; inferred S10] |
| Usage ring | thin blue arc `~#4a7cf7` on dark track, ≈ 14–16 px | [seen S6, S7] |

**Proposal for Tandem (theme-correct, not hex):** page = `JewelTheme.globalColors.panelBackground`; secondary text = `globalColors.text.info`; error/failed = `globalColors.text.error`; warning = `globalColors.text.warning`; user bubble and cards = a one-step surface from `JewelTheme.colorPalette.gray(n)` chosen by `JewelTheme.isDark` (today's `borders.disabled` is the wrong token); diff colours = the IDE editor scheme's `DiffColors` (`DIFF_INSERTED` / `DIFF_DELETED`) so they match the IDE diff viewer; links = `JewelTheme.linkStyle`; inline code and fences = the bridge `MarkdownStyling`. The coral `#d97757` is kept **only** for the spark glyph, which is the one brand element every screenshot shares. Proposal, not measured in the IDE.

### 2.4 Typography

- Body: humanist/geometric sans (claude.ai's UI face on Desktop, the editor's chat font in VS Code), ≈ 14 px at 1x on Windows, ≈ 15–16 px on macOS, line height ≈ 1.5 [inferred S6, S7]. VS Code: `chat.fontSize` default 13 px, line height 1.6 [stated S17].
- Headings in replies are bold body text, not larger [seen S6].
- Summary rows and sub-rows: same size as body (Desktop) or one step smaller (VS Code), secondary colour [seen S6, S1].
- Tool verbs (`Edit`, `Write`, `Read`, `Bash`) bold; file paths, commands, diff stats, MCP ids monospace ≈ 12.5–13 px [seen S8, S1, S11].
- Monospace elements get a chip background when inline (`Edit  src/lib/easing.ts  +6 ›`) [seen S8].
- No per-message timestamps in Desktop [seen S3 desktop-pop-out-01, desktop-auto-continue-01; S6]; VS Code shows times above messages and a date line when the day changes, default on since 2.1.290 [stated S13].

**Proposal for Tandem:** `JewelTheme.defaultTextStyle` for body, `JewelTheme.editorTextStyle` for monospace, `Typography.small`/`InfoText` for secondary rows.

### 2.5 Spacing (measured at 1x, Desktop dark, S6; VS Code S1)

| Gap | Value | Confidence |
|-----|-------|------------|
| User bubble → first summary row | ≈ 56 px | [seen S6] |
| Summary row → reply text | ≈ 22 px | [seen S6] |
| Paragraph gap inside a reply | ≈ 10–12 px | [seen S6] |
| List item gap | ≈ 6 px | [seen S6] |
| Between tool rows inside one group | ≈ 2–6 px (rows ≈ 24–28 px tall) | [inferred S8, S6] |
| Between groups / before a short interstitial sentence | ≈ 12–16 px | [inferred S8] |
| Reply → file card | ≈ 16 px | [seen S6] |
| Card padding | 12–16 px (Desktop dark), 24 px (Desktop light permission card) | [inferred S6, S9] |
| Composer box → footer row | ≈ 12 px; footer → window bottom ≈ 12 px | [seen S6] |
| VS Code: tool row → `└` sub-row | ≈ 4–6 px; between steps ≈ 12–16 px; before a new user bubble ≈ 24 px | [inferred S1] |

### 2.6 How it feels

Calm, flat, low contrast: the reply is the only thing in full-contrast text; everything the model *did* is a quiet grey line you can open; the only saturated colours are the coral spark, the green/red diff numbers and a red failure row [seen S3 desktop-pop-out-01, desktop-auto-continue-04; S4, S6]. Nothing moves except the spark and the streaming text. Rows do not have icons (Desktop) or have a single 8 px dot (VS Code). Cards are rare and reserved for things that need the user (permission, question, usage limit) or that summarise many files (`Edited 3 files`). Users who dislike the Desktop say the tool affordances are "dim grey on dark grey" [stated S4], which tells you how far it leans toward quiet.

---

## 3. Elements

### 3.1 User message

**Appearance.** A filled rounded rectangle, no avatar, no label, no timestamp [seen S3 desktop-pop-out-01, desktop-auto-continue-01; S6]. Desktop (current): **right-aligned**, right edge flush with the column, max width ≈ 80 % of the column, shorter prompts hug the text; background one step lighter than the page; radius ≈ 10–14 px; padding ≈ 12 × 14 px; text same size as the reply [seen S3 desktop-pop-out-01, desktop-auto-continue-01, desktop-browser-03, desktop-browser-11; S6, S8, S11]. The April 2026 macOS build used a left-aligned, full-width, blue-tinted bubble [seen S7]; it was replaced, so **right-aligned grey is the target**. VS Code: left-aligned, width fits content, radius ≈ 8–10 px, with a small grey context line under it listing attached context (`utils.py#2-3`, `localizationUtils.ts`) [seen S1].

**Attachments.** An image attached to the prompt shows as a ≈ 480 × 270 thumbnail with a 1 px border **above** the bubble (Desktop) [seen S6]. File pills in the text show the project-relative path on hover (VS Code) [stated S13].

**States.** Normal; *queued* (sent while Claude works): Desktop reads it after the current action; VS Code shows it in grey at the bottom until Claude starts on it, screen reader says `Message queued.` [stated S1, S13]. Hover (VS Code): rewind/fork button with `Fork conversation from here`, `Rewind code to here`, `Fork conversation and rewind code` [stated S1].

**Sketch (Desktop):**

```
                              ┌──────────────────────────────────────────┐
                              │ cart.spec.ts is flaky on CI — passes     │
                              │ locally, fails about 1 in 5 runs.        │
                              │ Find out why and fix it.                 │
                              └──────────────────────────────────────────┘
```

### 3.2 Assistant text and Markdown

**Appearance.** Plain Markdown flush with the column's left edge; no bubble, no label [seen S3 desktop-pop-out-01, desktop-pop-out-07; S6]. VS Code prefixes each text step with a grey 8 px dot [seen S1]. Streaming text appears incrementally; the last few words of a streaming paragraph render slightly faded (a tail fade) in Desktop [seen S3 desktop-pop-out-01 (`now wraps`), desktop-auto-continue-04 (`deleted,`)].

**Markdown support [seen S6; S3 desktop-pop-out-01, desktop-pop-out-07, desktop-auto-continue-01, desktop-browser-11; S1]:** bold; bullet lists with ≈ 20 px hanging indent and nested indentation; numbered lists; horizontal rules; **tables** with 1 px borders and a header-row background; links (blue, underlined; file paths become clickable and open the file pane/editor); inline code as a chip (mono, subtle background, coral/salmon text in Desktop); fenced code as a dark box, radius ≈ 6 px, mono ≈ 13 px, with a **copy** icon top-right and, for single-command blocks, a **run** (play) icon [seen S6]. LaTeX renders [stated S13]; Mermaid does not render in Desktop [stated S1-era issue]. GFM checkboxes `- [ ]` render as checkboxes (2.1.149) [stated S13].

**Interactions.** Hover a finished reply (Desktop): a row of four small grey icons under it (copy, share, pin, read-aloud) followed by a relative time `just now` / `9 minutes ago` [seen S6]. VS Code: `Copy response`, `Bookmark response` on hover [stated S1].

**Interstitial sentences.** A one-line sentence between tool groups (`Swapping the back-out curve for a hard-stop snap.`) is plain body text at the same margin as the rows [seen S8].

**Sketch:**

```
The spec clicks “Place order” before the cart request settles, so it races the
network. Plan:

• Wait on the `/api/cart` response instead of a fixed 500 ms sleep
• Add a small `retry()` helper around the payment stub so timing is deterministic
• Re-run the suite 20× to confirm the flake is gone
```

### 3.3 Thinking

**Desktop.** Thinking is hidden in the default (Normal) view. It shows only in the **Thinking** or **Verbose** view mode (session menu ▸ Transcript view, or Ctrl+O cycles); the Thinking entry appears in the menu only after thinking has occurred [stated S2]. Rendering in those modes: expanded while streaming, collapsed after a restart; an expanded block has a copy button that copies a Markdown blockquote headed `Thinking` [stated S16 #78593, #100521]. No Desktop screenshot of the block exists.

**VS Code** [seen S5]: a collapsed row with a grey dot and grey text:

- while streaming: `Thinking…` (sometimes a rotating label like `Imagining…`), with a live `· N tokens` counter [stated S13, S1];
- after the block completes: `Thought for 9s` / `Thought for 0s` (seconds, integer) [seen S5];
- click the row to expand the reasoning; Ctrl+O expands/collapses every thinking block [stated S1]; the row should carry a disclosure chevron (its absence was filed as a regression, #55120) [stated S16].
- Focus view: thinking-only folds read `Thought for Ns` and re-collapse when the turn completes [stated S13].
- Screen reader label: `Claude, thinking` [stated S1].
- Thinking toggle lives in the `/` menu and is hidden for models that always think [stated S13].

**Sketch (VS Code):**

```
●  Thought for 9s                                   ← grey dot, grey text, click to expand
```

**Target for Tandem (proposal):** VS Code's collapsed row, Desktop's default of showing it only when the user opts in (a "Show thinking" toggle in the session menu; default off, remembered per project). Elapsed seconds come from the host clock between `content_block_start` (thinking) and that block's `content_block_stop`. Note that on the recorded CLI the thinking *text* is empty (section 4.3), so the row may only ever show tokens and duration.

### 3.4 Tool call row (generic)

Two generations exist and both are "the desktop look"; the newer one is the target.

**Desktop Normal view (current, Sept–Oct 2026) [seen S3 desktop-auto-continue-01, desktop-auto-continue-04, desktop-pop-out-01, desktop-pop-out-07; S4, S6]:** one grey text line per *group* of tool activity, same font size as body, no icon, no border, ending with a small chevron `›`. Diff stats, when any file changed, sit before the chevron as `+N` green and `-N` red (zero side still shown as `-0` in Sept 2026 [seen S6], omitted in April 2026 [seen S8]). File names and commands inside the line are monospace. Row height ≈ 24 px. Clicking the row expands details below it (chevron turns `⌄`) [seen S6].

Exact strings seen:

- `Read cart.spec.ts, checkout.ts ›`
- `Read 38 files, searched for 6 patterns ›`
- `Edited billing/charge.ts +31 -40 ›` ; `Edited 61 files +904 -1130 ›` ; `Edited 14 files ›` ; `Edited http.ts +10 -3 ›`
- `Ran pnpm test ›` (command in mono) ; `Ran npx @tailwindcss/upgrade ›` ; `Ran 2 commands ›`
- `Used 2 tools ›` ; `Used 3 tools ›` ; `Ran a command, edited a file ›` ; `Ran a command, used 4 tools ›`
- `Created pricing.html, ran a command +146 -0 ›`
- `Found files, read 2 files, created a file, edited a file, used 4 tools +73 -1 ›`
- `Using plugin playwright playwright: browser evaluate ›`
- `Failed to check businesses/branch/causegroup OData queries ›` (whole row red)
- Running: `Reading weather.ts ›` [seen S7], `Checking for node and headless browser options ›` [seen S6], `Opening in Browser` (grey sub-row with a small coral dot, under the assistant sentence `Starting the dev server to look at it live.`) [seen S3 desktop-browser-03]. Present participle while running, past tense when done.

**Desktop April 2026 build [seen S8]:** one line per *call*: bold verb + mono path chip + stats + chevron: `Edit  src/components/AlignmentGrid.tsx  +142 -38 ›`, `Write  src/components/PanelGrid.tsx  +62 ›`; Bash shows its description instead of the command: `Lint ›`, `Typecheck ›`, `Build ›`, `Start the dev server ›`.

**Desktop early 2026 light build [seen S9]:** `› <natural-language description>` rows (`› Read launch.json configuration file for …`, `› Pushed code changes to GitHub main branch`), expanding to a tree with `└` connectors: `Preview Stop`, `Server … stopped`, `Edit  <abs path, middle-truncated>  +6 -6`.

**VS Code (Normal view) [seen S1, S4, S5]:** a row per call: status dot (green when finished, grey otherwise) + **bold tool name** + argument: `● Read  localizationUtils.ts`, `● Write  (src/test/localization.ts)`, `● Bash  Commit staged changes + push to origin` (Bash shows the description, grey). A result sub-row with an `L`/`└` glyph: `└ Read 256 lines`. Long paths truncate in the middle [stated S13]. Auto-mode skipped tools render as a dim `Not run` row [stated S13]. Rows carry `Waiting…` / `Running…` while input streams (2.1.300) [stated S13].

**States (all hosts):** *running* (present participle, no stats, VS Code dot grey / Desktop spinner row underneath), *done* (past tense, green dot in VS Code), *failed* (red row text in Desktop; red error box in VS Code), *denied / not run* (dim `Not run`), *expanded* (chevron `⌄`, body below).

**Sketch (Desktop, target):**

```
Read cart.spec.ts, checkout.ts ›
                                                     ← reply text follows
Edited billing/charge.ts +31 -40 ›
Ran pnpm test ›
Failed to check businesses/branch/causegroup OData queries ›     (red)
```

**Sketch (VS Code):**

```
●  Read  localizationUtils.ts
   └ Read 256 lines
●  Write  (src/test/localization.ts)
   ┌────────────────────────────────────────────┐
   │ 1 │ import { formatDate, … }     (green bg)│
   │ 2 │                                        │
   │ 3 │ describe('LocalizationUtil', () => {   │
   └────────────────────────────────────────────┘
```

### 3.5 Per-tool summaries and bodies

The host must build the summary from the tool input and its result (Desktop's natural-language groups are not sent over the wire; section 4). Rules below are the strings seen, plus the open-source hosts' conventions as fallback precedent [stated S20].

| Tool | Summary, running → done (Desktop) | VS Code row | Body when expanded |
|------|------------------------------------|-------------|--------------------|
| **Read** | `Reading <file> ›` → `Read <file> ›`; several: `Read <a>, <b> ›` or `Read N files ›` [seen S7; S3 desktop-pop-out-01 (`Read cart.spec.ts, checkout.ts ›`), desktop-auto-continue-01 (`Read 38 files, searched for 6 patterns ›`)] | `● Read  <file>` + `└ Read 256 lines` [seen S1]; with offset/limit CLI shows `Read(path):offset,end` [stated S20] | File content with line numbers, clamped (VS Code ≈ 60 px with fade [stated S17]); Desktop verbose rendering of Read unknown |
| **Edit** | `Editing <file>` → `Edited <file> +A -B ›`; several: `Edited N files +A -B ›` [seen S3 desktop-auto-continue-04 (`Edited 61 files +904 -1130 ›`), desktop-pop-out-07 (`Edited 14 files ›`, no stats)]; a file card list `Edited 3 files` with `View changes` button and rows `<icon> cart.spec.ts  +12 -4 ›` [seen S3 desktop-pop-out-01] | `● Write`/`Edit (path)` + inline diff box with line numbers, added lines green [seen S1]; subtitle `Modified` / `Added N lines` / `Added N lines, removed M lines` / `Removed N lines` [stated S17] | Desktop: header `Edited README.md +23 -16 ⌄`, then one dark card per hunk: grey file-path header, line-number gutter, `-` rows on dark-red background, `+` rows on dark-green, context rows plain; tall hunks clamp at ≈ 140 px with a bottom fade and a centred `⌄` expander [seen S10]. Verbose variant: card header `src/app/checkout/ShippingStep.tsx` with right-aligned `Edit +6 -2`, body = unified diff lines `-`/`+` in red/green text [seen S3 desktop-browser-10 (close-up), desktop-browser-11] |
| **Write** | `Created <file> +N -0 ›` [seen S6]; April build `Write  <path>  +62 ›` [seen S8] | `● Write  (path)` + green-highlighted new-file box [seen S1] | Same diff card with only `+` rows [seen S1]; CLI: `Wrote 96 lines to <path>` [seen S3 cross-session-01] |
| **Bash** | `Ran <command> ›` (mono) when one, `Ran N commands ›` when several [seen S3 desktop-auto-continue-04 (`Ran pnpm test ›`), desktop-pop-out-07 (`Ran npx @tailwindcss/upgrade ›`); S6]; a Bash whose `description` is short shows the description instead: `Lint ›`, `Start the dev server ›` [seen S8] | `● Bash  <description>` [seen S4, S5] | VS Code: a rounded box with a two-row layout, grey uppercase gutter labels `IN` (command, mono) and `OUT` (stdout, mono), copy button; long output clipped with a fade and openable in an editor tab [seen S4, S5]. Desktop: inside the expanded group, one sub-row per command with its own chevron; an opened command shows `$ <command>` in blue mono inside a darker box, then the raw stdout in light-grey mono; hundreds of lines show in full [seen S6]. ANSI stripped [stated S13]. Running shows a live elapsed counter on the row (CLI) [stated S13]. |
| **Grep** | folded into `searched for N patterns` [seen S3 desktop-auto-continue-01]; CLI `Searching for …` → `Searched for …` [stated S14] | VS Code shows a `Grep` row; no screenshot | CLI/Community: `Found N lines` / `Found N files`, file list collapsed [stated S20]; a search-group list capped at 3 visible rows then scrolls (jetbrains-cc-gui) [stated S20] |
| **Glob** | folded into `Found files` [seen S6] | same as Grep | `Found N files` + list [stated S20] |
| **WebSearch** | unknown; CLI/community `Web Search("query")` → `Did 1 search` [stated S20] | unknown | results as title + URL links [stated S20] |
| **WebFetch** | unknown; community `Fetch(url)` → `Received content from URL` with a domain pill [stated S20] | unknown | markdown of the fetched summary [stated S20] |
| **Task / Agent** | CLI row `<agent-name>(<short task description>)`, e.g. `code-improver(Suggest code improvements)`; running: `Task(Performing task X)`; done sub-line `⎿ Done (17 tool uses · 56.6k tokens · 1m 34.3s)` [stated S14, S13]. Desktop: subagents run in the background; `N running tasks` link in the spark row [seen S4]; Tasks pane card `Running Bash · 2m 8s · 170.0k tokens · 53 tool uses` with an `Agent` pill and `×` [seen S12]. VS Code: `N agents` footer pill with status dot → agent map; live progress rows under the group in Focus view [stated S1, S13] | nested: the subagent's own tool rows are not shown in the main transcript; its final text comes back as the Task result [stated S14] | Collapsed by default; expanding shows the subagent's tool timeline (community) [stated S20] |
| **TodoWrite / TaskCreate** | Desktop: separate **Tasks** pane, empty state `No tasks.` [seen S7]; Oct 2026 adds a live to-do list "that follows Claude's plan" [stated S13-era coverage]. VS Code: the latest to-do list stays visible even in Focus view [stated S1]. CLI: Ctrl+T list under the spinner, up to 5 items, pending / in-progress / completed marks; collapsed it shows the `activeForm` as spinner text plus `└ Next: <next task>` [stated S14]. No GUI screenshot of the inline widget. | — | Proposal: an inline checklist card from the newest `TodoWrite` (title = in-progress `activeForm` + `done/total`), older ones collapse to one line; TaskCreate/TaskUpdate fold into the same card by `taskId`. |
| **AskUserQuestion** | VS Code: a bold `● AskUserQuestion` row in the transcript, then the question card docked at the bottom [seen S5]; after answering, a `Questions` row lists each question with the picks [stated S13] | same | see 3.5.1 |
| **ExitPlanMode** | plan approval card (3.5.2) | same | plan Markdown |
| **MCP tools** | Desktop verbose: one line `mcp__browser__click  Select Express shipping` = tool id in grey mono + description in light text, no box [seen S3 desktop-browser-10 (close-up), desktop-browser-11]; Normal view folds them into `Used N tools ›` or `Using plugin <plugin> <server>: <tool> ›` [seen S4]; CLI collapses repeats to `Called slack 3 times` [stated S14]; permission prompts and sidebar name the tool as server + readable name, not `mcp__server__tool` [stated S2] | `mcp__server__tool` row; community `tool (server)` [stated S20] | request/response JSON, collapsed |
| **Unknown tool** | `Used N tools ›` [seen S4] | tool name bold + first descriptive input field | community: `INPUT` / `OUTPUT` uppercase sections with pretty JSON [stated S20] |

#### 3.5.1 AskUserQuestion card

**Appearance (VS Code, [seen S5]):** a dark rounded card (radius ≈ 8–12 px, padding ≈ 16 px) docked over the bottom of the transcript, replacing the composer while open. Top row: the question's `header` as a tab label underlined in blue (`Render test`) and a close `×` at the right; several questions = several tabs. Body: question text in semibold; options stacked, each a full-width row with a **bold label** line and a grey **description** line; the highlighted/selected row has a lighter background; an `Other` row with a free-text field (Shift+Enter for new lines) [stated S13]; a full-width button `1 Submit answers` (outlined until an answer is chosen, then filled blue; the `1` is the number key) and a footer `Esc to cancel`. Multi-select questions take several picks (checkboxes) [stated S15]. Option `preview` (markdown/HTML mockup) renders beside or under the options in newer builds [stated S13]. A fold button lets the transcript behind be read [stated S13].

**Desktop [stated S16 #99338]:** the same card "always opens expanded over the composer"; it has a chevron to collapse to a title row whose accessible label is `View question options`. Optional auto-continue timeout with a 20 s countdown when `askUserQuestionTimeout` is set; otherwise the question stays open until answered [stated S15].

**Sketch:**

```
┌────────────────────────────────────────────────────────────┐
│ Format      Sections                                     × │
│ ‾‾‾‾‾‾                                                     │
│ How should I format the output?                            │
│ ┌────────────────────────────────────────────────────────┐ │
│ │ Summary                                   (highlighted)│ │
│ │ Brief overview                                         │ │
│ └────────────────────────────────────────────────────────┘ │
│   Detailed                                                 │
│   Full explanation                                         │
│   Other                                                    │
│   [ free text …                                          ] │
│ ┌────────────────────────────────────────────────────────┐ │
│ │ 1  Submit answers                                      │ │
│ └────────────────────────────────────────────────────────┘ │
│ Esc to cancel                                              │
└────────────────────────────────────────────────────────────┘
```

**Keyboard:** number keys pick options (VS Code), Enter submits, Esc cancels (deny) [seen S5; stated S1].

**What is sent back:** see section 4.8. Short form: allow the `can_use_tool` request with `updatedInput = { questions: <original array unchanged>, answers: { "<question text>": "<label>" } }`; multi-select labels joined with `", "`; free text = the typed text itself, not `Other`.

#### 3.5.2 ExitPlanMode (plan approval)

No GUI screenshot exists; everything here is [stated S14, S13, S1].

- VS Code opens the plan as a full Markdown document in an editor tab (tab title = the plan's heading) where the user adds inline comments; the chat shows a **plan review card** with the comment list (scrollable), a reason field and a `Send feedback` button.
- Options, exact: `Yes, and use auto mode` (first when auto mode is available; reads `Yes, auto-accept edits` when it is not, or `Yes, and switch to BYPASS PERMISSIONS (no further prompts) for this session` when started with bypass), `Yes, manually approve edits`, `No, keep planning`. With `showClearContextOnPlanAccept` an extra first option approves and clears context.
- Approving exits plan mode into the mode the option names; `No, keep planning` with feedback sends the feedback to Claude, which revises and stays in plan mode.
- Desktop: the approval UI covers the composer entirely while it waits (#95473); a `Plan` pane shows the plan with empty state `No plan yet. Claude writes the plan here as it explores. Keep chatting.` [seen S7].
- Keyboard: Shift+Tab jumps to the auto-accept option; Ctrl+G opens the plan in an editor (CLI).

**Proposal for Tandem:** a card like the permission card, title `Approve this plan?`, body = the plan Markdown (clamped with a fade, `Open plan` link opens the plan file in the editor), buttons `Yes, auto-accept edits` (primary), `Yes, manually approve edits`, `No, keep planning` plus a feedback text field; wire shape in section 4.9.

### 3.6 Tool results

- **Collapsed by default** in Desktop: the summary row *is* the result; the chevron opens it [seen S6]. VS Code shows a one-line result sub-row (`└ Read 256 lines`) and, for Bash/Write/Edit, a body clamped to ≈ 60 px with a fade mask; click to expand (0.25 s max-height animation) [seen S1; stated S17].
- **Expanded** Bash (Desktop): command box then raw output in full; no "show more" seen [seen S6]. VS Code: IN/OUT box, long output fades and can be opened in an editor tab [seen S4; stated S1].
- **Error:** Desktop paints the whole summary row red: `Failed to check … ›` [seen S4]. VS Code/community: a red-bordered row labelled `Error` with a one-line preview, expandable [stated S20]. Denied tools: CLI result text `The user denied this command.` (recorded, `permission.ndjson:56`); community hosts show a distinct orange `Denied` badge by matching the strings `user denied tool use`, `tool disallowed by settings`, `permission request timed out`, `permission request cancelled` [stated S20]. Auto-mode skips: dim `Not run` [stated S13].
- **Images:** a Read of an image returns an image block; the tool result carries `tool_use_result.type = "image"` with base64 (section 4.5). No GUI screenshot of the rendered thumbnail; **proposal:** render inline as a thumbnail ≤ 480 px wide with a 1 px border, like the user-attachment thumbnail [seen S6], click opens the image in the IDE.
- Hidden results: `<system-reminder>` text inside Read results is stripped by every host [stated S20]; `(Bash completed with no output)` collapses to nothing.

### 3.7 Grouping of consecutive tool calls

Rules observed (Desktop Normal view and CLI) [seen S3 desktop-auto-continue-01, desktop-auto-continue-04, desktop-pop-out-01, desktop-pop-out-07; S4, S6; stated S14]:

1. Consecutive reads and searches merge into one row: `Read N files, searched for M patterns ›`, or `Read <a>, <b> ›` when ≤ 2–3 names fit. Running: `Reading 3 files…`. `ls`/`cat` Bash calls count as reads; `ls/tree/du` groups read `Listed N directories` (CLI).
2. Consecutive edits merge into `Edited N files +A -B ›`; a lone edit stays `Edited <file> +a -b ›`; in the latest Desktop build a turn that touched files also gets an `Edited N files` card listing each file with `+a -b ›` and a `View changes` button [seen S3 desktop-pop-out-01].
3. Consecutive shell runs merge into `Ran N commands ›` (`Ran 1 shell command` in the CLI); one run shows the command `Ran pnpm test ›`. Todo/task updates between commands do not split the group (CLI 2.1.234).
4. Mixed groups: `Used N tools ›`, or a sentence listing kinds in order: `Ran a command, edited a file ›`, `Found files, read 2 files, created a file, edited a file, used 4 tools +73 -1 ›` [seen S4, S6].
5. A group is any run of ≥ 2 consecutive tool calls not interrupted by assistant text; a text block closes the group [seen S8: `Swapping the back-out curve…` sits between groups]. Agent/Task blocks absorb every following call until the next text block (community precedent) [stated S20].
6. Expanding a group shows a bordered box with one sub-row per call, each with its own chevron [seen S6].
7. Failed calls are not merged silently: the failing call becomes its own red row [seen S4].
8. VS Code's Normal view does **not** group (each call its own row), which users file as noise (#55888, #71467); its Focus view folds the whole turn's activity into one expandable row with a live running-tool indicator [stated S1, S16].

**Proposal for Tandem:** implement rules 1–7 deterministically from tool names and results (counts, file names, `+a -b` from the edit result's `structuredPatch`); expose a "Verbose" toggle that renders one row per call like the April 2026 build.

### 3.8 Working indicator

**Where.** The last row of the transcript, under the latest content, left-aligned at the text margin (Desktop, VS Code) [seen S3 desktop-auto-continue-04, desktop-browser-10, desktop-pop-out-07; S1, S6]. The light early-2026 build put elapsed/tokens right-aligned on the same row [seen S9].

**Form.** The coral Claude **spark** (8-spoke asterisk `✻`, ≈ 12–16 px, `#d97757`) followed by grey text [seen S3 desktop-auto-continue-04, desktop-browser-10; S1, S7]. Variants seen:

| Build | Row text | Confidence |
|-------|----------|------------|
| Desktop Oct 2026 light/dark (docs videos) | `✻ Testing… · 3s`, `✻ Migrating… · 43m 11s`, `✻ Filling form`, `✻ Applying edit`, `✻ Writing summary…`, `Running npm test · 46/340 passed` | [seen S3 desktop-auto-continue-04 (`Testing… · 3s`), desktop-auto-continue-01 (`Migrating… · 43m 11s`), desktop-auto-continue-03 (`Resuming… · 0s`), desktop-browser-11 (`Filling form`), desktop-browser-10 (`Applying edit`), desktop-pop-out-07 (`Writing summary…`, `Running npm test · 46/340 passed`), desktop-pop-out-03 (`Running npm test · 0/340 passed` with a plain coral dot instead of the spark); S11] |
| Desktop June 2026 dark | `✻ 6m 25s · 35.4k tokens · 2 running tasks` (last item a blue link) | [seen S4] |
| Desktop Sept 2026 dark (Windows) | small coral dot + `13s · 109 tokens · Running tools...`; after the turn a lone `✻` under the footer icons | [seen S6] |
| Desktop April 2026 dark | `✻ 7s · ↓ 201 tokens`, `✻ 25s · ↓ 35.7k tokens`, `✻ 3m 22s · ↓160 tokens` (stays as the turn's footer when done) | [seen S7, S8] |
| Desktop early 2026 light | `· Clauding...` / `✱ Forging...` in coral at left, `11m 16s · ↓ 22.6k tokens` grey at right | [seen S9] |
| VS Code | `✻ Pondering...` | [seen S1] |
| CLI | `✻ Deciphering… (10m 27s · ↓ 5.5k tokens)`; with todos `(esc to interrupt · ctrl+t to show todos · 8s · ↓ 215 tokens)` + `└ Next: <task>` | [stated S14] |

**Target string (proposal, union of the seen parts):** `✻ <Verb>… · <elapsed> · <tokens> tokens` with the current activity (`Running tools…`, `Running npm test · 46/340 passed`, `2 running tasks`) appended with ` · ` when known. Elapsed: `Ns` under a minute, `Mm Ss` above (`3s`, `43m 11s`, `6m 25s`) [seen]. Tokens: `201 tokens`, `35.4k tokens` (one decimal, `k` suffix ≥ 1000; `1.5m` ≥ 1M per CLI) [seen; stated S14]; hidden until the first token arrives [stated S13].

**Verbs.** Picked at random from the CLI's built-in list, present participle + `…`; configurable with `spinnerVerbs` (`{mode: "append" | "replace", verbs: [...]}`) [stated S1, S14]. Verb-change cadence is **unknown** (old reverse-engineering: random every 1000 ms; tweakcc: "randomly selects one") [stated S18]. Community-compiled list (~190 words, unofficial) [stated S18]: Accomplishing, Actioning, Actualizing, Architecting, Baking, Beaming, Beboppin', Befuddling, Billowing, Blanching, Bloviating, Boogieing, Boondoggling, Booping, Bootstrapping, Brewing, Burrowing, Calculating, Canoodling, Caramelizing, Cascading, Catapulting, Cerebrating, Channelling, Choreographing, Churning, Clauding, Coalescing, Cogitating, Combobulating, Composing, Computing, Concocting, Considering, Contemplating, Cooking, Crafting, Creating, Crystallizing, Cultivating, Crunching, Deciphering, Deliberating, Determining, Dilly-dallying, Discombobulating, Doing, Doodling, Drizzling, Ebbing, Effecting, Elucidating, Embellishing, Enchanting, Envisioning, Evaporating, Fermenting, Fiddle-faddling, Finagling, Flambéing, Flibbertigibbeting, Flowing, Flummoxing, Fluttering, Forging, Forming, Frosting, Frolicking, Gallivanting, Galloping, Garnishing, Generating, Germinating, Gitifying, Grooving, Gusting, Harmonizing, Hashing, Hatching, Herding, Hibernating, Honking, Hullaballooing, Hyperspacing, Ideating, Imagining, Improvising, Incubating, Inferring, Infusing, Ionizing, Jitterbugging, Julienning, Kneading, Leavening, Levitating, Lollygagging, Manifesting, Marinating, Meandering, Metamorphosing, Misting, Moonwalking, Moseying, Mulling, Mustering, Musing, Nebulizing, Nesting, Noodling, Nucleating, Orbiting, Orchestrating, Osmosing, Perambulating, Percolating, Perusing, Philosophising, Photosynthesizing, Pollinating, Pontificating, Pondering, Pouncing, Precipitating, Prestidigitating, Processing, Proofing, Propagating, Puttering, Puzzling, Quantumizing, Razzle-dazzling, Razzmatazzing, Recombobulating, Reticulating, Roosting, Ruminating, Sautéing, Scampering, Scheming, Schlepping, Scurrying, Seasoning, Shenaniganing, Shimmying, Simmering, Skedaddling, Sketching, Slithering, Smooshing, Sock-hopping, Spelunking, Spinning, Sprouting, Stewing, Sublimating, Sussing, Swirling, Swooping, Symbioting, Synthesizing, Tempering, Thinking, Thundering, Tinkering, Tomfoolering, Topsy-turvying, Transfiguring, Transmuting, Twisting, Undulating, Unfurling, Unravelling, Vibing, Waddling, Wandering, Warping, Whatchamacalliting, Whirlpooling, Whirring, Whisking, Wibbling, Working, Wrangling, Zesting, Zigzagging. Documented examples in the official docs: Accomplishing, Architecting, Baking, Pondering [stated S1].

**Animation.** The glyph cycles `·` `✢` `✳` `✶` `✻` `✽` and back (grows, then shrinks), one step every 120 ms, in both CLI and VS Code [stated S18]; a shimmer highlight sweeps the text (50 ms step while a request is in flight, 200 ms otherwise, 3 chars wide) [stated S18]. Reduced-motion stops the animation and screen readers get a plain text label [stated S1, S13]. A newer Desktop build morphs the spark into a dot and then a rotating node graph; users dislike it (#99139) [stated S16]; do not copy that.

**Colour states.** Coral normally; CLI warms to amber after 10 s of thinking and turns red when an auto-mode check stalls; VS Code turns red with `Not responding` after 60 s without a backend response [stated S13, S18].

**Long-thinking phases (CLI, inline in the row):** `thinking` → `still thinking` → `thinking more` → `almost done thinking`; `deep in thought` after 45 s [stated S13].

**When it shows and hides.** Shows from the moment the prompt is sent (before any token) until the turn's `result`; it is the *last* row and is pushed down by streaming content. On completion the Desktop replaces it with a quiet footer (`✻ 7s · ↓ 201 tokens` in April 2026; icons + `just now` + lone `✻` in Sept 2026) [seen S7, S6]; the CLI prints `Worked for 5s` [stated S13]. On interrupt the CLI shows an `Interrupted` row with the hint `What should Claude do instead?` [stated S13]. During a permission wait the spinner keeps running in VS Code (the row stays) [inferred S1].

**Sketch:**

```
Ran pnpm test ›
All 214 call sites are on the v3 client, the shim is deleted,     (streaming, tail faded)

✻  Testing…  ·  3s  ·  1.2k tokens
```

### 3.9 Status and notice lines

Inline text rows in the transcript, secondary colour, no card unless noted [stated S13, S14; seen S3 desktop-auto-continue-03; S9]:

- Errors: `API Error: 500`, `529 Overloaded`, `Prompt is too long`, `Not logged in · Please run /login`, `API Error: No response from API` [stated S14].
- Retries, under the running row, one line per attempt: `529 Overloaded · Retrying in 20s · attempt 10/10`; older long form `API Error (529 {…}) · Retrying in 1 seconds… (attempt 1/10)`; since 2.1.198 the reason shows only from the second attempt, and a status-page hint replaces the spinner tip while overloaded; connection errors show the retry immediately; a stalled stream after 20 s: `Waiting for API response · will retry in … · check your network` [stated S13, S14].
- Mid-response failure keeps the partial text and adds a banner: `Server error mid-response. The response above may be incomplete.` (variants `Connection lost mid-response`, `Response stalled mid-stream`) [stated S14].
- Model switch: a centred grey line with wavy rules, `〰 Switched to claude-opus-4-6[1m] 〰` (VS Code) [seen S9]; model fallback chains show a notice when switching [stated S1].
- Usage limit: a bordered card `Usage limit reached` / `Auto-resuming at 3:40 PM` with `View details`, a checkbox `Auto-continue when limits reset`, right-aligned `Try again`; once resolved the card stays in the transcript dimmed [seen S3 desktop-auto-continue-02, desktop-auto-continue-03, desktop-auto-continue-04]; CLI two-liner `Usage limit reached · continuing automatically at 3:45pm · esc to cancel` [stated S14]; VS Code banner with a `Get more usage` link [stated S13].
- Compaction: collapsible `Compacted chat` card with the summary inside (VS Code) [stated S13]; community: 6 px dot + `Compacted` + `full summary` details [stated S20].
- Interrupt: `Interrupted` + `What should Claude do instead?` (CLI) [stated S13].
- Auto-mode skip: dim `Not run` row [stated S13]. Hook block reasons appear as text rows [stated S14].

**Proposal for Tandem:** keep these as transcript rows (not balloons) using Jewel `InlineWarningBanner` / `InlineErrorBanner` only for the usage-limit card and mid-response failure; plain secondary text for retries and model notices.

### 3.10 Permission prompt

**Desktop [seen S9 light; S10 dark; stated S2].** A card docked above the composer, full column width, radius 12 px (light) / 8 px (dark), padding ≈ 24 px (light) / 12–16 px (dark); transcript behind it dims. Contents, top to bottom:

1. Title, body size + 2 px: `Allow Claude to **Edit** launch.json?` / `Allow Claude to **Run** <full command>?` (verb bold, file name plain); dark build: a small yellow dot before `Allow Claude to edit README.md?` and a right-aligned yellow-on-dark pill `session`.
2. Grey one-line description for Bash (the tool's `description`, e.g. `Build and deploy to GitHub Pages`).
3. The payload: mono absolute path then a diff box (line numbers, `-` rows pale red, `+` rows pale green, horizontal scroll, no wrapping) for edits; a mono command box for Bash.
4. Buttons, right-aligned, ≈ 44 px tall, radius 8 px, key caps drawn as small grey keys: `Deny [Esc]` (outlined), `Always allow for session [⌘ ⏎]` (outlined; edits only; dark build reads `Always allow`), `Allow once [⏎]` (filled primary: black on light, white on dark; dark build shows `Allow once ⌘↩`).

Docs name the choices `Allow once`, `Always allow`, `Deny`; computer-use shows `Allow for this session` / `Deny` [stated S2]. A denied edit makes Claude ask how to proceed [stated S2].

**VS Code [seen S1].** Card with a 1 px blue outline (`focusBorder`), radius ≈ 8 px, padding ≈ 12 px, bottom of the transcript. Title bold: `Make this edit to utils.py?` (file name mono). Full-width stacked option rows ≈ 40 px tall, 8 px apart, radius 6 px, each prefixed with its number: `1 Yes` (filled blue = focused/default), `2 Yes, and don't ask again`, `3 No`, then a text field placeholder `Tell Claude what to do instead`. The proposed edit opens in an editor diff tab titled `✻ [Claude Code] utils.py` with added lines tinted green and per-hunk `Accept this change` / `Reject this change` (≤ 100 changes) [seen S1; stated S13]. Newer labels are descriptive (`Yes, allow npm for this project`) with a clickable save-destination (`this project` / `all projects` / `shared with team` / `session only`; Left/Right arrows change it); Bash prompts add `Yes, and switch to auto mode` when auto mode is available; a fold button collapses the card [stated S1, S13]. Tab/spark icon shows a blue dot while a permission waits, orange when a turn finished unseen [stated S1].

**CLI (strings the GUIs mirror) [seen S14 screenshot]:** title `Bash command`, description, dashed rules around the command, `This command requires approval`, `Do you want to proceed?`, `1. Yes`, `2. Yes, and don't ask again for: npm test *`, `3. Yes, and switch to auto mode · auto mode handles these prompts for you`, `4. No`, footer `Esc to cancel · Tab to amend`. WebFetch: `Yes`, `Yes, and don't ask again for <domain>`, `No, and tell Claude what to do differently`.

**Keyboard:** digits pick, Enter confirms the focused option, Esc denies, Tab opens a comment field (VS Code/CLI); Desktop `⌘↩` = always/for-session, `↩` = allow once [seen S9; stated S1, S14].

**Auto-accept.** Permission mode `acceptEdits` (`Accept edits`) auto-approves Edit/Write and safe file commands inside the working directory; `auto` and `bypassPermissions` approve more; mode is picked in the composer footer, not in the card [stated S14, S2]. A `don't ask again` choice persists a rule (section 4.7).

**Sketch (Desktop, target):**

```
┌──────────────────────────────────────────────────────────────────────┐
│ ●  Allow Claude to Edit launch.json?                       [session] │
│ .claude/launch.json                                                  │
│ ┌──────────────────────────────────────────────────────────────────┐ │
│ │ 1 │ -   {                                            (red row)   │ │
│ │ 2 │ -     "name": "iovis-dev",                                   │ │
│ │ 7 │ +   {                                            (green row) │ │
│ │ 8 │ +     "name": "iovis-dev",                                   │ │
│ └──────────────────────────────────────────────────────────────────┘ │
│                     [ Deny  Esc ] [ Always allow for session ⌘↩ ] [ Allow once ↩ ] │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.11 Composer

**Desktop [seen S3 desktop-pop-out-01, desktop-auto-continue-02, desktop-browser-10; S6, S7, S11].** A single rounded text field spanning the column (radius ≈ 8–12 px, 1 px border, ≈ 40–52 px tall when empty, grows with text). Placeholder `Type / for commands` inside a session; `Describe a task or ask a question` on the new-session screen; the light early-2026 build used `Reply...`. At the right end inside the field a small glyph: `↵` when idle, a stop square `□`/`⊙` while a turn runs (the Oct 2026 light build still showed `↵` while running, so the glyph is not consistent across builds). Suggested next prompt appears as grey ghost text; Tab or Right accepts it [stated S2].

Footer row **below** the field, outside it, 13 px grey:

- Left: `+` (attachments, skills, connectors, plugins, slash commands), mic icon with a `⌄`, then the **permission-mode label**: `Auto` (plain text, current), `Accept edits`, `Manual`, `Plan`, `Bypass permissions`; mid-2026 builds show a yellow-outlined pill `Auto mode`; older builds `Ask permissions`, `Auto accept edits` with a small icon [seen S3 desktop-pop-out-01 (`Auto`), desktop-pop-out-07 (`Accept edits`), desktop-browser-10 (yellow `Auto mode` pill); S6, S11, S8, S9; stated S2].
- Right: model name (`Opus 5`, `Sonnet 5.5`, `Opus 4.8`, `Sonnet 4.6 · Medium`), effort (`High`, `Max`, `Medium`), optional `Ultracode`, then a thin circular **usage ring** (≈ 14–16 px) whose tooltip reads `Context 20.1k / 200.0k (10%) · Plan 24%` [seen S3 desktop-pop-out-01 (`Opus 5  High`), desktop-pop-out-07 (`Fable 5.1  High`), desktop-auto-continue-02 (`Opus 5  Max`), desktop-browser-10 (`Opus`); S4, S6, S7].

**VS Code [seen S1].** The footer is **inside** the box: left = mode pill with a pencil icon (`Ask before editing`; current labels `Auto` / `Manual` / `Plan` / `Edit automatically` / `Bypass permissions`), current-file chip `📎 localizationUtils.ts` or `2 lines sel…` with an eye-slash/× control; right = `+`, `/`, and the action button: coral circle with a white `↑` (send) that becomes a square-in-a-box Stop while running. Placeholder `⌘ Esc to focus or unfocus Claude` (Mac) / `ctrl esc to focus or unfocus Claude` (Windows) when idle, `Queue another message...` while Claude works. Box border turns coral when focused. Model pill, context indicator, prompt-cache clock (red when expired), `N agents` pill also live in the footer [stated S1].

**Pickers.** Desktop: model dropdown and permission-mode selector next to the send button; effort is its own menu; shortcuts `⌘⇧M` mode, `⌘⇧I` model, `⌘⇧E` effort, `1–9` pick an item [stated S2]. VS Code: click the model pill or `/model`: one flat list of models with prices, an `Effort` row (slider low/medium/high/xhigh/max) and an `Ultracode` switch [stated S1]. Permission-mode names: `default` was renamed `Manual` everywhere; `Auto` is the starting mode since 2.1.283 [stated S14].

**Keys.** Enter sends; Shift+Enter newline; Esc stops the current turn (background agents keep running); Enter while busy queues; `/` opens the command menu; `@file` mentions; drag-and-drop attachments; Option/Alt+K inserts `@file#5-10` (VS Code) [stated S2, S1].

**Sketch (Desktop):**

```
┌────────────────────────────────────────────────────────────────────┐
│ Type / for commands                                              ↵ │
└────────────────────────────────────────────────────────────────────┘
 +   🎤 ⌄   Auto                                 Opus 5   High   ◯
```

### 3.12 Autoscroll

Documented rule (CLI fullscreen, which the GUIs follow) [stated S14, S13, S16]:

1. **Auto-follow** keeps the view at the bottom while output streams **only if the user is already at the bottom**.
2. Scrolling up pauses auto-follow; new output must not pull the view back. VS Code's 2.1.275 fix is titled exactly that: "Fixed the conversation pulling you back to the bottom while a reply streams after you scroll up".
3. A floating **`Jump to bottom`** affordance appears over the bottom edge while scrolled up and shows a count like `3 new messages` when output arrives; click it, press Ctrl+End or scroll to the bottom to resume following. Desktop's Cowork tab shows the same as a white circular `↓` button (≈ 48 px, shadow) centred over the bottom edge [seen S20-era Cowork screenshot]; community hosts put a 32 px round button ≈ 44 px above the composer [stated S20].
4. **On send**, scroll to the bottom once (`scrollToBottomOnSend`, default true) and do not stop short when the reply starts arriving during the scroll [stated S13].
5. Permission/question cards do **not** force-scroll and must not make the transcript creep down while content keeps arriving (2.1.275 fix) [stated S13]; users ask for an `input required` badge instead [stated S16 #72117].
6. When auto-follow is paused the view also stays put when the response finishes [stated S14].
7. Anti-pattern to avoid: a 50 px "near bottom" threshold that re-locks after a 10–40 px trackpad tick (#58767) [stated S16].

**Proposal for Tandem (algorithm, from the best community implementation [stated S20] adapted to Compose):** at-bottom = `scrollHeight - scrollTop - clientHeight < 100 px` (in `LazyListState`: last item visible with its bottom ≤ viewport end + 100 px). Treat user wheel/drag input as the only intent: wheel up → `paused = true` immediately; wheel down → un-pause only once at-bottom. Ignore scroll events caused by our own `scrollToItem` (set a flag, clear on the next frame). Re-pin on every content/size change (`LaunchedEffect` keyed on row count and tail length, plus composer height) only when `!paused`. On send: `animateScrollToItem(last)` regardless. Show the `↓` button when `paused && canScrollForward`, with a `N new` badge counting rows appended while paused; hide 1.5 s after the wheel stops when at bottom.

---

## 4. What the stream gives us, and what the host sends back

Evidence is `file:line` in `src/test/resources/fixtures/`; shapes without a fixture come from the SDK reference [stated S15] and must be pinned by a recording before the UI relies on them (hard rule 6).

### 4.1 Turn lifecycle (working indicator, footer)

- Host sends `{type:"user", message:{role:"user", content:[{type:"text", text}]}, parent_tool_use_id:null, uuid}` (`HostMessages.kt:200-214`); images as `{type:"image", source:{type:"base64", media_type, data}}` blocks in the same array (`docs/protocol.md` §3).
- CLI echoes the uuid: `command_lifecycle {command_uuid, state:"queued"}` then `"started"` (`permission.ndjson:5,7`), `system/init` (`:8`), `system/status {status:"requesting"}` (`:9`, again after each tool result `:26`), `stream_event message_start` with `ttft_ms` (`:10`; `permission-mode.ndjson:23` has `ttft_ms:3851`).
- End: `result {duration_ms, duration_api_ms, ttft_ms, num_turns, usage{output_tokens…}, modelUsage{<model>:{inputTokens, outputTokens, costUSD, contextWindow}}, total_cost_usd, terminal_reason:"completed", permission_denials:[]}` (`permission.ndjson:35`), `command_lifecycle "completed"` (`:36`), `system/task_summary {detail:null}` (`:37`). Undocumented `system/post_turn_summary {status_category:"completed"|"blocked"|…}` (`:34`, `:65`).
- Per API message: `message_delta.usage {output_tokens, output_tokens_details:{thinking_tokens}}` (`permission.ndjson:22`). **There is no server-side elapsed clock mid-turn**: the host runs its own timer from send to `result`, and sums `message_delta.usage.output_tokens` (plus `thinking_tokens` estimates) for the live token count.
- Running activity text: `system/task_summary {detail:"Creating empty fixture file"}` while a tool runs (`:24`) and `{detail:null}` when done (`:37`), undocumented; `tool_progress {tool_use_id, tool_name, elapsed_time_seconds, heartbeat:true}` every 30 s for a long main-thread tool (SDK, no fixture).
- Today: `Ndjson.kt:41-78` types only `init`, `status`, `api_retry`, `model_refusal_*`; everything else is `CliMessage.Unknown` and `Reducer.kt:140` drops it; `decodeResult` (`Ndjson.kt:165`) keeps no durations or usage; `MessageDelta` is ignored (`Reducer.kt:260`).

### 4.2 Assistant text

Order for one block: `message_start` → `content_block_start {type:"text"}` → `text_delta` frames → complete `assistant` line (same `message.id`) → `content_block_stop` (after the assistant line) → `message_delta {stop_reason:"end_turn"}` → `message_stop` (`plain-reply.ndjson:7-13`). Reducer builds a draft on `message_start`, appends deltas, replaces block N with the Nth complete line (`Reducer.kt:184-196, 224-258`). A cut tool call gets no `content_block_stop`; treat its input as incomplete when `message_stop` arrives with the block open [stated S15].

### 4.3 Thinking

`content_block_start {type:"thinking", thinking:"", signature:""}` (`permission-mode.ndjson:24`), `thinking_delta` frames carrying `estimated_tokens` (`:26-27`), `signature_delta` (`:29`), complete assistant line with the thinking block (`:30`), `content_block_stop` (`:31`), then the text block at index 1 (`:32-35`). `message_start` carries `thinking_display:"updates"` (`:23`). Interleaved `system/thinking_tokens {estimated_tokens:50, estimated_tokens_delta:50}` (`:25`), then `312` (`:28`). **On the recorded CLI (2.1.301, Sonnet 5.5) every thinking text is empty**: only tokens and a signature arrive, so the row can show `Thinking… · 312 tokens` and `Thought for Ns` but no prose. The SDK's `ThinkingConfig.display = "summarized"` would return summaries; the stdio flag for it is unknown (`set_max_thinking_tokens` has a `thinking_display` field, `docs/protocol.md` §4.4). Today the reducer keeps `AssistantBlock.Thinking(text)` (`ChatState.kt:104`, `Reducer.kt:249`) and the UI drops it (`ChatTranscript.kt:40`); `thinking_tokens` is Unknown.

### 4.4 Tool call rows

`content_block_start {type:"tool_use", id, name:"Bash", input:{}}` (`permission.ndjson:11`) → `input_json_delta` fragments of `{"command": "touch tandem-fixture.txt", "description": "Create empty fixture file"}` (`:12-18`) → complete assistant line with the full `input` and a top-level `wire_tool_inputs {<id>: input}` (`:19`) → `content_block_stop` (`:20`). The row can be drawn from `content_block_start` (name known) and filled as JSON streams; a running row needs no result. Today `Reducer.kt:250` accumulates `partialInput`, `Reducer.kt:339` sets `input`, and the UI shows only `**Name** \`command\` \`file_path\`` (`ChatTranscript.kt:41-45`), never `partialInput`.

Tool names on the wire: `Agent` (older `Task`, and `init.tools` still lists `Task`; match both), `AskUserQuestion`, `Bash`, `Edit`, `EnterPlanMode`, `ExitPlanMode`, `Glob`, `Grep`, `LSP`, `Monitor`, `NotebookEdit`, `PowerShell`, `Read`, `Skill`, `TaskCreate/Get/List/Update`, `TaskStop`, `TodoWrite`, `ToolSearch`, `WebFetch`, `WebSearch`, `Write`, `Workflow`, `mcp__<server>__<tool>` [stated S15]. Input fields (verbatim): Bash `{command, timeout?, description?, run_in_background?}`; Read `{file_path, offset?, limit?}`; Edit `{file_path, old_string, new_string, replace_all?}`; Write `{file_path, content}`; Glob `{pattern, path?}`; Grep `{pattern, path?, glob?, type?, output_mode?, -n?, -A?, -B?, -C?, head_limit?}`; WebFetch `{url, prompt}`; WebSearch `{query, allowed_domains?, blocked_domains?}`; Agent `{description, prompt, subagent_type?, model?, run_in_background?, name?}`; TodoWrite `{todos:[{content, status:"pending"|"in_progress"|"completed", activeForm}]}`; TaskCreate `{subject, description, activeForm?}`; TaskUpdate `{taskId, status?, activeForm?, …}`; ExitPlanMode `{}` (plan injected later); AskUserQuestion `{questions:[{question, header, options:[{label, description, preview?}], multiSelect}]}` [stated S15]. Note: Tandem passes `--disallowedTools Edit,Write,MultiEdit,NotebookEdit` (`ClaudeSession.kt:77`), so edit rows will mostly come from the planned `mcp__tandem__*` tools (hard rule 4); the row renderer must key on both names.

### 4.5 Tool results and bodies

`user` line `{message:{content:[{tool_use_id, type:"tool_result", content:"(Bash completed with no output)", is_error:false}]}, tool_use_result:{stdout:"", stderr:"", interrupted:false, isImage:false, noOutputExpected:true}, tool_result_meta:[{id, permission_decision:{decision:"accept", source:"user_temporary"}}]}` (`permission.ndjson:25`); a denied call: `content:"The user denied this command."` with `is_error` (`:56`). Structured outputs in `tool_use_result` [stated S15]: Bash `{stdout, stderr, interrupted, backgroundTaskId?, timedOutAfterMs?}`; Edit `{filePath, oldString, newString, originalFile, structuredPatch:[{oldStart, oldLines, newStart, newLines, lines[]}], gitDiff?{additions, deletions, patch}}` (gives `+a -b` and the hunk cards); Write `{type:"create"|"update", filePath, content, structuredPatch}`; Read `type:"text" {file:{filePath, content, numLines, startLine, totalLines}}` | `"image" {file:{base64, type, dimensions?}}` | `"pdf"` | `"notebook"`; Glob `{numFiles, filenames[], truncated}`; Grep `{numFiles, filenames[], content?, numLines?, numMatches?}`; WebFetch `{url, code, result, durationMs}`; WebSearch `{query, results:[{content:[{title,url}]}|string], durationSeconds}`; TodoWrite `{oldTodos, newTodos}`; TaskCreate `{task:{id, subject}}`; Agent `status:"completed" {totalToolUseCount, totalDurationMs, totalTokens, toolStats{readCount, searchCount, bashCount, editFileCount, linesAdded, linesRemoved}}` | `"async_launched" {agentId, description, outputFile}`. Today `Ndjson.decodeUser` (`Ndjson.kt:97`) keeps only `content`, and `toolResultText` (`Reducer.kt:344`) joins the text members, so image blocks and every structured field are lost; `ChatEntry.ToolResult` holds `(toolUseId, text, isError)` (`ChatState.kt:93`). Transcript files mirror this with camelCase `toolUseResult` (`transcripts/history-permission.jsonl:19`).

### 4.6 Grouping, subagents, todos

- Grouping needs only the ordered list of `tool_use` blocks and their results; the host computes the summary.
- Subagents: parent `tool_use` named `Agent`/`Task`; lines from inside the subagent carry `parent_tool_use_id` (and `agent_id` on SDK ≥ 0.3.292); their token deltas are not forwarded; their complete text/thinking lines are omitted unless the host sets `forwardSubagentText:true` in `initialize` (Tandem sends `{hooks:null}`, `HostMessages.kt:216`). Lifecycle: `system/task_started {task_id, tool_use_id?, description, task_type}`, `task_progress {usage:{total_tokens, tool_uses, duration_ms}, last_tool_name?, summary?}`, `task_updated {patch:{status}}`, `task_notification {status, summary}`, `background_tasks_changed {tasks[]}` (replace the cached set) [stated S15]; all Unknown today. Reducer keys subagent lines as separate `ChatEntry.Assistant` rows with `parentToolUseId` (`Reducer.kt:350`), rendered flat.
- Todos: ordinary `tool_use` blocks (`TodoWrite`, `TaskCreate/Update`), repaired key names are not reflected in the stream; available by default only on Opus 4.7 / Sonnet 4.6 / Haiku 4.5 and earlier, else `CLAUDE_CODE_ENABLE_TODO_TOOLS=1`; check `system/init.tools` [stated S15]. No fixture.

### 4.7 Permission prompt

Request: `control_request {request_id, request:{subtype:"can_use_tool", tool_name:"Bash", display_name:"Bash", input:{command, description}, description:"Create empty fixture file", permission_suggestions:[{type:"addRules", rules:[{toolName:"Bash", ruleContent:"touch tandem-fixture.txt"}], behavior:"allow", destination:"localSettings"}, {type:"addDirectories", directories:[…], destination:"session"}, {type:"setMode", mode:"acceptEdits", destination:"session"}], blocked_path, tool_use_id}}` (`permission.ndjson:21`), arriving **after** the complete `tool_use` line whose id equals `tool_use_id`, so the card can be attached to the already-drawn row. Extra typed fields (`docs/protocol.md` §4.3): `decision_reason`, `decision_reason_type`, `suppress_always_allow_rule`, `default_to_no`, `requires_user_interaction`, `title`, `agent_id`, `mcp_server {name, source}`. `Ndjson.kt:188-206` keeps `tool_name, input, tool_use_id, permission_suggestions, blocked_path, title, display_name, description`.

Answers (`HostMessages.kt:233-247`): allow `{type:"control_response", response:{subtype:"success", request_id, response:{behavior:"allow", updatedInput:<input>, updatedPermissions?:[…], toolUseID}}}`; deny `{… response:{behavior:"deny", message, interrupt?:true, toolUseID}}`. `updatedInput` is required before CLI 2.1.207. "Don't ask again" = echo the `addRules` suggestion in `updatedPermissions` (destination `localSettings` writes `.claude/settings.local.json`, `session` keeps it in memory). **Bug risk today:** `ClaudeSession.allow(always=true)` (`ClaudeSession.kt:229-230, 357`) echoes *every* suggestion with destination `session`, including `setMode acceptEdits` and `addDirectories`, so one click can switch the live session mode; echo only `addRules` for "always allow" [inferred from code + SDK statement]. Cancel: `control_cancel_request` removes the pending card (`Reducer.kt:130`). Mode switch acknowledgements: `control_response {response:{mode:"plan"}}` + `system/status {permissionMode:"plan"}` (`permission-mode.ndjson:17-18, 40-41`); rejection `control_response subtype:"error"` with `error_code` (`permission-mode-rejected.ndjson:17`). Auto-denials without a prompt: `system/permission_denied {tool_name, tool_use_id, message}` (Unknown today).

### 4.8 AskUserQuestion

Arrives as a `can_use_tool` request with `tool_name:"AskUserQuestion"` and `input.questions` (see 4.4) — it always reaches the host even in acceptEdits, except in `dontAsk` where it is denied [stated S15]. Today it lands in the generic `PendingPermission` path (`Reducer.kt:118`) and the UI shows `Allow AskUserQuestion?` with 300 chars of raw JSON (`ChatScreen.kt:68`), which is the owner's complaint.

Exact answer to send (verbatim shape from the SDK docs, keyed by question **text**, value = option **label**) [stated S15]:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id>","response":{
  "behavior":"allow",
  "updatedInput":{
    "questions":[ …the original questions array, unchanged… ],
    "answers":{
      "How should I format the output?":"Summary",
      "Which sections should I include?":"Introduction, Conclusion"
    }
  },
  "toolUseID":"<tool_use_id>"
}}}
```

Multi-select: join labels with `", "` (the TS example) or pass an array (Python example). Free text: the typed text is the value, not `Other`. An optional top-level `updatedInput.response` string is a free-form reply instead of answers (Claude then sees `The user responded: …`). `behavior:"deny"` with `message` cancels the question. The tool then returns `tool_use_result {questions, answers, response?}` and a `tool_result` text block. No fixture; record one.

### 4.9 ExitPlanMode

A `tool_use` named `ExitPlanMode` with empty input, then a `can_use_tool` request (it needs permission). Hooks see injected `plan` and `planFilePath`; whether the stdio `can_use_tool.input` carries them is **unknown** (a third-party host reads the newest `.md` written under `~/.claude/plans/` instead, promoted once that Write's result succeeds) [stated S15]. Approve = allow with `updatedInput:<input>` and `updatedPermissions:[{type:"setMode", mode:"acceptEdits"|"default"|"bypassPermissions", destination:"session"}]` (how the VS Code extension and another host do it; not Anthropic docs); `No, keep planning` with feedback = deny with `message` = the feedback; never persist an always-allow rule for `ExitPlanMode` [stated S15-adjacent PRs]. The CLI should confirm with `system/status {permissionMode}`. Risk: plan mode writes the plan file with `Write`, which Tandem disallows; unmeasured. Needs a `--permission-mode plan` fixture.

### 4.10 Notices and errors already typed

`system/api_retry {attempt, max_retries, retry_delay_ms, error_status, error}` → warning notice (`Reducer.kt:94`); `model_refusal_fallback` → retraction + notice (`:105`); `model_refusal_no_fallback` (`:111`); `assistant.error` with `model:"<synthetic>"`, `error:"model_not_found"`, `api_error_status:404` (`invalid-model.ndjson:4`) → error notice; `result is_error` with `terminal_reason:"api_error"` (`:5`); interrupt = `control_response {still_queued:[]}`, `assistant aborted:true`, user text `[Request interrupted by user]` → `Stopped.` (`interrupt.ndjson:13-19`, `Reducer.kt:214`). Not yet surfaced: `rate_limit_event {rate_limit_info:{status, unifiedWindows:{five_hour:{utilization, resetsAt}, seven_day:{…}}}}` (`plain-reply.ndjson:6`; enough for the usage ring), `system/informational {content, level}`, `system/notification {text, priority}`, `system/compact_boundary`, `conversation_reset`, `tool_progress.subagent_retry`, `result.errors[]`, the replayed `<local-command-stdout>Set model to …</local-command-stdout>` user line (`model-switch.ndjson:20`, skipped by `Reducer.kt:207`). `system/session_state_changed {state:"idle"|"running"|"requires_action"}` needs an env flag whose name differs between the SDK docs (`CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`) and `docs/protocol.md` §1.2 (`CLAUDE_CODE_SDK_READS_SESSION_STATE=1`).

### 4.11 Composer data

`initialize` response (`fork.ndjson:2`) lists `models:[{value:"default", resolvedModel:"claude-sonnet-5-5", displayName:"Default (recommended)", description:"Sonnet 5.5 · Efficient for routine tasks", supportsEffort:true, supportedEffortLevels:["low","medium","high","xhigh","max"], supportsAdaptiveThinking, supportsAutoMode, supportsThinkingOff, supportsFastMode?}, …]` plus `claude_code_version`, `current_permission_mode`, `capabilities`, `pending_permission_requests`. Reducer keeps `value/displayName/description` only (`Reducer.kt:353-362`). Context usage for the ring: `modelUsage.<model>.contextWindow` from `result` plus `message_delta.usage.input_tokens`/`cache_read_input_tokens` [inferred]; plan usage from `rate_limit_event.unifiedWindows`.

---

## 5. Gap table: element → Tandem today → target → size

| Element | Tandem today (verified in code) | Target | Size |
|---------|-------------------------------|--------|------|
| Row model | One flat `List<TranscriptEntry(role, markdown)>`; everything flattened to Markdown (`ChatTranscript.kt`); labels `You` / `Claude ▍` / `Tool result` / `Tandem` (`ChatPanel.kt:70-73`) | Structured rows: `User`, `Text`, `Thinking`, `ToolGroup(calls, summary, expanded)`, `ToolCall`, `Card(FilesEdited/UsageLimit)`, `Working`, `Notice`, `PendingCard`; no labels | **L** |
| User message | Full-width grey box, 6 dp radius, label `You` (`ChatPanel.kt:66-70`) | Right-aligned bubble ≤ 80 % width, radius 10–12 dp, no label; attachment thumbnail above | S |
| Assistant text | Markdown via Jewel bridge, CommonMark + autolink only; whole message re-parsed per delta (`ChatRows.kt`) | Same renderer plus `gfmTables` (and strikethrough/alerts) extensions; copy button on fences; file-path links open the editor; tail fade while streaming; hover actions | M |
| Thinking | Dropped (`ChatTranscript.kt:40`) | Collapsed `Thinking… · N tokens` / `Thought for Ns` row, off by default behind a toggle; duration from host clock; `thinking_tokens` decoded | M |
| Tool call row | `**Name** \`command\` \`file_path\`` text only; no state, no partial input (`ChatTranscript.kt:41-45`) | Grey summary row with chevron, running/done/failed states, per-tool label rules (3.5), fills while JSON streams | **L** |
| Tool result | Separate `Tool result` row, 12-line fence (`ChatTranscript.kt:20,36`) | Joined to its call by `toolUseId`; collapsed by default; typed bodies (Bash command+output, Read with line numbers, diff cards from `structuredPatch`, image thumbnails); `tool_use_result` decoded | **L** |
| Grouping | None | Rules 1–7 of 3.7 with expandable box; verbose toggle | M |
| Edit/Write diff | Not rendered (tools disabled; MCP tools pending) | Diff card (hunks, red/green rows, clamp + expander) for `Edit`/`Write`/`mcp__tandem__*`; `Edited N files` card with `View changes` → IDE diff | M (depends on Phase 3) |
| Working indicator | `working…` in the header (`SessionHeader.kt:180`), `Claude ▍` label, Stop button | Last-row `✻ <Verb>… · <elapsed> · <tokens> tokens`, spark glyph cycle at 120 ms, verb list, amber/red states, replaced by a footer on completion; host timer + usage sums | M |
| Notices | Markdown rows labelled `Tandem` with `⚠`/`**Error:**` (`ChatTranscript.kt:28-29`); hard-coded red bar `Color(0xFFB3261E)` (`SessionHeader.kt:173`) | Secondary-text rows; Jewel inline banners for usage limit / mid-response failure; theme colours; retry string format | S |
| Permission prompt | `Allow <tool>?` + 300 chars raw JSON + `Allow once` / `Allow for this session` / `Deny`, below the list (`ChatScreen.kt:60-72`); "always" echoes every suggestion incl. `setMode` (`ClaudeSession.kt:229-230`) | Card docked above the composer: title `Allow Claude to Edit <file>?` / `Allow Claude to Run <cmd>?`, description, mono payload or diff, buttons `Deny [Esc]` / `Always allow for session [⌘↩]` / `Allow once [↩]`, digit/Enter/Esc keys, dims transcript; echo only `addRules`; `Not run`/denied states | M |
| AskUserQuestion | Generic permission prompt with JSON; allow does not answer | Question card (3.5.1) with tabs per `header`, radio/checkbox rows, `Other` field, `Submit answers`, `Esc to cancel`; sends `updatedInput.answers`; `Questions` row afterwards | M |
| ExitPlanMode | Generic permission prompt | Plan approval card with the three options and feedback field; `setMode` in `updatedPermissions`; plan file opened in the editor | M (blocked on fixture) |
| Todo list | Nothing | Inline checklist card (newest expanded) or sidebar panel; only if `init.tools` has the tools | S–M |
| Subagents | Flat `Claude` rows with `parentToolUseId` | Nested under the `Agent` row, collapsed; `Done (N tool uses · tokens · time)` sub-line from `AgentOutput`; `task_*` decoded for live progress | M |
| Composer | `TextArea` + `Send`/`Stop` buttons; pickers row with `ListComboBox`es and `effort: x` badge (`PromptBar.kt`); no placeholder, no Enter-to-send | Rounded field with placeholder `Type / for commands`, `↵`/stop glyph inside, Enter sends / Shift+Enter newline / Esc stops; footer row: mode label + model · effort as `DropdownLink`s + usage ring; queued-prompt display | M |
| Autoscroll | Unconditional `scrollToItem(last)` on every delta (`ChatPanel.kt:41-42`) | Stick-to-bottom only when at bottom (3.12), wheel-up pauses, scroll on send, `↓ N new` button, cards never force-scroll | S–M |
| Scrollbar | Plain `LazyColumn`, no IDE scrollbar | `VerticallyScrollableContainer(listState)` | S |
| Protocol decoding | `Unknown` for `thinking_tokens`, `task_*`, `tool_progress`, `rate_limit_event`, `permission_denied`, `informational`, `compact_boundary`, `session_state_changed`; `tool_use_result`, `message_delta.usage`, `result` durations dropped | Type each with a fixture test (hard rule 6) | M |

---

## 6. Open questions

Each with the best guess and how to verify.

1. **Exact Desktop hex colours, font family and pixel metrics.** Best guess: section 2 values. Verify: ask the owner for a 2x screenshot of a dark and a light session with one tool group expanded, and sample it; or accept the theme-token proposal and compare side by side in `runIde`.
2. **Desktop grouping thresholds** (when `Read <a>, <b>` becomes `Read N files`; when per-file `Edited <file>` becomes `Edited N files`). Best guess: list names while ≤ 2 and the row fits one line, else count; per-file row for a single edit, card list when ≥ 2 files changed in the turn. Verify: owner screenshot of a turn with 3 reads and of a turn with 2 edits.
3. **Working-row composition in the current Desktop build** (verb + elapsed only, or verb + elapsed + tokens + activity). Best guess: `✻ <Verb>… · <elapsed>` with tokens shown once > 0 (union of S3 desktop-auto-continue-04 and S4). Verify: owner screenshot mid-turn.
4. **Verb rotation interval and whether Desktop rotates verbs at all** (S3 desktop-auto-continue-01 and -04 show task-specific words like `Migrating…`, `Testing…`, which look generated, not random). Best guess: random verb from the list every ~1–2 s, replaced by `task_summary.detail` when present. Verify: 10 s screen recording from the owner; check `task_summary` lines in a new fixture.
5. **Thinking text over stdio.** The recorded blocks are empty. Best guess: `set_max_thinking_tokens` with `thinking_display:"summarized"` or a `--thinking` flag returns summaries. Verify: record a fixture against CLI 2.1.30x with each option (`scripts/record-fixture.sh`).
6. **Desktop AskUserQuestion and plan-approval card visuals.** Best guess: VS Code's card (3.5.1) with Desktop's button style (3.10); plan card as proposed in 3.5.2. Verify: owner screenshots of each; record `AskUserQuestion` and `--permission-mode plan` fixtures for the wire shapes.
7. **Does `can_use_tool.input` for `ExitPlanMode` carry `plan`/`planFilePath`, and does plan mode work with `Write` disallowed?** Best guess: no plan in input (read the newest file in the plans directory); plan mode likely breaks until `Write` is allowed for the plans directory or served by the IDE MCP tool. Verify: the plan-mode fixture above.
8. **Does echoing `setMode` in `updatedPermissions` switch the live session and emit `system/status {permissionMode}`?** Best guess: yes (SDK says echoed suggestions are applied; `set_permission_mode` already emits the status line). Verify: same fixture; also confirms the "Allow for this session" bug in 4.7.
9. **TodoWrite/TaskCreate availability on the owner's model and the GUI widget look.** Best guess: absent on Sonnet 5.5 unless opted in; render an inline checklist. Verify: `system/init.tools` of a real session; owner screenshot of Desktop's to-do list (Oct 2026 build).
10. **Desktop autoscroll specifics and the `↓` button look in the Code tab.** Best guess: VS Code's 2.1.275 behaviour with a Cowork-style floating `↓`. Verify: owner scrolls up during a stream and reports; screenshot of the button.
11. **Image tool results in the GUI** (thumbnail size, click behaviour). Best guess: inline thumbnail ≤ 480 px, click opens in the IDE. Verify: record a `Read` of a PNG fixture; owner screenshot.
12. **Verbose-view rendering of Read/Grep in Desktop** (only MCP and Edit rows were seen). Best guess: `Read  <path>` / `Grep  <pattern>` rows in the same mono-id + description style as MCP rows. Verify: owner screenshot in Verbose mode.
13. **Spinner glyph timing feel in Compose** (does a 120 ms six-glyph cycle in a Jewel `Text` look like the app, or is a rotating painter closer?). Best guess: text glyph cycle, since the VS Code webview does exactly that. Verify: `runIde` under a human eye; agents cannot judge motion.
14. **Which env var enables `session_state_changed`.** Best guess: `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` (newer docs). Verify: set each in `CliEnvironment` and look for the subtype in a recording.
