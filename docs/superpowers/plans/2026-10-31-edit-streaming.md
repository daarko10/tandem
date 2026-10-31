# Edit Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The diff opens as soon as the CLI's stream names the file of an `edit_file` / `write_file` call and its right side grows while Claude writes; the complete `tools/call` turns the same diff into today's editable proposal.

**Architecture:** A tool-agnostic incremental JSON-prefix reader in `protocol/` (`JsonPrefix`, fed one `input_json_delta` at a time) and a table of the tools' field names beside `TandemTools`; the reducer keeps the prefix on the streaming `ToolUse` block and derives an `EditPreview`; `SessionHandle.previews` emits it throttled; `EditPreviewer` in `ide/edits/` opens, updates and closes a read-only preview diff through `DiffPresenter`, and `EditDiff.show` adopts the open preview when the call for the same `toolUseId` arrives.

**Tech Stack:** Kotlin, kotlinx.coroutines (`sample`), kotlinx.serialization.json, IntelliJ diff API (`SimpleDiffRequest`, `DiffRequestProcessor`), JUnit 5, the fake CLI, IDE Starter.

**Spec:** `docs/superpowers/specs/2026-10-30-edit-streaming-design.md`

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- Hard rule 1: parsing and text building off the EDT; the EDT only applies text (spec 7). Hard rule 4: nothing is written before the user decides. Hard rule 5: a preview that ends without a call ends its row as `Stopped` with `Claude did not finish this edit`. Hard rule 6: the reader's test replays `ide-edit.ndjson`. Hard rule 8: the strings of spec 2.5 and nothing else new.
- `protocol/` and `chat/` import no IntelliJ; `session/` imports only what CLAUDE.md allows.
- Throttle: `sample(100.milliseconds)`; `JsonPrefixReader.MAX_DEPTH = 32`; strings past `IdeEditTools.MAX_CONTENT_BYTES` read as malformed (spec 4.1).
- The reader never throws, on any input (spec 4.1 principle 4).
- Coverage stays at 90 % per package of `protocol/`, `session/`, `chat/` (`koverVerify`).
- Commits are Conventional Commits, authored by the owner, no AI attribution lines.
- Model tiers: implementation and per-task review on Opus; docs and test scaffolding on Sonnet; the whole-branch review on Fable.

## Review Focus

1. A `😀` surrogate pair split across two deltas: the string must never show a lone surrogate (test in Task 1).
2. `old_string` found twice in the file: the preview shows the file unchanged, no guess (test in Task 6).
3. A path outside the roots or a binary file streamed as `file_path`: no diff opens, no notice; the call's reply says why (test in Task 6).
4. The user closes the preview's tab, then the call arrives: the diff opens anew as today, with the final text (test in Task 5).
5. Stop pressed, then the call for the stopped preview still arrives: the ordinary diff opens; nothing adopts a closed preview (test in Task 6).

---

### Task 1: `JsonPrefix`, the incremental JSON-prefix reader

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/protocol/JsonPrefix.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/JsonPrefixReaderTest.kt`

**Interfaces:**
- Produces:

```kotlin
sealed interface JsonPrefix {
    val complete: Boolean
    val malformed: Boolean
    fun feed(chunk: String): JsonPrefix
    data class Obj(val fields: Map<String, JsonPrefix>, val openKey: String?, override val complete: Boolean, override val malformed: Boolean) : JsonPrefix
    data class Arr(val items: List<JsonPrefix>, override val complete: Boolean, override val malformed: Boolean) : JsonPrefix
    data class Str(val text: String, override val complete: Boolean, override val malformed: Boolean) : JsonPrefix
    data class Num(val text: String, override val complete: Boolean, override val malformed: Boolean) : JsonPrefix
    data class Bool(val value: Boolean, override val malformed: Boolean) : JsonPrefix   // complete = true
    data object Null : JsonPrefix
    data object Empty : JsonPrefix
}
object JsonPrefixReader {
    const val MAX_DEPTH: Int = 32
    val START: JsonPrefix = JsonPrefix.Empty
    fun read(text: String): JsonPrefix = START.feed(text)
    /** The string at [path] (keys), null when absent, not a string, or the root is not an object. */
    fun string(prefix: JsonPrefix, vararg path: String): JsonPrefix.Str?
}
```

  `Obj.openKey` is the key whose value is being read; that value is in `fields` from its first token on, with `complete = false`. `Str.text` is unescaped so far; an escape cut by the chunk end is held back. A `Str` carries `malformed = true` past `JsonPrefixReader.MAX_STRING_CHARS = 10 * 1024 * 1024`. The implementation keeps the lexer state (inside a string, pending escape chars, the token so far) in private fields of the data classes' companions or a private wrapper; the public shape above is what tests see. `feed` on a `complete` or `malformed` value returns it unchanged.

- [ ] **Step 1: Write the failing tests**

```kotlin
class JsonPrefixReaderTest {
    private val deltas: List<String> = Fixtures.decode("ide-edit")
        .filterIsInstance<CliMessage.StreamEvent>()   // the recorded block <id-32>: the partial_json of its content_block_delta frames, in order
        .let { events -> partialJsonOf(events, blockId = "<id-32>") }

