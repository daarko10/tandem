# Chat Redesign Slice B: Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The permission, question and plan approval cards docked above the composer, each answering the CLI with the exact `control_response` of spec 5.2 through `SessionManager.answer`, with the keyboard map of spec 4.18 and the IDE tests of 10.6.

**Architecture:** Slice A already types the first pending `can_use_tool` request as a `PermissionCard`, `QuestionCard` or `PlanCard` (`chat/Cards.kt`, T6) and builds every answer frame (`session/PermissionAnswers.kt`, T3). Slice B adds only `ui/cards/`: one `CardFrame` (chrome, focus, keys), three card views, a `PendingCardHost` that turns a click into `manager.answer(id, requestId, PermissionAnswers.…)`, and pure key mappings tested like `rowKey`. Today's `PermissionPrompt` goes.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.1.5 (Jewel 0.37.0: `DefaultButton`, `OutlinedButton`, `RadioButtonRow`, `CheckboxRow`, `SegmentedControl`, `TextField`, `TextArea`, `Link`, `HorizontallyScrollableContainer`, `Markdown`), Compose `FlowRow`, JUnit 5, the Python fake CLI, IDE Starter + UI Driver.

**Spec:** `docs/superpowers/specs/2026-10-19-chat-redesign-design.md`, sections 4.12–4.14 (the cards), 4.18–4.20 (keys, semantics, strings), 5.2 (answers and frames), 5.4 (`Cards`), 5.6, 6 (`CardFrame`, `cardKey`, `questionKey`), 7.2–7.4, 8, 10.4–10.6, 12 (T10–T12), open items 1, 2, 8, 10, 21.

**Dependencies (spec 12):** T10 [T7, T3, T6; starts after T9 per spec 12's order, consuming T9's `onCardEscape` and `iconButton`] → T11 [T10] ‖ T12 [T10]. Slice C's T13 consumes T10's card line `From the agent "<d>"` (`agentLine`). Slice C's T14 consumes T12's `openInEditor(project: Project, path: Path)`, `PendingCardHost`'s `ProvideMarkdownStyling(project)` around `PlanCardView` and its `Markdown(...)` call, so T14 starts after T12 lands (C: T14 [T7, T12]). Slice C's T16 consumes T10's `FOCUSED_IN_CARD` and `Driver.answerCard()` (both in `ToolWindowIdeTest`) and defines neither.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- CLAUDE.md hard rules, copied (slice-specific notes after "Here:"):
  1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill). Here: the `Open plan file` VFS lookup runs on `Dispatchers.IO` in `TandemProjectScope`; click handlers parse nothing.
  2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar.
  3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
  4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept.
  5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked. Here: a malformed question input and an untyped card still render a card; a card whose request is gone answers nothing, without an exception.
  6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw. Here: slice B changes no protocol; its tests replay the recorded fixtures.
  7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both. Here: `FlowRow` comes with the Compose the platform already provides; add no Compose, coroutines or stdlib dependency to `build.gradle.kts`.
  8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code. Here: `Claude` alone is fine in card strings.
- Jewel 0.37.0 as bundled in 2026.1.5; `verifyPlugin` runs against every supported release, so nothing newer. Every colour is a theme token read in composition (`JewelTheme.globalColors.*`, spec 4.2); box and diff fills come from `chatPalette()` (T7: `boxFill`, `insertedFill`, `deletedFill`).
- Every string exactly as spec 4.20 lists it: `Allow Claude to <Verb> <object>?`, `From the agent "<d>"`, `Deny`, `Always allow for session`, `Allow once`, caps `Esc`, `⌘↩` (macOS) / `Ctrl↩`, `↩`, `Show more`, `Show less`, `1 more waiting` / `N more waiting`; `Other`, `Something else…`, `Cancel`, `Submit answers`, ` ·`; `Approve this plan?`, `From the plan file <name>`, `From the plan Claude sent`, `From Claude's draft of <name> (the plan file was not saved)`, `From Claude's last message (no plan file)`, `Claude sent no plan text.`, `Open plan file`, `What should change?`, `Keep planning`, `Approve, keep current mode`, `Approve and start coding`; `contentDescription` `Permission request` / `Question from Claude` / `Plan approval`; `testTag` `permission-card` / `question-card` / `plan-card`.
- Semantics for the Driver (4.19): every card button `semantics { contentDescription = <label> }` (the cap is a second `Text`, so `@text` is no longer the label); option rows `contentDescription = <label>`; the title is one `Text` node; cards sit above the composer in tree order so `PROMPT_BOX` (the last `@editabletext`) stays the composer (the `Other` and feedback fields are editables that come before it).
- One answer path: `manager.answer(handle.id, requestId, PermissionAnswer)` (T3). The `PendingPermission` is read from `handle.state.value` inside the click handler, never collected per delta; after T10 no composable collects `handle.state` (spec 6.0).
- The fake CLI replays the recorded result whatever the host answers, so tests assert the host's frame from `TANDEM_FAKE_LOG` (as `PermissionUpdatesTest.frames` does), never the transcript.
- Commands (CLAUDE.md), each with `--init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (the absolute path, so it works from a git worktree too): `./gradlew test` (unit, fixture and headless platform tests); `./gradlew koverLog` (line coverage per package); `./gradlew check` (test + `koverVerify`: 90 % lines per package of `protocol/`, `session/`, `chat/`; ends every task green); `xvfb-run ./gradlew integrationTest` (IDE Starter + UI Driver; only in T10 and T11, 1.5–3 min per test alone); `./gradlew runIde` (sandbox IDE: feel, freezes, focus; the owner's checklist); `./gradlew verifyPlugin` (Plugin Verifier against every supported IDE release); `./gradlew buildPlugin` (installable ZIP in `build/distributions/`).
- Commits: Conventional Commits, `git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>"`, no trailers, stage by path; branch `feat/chat-redesign`.
- Pure key mappings follow `rowKey` (`SessionSidebar.kt`): a `Key` + `KeyEventType` core tested with `Key(AwtKeyEvent.VK_*, KEY_LOCATION_STANDARD)` as `SessionSidebarTest.key` does (`CardKeysTest` copies that private `key(code: Int, numPad: Boolean = false)` helper), plus a `KeyEvent` overload with the spec's signature for the composable.
- `CardKeysTest`, `PendingCardHostTest`, `QuestionCardTest` and `PlanCardTest` are new JUnit 5 classes (`org.junit.jupiter.api.Test`, `kotlin.test` asserts); `PlanCardTest` uses `org.junit.jupiter.api.io.TempDir`. Additions to `ChatPresenterTest` (T7) and `TandemSessionsTest` follow those JUnit 5 classes.

## Review Focus

