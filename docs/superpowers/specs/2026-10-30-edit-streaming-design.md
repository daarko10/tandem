# Edit streaming: the diff shows Claude's edit as it is written

Date: 2026-10-30. Builds on `2026-10-28-ide-edits-design.md` (the editable diff, Tandem's `edit_file` and `write_file`
tools). Section numbers below refer to this document; "the edits spec" means that one.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## 1. Goal

Today the diff opens when the complete `tools/call` arrives, so a long edit is a blank wait. The CLI streams the call's
input several seconds earlier, piece by piece (`stream_event` / `content_block_delta` / `input_json_delta`, recorded in
`ide-edit.ndjson` lines 26 to 36, CLI 2.1.312). The diff opens as soon as the stream names the file, and the proposal
on its right side grows while Claude writes it. When the `tools/call` arrives, the same diff becomes the editable
proposal of the edits spec, with Accept, Accept all and Reject. Nothing is applied before the user decides (hard rule 4).

Decided with the owner (2026-10-30): the live preview in the diff, not a progress line and not a preview in the chat;
one diff at a time stays; auto-accept modes get no preview; updates about ten times a second; tests at every layer and
one IDE test with the call held back.

## 2. What the user sees

### 2.1 The preview

1. Claude starts `edit_file` or `write_file`. Within a moment of the file path streaming in, the diff opens: the file on
   the left (as today: `Current`, `Current (unsaved)` or `New file`), `Proposed by Claude` on the right, title
   `Tandem: <name>`.
2. The right side is read-only while the preview lasts, and its text follows the stream:
   - `write_file`: the content so far. The diff grows line by line from an empty right side.
   - `edit_file`: while `old_string` is still streaming the right side equals the left and the bar reads
     `Claude is choosing what to change…`; once `new_string` starts, the right side is the file with the old text
     replaced by the new text so far, and the bar reads `Claude is writing…`. The old text is located with the same
     rule as the real edit (`EditMatcher`); when it is not found the right side stays equal to the left until the call,
     whose reply says so (the edits spec, 4.3).
3. The bar's buttons are present and disabled, tooltip `Available when Claude has finished writing`. Ctrl+S / ⌘S and
   Escape do nothing while the preview lasts.
4. The right side scrolls to keep the growing end visible.
5. The chat row of the call reads `Writing <name>…` with the working spinner, and its `Show diff` action focuses the
   preview, as `OPEN` does today.

### 2.2 The flip

When the `tools/call` arrives for the same tool use (`_meta.claudecode/toolUseId` equals the streamed block's `id`,
recorded in `ide-edit.ndjson` line 38), the open diff becomes the proposal: its right side is replaced by the final text
Tandem computes from the call (the edits spec, 4.3), becomes editable, the buttons and shortcuts turn on, the bar reads
as today, and the row's phase is `OPEN`. No second diff opens; the editor tab does not blink. From here on everything is
the edits spec: Accept with the user's changes, Accept all, Reject with a reason, Stop.

### 2.3 A preview that ends without a call

The stream can end with no `tools/call`: the user pressed Stop, the turn failed, the process ended, the model stopped
after the block, or the CLI answered the block itself (a mismatch it refused, a permission it denied). Then the preview
closes, its row ends as `Stopped` with the notice `Claude did not finish this edit`, and nothing was written.

### 2.4 What does not preview