    @Test fun `the recorded edit_file stream reads field by field`() {
        var p: JsonPrefix = JsonPrefixReader.START
        val stages = deltas.map { p = p.feed(it); p }
        // after "{\"file_path\": \"/t": file_path open
        assertEquals("/t", JsonPrefixReader.string(stages[1], "file_path")!!.text); assertFalse(stages[1].complete)
        // after "\", \"old_string\": \"alpha": file_path complete, old_string open
        assertEquals("<path>/hello.txt", JsonPrefixReader.string(stages[4], "file_path")!!.text)
        assertEquals("alpha", JsonPrefixReader.string(stages[4], "old_string")!!.text); assertFalse(JsonPrefixReader.string(stages[4], "old_string")!!.complete)
        assertEquals("beta", JsonPrefixReader.string(stages[5], "new_string")!!.text)
        assertTrue(stages.last().complete); assertFalse(stages.any { it.malformed })
        assertEquals(JsonPrefixReader.read(deltas.joinToString("")), stages.last())
    }

    @Test fun `every split of the recorded text gives the same value`() {
        val text = deltas.joinToString("")
        val whole = JsonPrefixReader.read(text)
        for (i in 0..text.length) {
            assertEquals(whole, JsonPrefixReader.START.feed(text.substring(0, i)).feed(text.substring(i)), "split at $i")
            for (j in i..text.length) assertEquals(whole, JsonPrefixReader.START.feed(text.substring(0, i)).feed(text.substring(i, j)).feed(text.substring(j)))
        }
    }

    @Test fun `escapes cut by a chunk end are held back`() {
        for (esc in listOf("\\\"", "\\\\", "\\n", "\\u00e9", "\\uD83D\\uDE00")) {
            val text = "{\"s\": \"a${esc}b\"}"
            val whole = JsonPrefixReader.string(JsonPrefixReader.read(text), "s")!!.text
            for (i in 0..text.length) {
                val cut = JsonPrefixReader.START.feed(text.substring(0, i))
                val so_far = JsonPrefixReader.string(cut, "s")?.text ?: ""
                assertTrue(whole.startsWith(so_far), "no half escape at $i: $so_far")
                assertFalse(so_far.any { it.isSurrogate() } && so_far.last().isHighSurrogate(), "no lone surrogate at $i")
            }
        }
    }

    @Test fun `the grammar: nesting, arrays, numbers, booleans, null, whitespace`() {
        val p = JsonPrefixReader.read(""" { "edits" : [ { "old": "a", "n": 12.5, "all": true, "x": null } ], "k" """)
        val edits = (p as JsonPrefix.Obj).fields["edits"] as JsonPrefix.Arr
        val first = edits.items.single() as JsonPrefix.Obj
        assertEquals("a", (first.fields["old"] as JsonPrefix.Str).text)
        assertEquals("12.5", (first.fields["n"] as JsonPrefix.Num).text)
        assertEquals(true, (first.fields["all"] as JsonPrefix.Bool).value)
        assertEquals(JsonPrefix.Null, first.fields["x"])
        assertEquals("k", p.openKey); assertFalse(p.complete); assertFalse(p.malformed)
    }

    @Test fun `a key cut mid word is not a field yet`() {
        val p = JsonPrefixReader.read("""{"file_pa""") as JsonPrefix.Obj
        assertTrue(p.fields.isEmpty()); assertNull(p.openKey)
    }