1. A card answered after its request is gone (a `control_cancel_request`, the turn ended) must do nothing and never throw → T10 `PendingCardHostTest` `a request that is gone yields no answer`.
2. Esc typed in the composer while the plan card holds feedback must send that feedback as the deny message, not the fixed `No, keep planning` → T10 `PendingCardHostTest` `escape answers per card`.
3. `Submit answers` must stay disabled until every question has an answer; a blank `Other` and an empty multi-select pick are no answer → T11 `QuestionCardTest` `picks are complete only when every question has an answer`.
4. A digit past the last option (`5` on a two-option question) must leave the picks unchanged → T11 `QuestionCardTest` `a digit past the last option changes nothing`.
5. `Open plan file` must open only an absolute path from the request; the redacted `<path>` or a relative path opens nothing, without an exception → T12 `PlanCardTest` `open plan file needs an absolute path from the request`.
6. Enter pressed right after a card appears performs the card default and the focus ring sits on that button, never on `Deny`, a `Show more` link or the plan feedback field; Shift+Enter on a card does nothing (4.18) → T10 IDE test (`answerCard()` asserts the focus is on `Allow once` before it presses Enter) and `CardKeysTest` `Enter, Cmd-Enter when offered, Esc`.

---

### Task T10: ui-permission-card

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/cards/CardKeys.kt`, `.../ui/cards/CardFrame.kt`, `.../ui/cards/PermissionCardView.kt`, `.../ui/cards/PendingCardHost.kt`. Package `dev.daarkolabs.tandem.ui.cards` for every file under `ui/cards/`; tests stay in `dev.daarkolabs.tandem.ui` and import `dev.daarkolabs.tandem.ui.cards.*`.
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ui/ChatScreen.kt` (`ChatPane`: `PermissionPrompt` deleted, the dim, the host, `onCardEscape` passed to `PromptBar`), `.../ui/TandemToolWindow.kt` (focus back to the composer), `.../ui/SessionHeader.kt` (only if T9 left `handle.state.collectAsState()` there, Step 4). `.../ui/PromptBar.kt` needs no change: T9's `onCardEscape` exists.
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ui/CardKeysTest.kt`, `.../ui/PendingCardHostTest.kt`, `.../ui/CardFixtures.kt` (helpers), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`