- A call in `acceptEdits` or `bypassPermissions` mode, or after `Accept all` in the turn: it applies at the call, as today.
  (A later round may preview these too; the edits spec's 3.3 is unchanged.)
- A second edit while a diff is open, proposal or preview: its row reads `Writing <name>…`, and its diff opens at its call,
  behind the queue, as today (one diff at a time, the edits spec 3.4).
- A subagent's edit (`parent_tool_use_id` set): no preview; its diff opens at the call (the edits spec, 2.2).
- A path the tools refuse (outside the project and the session's folders, binary, over the size limit, a directory): no
  preview; the call's error reply says why, as today. The same check runs for the preview, so a refused path never opens
  a diff.
- A stream the parser cannot read (an unknown field order is fine; a malformed prefix is not): no preview until the call.

### 2.5 Strings (hard rule 8: none says "Claude Code")

`Claude is choosing what to change…`, `Claude is writing…`, `Available when Claude has finished writing`,
`Writing <name>…`, `Claude did not finish this edit`.

## 3. Where the data comes from

- The reducer already accumulates `input_json_delta` into `AssistantBlock.ToolUse.partialInput` for the streaming draft
  message (`Reducer.kt`, `ChatState.kt`). It now also feeds each delta to the block's `JsonPrefix` (4.1), kept beside
  `partialInput` as `ToolUse.inputPrefix`, for every tool (the reader is cheap and tool-agnostic; the chat's live tool
  rows may use it later). A preview is derived from a `ToolUse` block whose `name` is `mcp__tandem__edit_file` or
  `mcp__tandem__write_file`, in the main thread's draft (`parent_tool_use_id` null).
- The block's `id` (`<id-32>` in the recording) reappears as `tool_use_id` in the CLI's `can_use_tool` (line 37, which
  Tandem already auto-allows for its own tools) and as `_meta.claudecode/toolUseId` in the `tools/call` (line 38). That is
  the key that joins the preview to the proposal.
- `write_file`'s input is `{file_path, content}`, `edit_file`'s `{file_path, old_string, new_string, replace_all?}`
  (`TandemTools`). The model writes the fields in the schema's order in every recording; the parser accepts any order.

## 4. Data model and components

### 4.1 The parser: a JSON-prefix reader, and the tool knowledge apart from it (`protocol/`, pure Kotlin)

The CLI and the model's output change often, so the parser is built on four principles, each with the test that
enforces it:

1. **One job per class.** `JsonPrefixReader` knows JSON and nothing about tools; `EditToolFields` knows which field of
   which tool means what and nothing about JSON; `EditPreview` (4.2) joins them. A new tool or field is a new entry in
   `EditToolFields`, never a change to the reader.
2. **The whole grammar, not today's shape.** The reader follows any JSON value a prefix can begin: objects, arrays,
   strings with every escape, numbers, `true` / `false` / `null`, nesting to a bounded depth. The model writes
   `{"file_path": …, "old_string": …}` today; if it starts writing `{"edits": [{…}]}`, nested objects, or fields in
   another order, the reader still reads it and only `EditToolFields` has to learn the new path.
3. **Incremental and order-independent.** The reader is an immutable value fed one chunk at a time
   (`JsonPrefix.feed(chunk): JsonPrefix`); feeding the chunks of a stream in any split gives the same result as feeding
   the whole text at once. That is a property, and it is tested over every chunk boundary of the recorded stream. The
   reducer feeds each `input_json_delta` as it arrives (4.2), so a tick costs the delta's length, not the input's.
4. **Fail safe, never silent.** The reader never throws. A prefix that breaks the grammar freezes the value at the last
   well-formed point and sets `malformed = true`; the previewer then stops updating and the call decides, and the chat
   row says nothing wrong until the call's reply does (hard rule 5 is served by the reply, which is authoritative).
   Depth past the bound, a string past the tools' size limit (the edits spec, 4.3) and a non-object root are read as
   `malformed` too.

```kotlin
/** A JSON value as far as a stream has written it. Immutable: feed returns the next value. Never throws. */
sealed interface JsonPrefix {
    val complete: Boolean          // the value's last token was seen
    val malformed: Boolean         // the text left the grammar; the value is frozen at the last well-formed point
    data class Obj(val fields: Map<String, JsonPrefix>, val openKey: String?, ...) : JsonPrefix   // openKey: the key being read, value in fields once its first token arrives
    data class Arr(val items: List<JsonPrefix>, ...) : JsonPrefix
    data class Str(val text: String, ...) : JsonPrefix        // text: unescaped so far; a cut escape is held back until it completes
    data class Num(val text: String, ...) : JsonPrefix
    data class Bool(val value: Boolean, ...) : JsonPrefix
    data object Null : JsonPrefix
    data object Empty : JsonPrefix                            // nothing yet

    fun feed(chunk: String): JsonPrefix
}

object JsonPrefixReader {
    val START: JsonPrefix = JsonPrefix.Empty
    fun read(text: String): JsonPrefix = START.feed(text)
    const val MAX_DEPTH: Int = 32
}

/** Which fields of Tandem's tools the preview reads: one table, beside TandemTools, the server's own schema. */
object EditToolFields {
    data class Shape(val path: String, val oldText: String?, val newText: String?, val content: String?, val replaceAll: String?)
    val byTool: Map<String, Shape>   // "edit_file" to Shape("file_path", "old_string", "new_string", null, "replace_all"), "write_file" to Shape("file_path", null, null, "content", null)
    /** Reads the preview's fields out of a prefix by the tool's shape; absent or non-string fields read as null. */
    fun of(tool: String, prefix: JsonPrefix): EditFields?
}
```

`EditToolFields.byTool` is generated from the same descriptions `TandemTools` answers `tools/list` with (the input
schema's property names), so the stream reader and the server's advertised schema cannot drift apart: one test asserts
that every `Shape` field is a property of the tool's `inputSchema`, and the other way round for the fields the preview
needs.

**The contract with the CLI** (what would break this, and the test that would tell us): the preview assumes that the
concatenated `partial_json` of a block is a prefix of the call's `arguments` and ends equal to it, and that the call
names the block in `_meta.claudecode/toolUseId`. `ContractTest` and `FixtureDecodeTest` assert both on every `ide-edit*`
fixture and on the nightly's recording, so a CLI that starts sending snapshots instead of deltas, re-keys the meta, or
reorders frames turns the nightly red and lands in the drift loop as decoder drift (`classify_drift.py` learns the two
assertions' messages). The previewer itself treats a missing or mismatched key as "no preview" (2.4), never as an error.

### 4.2 `session/`: the preview in `ChatState`

- `EditPhase` gains `WRITING`, before `QUEUED`. `EditReview` is unchanged: `EditReview(file, WRITING)` is the row's state
  while the block streams and before any call event.
- The reducer sets `edits[block.id] = EditReview(name, WRITING)` when a tandem tool block in the main draft yields a
  complete or open `file_path` whose file name is non-empty, and leaves it alone afterwards: the call's `EditEvent`s move it
  to `QUEUED` / `OPEN` / `DONE` as today. A `result`, an interrupt (`Stopped`) or the process' end while a review is
  still `WRITING` ends it as `DONE` / `STOPPED` with the 2.3 notice, as does the previewer's own `EditPhase` event
  (4.3) (hard rule 5: the row never stays "writing" after the turn).
- `SessionHandle` exposes `previews: Flow<EditPreview?>` derived from `state`:

```kotlin
/** What the stream has said about a tandem tool call so far; null when no main-thread tandem tool block is streaming. */
data class EditPreview(
    val toolUseId: String,
    val tool: String,              // "edit_file" or "write_file"
    val path: String,              // file_path, complete or so far
    val pathComplete: Boolean,
    val oldString: String?,        // edit_file: complete old_string, else null
    val newString: String?,        // edit_file: new_string so far once it started, else null
    val content: String?,          // write_file: content so far once it started, else null
)
```

  `previews` is `state.map { it.preview() }.distinctUntilChanged().sample(100.ms)`-shaped: at most ten emissions a
  second, the latest wins, computed off the EDT (the handle's scope). It emits null when the streaming block is gone
  (the assistant message completed, with or without a call). The flow is the only new surface of `session/`.

### 4.3 `ide/edits/`

- `DiffPresenter` gains:

```kotlin
/** Opens the preview diff for [toolUseId] on [file]/[document] (or a new file at [path]) with [text] on the right, read-only, buttons off. */
fun preview(session: SessionId, toolUseId: String, target: EditTarget, text: String, stage: PreviewStage)
/** Replaces the preview's right side; a no-op when no preview for [toolUseId] is shown. */
fun updatePreview(toolUseId: String, text: String, stage: PreviewStage)
/** Closes the preview for [toolUseId], if shown. */
fun closePreview(toolUseId: String)
```

  `PreviewStage` is `CHOOSING` or `WRITING` (the bar's text). `EditTarget` is the located file: `file`, `document`,
  `path`, `isNew`, `fileType`, built by the same `IdeEditTools` path check the call uses (refactored out of `edit`/`write`
  into `locate(path): EditTarget` with the existing refusals).
- `EditDiff` keeps `Shown` as today plus the preview's `toolUseId`. `preview` builds the same request as `open` (left
  content, right `createEditable`, the bar, the chain) with `FORCE_READ_ONLY_CONTENTS = [true, true]`, the buttons
  disabled, the actions unregistered, and the bar text by stage. `updatePreview` writes the right document inside a write
  action on the EDT and scrolls its editor to the end. `show(proposal)`: when `shown.toolUseId == proposal.call.toolUseId`
  it adopts the open diff: right text replaced by `proposal.proposed`, the right side writable (the request's user data is
  replaced and the viewer rebuilt by `DiffRequestProcessor.updateRequest`, which keeps the tab), buttons and shortcuts on,
  bar text as today; otherwise as today, after closing any preview still open.
- `EditQueue` is unchanged. `TandemEdits` owns a new `EditPreviewer`:
  - collects every live session's `previews` (`EditSessions` gains `previews(id): Flow<EditPreview?>` and the sessions
    list it needs; `TandemSessions` wires it to the manager);
  - on a non-null preview with a complete path, when `queue.open.value == null` and no other preview is shown and the
    session's mode is not auto-accept: `locate`, then `presenter.preview(...)` with the 2.1 text;
  - on later emissions for the same `toolUseId`: `updatePreview` with the recomputed text;
  - on null, on `cancel`/`cancelAll`, on the turn's end: `closePreview` for that id, and, when no call came,
    `EditEvent.EditPhase(toolUseId, EditReview(name, DONE, STOPPED))` with the 2.3 notice to the session;
  - the text: `write_file` → `content ?: ""`; `edit_file` → `original` until `newString != null`, then
    `EditMatcher.match(original, oldString, replaceAll = false)` applied with `newString` when it matches once, else
    `original`.
- The flip is `show(proposal)` adopting the diff (above). The proposal is built from the call exactly as today, so the
  final text, the ranges and the result texts are unchanged.

### 4.4 `chat/`

`ChatRowsBuilder`: `WRITING` reads `Writing <name>…` with the spinner (`ToolLabels.ReviewLabel.WRITING`) and carries the
`ShowDiff` action, like `OPEN`. The decided row is as today.

## 5. Flows

1. **Edit with a preview.** `content_block_start` (tandem tool) → deltas → reducer: `partialInput` grows, `edits[id] =
   WRITING` at the first path → `previews` emits → `EditPreviewer.locate` + `preview` (CHOOSING) → deltas → `updatePreview`
   (WRITING once `new_string` starts) → `assistant` message, `can_use_tool` (auto-allowed), `tools/call` → `IdeEditTools`
   builds the proposal → `queue.enqueue` → `show` adopts the diff → user decides → reply, as today.
2. **Stop during the preview.** Stop → the CLI's interrupt → the stream stops, the handle's `Stopped` → `previews` emits
   null → `closePreview`, `EditEvent.Stopped` → row `Stopped`, notice 2.3. A late `tools/call` for that id (the CLI sent it
   before the interrupt landed) opens the diff as today: the adoption key is gone, the ordinary path runs.
3. **A second edit while the first diff is open.** Its preview is skipped (4.3); its row is `WRITING`; its call queues
   (`QUEUED`), and its diff opens when the first is decided, as today.
4. **Auto-accept.** No preview; `WRITING` row; the call applies; row `AUTO`.
5. **The call for a different id than the preview** (an earlier preview's call never came, the CLI moved on): the preview
   closes with the 2.3 notice, then the call's diff opens as today.

## 6. Errors and edge cases

- The parser never throws; a malformed prefix gives the fields read so far (4.1). A preview whose path changes after it
  opened (impossible in a well-formed stream; a model error) closes the preview and the call decides.
- `locate` refuses the path: no preview, no notice; the call's reply says why (as today).
- The document changes under the preview (the user types in the editor): the left side is the live document, the right
  side is recomputed from the current text on the next tick (`original` is read each tick, off the EDT via
  `ReadAction`). At the call, the proposal is built from the document as it is then, as today.
- The CLI withdraws the call (`notifications/cancelled`) during the review: unchanged from the edits spec.
- Two tandem blocks in one message (the model issues two calls at once): the first previews, the second only at its
  call (one diff at a time).
- `write_file` of a huge file: the right side is bounded by the tools' size limit; the throttle keeps the EDT to ten
  document replacements a second, each a single `setText`.
- The IDE closes the diff tab during the preview (the user closes it): the preview is gone; the call opens the diff anew.

## 7. Performance

Parsing and text building run on the handle's dispatcher; the EDT sees one `setText` per tick at most. `sample(100 ms)`
bounds the rate. Hard rule 1 holds: no I/O, no parsing on the EDT.

## 8. Testing

- **Fixture (hard rule 6):** `JsonPrefixReaderTest` replays the `input_json_delta` chunks of block `<id-32>` in
  `ide-edit.ndjson` one by one and asserts the value after each (`file_path` open, complete; `old_string` complete;
  `new_string` open "beta"; complete), then the property of 4.1 principle 3: the same text split at every character
  boundary, and at every pair of boundaries, gives the same value at the end and never a `malformed` one on the way.
  Synthetic cases cover the grammar: escapes split across chunks (`\"`, `\\`, `\n`, `\uXXXX` cut at each of its six
  characters), nested objects and arrays, numbers, booleans and null, whitespace between tokens, a key cut mid-word,
  depth past the bound, a non-object root, and garbage at each position (frozen value, `malformed`, no throw).
  `EditToolFieldsTest` reads both tools' shapes from prefixes at each stage and from a nested shape it does not know
  (null fields, no throw), and checks the shapes against `TandemTools`' schemas. A recording of a `write_file` call
  (`scripts/record-scenario.sh ide-edit-write`, a new scenario that asks for a new file; needs a logged-in `claude`) is
  added when the recorder is available; until then the `write_file` case is synthetic, and the test says so.
- **Contract (the nightly):** `ContractTest` and `FixtureDecodeTest` assert, for every tandem tool block in the
  `ide-edit*` fixtures and the nightly's `ide-edit` recording, that the block's concatenated `partial_json` equals the
  `tools/call`'s `arguments` as JSON text and that `_meta.claudecode/toolUseId` names the block (4.1).
- **Reducer:** `WRITING` appears at the first path, survives `MessageStop`, moves on with the call's events, and ends
  `STOPPED` on `result` / interrupt / process end; `previews` emits the stages and null at the end; a subagent's block
  and a non-tandem tool never produce one.
- **Platform:** `EditDiffTest`: `preview` opens read-only with the buttons disabled and the stage text; `updatePreview`
  replaces the right text; `show` adopts (same tab, text replaced, editable, buttons on, Accept returns the edited
  text); `closePreview` closes. `TandemEditsTest` with a fake presenter: preview → call adopts; preview then null → closed
  and `Stopped` event; second preview skipped while a diff is open; auto-accept mode previews nothing.
- **Fake CLI:** no new switch: `TANDEM_FAKE_LINE_DELAY_MS` paces the deltas and `TANDEM_FAKE_GATE_FILE` holds the
  `tools/call`.
- **IDE test (one, `ToolWindowIdeTest`):** with the call held, the diff shows `Proposed by Claude` read-only with `beta`
  and disabled buttons and the row says `Writing hello.txt…`; the gate file is created; the buttons enable; Accept
  writes the file, as the existing edit test checks.
- **Owner's `runIde` pass:** the feel of the growing right side, the scroll, and the flip's lack of flicker.

## 9. Task slices (dependencies in brackets; each green on its own)

T1 `JsonPrefixReader` and `EditToolFields` + fixture, property and contract tests. T2 reducer `WRITING`, `EditPreview`, `previews` [T1]. T3 `IdeEditTools.locate`
refactor, `DiffPresenter.preview/updatePreview/closePreview`, `EditDiff` adoption [none]. T4 `EditPreviewer` in
`TandemEdits`, `EditSessions.previews`, `TandemSessions` wiring, tests [T2, T3]. T5 chat row `WRITING` + `Show diff`
[T2]. T6 IDE test [T4, T5]. T7 docs: `docs/design.md` (the edit flow), `docs/protocol.md` (the delta-to-call key,
`_meta.claudecode/toolUseId`), `CLAUDE.md` layout lines, the recording scenario [T6].

Model tiers for the plan: the plan and the whole-branch review on Fable; implementation and per-task review on Opus;
docs and test scaffolding on Sonnet.

## 10. Deferred, with the reason

- Preview in auto-accept modes: the edit lands within the same second; the preview would flash.
- Several previews at once, or a preview beside the chat instead of the editor area: one diff at a time was decided.
- Per-hunk accept during the preview; editing the right side before the call: the final text replaces it at the flip,
  so edits would be lost; the edits spec's rule (edit after the call) stays.
- A setting to turn previews off: the preview costs nothing until a tandem tool streams; revisit if a user asks.
- Subagent previews: unrecorded (the edits spec, 11 item 8).