    @Test fun `garbage freezes the value and marks it malformed, never throws`() {
        val good = """{"file_path": "a.txt", "old_string": "x"""
        for (bad in listOf("$good, 7: 1", "$good}}", "[1", "tru", "{\"a\" 1}", "\u0000", "{\"a\": \"b\"} trailing")) {
            val p = JsonPrefixReader.read(bad)
            if (bad.startsWith(good)) assertEquals("a.txt", JsonPrefixReader.string(p, "file_path")!!.text)
            assertTrue(p.malformed || p.complete, bad)
            assertEquals(p, p.feed("more"))
        }
        val deep = "[".repeat(JsonPrefixReader.MAX_DEPTH + 1)
        assertTrue(JsonPrefixReader.read(deep).malformed)
    }
}
```

  `partialJsonOf` is a private helper in the test: the `partial_json` of every `content_block_delta` whose `index` is the index of the `content_block_start` carrying `id == blockId` (both are in the decoded `StreamEvent`s of `Fixtures.decode`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.protocol.JsonPrefixReaderTest'`
Expected: compilation failure, `JsonPrefix` unresolved.

- [ ] **Step 3: Implement `JsonPrefix.kt`**

A recursive-descent state machine over chunks: a private `Lexer` value (the pending partial token, the escape buffer, the depth stack) travels with the public value; `feed` appends the chunk and advances as far as complete tokens allow. Strings are unescaped on the fly; a trailing `\`, an incomplete `\uXXXX`, or a high surrogate whose low escape has not arrived stay in the escape buffer. Any token the grammar does not allow, depth past `MAX_DEPTH`, a non-object/array/scalar root prefix, or a string past `MAX_STRING_CHARS` sets `malformed` on the value where it happened and on the root, and freezes `feed`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.protocol.JsonPrefixReaderTest'`
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/protocol/JsonPrefix.kt src/test/kotlin/dev/daarkolabs/tandem/protocol/JsonPrefixReaderTest.kt
git commit -m "feat(protocol): an incremental JSON-prefix reader for streamed tool input"
```

### Task 2: `EditToolFields`, the tools' fields beside their schema

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/protocol/EditToolFields.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/protocol/TandemTools.kt:40-90` (the property names become constants the shapes reuse)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/EditToolFieldsTest.kt`

**Interfaces:**
- Consumes: `JsonPrefix`, `JsonPrefixReader.string` (Task 1); `TandemTools.EDIT_FILE`, `TandemTools.WRITE_FILE`, and the `tools/list` answer (`TandemToolsTest` shows the call).
- Produces:

```kotlin
object EditToolFields {
    data class Shape(val path: String, val oldText: String?, val newText: String?, val content: String?, val replaceAll: String?)
    val byTool: Map<String, Shape>   // keyed "edit_file", "write_file"
    /** Strips the CLI's `mcp__tandem__` prefix; null for another server's tool. */
    fun toolOf(blockName: String): String?
    fun of(tool: String, prefix: JsonPrefix): EditFields?   // null for an unknown tool or a non-object prefix
}
data class EditFields(
    val path: JsonPrefix.Str?, val oldText: JsonPrefix.Str?, val newText: JsonPrefix.Str?, val content: JsonPrefix.Str?,
)
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `edit_file fields at each stage of the recorded stream`()   // path open → complete; oldText complete; newText "beta"; content null
@Test fun `write_file fields from a synthetic stream`()                 // {"file_path": "a.md", "content": "# T\n" → content.text == "# T\n", not complete (synthetic: no write_file recording yet, spec 8)
@Test fun `a nested shape it does not know reads null fields, no throw`() // {"edits":[{"old_string":"a"}]} → EditFields(null, null, null, null)
@Test fun `toolOf strips the server prefix`()                           // "mcp__tandem__edit_file" → "edit_file"; "mcp__other__edit_file" → null; "Bash" → null
@Test fun `every shape field is a property of the tool's inputSchema, and the required ones are shaped`() // read tools/list through TandemTools.answer as TandemToolsTest does
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.protocol.EditToolFieldsTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `EditToolFields.kt`; make `TandemTools` build its `inputSchema` property names from `EditToolFields.byTool`** (`FILE_PATH`, `OLD_STRING`, `NEW_STRING`, `CONTENT`, `REPLACE_ALL` constants in `TandemTools`, the shapes referring to them).