**Interfaces:**
- Consumes (T3, `session/`): `PermissionAnswer.Allow(updatedInput, updatedPermissions)` / `Deny(message)`; `PermissionAnswers.allowOnce(p)`, `allowForSession(p)`, `deny()`, `cancelQuestion()`, `keepPlanning(feedback)`; `SessionManager.answer(id: SessionId, requestId: String, answer: PermissionAnswer)`; `PendingPermission` (`requestId`, `input`, `agentId`).
- Consumes (T6, `chat/`): `PendingCard { requestId; toolUseId; agentDescription }`, `PermissionCard(verb, objectText, description, payload, canAlwaysAllow)` (T6's split for Agent/Task: verb `Run an agent`, objectText `""`), `Payload.Mono(text) | OldNew(path, old, new) | Json(text) | None`, `QuestionCard`, `PlanCard`, `Cards.pending(state)`, `Cards.permission(p, cwd)`, `Cards.question(p)`, `Cards.plan(p, state)`.
- Consumes (T7/T9, `ui/`): `ChatPresenter.chat: StateFlow<RenderedChat>` (`card`, `moreWaiting`, `planBlocks`), `ChatPresenter.composer: StateFlow<ComposerModel>` (`ended`, `claudeCodeVersion`, `modelWarning`), `ChatPane` as T9 left it (still holding T7's `handle.state.map { it.pendingPermissions }.distinctUntilChanged()` collector for the legacy prompt), `Modifier.chatColumn()` and `Modifier.bottomFade(height: Dp)` (`ui/Modifiers.kt`), `@Composable chatPalette(): ChatPalette` (`boxFill`, `insertedFill`, `deletedFill`), `composerKey(…, cardOpen)` → `ComposerAction.CardEscape`, `PromptBar(handle, manager, workspace, presenter, ui, focus, onFocused, onCardEscape: () -> Unit = {})` (T9), `promptFocusFor` in `TandemToolWindow.kt`, the IDE locators `iconButton(label)` (T9) and `private fun Driver.toolRow(label: String): UiComponent` (T7; `//div[@class='ComposeNode' and @contentdescription='<label>']`).
- Produces (T11 and T12 build on every one of these; slice C's T13 uses `agentLine`, T16 the two IDE-test names):
```kotlin
// ui/cards/CardKeys.kt
enum class CardKey { ENTER, CMD_ENTER, ESCAPE }
fun cardKey(event: KeyEvent, hasCmdEnter: Boolean, textFieldFocused: Boolean): CardKey?   // = cardKey(event.key, event.type, event.isShiftPressed || event.isAltPressed, event.isMetaPressed || event.isCtrlPressed, hasCmdEnter, textFieldFocused)
internal fun cardKey(key: Key, type: KeyEventType, shift: Boolean, cmdOrCtrl: Boolean, hasCmdEnter: Boolean, textFieldFocused: Boolean): CardKey?
// KeyDown only: Enter/NumPadEnter -> ENTER only when !shift && !cmdOrCtrl && !textFieldFocused (4.18: Shift+Enter does nothing on a card); with cmdOrCtrl -> CMD_ENTER when hasCmdEnter (also while a text field has focus), else null; Escape -> ESCAPE; else null
internal fun cmdEnterCap(mac: Boolean = SystemInfo.isMac): String                       // "⌘↩" / "Ctrl↩"
// ui/cards/CardFrame.kt (spec 6 sketch: chatColumn, 16 dp padding, radius 8, panelBackground, focusGroup, testTag, contentDescription, onPreviewKeyEvent -> cardKey; focus and border as below)
@Composable internal fun CardFrame(requestId: String, tag: String, description: String, textFieldFocused: Boolean, defaultFocus: FocusRequester, onEnter: () -> Unit, onCmdEnter: (() -> Unit)?, onEscape: () -> Unit, content: @Composable ColumnScope.() -> Unit)
//   focus: LaunchedEffect(requestId) { defaultFocus.requestFocus() }, and the card's default button carries Modifier.focusRequester(defaultFocus) (a requester on the focusGroup itself hands focus to the first focusable child: Deny, a Show more link or the plan feedback field)
//   border: var focused by remember { mutableStateOf(false) }; .onFocusChanged { focused = it.hasFocus } before .border(1.dp, if (focused) JewelTheme.globalColors.borders.focused else JewelTheme.globalColors.borders.normal, RoundedCornerShape(8.dp)) (spec 4.2: focused card borders.focused)
@Composable internal fun CardButton(label: String, cap: String?, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit)   // DefaultButton when primary else OutlinedButton, with modifier; Text(label) then Text(cap, secondary); semantics { contentDescription = label }
@Composable internal fun MoreWaiting(count: Int)                                        // nothing when 0, else moreWaitingText in secondary
internal fun moreWaitingText(count: Int): String?                                       // null / "1 more waiting" / "N more waiting"
// ui/cards/PermissionCardView.kt
@Composable internal fun PermissionCardView(card: PermissionCard, moreWaiting: Int, onAllowOnce: () -> Unit, onAllowForSession: () -> Unit, onDeny: () -> Unit)
internal fun permissionTitle(card: PermissionCard): String                              // if (card.objectText.isEmpty()) "Allow Claude to ${card.verb}?" else "Allow Claude to ${card.verb} ${card.objectText}?": the plain text of the one AnnotatedString Text node (the mono span only for a non-empty object)
internal fun agentLine(card: PendingCard): String?                                      // card.agentDescription?.let { "From the agent \"$it\"" } (4.20)
// ui/cards/PendingCardHost.kt
internal data class PendingCardModel(val card: PendingCard?, val moreWaiting: Int, val planBlocks: ImmutableList<MarkdownBlock>?)
internal fun RenderedChat.pendingCard(): PendingCardModel
@Composable internal fun PendingCardHost(project: Project, handle: SessionHandle, manager: SessionManager, model: PendingCardModel, feedback: TextFieldState)   // when (card) { is PermissionCard -> PermissionCardView; else -> PermissionCardView(genericCard(card)) }; T11/T12 replace the else
internal fun genericCard(card: PendingCard): PermissionCard                             // verb "Use", objectText "AskUserQuestion" / "ExitPlanMode", description null, Payload.None, canAlwaysAllow false
internal fun answerFor(state: ChatState, requestId: String, build: (PendingPermission) -> PermissionAnswer): PermissionAnswer?   // null when no pending permission has requestId
internal fun escapeAnswer(card: PendingCard, feedback: String): (PendingPermission) -> PermissionAnswer   // PermissionCard -> deny(); QuestionCard -> cancelQuestion(); PlanCard -> keepPlanning(feedback)
// src/test/.../ui/CardFixtures.kt
internal fun replay(name: String, through: Int): ChatState       // Fixtures.lines(name).take(through).map(Ndjson::decode).fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
internal fun pendingAt(name: String, line: Int): PendingPermission   // replay(name, line).pendingPermissions.single()
// src/integrationTest/.../ToolWindowIdeTest.kt (slice C's T16 uses both and defines neither)
const val FOCUSED_IN_CARD = "//div[@class='ComposeNode' and @role='Button' and @focused='true' and (@contentdescription='Deny' or @contentdescription='Always allow for session' or @contentdescription='Allow once')]"   // companion
private fun Driver.answerCard()                                                          // Enter on a focused card after asserting the focus is on Allow once, else click Allow once (Step 6)
```

- [ ] **Step 1: Write the failing unit tests**

```kotlin
// CardKeysTest.kt (key() as in SessionSidebarTest)
private fun press(code: Int, cmd: Boolean = false, shift: Boolean = false, hasCmdEnter: Boolean = false, textField: Boolean = false, numPad: Boolean = false): CardKey? =
    cardKey(key(code, numPad), KeyEventType.KeyDown, shift, cmd, hasCmdEnter, textField)
@Test fun `Enter, Cmd-Enter when offered, Esc`() {
    assertEquals(CardKey.ENTER, press(AwtKeyEvent.VK_ENTER)); assertEquals(CardKey.ENTER, press(AwtKeyEvent.VK_ENTER, numPad = true))
    assertEquals(CardKey.CMD_ENTER, press(AwtKeyEvent.VK_ENTER, cmd = true, hasCmdEnter = true)); assertNull(press(AwtKeyEvent.VK_ENTER, cmd = true))
    assertNull(press(AwtKeyEvent.VK_ENTER, shift = true))                       // 4.18: Shift+Enter does nothing on a card
    assertEquals(CardKey.ESCAPE, press(AwtKeyEvent.VK_ESCAPE)); assertNull(press(AwtKeyEvent.VK_A))
    assertNull(cardKey(key(AwtKeyEvent.VK_ENTER), KeyEventType.KeyUp, false, false, false, false))
}
@Test fun `Enter is not returned while a text field has focus`() {
    assertNull(press(AwtKeyEvent.VK_ENTER, textField = true)); assertEquals(CardKey.ESCAPE, press(AwtKeyEvent.VK_ESCAPE, textField = true))
    assertEquals(CardKey.CMD_ENTER, press(AwtKeyEvent.VK_ENTER, cmd = true, hasCmdEnter = true, textField = true))
}
@Test fun `the Cmd-Enter cap names the platform's key`() { assertEquals("⌘↩", cmdEnterCap(mac = true)); assertEquals("Ctrl↩", cmdEnterCap(mac = false)) }
```
```kotlin
// PendingCardHostTest.kt
@Test fun `a request that is gone yields no answer`() {   // Review Focus 1
    val state = replay("permission", 21)                   // the touch request (<request_id-26>) is pending; <request_id-63> (line 52) is not
    assertEquals(PermissionAnswer.Deny("The user denied this request."), answerFor(state, "<request_id-26>") { PermissionAnswers.deny() })
    assertNull(answerFor(state, "<request_id-63>") { PermissionAnswers.deny() }); assertNull(answerFor(ChatState(), "<request_id-26>") { PermissionAnswers.deny() })
}
@Test fun `escape answers per card`() {                   // Review Focus 2
    val p = pendingAt("permission", 21); assertEquals(PermissionAnswer.Deny("The user denied this request."), escapeAnswer(Cards.permission(p, null), "")(p))
    val q = pendingAt("ask-single", 22); assertEquals(PermissionAnswer.Deny("cancelled"), escapeAnswer(Cards.question(q)!!, "")(q))
    val plan = replay("plan-approve", 88); val pp = plan.pendingPermissions.single()
    assertEquals(PermissionAnswer.Deny("also add a date"), escapeAnswer(Cards.plan(pp, plan), "also add a date")(pp))
    assertEquals(PermissionAnswer.Deny("No, keep planning"), escapeAnswer(Cards.plan(pp, plan), "  ")(pp))
}
@Test fun `titles and the waiting count`() {
    assertEquals("Allow Claude to Run touch tandem-fixture.txt?", permissionTitle(Cards.permission(pendingAt("permission", 21), null)))
    val agent = pendingAt("permission", 21).copy(toolName = "Agent", input = buildJsonObject { put("description", "Count lines"); put("prompt", "Count the lines of seed.txt") })
    assertEquals("Allow Claude to Run an agent?", permissionTitle(Cards.permission(agent, null)))   // empty object: no space before "?"
    assertEquals("From the agent \"Count lines in seed.txt\"", agentLine(Cards.pending(replay("agent-sync", 43)).first!!))
    assertNull(agentLine(Cards.permission(pendingAt("permission", 21), null)))
    assertEquals("Allow Claude to Use AskUserQuestion?", permissionTitle(genericCard(Cards.question(pendingAt("ask-single", 22))!!)))
    val plan = replay("plan-approve", 88); assertEquals("Allow Claude to Use ExitPlanMode?", permissionTitle(genericCard(Cards.plan(plan.pendingPermissions.single(), plan))))
    assertNull(moreWaitingText(0)); assertEquals("1 more waiting", moreWaitingText(1)); assertEquals("3 more waiting", moreWaitingText(3))
}
```

- [ ] **Step 2: Run them to verify they fail** — `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.CardKeysTest' --tests 'dev.daarkolabs.tandem.ui.PendingCardHostTest'` → unresolved references.

- [ ] **Step 3: Implement `CardKeys.kt`, `CardFrame.kt`, `PermissionCardView.kt`, `PendingCardHost.kt`** with the signatures above. `PermissionCardView` keeps `val defaultFocus = remember(card.requestId) { FocusRequester() }` and lays out spec 4.12 items 1–6 inside `CardFrame(card.requestId, "permission-card", "Permission request", textFieldFocused = false, defaultFocus = defaultFocus, onEnter = onAllowOnce, onCmdEnter = onAllowForSession.takeIf { card.canAlwaysAllow }, onEscape = onDeny)`: a 6 dp `text.warning` dot then the title (`AnnotatedString`: verb `FontWeight.SemiBold`, then, only when `card.objectText` is non-empty, a space and the object in the 4.2 mono style; its plain text is `permissionTitle(card)`); `agentLine(card)` in secondary when non-null; `card.description` in secondary when non-null and `!= card.objectText` (4.12 item 3); the payload box (`Modifier.background(chatPalette().boxFill, RoundedCornerShape(6.dp)).border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(8.dp)`, `HorizontallyScrollableContainer`, `SelectionContainer`): `Mono`/`Json` → mono text, `OldNew` → the path box then `- ` lines on `chatPalette().deletedFill` and `+ ` lines on `chatPalette().insertedFill`, `None` → nothing; the box clamps at 8 lines / 160 dp with `Modifier.bottomFade` and a `Link` `Show more` / `Show less` on a `remember(card.requestId) { mutableStateOf(false) }`, the link and the fade only when the payload has more than 8 lines (`text.lines().size > 8`; `OldNew` counts the path, old and new lines); buttons in `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp))` (`@OptIn(ExperimentalLayoutApi::class)`): `CardButton("Deny", "Esc", primary = false, onClick = onDeny)`, a weighted spacer, `CardButton("Always allow for session", cmdEnterCap(), false, onClick = onAllowForSession)` only when `card.canAlwaysAllow`, `CardButton("Allow once", "↩", primary = true, modifier = Modifier.focusRequester(defaultFocus), onClick = onAllowOnce)`; then `MoreWaiting(moreWaiting)`. `PendingCardHost` answers with `answerFor(handle.state.value, card.requestId) { PermissionAnswers.allowOnce(it) }?.let { manager.answer(handle.id, card.requestId, it) }` (and `allowForSession` / `deny`). Enter on a Tab-focused `Deny` still allows (the card root maps Enter, spec 4.18); recorded for the slice-B review.

- [ ] **Step 4: Wire `ChatPane`, `TandemToolWindow` and, if needed, `SessionHeader`.** `ChatPane`: delete `PermissionPrompt` and its `handle.state.map { it.pendingPermissions }.distinctUntilChanged()` collector (T7); `val composer by presenter.composer.collectAsState()` (the empty-state text from `composer.ended`); `val pending by remember(presenter) { presenter.chat.map { it.pendingCard() }.distinctUntilChanged() }.collectAsState(presenter.chat.value.pendingCard())`; `val feedback = remember(pending.card?.requestId) { TextFieldState() }`; the list `Box` gains the dim of 4.12 while `pending.card is PermissionCard`: `drawWithContent { drawContent(); drawRect(panel.copy(alpha = 0.5f)) }` with `panel = JewelTheme.globalColors.panelBackground` read in composition (never `Modifier.alpha`); `pending.card?.let { PendingCardHost(project, handle, manager, pending, feedback) }` between the list and `PromptBar`; `PromptBar(…, onCardEscape = { pending.card?.let { c -> answerFor(handle.state.value, c.requestId, escapeAnswer(c, feedback.text.toString()))?.let { manager.answer(handle.id, c.requestId, it) } } })` (T9's parameter; `PromptBar.kt` itself is unchanged). `TandemToolWindow`, in the per-session block: `LaunchedEffect(presenter) { var open = false; presenter.chat.map { it.card != null }.distinctUntilChanged().collect { now -> if (open && !now) promptFocusFor = selected.id; open = now } }` (4.12: focus returns to the composer when the card closes). `SessionHeader`: if T9 left `handle.state.collectAsState()` there, `StatusLine` takes `ComposerModel` (`claudeCodeVersion`, `modelWarning`) from `presenter.composer.collectAsState()`, so after T10 no composable collects `handle.state` (spec 6.0).

- [ ] **Step 5: Run the unit tests and the suite** — the Step 2 command → PASS; `grep -rn "handle.state" src/main/kotlin/dev/daarkolabs/tandem/ui` → only `handle.state.value` reads inside click handlers and the `ChatPresenter(…, handle.state, …)` construction (no composable collects it); then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → BUILD SUCCESSFUL (Kover unchanged: `ui/` is not gated).

- [ ] **Step 6: Rewrite the permission IDE test** (replaces `permission prompts are answered from the tool window`; in `several sessions live side by side in the sidebar` the `waitForText("Allow Bash?")` / `clickButton("Allow once")` pair becomes `waitForText("Allow Claude to Run touch tandem-fixture.txt?")` / `iconButton("Allow once").click(Point(10, 10))`)

```kotlin
@Test fun `the permission card names the command and answers from the keyboard`() {
    val sent = runChat("permissions", fixtures = listOf("permission")) {
        newSession(); typePrompt("Create the fixture file")
        waitForText("Allow Claude to Run touch tandem-fixture.txt?")
        answerCard()                                                             // Review Focus 6: the focus is on Allow once, then Enter
        waitFor("tool row", 2.minutes) { toolRow("Ran Create empty fixture file").present() }
        waitForText("done.")                                                     // the first turn has ended, so Send is back for the next prompt
        typePrompt("Now remove it")
        waitForText("Allow Claude to Run rm tandem-fixture.txt?")
        iconButton("Deny").click(Point(10, 10))
        waitFor("denied row", 2.minutes) { toolRow("Remove fixture file · Denied").present() }
        waitForText("denied.")
    }
    val answers = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }
    assertEquals(listOf("<request_id-26>", "<request_id-63>"), answers.map { it["request_id"]!!.jsonPrimitive.content })
    assertEquals(listOf("allow", "deny"), answers.map { it["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content })
}
private fun Driver.answerCard() {                                                // slice C's T16 calls it too
    val focused = runCatching { waitFor("card focused", 10.seconds) { ui.x(FOCUSED_IN_CARD).present() }; true }.getOrDefault(false)
    if (focused) {
        assertTrue(ui.x("//div[@class='ComposeNode' and @role='Button' and @focused='true' and @contentdescription='Allow once']").present(), "the card's focus must sit on Allow once, never on Deny")
        ui.keyboard { enter() }
    } else {
        println("card focus not visible to the Driver; clicking Allow once (runIde checklist 6)")   // 10.6: the keyboard path stays on runIde checklist 6
        iconButton("Allow once").click(Point(10, 10))
    }
}
// companion: const val FOCUSED_IN_CARD = "//div[@class='ComposeNode' and @role='Button' and @focused='true' and (@contentdescription='Deny' or @contentdescription='Always allow for session' or @contentdescription='Allow once')]"   // CardFrame requests focus on the card's default button, so a focused card button means the card has focus; no assumption about nesting in the Driver's hierarchy
```

- [ ] **Step 7: Run the IDE tests** — `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests '*permission card*'`, then the whole `xvfb-run ./gradlew integrationTest --init-script …` → all PASS. On a locator miss dump the hierarchy (`error("DIAGNOSTIC")`, read `out/ide-tests/…/ui-hierarchy/ui.html`) and adjust `FOCUSED_IN_CARD` / `toolRow` once, as Phase 1 did for the prompt box.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt
git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat: permission card docked above the composer"
```

---

### Task T11: ui-question-card

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/cards/QuestionCardView.kt`
- Modify: `.../ui/cards/CardKeys.kt` (`questionKey`), `.../ui/cards/PendingCardHost.kt` (the `QuestionCard` branch)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ui/CardKeysTest.kt`, `.../ui/QuestionCardTest.kt`, `.../ide/TandemSessionsTest.kt`, `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`

**Interfaces:**
- Consumes (T6): `QuestionCard(questions)`, `Question(text, header, multiSelect, options)`, `Option(label, description)`, `Cards.question(p)` (null → `Cards.pending` (T6) already yields the `PermissionCard` `Allow Claude to Use …?` of 4.13, which T10's `PermissionCardView` renders: the malformed-input fallback needs no view code in T11; `QuestionCardTest` `a malformed question input renders the generic permission card` pins the rendered title), `Cards.answers(card, picks: Map<Int, Pick>)`, `Pick.Options(indices)` / `Pick.Other(text)` as T6 declared them (tests never construct a `Pick`: they go through `picksOf` and compare `Cards.answers`). (T3): `PermissionAnswers.questionAnswers(p, answers)`, `cancelQuestion()`. (T10): everything in its Produces block. (`ide/`): `TandemSessions.extraEnvironment`, `TandemSessions.manager`.
- Produces:
```kotlin
// CardKeys.kt
sealed interface QuestionKey { data class Pick(val index: Int) : QuestionKey; data object PrevTab : QuestionKey; data object NextTab : QuestionKey; data object Submit : QuestionKey }
fun questionKey(event: KeyEvent, otherFieldFocused: Boolean, stripFocused: Boolean): QuestionKey?   // = questionKey(event.key, event.type, event.isShiftPressed, event.isMetaPressed || event.isCtrlPressed, otherFieldFocused, stripFocused)
internal fun questionKey(key: Key, type: KeyEventType, shift: Boolean, ctrlOrMeta: Boolean, otherFieldFocused: Boolean, stripFocused: Boolean): QuestionKey?
// KeyDown only: digits 1–9 (main row and numpad) -> Pick(n - 1) unless otherFieldFocused; Left/Right -> PrevTab/NextTab only when stripFocused; Enter/NumPadEnter without Shift, Ctrl or Meta while otherFieldFocused -> Submit; else null
// Submit is a recorded deviation from spec 4.13/6 (Step 3): the card's outer preview maps Enter in Other instead of the field's own onPreviewKeyEvent; the behaviour is the same
// QuestionCardView.kt
@Composable internal fun QuestionCardView(card: QuestionCard, moreWaiting: Int, onSubmit: (Map<Int, Pick>) -> Unit, onCancel: () -> Unit)
internal fun togglePick(question: Question, picked: Set<Int>, index: Int): Set<Int>      // index !in question.options.indices (a digit past the last option, or the Other row's index options.size) -> picked; single -> setOf(index); multi -> toggled
internal fun picksOf(card: QuestionCard, picked: Map<Int, Set<Int>>, others: Map<Int, String>): Map<Int, Pick>?   // per question: Other(text.trim()) when non-blank, else Options(picked.sorted()) when non-empty (T6's `Options(indices: List<Int>)`, in option order); null when any question has neither
internal fun tabLabel(header: String, answered: Boolean): String                            // header, or "$header ·"
```

- [ ] **Step 1: Write the failing unit tests**

```kotlin
// CardKeysTest.kt
private fun q(code: Int, other: Boolean = false, strip: Boolean = false, shift: Boolean = false, cmd: Boolean = false, numPad: Boolean = false): QuestionKey? =
    questionKey(key(code, numPad), KeyEventType.KeyDown, shift, cmd, other, strip)
@Test fun `digits pick options unless the Other field has focus`() {
    assertEquals(QuestionKey.Pick(0), q(AwtKeyEvent.VK_1)); assertEquals(QuestionKey.Pick(8), q(AwtKeyEvent.VK_9)); assertEquals(QuestionKey.Pick(1), q(AwtKeyEvent.VK_NUMPAD2, numPad = true))
    assertNull(q(AwtKeyEvent.VK_0)); assertNull(q(AwtKeyEvent.VK_1, other = true))
    assertEquals(QuestionKey.Submit, q(AwtKeyEvent.VK_ENTER, other = true)); assertNull(q(AwtKeyEvent.VK_ENTER, other = true, shift = true)); assertNull(q(AwtKeyEvent.VK_ENTER, other = true, cmd = true)); assertNull(q(AwtKeyEvent.VK_ENTER))
}
@Test fun `arrows switch tabs only when the strip has focus`() {
    assertEquals(QuestionKey.PrevTab, q(AwtKeyEvent.VK_LEFT, strip = true)); assertEquals(QuestionKey.NextTab, q(AwtKeyEvent.VK_RIGHT, strip = true))
    assertNull(q(AwtKeyEvent.VK_LEFT)); assertNull(q(AwtKeyEvent.VK_RIGHT, other = true))
}
```
```kotlin
// QuestionCardTest.kt
private val single = Cards.question(pendingAt("ask-single", 22))!!      // Which colour? Red, Blue
private val multi = Cards.question(pendingAt("ask-multi", 27))!!        // Which toppings would you like? Cheese, Olives, Ham; multiSelect
private val two = single.copy(questions = (single.questions + multi.questions).toImmutableList())
@Test fun `picks are complete only when every question has an answer`() {   // Review Focus 3
    assertNull(picksOf(two, emptyMap(), emptyMap()))
    assertNull(picksOf(two, mapOf(0 to setOf(1)), mapOf(1 to "  ")))                 // a blank Other is no answer
    assertNull(picksOf(two, mapOf(0 to setOf(1), 1 to emptySet()), emptyMap()))      // an empty multi-select pick is no answer
    assertEquals(mapOf("Which colour?" to "Blue", "Which toppings would you like?" to "Cheese, Ham"), Cards.answers(two, picksOf(two, mapOf(0 to setOf(1), 1 to setOf(2, 0)), emptyMap())!!))
    assertEquals(mapOf("Which colour?" to "Green"), Cards.answers(single, picksOf(single, mapOf(0 to setOf(0)), mapOf(0 to " Green "))!!))   // Other wins over a pick
}
@Test fun `a digit past the last option changes nothing`() {                // Review Focus 4
    val colour = single.questions[0]; val toppings = multi.questions[0]
    assertEquals(setOf(1), togglePick(colour, setOf(1), 4)); assertEquals(setOf(0), togglePick(colour, setOf(1), 0))
    assertEquals(setOf(0, 2), togglePick(toppings, setOf(0), 2)); assertEquals(setOf(2), togglePick(toppings, setOf(0, 2), 0))
    assertEquals(setOf(1), togglePick(colour, setOf(1), 2))                    // the Other row's index (digit 3 on a two-option question) never changes the option picks
}
@Test fun `an unanswered tab carries the marker`() { assertEquals("Colour ·", tabLabel("Colour", false)); assertEquals("Colour", tabLabel("Colour", true)) }
@Test fun `a malformed question input renders the generic permission card`() {   // 4.13, 8: T11's malformed-input fallback
    val state = replay("ask-single", 22); val p = state.pendingPermissions.single()
    for (input in listOf(JsonObject(p.input - "questions"), buildJsonObject { put("questions", "nope") })) {
        val card = Cards.pending(state.copy(pendingPermissions = listOf(p.copy(input = input)))).first
        assertTrue(card is PermissionCard); assertEquals("Allow Claude to Use AskUserQuestion?", permissionTitle(card as PermissionCard))
    }
}
```
```kotlin
// TandemSessionsTest.kt (the spec's name, 10.5, kept verbatim)
@Test fun aQuestionAnsweredFromTheServiceReachesTheFake() = timeoutRunBlocking(30.seconds) {
    val dir = tempDir.get().resolve("question").createDirectories(); val log = Files.createTempFile("tandem-sent", ".ndjson")
    openProjectAsync(dir).useProjectAsync { project ->
        val sessions = TandemSessions.get(project)
        sessions.extraEnvironment = mapOf("TANDEM_FAKE_FIXTURE" to "ask-single", "TANDEM_FAKE_LOG" to log.toString())
        sessions.ensureStarted()
        val handle = sessions.manager.create(NewSessionOptions()); sessions.manager.send(handle.id, "Ask me a colour")
        val p = handle.state.first { it.pendingPermissions.isNotEmpty() }.pendingPermissions.single(); val card = Cards.question(p)!!
        sessions.manager.answer(handle.id, p.requestId, PermissionAnswers.questionAnswers(p, Cards.answers(card, picksOf(card, mapOf(0 to setOf(1)), emptyMap())!!)))
        handle.state.first { !it.busy }
    }
    awaitNoOwnFakeCli()                                                          // as the other tests of the class: no fake CLI outlives the project
    val frames = log.readText().split('\n').dropLast(1).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }   // as PermissionUpdatesTest.frames
    val answer = frames.single { it["type"]?.jsonPrimitive?.content == "control_response" }["response"]!!.jsonObject
    assertEquals("<request_id-27>", answer["request_id"]!!.jsonPrimitive.content)
    val response = answer["response"]!!.jsonObject
    assertEquals("allow", response["behavior"]!!.jsonPrimitive.content); assertEquals("<id-12>", response["toolUseID"]!!.jsonPrimitive.content)
    assertEquals("Blue", response["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
}
```

- [ ] **Step 2: Run them to verify they fail** — `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.CardKeysTest' --tests 'dev.daarkolabs.tandem.ui.QuestionCardTest' --tests 'dev.daarkolabs.tandem.ide.TandemSessionsTest'` → unresolved references (`questionKey`, `picksOf`; the service test fails on `picksOf`; `QuestionCardTest` does not compile, so `a malformed question input renders the generic permission card` fails with it and passes on T6's fallback once the file compiles).

- [ ] **Step 3: Implement `questionKey` and `QuestionCardView`** per spec 4.13. State in `remember(card.requestId)`: `tab: Int`, `picked: Map<Int, Set<Int>>`, one `TextFieldState` per question for `Other` (`otherStates[i]`; `others: Map<Int, String>` = their texts, read in composition, is what `picksOf` takes), one `FocusRequester` per question for its `Other` field (`otherFocus[i]`), `defaultFocus: FocusRequester`, `otherFocused: Boolean`, `stripFocused: Boolean`. Helpers: `answered(i) = !others[i].isNullOrBlank() || picked[i].orEmpty().isNotEmpty()` (the same rule `picksOf` applies per question); `pick(i)` on the active question `q` = `when { i == q.options.size -> otherFocus[tab].requestFocus(); i in q.options.indices -> { picked[tab] = togglePick(q, picked[tab].orEmpty(), i); if (!q.multiSelect) otherStates[tab].clearText() }; else -> Unit }` (the `Other` index never reaches `togglePick`; in a single-select question picking an option clears that question's `Other` text, so one radio is selected at a time; a digit past the `Other` row changes nothing); `submit = { picksOf(card, picked, others)?.let(onSubmit) }`. Layout: a `Box(Modifier.onPreviewKeyEvent { questionKey(it, otherFocused, stripFocused)?.let { k -> when (k) { is QuestionKey.Pick -> pick(k.index); QuestionKey.PrevTab -> tab = (tab - 1).coerceAtLeast(0); QuestionKey.NextTab -> tab = (tab + 1).coerceAtMost(questions.lastIndex); QuestionKey.Submit -> submit() }; true } ?: false })` around `CardFrame(card.requestId, "question-card", "Question from Claude", textFieldFocused = otherFocused, defaultFocus = defaultFocus, onEnter = submit, onCmdEnter = null, onEscape = onCancel)` (the parent's preview runs first, so digits, arrows and Enter in `Other` never reach `CardFrame`); when `questions.size > 1` a `HorizontallyScrollableContainer { SegmentedControl(buttons = questions.mapIndexed { i, q -> SegmentedControlButtonData(selected = i == tab, content = { Text(tabLabel(q.header, answered(i))) }, onSelect = { tab = i }) }) }` with `onFocusChanged { stripFocused = it.hasFocus }`; the active question's `text` in `FontWeight.SemiBold`; per option a `RadioButtonRow(selected = others[tab].isNullOrBlank() && index in picked[tab].orEmpty(), onClick = { pick(index) })` (single) or `CheckboxRow(checked = <the same>, onCheckedChange = { pick(index) })` (multi), so the card shows exactly what `picksOf` will send (in a multi-select question checked options show unchecked while the `Other` text is non-blank, since `Other` wins, 4.13), each with `Text(label, SemiBold)`, a spacer, `Text("${index + 1}", secondary)`, `Modifier.semantics { contentDescription = label }`, and the description under it in secondary indented to the label; the first option row of the active tab carries `Modifier.focusRequester(defaultFocus)` (`Submit answers` is disabled when the card appears, so the focus starts on the first option); the `Other` row is `RadioButtonRow(selected = !others[tab].isNullOrBlank(), onClick = { otherFocus[tab].requestFocus() })` (multi-select: `CheckboxRow(checked = !others[tab].isNullOrBlank(), onCheckedChange = { otherFocus[tab].requestFocus() })`) with `Modifier.semantics { contentDescription = "Other" }`, `Text("Other", SemiBold)` and the cap `"${options.size + 1}"` (the 4.13 sketch's `○ Other 3`), followed by `TextArea(otherStates[tab], Modifier.focusRequester(otherFocus[tab]).onFocusChanged { otherFocused = it.isFocused }, lineLimits = TextFieldLineLimits.MultiLine(1, 4), placeholder = { Text("Something else…") })`; the row's click and its digit focus the field and change no picks; no `snapshotFlow` is needed, since `selected` is derived from the text. Deliberate deviation from spec 4.13/6, recorded for the slice-B review: Enter in `Other` is mapped by the card's outer `Box` preview (`QuestionKey.Submit`, submitting only when `picksOf(...) != null`) instead of the field's own `onPreviewKeyEvent`; the behaviour is identical (plain Enter submits when complete, Shift+Enter falls through as the field's newline, and `Submit` requires no Shift, Ctrl or Meta). Buttons in a `FlowRow`: `CardButton("Cancel", "Esc", primary = false, onClick = onCancel)`, a weighted spacer, `CardButton("Submit answers", "↩", primary = true, enabled = picksOf(card, picked, others) != null) { onSubmit(picksOf(...)!!) }`; `MoreWaiting(moreWaiting)`. `PendingCardHost`: `is QuestionCard -> QuestionCardView(card, model.moreWaiting, onSubmit = { picks -> answerFor(handle.state.value, card.requestId) { PermissionAnswers.questionAnswers(it, Cards.answers(card, picks)) }?.let { manager.answer(handle.id, card.requestId, it) } }, onCancel = { … PermissionAnswers.cancelQuestion() … })`. The `else -> PermissionCardView(genericCard(card))` branch stays while the other of T11/T12 is unmerged; whichever of the two lands second deletes `genericCard`, the `else` branch (the `when` is then exhaustive over `PendingCard`) and the two `genericCard` assertions of `titles and the waiting count`.

- [ ] **Step 4: Run the tests and the suite** — the Step 2 command → PASS; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → BUILD SUCCESSFUL.

- [ ] **Step 5: Write the IDE test**

```kotlin
@Test fun `a question card answers with the picked option`() {
    val sent = runChat("question", fixtures = listOf("ask-single")) {
        newSession(); typePrompt("Ask me a colour")
        waitForText("Which colour?")
        option("Blue").click(Point(10, 10))
        iconButton("Submit answers").click(Point(10, 10))
        waitFor("question card closed", 2.minutes) { !ui.x("//div[@class='ComposeNode' and @contentdescription='Question from Claude']").present() }   // the card's own option is a "Blue" text node too
        waitForText("Blue")                                                      // the model's reply, ask-single:31
        waitFor("Questions row", 2.minutes) { toolRow("Questions").present() }
    }
    val answer = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }.single { it["request_id"]!!.jsonPrimitive.content == "<request_id-27>" }["response"]!!.jsonObject
    assertEquals("allow", answer["behavior"]!!.jsonPrimitive.content)
    assertEquals("Blue", answer["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
}
private fun Driver.option(label: String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$label' and (@role='RadioButton' or @role='Checkbox')]")   // open item 8: drop the role predicate if the hierarchy dump prints none
```

- [ ] **Step 6: Run the IDE tests** — `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests '*question card*'`, then the whole `integrationTest` → all PASS (`PROMPT_BOX` is still the composer: the `Other` field precedes it in tree order).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/ui/cards src/test/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ide/TandemSessionsTest.kt src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt
git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat: question card for AskUserQuestion"
```

---

### Task T12: ui-plan-card

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/cards/PlanCardView.kt`, `.../ui/OpenInEditor.kt` (package `dev.daarkolabs.tandem.ui`)
- Modify: `.../ui/cards/CardKeys.kt` (`planFeedbackKey`), `.../ui/cards/PendingCardHost.kt` (the `PlanCard` branch), `.../ui/ChatPresenter.kt` (`planBlocks`, only if the Step 4 test fails)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ui/CardKeysTest.kt`, `.../ui/PlanCardTest.kt`, `.../ui/ChatPresenterTest.kt`

**Interfaces:**
- Consumes (T6): `PlanCard(plan, source, planFilePath)`, `PlanSource.EXIT_PLAN_INPUT / PLAN_FILE_DRAFT / ASSISTANT_TEXT / NONE`, `Cards.plan(p, state)`, `Cards.sourceLine(card: PlanCard): String` (T6; the five 4.14 strings live there, pinned by `CardsTest` `plan card says where the plan came from`, and T12 adds no second copy). (T3): `PermissionAnswers.approvePlan(p, startCoding)`, `keepPlanning(feedback)`. (T7): `RenderedChat.planBlocks`, `ChatPresenter(scope, state: StateFlow<ChatState>, options: StateFlow<RowOptions>)` and `ChatPresenterTest`'s `MutableStateFlow<ChatState>`, `Modifier.bottomFade`, `ProvideMarkdownStyling(project)` as `ChatPanel` uses it. (T10): `CardFrame` (with `defaultFocus`), `CardButton` (with `modifier`), `MoreWaiting`, `PendingCardHost`, `PendingCardModel.planBlocks`, `feedback: TextFieldState`, `answerFor`, `replay`. (Existing): `dev.daarkolabs.tandem.TandemProjectScope` (`TandemProjectScope.get(project).scope`, the project scope spec 4.4 and 4.14 name).
- Produces (slice C's T14 reuses `openInEditor` and edits the `PlanCard` branch's `ProvideMarkdownStyling(project)` and `PlanCardView`'s `Markdown(...)` call):
```kotlin
// CardKeys.kt
enum class PlanKey { KEEP_PLANNING }
fun planFeedbackKey(event: KeyEvent): PlanKey?                                        // the feedback field's own onPreviewKeyEvent (4.14)
internal fun planFeedbackKey(key: Key, type: KeyEventType, shift: Boolean): PlanKey?  // KeyDown Enter/NumPadEnter without shift -> KEEP_PLANNING, else null
// PlanCardView.kt
@Composable internal fun PlanCardView(card: PlanCard, blocks: ImmutableList<MarkdownBlock>?, moreWaiting: Int, feedback: TextFieldState, onApprove: (startCoding: Boolean) -> Unit, onKeepPlanning: () -> Unit, onOpenPlanFile: () -> Unit)
internal fun planFileToOpen(card: PlanCard): Path?                                    // card.planFilePath?.takeIf { card.source == PlanSource.EXIT_PLAN_INPUT }?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
// ui/OpenInEditor.kt
internal fun openInEditor(project: Project, path: Path)                               // TandemProjectScope.get(project).scope.launch(Dispatchers.IO) { val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: run { LOG.debug("plan file not found"); return@launch }; withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file).navigate(true) } }   (spec 4.14; a miss logs at debug without the path)
```

- [ ] **Step 1: Write the failing unit tests**

```kotlin
// CardKeysTest.kt
@Test fun `Enter in the feedback field keeps planning`() {
    assertEquals(PlanKey.KEEP_PLANNING, planFeedbackKey(key(AwtKeyEvent.VK_ENTER), KeyEventType.KeyDown, shift = false))
    assertEquals(PlanKey.KEEP_PLANNING, planFeedbackKey(key(AwtKeyEvent.VK_ENTER, numPad = true), KeyEventType.KeyDown, shift = false))
    assertNull(planFeedbackKey(key(AwtKeyEvent.VK_ENTER), KeyEventType.KeyDown, shift = true)); assertNull(planFeedbackKey(key(AwtKeyEvent.VK_ENTER), KeyEventType.KeyUp, shift = false))
    assertNull(press(AwtKeyEvent.VK_ENTER, textField = true))   // the frame never approves while the field has focus
}
// PlanCardTest.kt
@Test fun `open plan file needs an absolute path from the request`(@TempDir tmp: Path) {   // Review Focus 5
    val approve = replay("plan-approve", 88); val p = approve.pendingPermissions.single()
    assertNull(planFileToOpen(Cards.plan(p, approve)))                                        // the redacted "<path>" is not absolute
    val file = tmp.resolve("plans").resolve("x.md")                                           // absolute on every OS
    val patched = p.copy(input = JsonObject(p.input + ("planFilePath" to JsonPrimitive(file.toString()))))
    assertEquals(file, planFileToOpen(Cards.plan(patched, approve)))
    val disallowed = replay("plan-disallowed", 173); assertNull(planFileToOpen(Cards.plan(disallowed.pendingPermissions.single(), disallowed)))   // NONE
}
```

- [ ] **Step 2: Run them to verify they fail** — `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.CardKeysTest' --tests 'dev.daarkolabs.tandem.ui.PlanCardTest'` → unresolved references (`planFeedbackKey`, `planFileToOpen`). The picker follow-through of spec 12 T12 (the reducer test for `plan-approve:90`) is T2's `ReducerTest` `plan-approve: the status line after approval switches the mode before the tool result` (slice A): `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.ReducerTest'` → PASS already; T12 adds nothing to it.

- [ ] **Step 3: Implement `planFeedbackKey`, `PlanCardView`, `openInEditor`** per spec 4.14. `PlanCardView`: `var feedbackFocused by remember(card.requestId) { mutableStateOf(false) }`; `val defaultFocus = remember(card.requestId) { FocusRequester() }`; `CardFrame(card.requestId, "plan-card", "Plan approval", textFieldFocused = feedbackFocused, defaultFocus = defaultFocus, onEnter = { onApprove(true) }, onCmdEnter = null, onEscape = onKeepPlanning)`; title `Approve this plan?` (one `Text`); the source line `Text(Cards.sourceLine(card), color = JewelTheme.globalColors.text.info)` (secondary); when `card.plan != null && blocks != null` a `Markdown(blocks, card.plan, Modifier.fillMaxWidth(), selectable = true)` clamped at 240 dp with `Modifier.bottomFade` and a `Link` `Show more` / `Show less` on a `remember(card.requestId)` flag, the link and the fade only when the unclamped Markdown measures over 240 dp (`onSizeChanged`) (`NONE` → no body); `Link("Open plan file", onOpenPlanFile)` only when `planFileToOpen(card) != null`; `TextField(feedback, Modifier.fillMaxWidth().onFocusChanged { feedbackFocused = it.isFocused }.onPreviewKeyEvent { planFeedbackKey(it) == PlanKey.KEEP_PLANNING && run { onKeepPlanning(); true } }, placeholder = { Text("What should change?") })`; buttons in a `FlowRow`: `CardButton("Keep planning", "Esc", primary = false, onClick = onKeepPlanning)`, a weighted spacer, `CardButton("Approve, keep current mode", null, primary = false) { onApprove(false) }`, `CardButton("Approve and start coding", "↩", primary = true, modifier = Modifier.focusRequester(defaultFocus)) { onApprove(true) }` (the card's focus starts here, never in the feedback field or on `Show more`); `MoreWaiting(moreWaiting)`. `PendingCardHost`: `is PlanCard -> ProvideMarkdownStyling(project) { PlanCardView(card, model.planBlocks, model.moreWaiting, feedback, onApprove = { start -> answerFor(handle.state.value, card.requestId) { PermissionAnswers.approvePlan(it, start) }?.let { manager.answer(handle.id, card.requestId, it) } }, onKeepPlanning = { … PermissionAnswers.keepPlanning(feedback.text.toString()) … }, onOpenPlanFile = { planFileToOpen(card)?.let { openInEditor(project, it) } }) }`. `openInEditor` runs in `TandemProjectScope.get(project).scope` as its Interfaces line says; `ide/TandemSessions.kt` is not touched. No `setMode` request and no always-allow rule anywhere on this card (4.14). `genericCard` and its `else` branch go when T12 lands after T11 (T11 Step 3 states the rule).

- [ ] **Step 4: Pin T7's plan parse** (spec 4.14: parsed by the presenter, keyed by `requestId`; T7 promises `RenderedChat.planBlocks` under the key `plan:<requestId>` but tests it nowhere). `ChatPresenterTest` `a plan card's markdown is parsed off the EDT`: `val state = MutableStateFlow(ChatState())`; `val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)))`; `state.value = replay("plan-approve", 88)` → `presenter.chat.first { it.planBlocks != null }.planBlocks!!.first() is MarkdownBlock.Heading`, parsed on a `Dispatchers.Default` thread (the thread name does not start with `AWT-EventQueue`, asserted as T7's `expanding a markdown body parses it once off the EDT and re-emits the row` does); then `state.value = reduce(state.value, SessionEvent.PermissionAnswered("<request_id-97>"))` → `presenter.chat.first { it.card == null }.planBlocks == null` (the tool_result at line 91 leaves the request pending: only `PermissionAnswered`, a `control_cancel_request` or the `result` at line 156 drop it). Run `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.ChatPresenterTest'`; change `ChatPresenter.kt` only if it fails: add the parse to its `Dispatchers.Default` collector.