- [ ] **Step 4: Run the tests, then the protocol package, to verify they pass**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.protocol.*'`
Expected: PASS, `TandemToolsTest` unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/protocol/EditToolFields.kt src/main/kotlin/dev/daarkolabs/tandem/protocol/TandemTools.kt src/test/kotlin/dev/daarkolabs/tandem/protocol/EditToolFieldsTest.kt
git commit -m "feat(protocol): the edit tools' field table, shared with their schema"
```

### Task 3: The stream contract in the fixture and nightly tests

**Files:**
- Modify: `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt` (a new test over every `ide-edit*` fixture)
- Modify: `src/test/kotlin/dev/daarkolabs/tandem/protocol/ContractTest.kt:120-140` (the same assertion on the nightly's `ide-edit` recording)
- Modify: `scripts/classify_drift.py` and its test `scripts/test_classify_drift.py`

**Interfaces:**
- Consumes: `Fixtures.decode`, `CliMessage.StreamEvent`, `CliMessage.ControlRequest` with `mcp_message` / `tools/call` (see `HostMessagesTest` for the decoded shape and `_meta`).

- [ ] **Step 1: Write the failing test in `FixtureDecodeTest`**

```kotlin
@Test fun `ide-edit - stream contract - a tandem block's deltas concatenate to its call's arguments and the call names the block`() {
    for (name in Fixtures.names().filter { it.startsWith("ide-edit") }) {
        val messages = Fixtures.decode(name)
        val blocks = tandemToolBlocks(messages)            // id → concatenated partial_json, from content_block_start/delta of names starting "mcp__tandem__"
        val calls = tandemToolCalls(messages)              // toolUseId (_meta["claudecode/toolUseId"]) → arguments JsonObject
        assertTrue(blocks.isNotEmpty(), name)
        for ((id, json) in blocks) {
            val arguments = assertNotNull(calls[id], "stream contract: $name: no tools/call names block $id")
            assertEquals(arguments, Json.parseToJsonElement(json).jsonObject, "stream contract: $name: block $id's deltas are not its call's arguments")
        }
    }
}
```

  (`ide-edit-stop`'s withdrawn call: the block whose call the CLI never sent is skipped by the test when `calls` lacks it and the fixture's name is `ide-edit-stop`; state that in the test.)

- [ ] **Step 2: Run it to verify it passes on the recordings** (the contract holds today)

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.protocol.FixtureDecodeTest'`
Expected: PASS.

- [ ] **Step 3: Add the same assertion to `ContractTest` for the `ide-edit` recording, named `ide-edit - stream contract …`**, sharing the two helpers (move them to `Fixtures.kt`).

- [ ] **Step 4: `classify_drift.py`: a red `ContractTest` whose failing test name contains `stream contract` classifies as `decoder`**; add that case to `scripts/test_classify_drift.py` beside the existing decoder case.

Run: `cd scripts && python3 -m unittest test_classify_drift` and `./gradlew test --tests '*ContractTest*'`
Expected: PASS (the contract test skips without `-Dtandem.contractDir`, as today).

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt src/test/kotlin/dev/daarkolabs/tandem/protocol/ContractTest.kt src/test/kotlin/dev/daarkolabs/tandem/protocol/Fixtures.kt scripts/classify_drift.py scripts/test_classify_drift.py
git commit -m "test(protocol): the stream contract: a tandem block's deltas are its call's arguments"
```

### Task 4: The preview in `session/`: `inputPrefix`, `WRITING`, `EditPreview`, `previews`

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt:140-152` (`EditPhase.WRITING`; `AssistantBlock.ToolUse.inputPrefix: JsonPrefix`), and a new `EditPreview` data class + `ChatState.preview(): EditPreview?` in a new file `src/main/kotlin/dev/daarkolabs/tandem/session/EditPreview.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/Reducer.kt:880-900` (feed the prefix; set `WRITING`), `:489` (`Result` ends `WRITING`), `:214` (`Ended` ends it), the `Stopped` branch
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/SessionHandle.kt:197-213` (`previews`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`, `src/test/kotlin/dev/daarkolabs/tandem/session/SessionManagerTest.kt`

**Interfaces:**
- Consumes: `JsonPrefix`, `JsonPrefixReader.START` (Task 1); `EditToolFields.toolOf`, `EditToolFields.of` (Task 2).
- Produces:

```kotlin
enum class EditPhase { WRITING, QUEUED, OPEN, DONE }
data class EditPreview(val toolUseId: String, val tool: String, val path: String, val pathComplete: Boolean,
                       val oldString: String?, val newString: String?, val content: String?)
fun ChatState.preview(): EditPreview?        // the main draft's streaming tandem block, null otherwise (spec 4.2)
val SessionHandle.previews: Flow<EditPreview?> // state.map { it.preview() }.distinctUntilChanged().sample(100.milliseconds) on the handle's scope
const val PREVIEW_STOPPED_NOTICE = "Claude did not finish this edit"   // in EditResults or ChatState's companion, where the other row notices live
```

  `preview()`: the `ToolUse` block of the draft assistant message with `parent == null`, `input == null` (still streaming), `EditToolFields.toolOf(name) != null`; `EditFields` read from `inputPrefix`; `path` is `fields.path?.text` and the preview is null until it is non-empty; `oldString` only when `fields.oldText?.complete == true`; `newString` / `content` the `.text` of the field once present. The reducer sets `edits[id] = EditReview(fileName(path), WRITING)` when `preview()` first yields that id and `edits` has no entry; `Result`, `Ended` and the interrupt's `Stopped` turn every `WRITING` entry into `EditReview(file, DONE, STOPPED)` and append the notice `PREVIEW_STOPPED_NOTICE` the way `STOPPED_WAITING` reaches the chat today (follow `SessionEvent.EditPhase` and the existing host notices).

- [ ] **Step 1: Write the failing tests**

```kotlin
// ReducerTest
@Test fun `a tandem block's deltas build its inputPrefix and set WRITING at the first path`()   // replay ide-edit lines 25-36 through the reducer; after the second delta edits["<id-32>"] == EditReview("hello.txt", WRITING); preview().path == "/t" after delta 1, pathComplete after delta 4; oldString == "alpha" after delta 5; newString == "beta" after delta 6
@Test fun `WRITING survives MessageStop and the call's EditPhase events move it on`()            // after message_stop: still WRITING; after EditPhase(QUEUED): QUEUED
@Test fun `a result, an interrupt or the process end ends a WRITING review as STOPPED with the notice`()
@Test fun `a subagent's block and another server's tool make no preview`()                       // parent_tool_use_id set; name "Bash"; name "mcp__other__edit_file"
@Test fun `preview is null once the block completes`()                                           // after the assistant message with input: preview() == null
// SessionManagerTest (the handle)
@Test fun `previews emits the stages at most ten times a second and null at the end`()          // feed 30 deltas within 100 ms of virtual time: ≤ 2 emissions; then the completed message: null
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.session.ReducerTest' --tests 'dev.daarkolabs.tandem.session.SessionManagerTest'`
Expected: compilation failure (`WRITING`, `preview`, `previews`).

- [ ] **Step 3: Implement** `ToolUse.inputPrefix` (default `JsonPrefixReader.START`; the `ContentBlockDelta` branch feeds `delta.partialJson` to it beside `partialInput`), `EditPreview.kt`, the `WRITING` transitions, and `SessionHandle.previews` (a `Flow`, not a `StateFlow`: `sample` needs a cold flow; `flowOn` the handle's dispatcher).

- [ ] **Step 4: Run the session tests to verify they pass**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.session.*'`
Expected: PASS; `koverLog` shows `session` ≥ 90 %.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session
git commit -m "feat(session): the edit preview: the streaming block's prefix, the WRITING phase and a throttled previews flow"
```

### Task 5: The preview diff: `DiffPresenter.preview`, `EditDiff` adoption, `IdeEditTools.locate`

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ide/edits/TandemEdits.kt:40-50` (`DiffPresenter` gains three methods)
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ide/edits/EditDiff.kt` (`Shown.toolUseId`, `Shown.previewing`; `preview`, `updatePreview`, `closePreview`; `show` adopts)
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ide/edits/IdeEditTools.kt:243-335` (`locate` factored out of `propose`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ide/edits/EditDiffTest.kt`, `src/test/kotlin/dev/daarkolabs/tandem/ide/edits/IdeEditToolsTest.kt`

**Interfaces:**
- Consumes: `EditProposal`, `Decision`, `EditDiff.Shown` (today), `EditDiffTest.withDiff` / `button` / `find` helpers.
- Produces:

```kotlin
enum class PreviewStage { CHOOSING, WRITING }
/** A file the tools may edit, as the preview and the call see it; a refusal carries the reason the call's reply would. */
sealed interface Located {
    data class Target(val path: Path, val file: VirtualFile?, val document: Document?, val isNew: Boolean, val fileType: FileType) : Located
    data class Refused(val reason: String) : Located
}
suspend fun IdeEditTools.locate(given: String, session: SessionId): Located   // the same checks propose() runs: roots, real path, directory, binary, size
interface DiffPresenter {
    suspend fun preview(session: SessionId, toolUseId: String, target: Located.Target, text: String, stage: PreviewStage)
    fun updatePreview(toolUseId: String, text: String, stage: PreviewStage)
    fun closePreview(toolUseId: String)
    // show, focus, close as today
}
// EditDiff companion strings
const val BAR_CHOOSING = "Claude is choosing what to change…"; const val BAR_WRITING = "Claude is writing…"
const val DISABLED_TOOLTIP = "Available when Claude has finished writing"
```

  `Shown` gains `toolUseId: String?` and `previewing: Boolean`. `preview` builds the request as `open` does with `FORCE_READ_ONLY_CONTENTS = [true, true]`, the buttons disabled with `DISABLED_TOOLTIP`, no shortcut registration, the bar's label by stage. `updatePreview` runs `WriteCommandAction`-free `runWriteAction { right.document.setText(text) }` on the EDT and `editor.scrollingModel.scrollTo(end)` on the right editor when present. `show(proposal)` when `shown?.toolUseId == proposal.call.toolUseId && shown.previewing`: sets the right text, replaces the request's read-only data with `[true, false]`, enables the buttons, registers the shortcuts, sets the bar label as today, `previewing = false`, keeps the tab (`DiffRequestProcessor.updateRequest` on the editor's processor, found as `focus` finds the file); otherwise closes any preview and opens as today. `closePreview` dismisses a shown preview with that id; a dismissed preview completes nothing (its `result` is unused).

- [ ] **Step 1: Write the failing tests**

```kotlin
// EditDiffTest
@Test fun `preview opens read-only with the buttons off and the stage text`()        // preview(...CHOOSING): both sides read-only (editors' isViewer), Accept/Accept all/Reject disabled with DISABLED_TOOLTIP, bar text BAR_CHOOSING
@Test fun `updatePreview replaces the right text and the stage`()                     // text "alpha\nbeta\n", WRITING → right.document.text, bar BAR_WRITING
@Test fun `show adopts the preview for the same toolUseId in the same tab`()          // proposal.call.toolUseId == preview's: shown.file === before, right text == proposal.proposed, right writable, buttons enabled; typing then Accept returns the typed text
@Test fun `show for another toolUseId closes the preview and opens anew`()
@Test fun `closePreview closes it and a later show opens a fresh diff`()              // Review Focus 4
// IdeEditToolsTest
@Test fun `locate refuses what propose refuses and targets what it accepts`()         // outside the roots → Refused(reason as the call's reply says); a binary file → Refused; hello.txt → Target(isNew = false, document non-null); a missing file under a root → Target(isNew = true)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.ide.edits.EditDiffTest' --tests 'dev.daarkolabs.tandem.ide.edits.IdeEditToolsTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement** `locate` (and `propose` calling it, no behaviour change), the three presenter methods in `EditDiff`, and the adoption in `show`.

- [ ] **Step 4: Run the edits tests to verify they pass**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.ide.edits.*'`
Expected: PASS, the existing tests untouched.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/ide/edits src/test/kotlin/dev/daarkolabs/tandem/ide/edits
git commit -m "feat(ide): a read-only preview diff that the call's proposal adopts in place"
```

### Task 6: `EditPreviewer`: from `previews` to the diff and back

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ide/edits/EditPreviewer.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ide/edits/TandemEdits.kt` (`EditSessions.previews(id)`, `EditSessions.live(): Flow<List<SessionId>>`; the previewer launched in `init`; `cancel`/`cancelAll` close previews; `ManagerSessions` wires both to the manager)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ide/edits/TandemEditsTest.kt` (the fake `DiffPresenter` and `EditSessions` gain the new members)

**Interfaces:**
- Consumes: `EditPreview`, `SessionHandle.previews`, `EditPhase.WRITING`, `PREVIEW_STOPPED_NOTICE` (Task 4); `Located`, `IdeEditTools.locate`, `DiffPresenter.preview/updatePreview/closePreview`, `PreviewStage` (Task 5); `EditMatcher.match(text, oldString, replaceAll = false)` and its `Match` result (`session/edits/EditMatcher.kt:34`).
- Produces:

```kotlin
internal class EditPreviewer(
    private val queue: EditQueue, private val presenter: () -> DiffPresenter, private val sessions: () -> EditSessions,
    private val locate: suspend (String, SessionId) -> Located, private val autoAccept: (SessionId) -> Boolean,
) {
    fun start(scope: CoroutineScope)                          // collects live sessions' previews
    fun closeFor(session: SessionId, toolUseId: String?)       // cancel / cancelAll hook: closes the preview, no event
    /** The right side's text for [preview] over [original]: content so far; or original until newString, then the single match replaced. */
    companion object { fun previewText(preview: EditPreview, original: String): String }
}
```

  Rules (spec 4.3 and 5): open only when `queue.open.value == null`, no preview shown, `autoAccept(session)` false and `pathComplete`; `locate` off the EDT (`Dispatchers.IO`), `Refused` → no preview; `original` is the document's text read in a `ReadAction` each tick, the file's text when no document; on `null` from `previews` for an id whose call has not arrived (`queue` has no proposal with that `toolUseId`): `closePreview` and `sessions.event(id, EditPhase(toolUseId, EditReview(file, DONE, STOPPED)))` plus `sessions.notice(id, PREVIEW_STOPPED_NOTICE)`; when the call did arrive, nothing (the queue owns it).

- [ ] **Step 1: Write the failing tests in `TandemEditsTest`**

```kotlin
@Test fun `a preview opens at the complete path, updates with the stream, and the call adopts it`()   // fake presenter records preview/update calls; then call(...) → show(proposal) with the same toolUseId; no closePreview
@Test fun `a preview that ends without a call closes and the row is STOPPED with the notice`()
@Test fun `no preview while a diff is open, in auto-accept mode, for a refused path, or before the path is complete`()  // Review Focus 3 for the refused path
@Test fun `a stopped preview's late call opens an ordinary diff`()                                       // Review Focus 5: previews emits null (interrupt), then call(): show() opens, adopts nothing
@Test fun `previewText - write_file content, edit_file before and after new_string, a twice-found old_string`()  // Review Focus 2: "alpha\nalpha\n" with old "alpha" → original unchanged
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.ide.edits.TandemEditsTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `EditPreviewer.kt` and the `TandemEdits` wiring** (`ManagerSessions.previews(id)` = the handle's `previews`; `live()` = the manager's `sessions` mapped to ids).

- [ ] **Step 4: Run the edits tests and the platform tests to verify they pass**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.ide.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/ide/edits src/test/kotlin/dev/daarkolabs/tandem/ide/edits
git commit -m "feat(ide): the previewer opens, updates and closes the preview diff from the session's stream"
```

### Task 7: The chat row: `Writing <name>…`

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/chat/ToolLabels.kt:208-217` (`ReviewLabel.WRITING`, `"Writing"` phrase)
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilder.kt:596-620` (`WRITING` → label with the spinner state and `ShowDiff`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/chat/ToolLabelsTest.kt`, `src/test/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilderTest.kt`

**Interfaces:**
- Consumes: `EditPhase.WRITING` (Task 4).

- [ ] **Step 1: Write the failing tests**

```kotlin
// ToolLabelsTest
@Test fun `WRITING reads Writing hello_txt with an ellipsis`()        // reviewLabel(EDIT, WRITING, "hello.txt") == phrase "Writing hello.txt…"
// ChatRowsBuilderTest
@Test fun `a WRITING review shows the working row with Show diff while the turn is open`()   // state == ToolState.RUNNING (spinner), action == RowAction.ShowDiff(block.id); after the turn: plain label
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.chat.ToolLabelsTest' --tests 'dev.daarkolabs.tandem.chat.ChatRowsBuilderTest'`
Expected: FAIL (`WRITING` unresolved in `ReviewLabel`).

- [ ] **Step 3: Implement** the label and the row branch (`WRITING` before `QUEUED` in the `when`; `action` also for `WRITING`).

- [ ] **Step 4: Run the chat tests to verify they pass**

Run: `./gradlew test --tests 'dev.daarkolabs.tandem.chat.*'`
Expected: PASS; `koverLog` shows `chat` ≥ 90 %.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat
git commit -m "feat(chat): the edit row reads Writing while Claude streams the edit"
```

### Task 8: The IDE test: the preview with the call held back

**Files:**
- Modify: `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt` (one test beside `edit-accept`, line 1687; `runChat`'s `gateFile` and `extraEnv` as the `ide-edit-two` test uses them)
- Modify: `src/test/resources/fake-claude.py` only if `TANDEM_FAKE_LINE_DELAY_MS` does not pace `content_block_delta` lines (its docstring says it sleeps before each `stream_event` line: expected no change)

- [ ] **Step 1: Write the test**

```kotlin
@Test fun `edit preview shows while the call is held, then the diff becomes editable`() {
    // ide-edit with TANDEM_FAKE_LINE_DELAY_MS=150 and TANDEM_FAKE_GATE_FILE=<gate>: the deltas stream, the tools/call waits.
    // Assert within 10 s: a diff tab "Tandem: hello.txt" with the right side text "beta\nline two\n", the Accept button present and disabled,
    // the chat row text "Writing hello.txt…". Create the gate file. Assert within 10 s: Accept enabled. Click Accept.
    // Assert the file reads "beta\nline two\n" and the row says "Edited hello.txt", as `edit-accept` asserts.
}
```

- [ ] **Step 2: Run it under the shared lock**

Run: `flock /tmp/tandem-ide.lock xvfb-run -a ./gradlew integrationTest --tests '*edit preview shows while the call is held*'`
Expected: PASS (first run may download the IDE).

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt
git commit -m "test(ide): the edit preview is visible with its buttons off while the call is held"
```

### Task 9: Docs and the `write_file` recording

**Files:**
- Modify: `docs/design.md` (the edit flow: the preview before the call), `docs/protocol.md` (the `input_json_delta` to `tools/call` key: `_meta.claudecode/toolUseId`, with the fixture lines), `CLAUDE.md` (`protocol/`: `JsonPrefix`, `EditToolFields`; `session/`: `previews`; `ide/edits/`: `EditPreviewer`), `CHANGELOG.md` (`[Unreleased]` → Added: the live preview)
- Modify: `scripts/drive_cli.py` (scenario `ide-edit-write`: a prompt that asks for a new file `notes.md` with two lines, answered as `ide-edit` is), `scripts/test_drive_cli.py` (its control calls), `scripts/record-scenario.sh` (the name), `.github/workflows/cli-contract.yml` (the scenario in the nightly's list)
- Create (if recorded): `src/test/resources/fixtures/ide-edit-write.ndjson`, and the `write_file` case of `EditToolFieldsTest` switches to it

- [ ] **Step 1: Add the scenario and its unit test; run `cd scripts && python3 -m unittest`** → PASS.
- [ ] **Step 2: Try the recording:** `scripts/record-scenario.sh ide-edit-write` (needs a logged-in `claude`; the sandbox's own sign-in). If it records: redact per the recorder, add the fixture, point `EditToolFieldsTest`'s `write_file` case at it, and add the fixture to `FixtureDecodeTest`'s `ide-edit*` loop. If it cannot: leave the synthetic case and say so in the test's name.
- [ ] **Step 3: Write the docs; `./gradlew check`** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit**

```bash
git add docs CLAUDE.md CHANGELOG.md scripts .github/workflows/cli-contract.yml src/test/resources/fixtures src/test/kotlin
git commit -m "docs: the edit preview in design, protocol and the layout; the ide-edit-write scenario"
```

### Finish

- [ ] `./gradlew check`, `flock /tmp/tandem-ide.lock xvfb-run -a ./gradlew integrationTest`, `./gradlew verifyPlugin` green.
- [ ] Whole-branch review on Fable (spec coverage, the four parser principles, hard rules 1, 4, 5, 8), fixes, then the pull request with context, approach and testing notes, and the owner's `runIde` checklist: the growing right side, the scroll, the flip without flicker, Stop during a preview.