- [ ] **Step 5: Run the tests and the suite** — the Step 2 command plus `--tests 'dev.daarkolabs.tandem.ui.ChatPresenterTest'` → PASS; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → BUILD SUCCESSFUL. No IDE test for the plan card in 2a (spec 10.6: the frames and the picker follow-through are pinned by T3's fake-CLI tests `approving a plan to start coding sends setMode and the mode follows the status line`, `approving a plan keeps the mode without permissions`, `keeping planning denies with the feedback` and T2's reducer test); `runIde` checklist 10.7 item 8 is the owner's end-to-end check.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui
git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat: plan approval card for ExitPlanMode"
```

---

Slice-B review with the owner (spec 13): open item 1 (the dim behind the permission card: one modifier to drop), open item 2 (the capitalised title verbs and the proposed `Edit` / `Create` / `Run an agent` / `Use <server>: <tool>` / `Search the web for "<q>"` forms: one table in `Cards.permission`), open item 21 (`Keep planning` sends the feedback verbatim; `Approve, keep current mode` ends in `Default` by the CLI's own exit, so the label may want to read `Approve`); two recorded behaviours for the owner to confirm: Enter on a Tab-focused `Deny` still allows (the card root maps Enter, spec 4.18), and the question card maps Enter in `Other` through its outer preview (`QuestionKey.Submit`) rather than the field's own handler (spec 4.13/6; same behaviour); `runIde` checklist 10.7 items 6, 7 and 8 (card focus, the focused card's border and IME; the question card at 350 px and 900 px; plan mode against the real CLI).
