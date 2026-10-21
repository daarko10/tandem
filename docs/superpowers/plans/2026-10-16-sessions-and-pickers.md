# Sessions and Pickers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A session sidebar in the Tandem tool window with several live `claude` processes at once, resume/fork/rename/delete backed by the CLI's own transcripts, and model and permission-mode pickers.

**Architecture:** A pure `SessionManager` in `session/` owns one `ClaudeSession` per live session plus a `SessionIndex` and `TranscriptReader` over `<config>/projects/<key>/*.jsonl`. A thin project service in `ide/` wires it to settings, workspace state and IDE notifications. Jewel composables in `ui/` only collect `StateFlow`s and call manager methods.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.1.5 (`sinceBuild` 261), Jewel/Compose, kotlinx-serialization tree API, kotlinx.coroutines from the platform, JUnit 5 platform tests (`@TestApplication`), IDE Starter + UI Driver, the Python fake CLI.

**Spec:** `docs/superpowers/specs/2026-10-16-sessions-and-pickers-design.md`

## Global Constraints

- Hard rules in `CLAUDE.md`: never block the EDT; no Node or SDK at runtime; never read anything under the config directory except `projects/`; every protocol change gets a fixture test; unknown input never throws; do not bundle coroutines or the stdlib; never log prompt or file contents.
- `protocol/` has no IntelliJ imports; `session/` imports only `com.intellij.openapi.diagnostic` and coroutines, as `ClaudeSession.kt` does today.
- Compile and verify against `platformVersion = 2026.1.5`. Jewel components available there (checked in `intellij.platform.jewel.ui.jar`): `ComboBox`, `ListComboBox`, `Dropdown`, `ContextMenu`, `Menu`, `PopupContainer`, `IconButton`, `IconActionButton`, `TextField`, `TextArea`, `Divider`, `Tooltip`, `Badge`, `Chip`, `SplitLayout`, `SimpleListItem`. Check any other signature with `javap -cp <gradle cache>/transformed/idea-2026.1.5/lib/intellij.platform.jewel.ui.jar <class>` before use (Context7 is unavailable in this sandbox). Any new IDE content module used must be declared under `<dependencies>` in `plugin.xml`.
- Branch `feat/sessions-and-pickers` (stacked on `feat/protocol-layer`). Conventional Commits; no AI attribution in commits or the PR; no names, e-mails, private paths or account details in committed files (grep every fixture before committing).
- `./gradlew test` must pass at every commit; `xvfb-run ./gradlew integrationTest` at the end of Tasks 10, 11 and 12. In this sandbox add `--init-script scratch/central-mirror.init.gradle.kts` to every Gradle command (Maven Central rate limits); not needed elsewhere.
- New test files use JUnit 5 (`org.junit.jupiter.api.Test`, `@TempDir`); additions to existing files keep that file's style.
- Exact values from the spec: transcript key = project path with every character outside `A–Z a–z 0–9` replaced by `-`, truncated to 200 (the CLI then adds `-<hash>`, which Tandem does not reproduce: it lists every folder with that 200-char prefix); config dir = `$CLAUDE_CONFIG_DIR` else `~/.claude`; cap default 4, range 1–16; sidebar auto-collapses below 500 px; watcher debounce 500 ms; title precedence custom > generated (`ai-title`/`summary`, whichever is last) > first prompt (one line, 80 chars) > id; the rename line Tandem appends matches the SDK's: `{"type":"custom-title","customTitle":"<title>","sessionId":"<id>","uuid":"<random uuid>","timestamp":"<ISO-8601>"}`; delete removes `<id>.jsonl` and the directory `<id>/` (what the SDK's `deleteSession` removes; spec open item 1 is settled); denylist `CLAUDECODE`, `CLAUDE_PID`, `CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_CHILD_SESSION`, `CLAUDE_CODE_SESSION_ATTENDED`, `CLAUDE_CODE_CONTAINER_ID`, `CLAUDE_CODE_WORKER_EPOCH`, `CLAUDE_SESSION_INGRESS_TOKEN_FILE`, prefixes `CLAUDE_CODE_REMOTE`, `CLAUDE_CODE_MESSAGING_`, `CLAUDE_CODE_ENVIRONMENT_`.
- The recorded `system/init` of CLI 2.1.300 has no `effort` field (`per_turn_effort_active` only), so the effort badge shows what Tandem passed with `--effort`, or the last assistant line's `effort` from a transcript. Transcript `user` lines carry `permissionMode`; `assistant` lines carry `effort` and `requestedModel`.

## Review Focus

1. A Windows project path (`C:\Users\dev\app`) must map to key `C--Users-dev-app`, not throw or produce an empty key → pinned in Task 2.
2. A transcript that ends mid-turn (a `tool_use` with no result, or an aborted reply) must load with `busy = false` and no streaming entry, so the prompt box is usable → pinned in Task 4.
3. Files in the transcript folder that are not `<uuid>.jsonl` (`agent-*.jsonl`, temp files) must be ignored, not listed as sessions → pinned in Task 5.
4. Two prompts sent quickly to a stopped session must start exactly one process and deliver both prompts → pinned in Task 7.
5. `init.session_id` differing from the resumed id must create a new row with a warning, never relabel the old one → pinned in Task 8.

---

### Task 1: CLI environment and spawn identity

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/CliEnvironment.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ClaudeSession.kt` (`SessionConfig`, `ProcessLauncher`, `initialState`, `rename`), `.../session/ChatState.kt` (`effort`), `.../protocol/HostMessages.kt` (`renameSession`), `.../ide/TandemChat.kt` (passes `sessionId = UUID.randomUUID().toString()`)
- Modify: `src/test/resources/fake-claude.py`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/CliEnvironmentTest.kt`, `.../session/ClaudeSessionTest.kt`, `.../protocol/HostMessagesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // CliEnvironment.kt
  fun cliEnvironment(parent: Map<String, String>): Map<String, String>   // denylist applied, NODE_OPTIONS removed, CLAUDE_CODE_ENTRYPOINT=sdk-tandem

  // ClaudeSession.kt
  data class SessionConfig(
      val binary: String, val workingDirectory: Path,
      val sessionId: String? = null,        // new session: --session-id
      val resumeSessionId: String? = null,  // existing session: --resume
      val model: String? = null, val effort: String? = null, val permissionMode: PermissionMode? = null,
      val forkSession: Boolean = false,     // with resumeSessionId: --fork-session
  ) { init { require((sessionId == null) != (resumeSessionId == null)) }; fun argv(): List<String> }
  class DefaultProcessLauncher(private val parentEnvironment: () -> Map<String, String> = System::getenv) : ProcessLauncher
  class ClaudeSession(parentScope, config, launcher = DefaultProcessLauncher(), initialState: ChatState = ChatState())
  fun ClaudeSession.rename(title: String)   // sends rename_session

  // ChatState.kt
  val effort: String? = null                // seeded from SessionConfig.effort; Task 4 fills it from transcripts

  // HostMessages.kt
  fun renameSession(requestId: String, title: String): JsonObject
  ```
- Fake CLI: first log line `{"type":"__spawn__","argv":[...],"env":{keys starting with CLAUDE, plus NODE_OPTIONS}}` (never the whole environment); `TANDEM_FAKE_FIXTURES` (comma list, picked by the number of `__spawn__` lines already in the log; `TANDEM_FAKE_FIXTURE` still works as a one-element list); an unknown fixture name exits with code 2 and `no fixture <name>` on stderr; every `<session_id-N>` placeholder in emitted lines is replaced by the id from `--session-id`/`--resume` (a fresh UUID with `--fork-session`; `TANDEM_FAKE_SESSION_ID` overrides both); `TANDEM_FAKE_DELAY_MS` sleeps that long before emitting each `control_request`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// CliEnvironmentTest.kt
@Test fun `drops variables that identify a parent session and keeps the rest`() {
    val parent = mapOf(
        "CLAUDECODE" to "1", "CLAUDE_PID" to "7", "CLAUDE_CODE_SESSION_ID" to "x", "CLAUDE_CODE_CHILD_SESSION" to "1",
        "CLAUDE_CODE_SESSION_ATTENDED" to "1", "CLAUDE_CODE_CONTAINER_ID" to "c", "CLAUDE_CODE_WORKER_EPOCH" to "3",
        "CLAUDE_SESSION_INGRESS_TOKEN_FILE" to "/t", "CLAUDE_CODE_REMOTE" to "1", "CLAUDE_CODE_REMOTE_SESSION_ID" to "r",
        "CLAUDE_CODE_MESSAGING_SOCKET" to "s", "CLAUDE_CODE_ENVIRONMENT_KIND" to "k", "NODE_OPTIONS" to "--inspect",
        "CLAUDE_CONFIG_DIR" to "/cfg", "CLAUDE_CODE_USE_BEDROCK" to "1", "PATH" to "/bin", "CLAUDE_CODE_ENTRYPOINT" to "cli",
    )
    assertEquals(
        mapOf("CLAUDE_CONFIG_DIR" to "/cfg", "CLAUDE_CODE_USE_BEDROCK" to "1", "PATH" to "/bin", "CLAUDE_CODE_ENTRYPOINT" to "sdk-tandem"),
        cliEnvironment(parent),
    )
}
```
```kotlin
// ClaudeSessionTest.kt: replace `argv follows the protocol reference`; every existing SessionConfig("claude", tmp) gains sessionId = "11111111-1111-4111-8111-111111111111"
@Test fun `argv for a new session and for a resumed fork`() {
    val base = listOf("claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
        "--include-partial-messages", "--permission-prompt-tool=stdio")
    assertEquals(base + listOf("--model", "opus", "--effort", "high", "--permission-mode", "plan",
        "--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit", "--session-id", "s1"),
        SessionConfig("claude", tmp, sessionId = "s1", model = "opus", effort = "high", permissionMode = PermissionMode.PLAN).argv())
    assertEquals(base + listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit", "--resume", "s2", "--fork-session"),
        SessionConfig("claude", tmp, resumeSessionId = "s2", forkSession = true).argv())
    assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp) }
    assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp, sessionId = "a", resumeSessionId = "b") }
}
@Test fun `a resumed session starts from the loaded history`() {  // fake: cat plain-reply fixture, then cat > /dev/null
    val history = ChatState(entries = listOf(ChatEntry.User("u0", "earlier")), effort = "low")
    val session = ClaudeSession(scope, SessionConfig("claude", tmp, resumeSessionId = "s1"), launcher, initialState = history)
    session.start()
    val final = withTimeout(10_000) { session.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } } }
    assertEquals("earlier", (final.entries.first() as ChatEntry.User).text)
    assertEquals("low", final.effort)
}
@Test fun `the default launcher strips inherited session variables and passes the config dir through`() {
    // binary = src/test/resources/fake-claude.py; parentEnvironment adds the bogus id and TANDEM_FAKE_LOG
    val launcher = DefaultProcessLauncher { System.getenv() + mapOf("CLAUDE_CODE_SESSION_ID" to "bogus", "CLAUDE_CONFIG_DIR" to "/cfg", "TANDEM_FAKE_LOG" to log.path) }
    ... start a session with sessionId = "11111111-1111-4111-8111-111111111111", wait for !busy, close ...
    val spawn = Json.parseToJsonElement(log.readLines().first()).jsonObject
    assertEquals("__spawn__", spawn["type"]!!.jsonPrimitive.content)
    val env = spawn["env"]!!.jsonObject
    assertFalse("CLAUDE_CODE_SESSION_ID" in env); assertEquals("/cfg", env["CLAUDE_CONFIG_DIR"]!!.jsonPrimitive.content)
    val argv = spawn["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals("11111111-1111-4111-8111-111111111111", argv[argv.indexOf("--session-id") + 1])
}
```
```kotlin
// HostMessagesTest.kt, in `control requests`
assertEquals("""{"type":"control_request","request_id":"a5","request":{"subtype":"rename_session","title":"Alpha","source":"host"}}""",
    wire(HostMessages.renameSession("a5", "Alpha")))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CliEnvironmentTest*' --tests '*ClaudeSessionTest*' --tests '*HostMessagesTest*'`
Expected: compilation errors for `cliEnvironment`, `DefaultProcessLauncher`, `sessionId`, `effort`, `renameSession`.

- [ ] **Step 3: Implement**

`cliEnvironment`: copy `parent`, remove the exact names and prefixes from the denylist, remove `NODE_OPTIONS`, set `CLAUDE_CODE_ENTRYPOINT`. `DefaultProcessLauncher.launch`: `ProcessBuilder(argv).directory(dir)`, then `environment().clear(); environment().putAll(cliEnvironment(parentEnvironment()))`. Keep `ProcessLauncher.Default` as `DefaultProcessLauncher()` for existing callers. `ClaudeSession` seeds `_state` with `initialState.copy(requestedModel = config.model ?: initialState.requestedModel, permissionMode = config.permissionMode ?: initialState.permissionMode, effort = config.effort ?: initialState.effort)`. Fake CLI changes as listed under Interfaces (Python stdlib only: `json`, `os`, `sys`, `time`, `uuid`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: the same command, then `./gradlew test`. Expected: all PASS (the IDE tests are unaffected: their fixtures' request ids are not session ids).

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat: pass a session id to the CLI and drop inherited session variables"
```

---

### Task 2: Transcript folder rules

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptFolder.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptFolderTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object TranscriptFolder {
      const val MAX_KEY_LENGTH = 200
      fun key(projectPath: String): String
      fun configDir(env: Map<String, String> = System.getenv(), home: Path = Path.of(System.getProperty("user.home"))): Path
      fun projectsDir(configDir: Path): Path = configDir.resolve("projects")
      /** The folders that can hold this project's transcripts: the exact key, or every folder sharing the 200-char prefix. */
      fun candidates(projectsDir: Path, projectPath: String): List<Path>
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `replaces every character outside letters and digits`() {
    assertEquals("-home-u-my-proj-v2", TranscriptFolder.key("/home/u/my proj.v2"))
    assertEquals("C--Users-dev-app", TranscriptFolder.key("C:\\Users\\dev\\app"))   // Review Focus 1
}
@Test fun `truncates keys longer than 200 characters`() {
    val key = TranscriptFolder.key("/" + "a".repeat(250))
    assertEquals(200, key.length); assertTrue(key.startsWith("-aaa"))
}
@Test fun `config dir comes from CLAUDE_CONFIG_DIR else home`() {
    assertEquals(Path.of("/cfg"), TranscriptFolder.configDir(mapOf("CLAUDE_CONFIG_DIR" to "/cfg"), Path.of("/h")))
    assertEquals(Path.of("/h/.claude"), TranscriptFolder.configDir(emptyMap(), Path.of("/h")))
}
@Test fun `candidates are the exact folder, or every folder with the long prefix`(@TempDir projects: Path) {
    assertEquals(listOf(projects.resolve("-home-u-app")), TranscriptFolder.candidates(projects, "/home/u/app"))   // whether or not it exists
    val long = "/" + "b".repeat(300); val prefix = TranscriptFolder.key(long)
    projects.resolve("$prefix-abc").createDirectories(); projects.resolve("$prefix-def").createDirectories(); projects.resolve("other").createDirectories()
    assertEquals(setOf("$prefix-abc", "$prefix-def"), TranscriptFolder.candidates(projects, long).map { it.fileName.toString() }.toSet())
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*TranscriptFolderTest*'` → unresolved reference.
- [ ] **Step 3: Implement `TranscriptFolder`** — `key` = `replace(Regex("[^A-Za-z0-9]"), "-").take(MAX_KEY_LENGTH)`; `candidates` lists directories starting with the key when `key.length == MAX_KEY_LENGTH` (a missing `projectsDir` gives an empty list), otherwise the single exact path.
- [ ] **Step 4: Run to verify pass** — same command, all PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: locate the CLI's transcript folder for a project"`

---

### Task 3: Transcript fixtures and new scenarios

**Files:**
- Modify: `scripts/drive_cli.py` (scenarios `permission-mode`, `fork`), `scripts/record-scenario.sh`, `scripts/record-fixture.sh`, `scripts/redact_fixture.py`
- Create: `scripts/copy_transcript.py`
- Create (recorded): `src/test/resources/fixtures/{history-reply,history-permission,permission-mode,fork}.ndjson` and `fixtures/transcripts/{history-reply,history-permission,fork}.jsonl`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt`, `.../protocol/Fixtures.kt`

**Interfaces:**
- Produces: `Fixtures.transcript(name: String): Path` (file under `fixtures/transcripts/`). A stream fixture and the transcript of the same name come from one recorded session. Existing fixtures stay untouched (the IDE tests assert their ids).
- `copy_transcript.py <work-dir> <raw-stream.ndjson> <out-raw.jsonl>`: reads `session_id` from the last `system/init` line of the stream, computes the key of `<work-dir>` as in Task 2, copies `${CLAUDE_CONFIG_DIR:-~/.claude}/projects/<key>/<id>.jsonl`.
- `redact_fixture.py`: `ID_KEYS` also covers `parentUuid`, `sessionId`, `promptId`, `leafUuid`, `requestId`, `toolUseID`, `agentId`, `sourceToolUseID`, `sourceToolAssistantUUID`; `gitBranch` values become `<branch>`; `cwd` is a path and already matches `PATH_RE`.
- `drive_cli.py` scenario `permission-mode`: prompt "Reply with the single word: one" → result; `set_permission_mode` `plan` → control_response; prompt "two" → result; `set_permission_mode` `acceptEdits` → response; prompt "three" → result. Scenario `fork`: prompt "Reply with the single word: alpha" → result → close; a second `Cli(extra + ["--resume", <id from init>, "--fork-session"])`: prompt "Reply with the single word: beta" → result; the second run's lines are appended to the first's output.

- [ ] **Step 1: Extend the scripts** (no Kotlin yet). `record-scenario.sh` and `record-fixture.sh` call `copy_transcript.py` after the run and redact the copy to `$out_dir/transcripts/<name>.jsonl`.
- [ ] **Step 2: Record** — `scripts/record-scenario.sh permission-mode`; `scripts/record-scenario.sh fork`; `scripts/record-fixture.sh history-reply "Reply with the single word: pong" src/test/resources/fixtures --effort medium` (a live run of the `--effort` flag); `scripts/record-scenario.sh permission scratch/rec` and move the pair to `history-permission.{ndjson,jsonl}`.
- [ ] **Step 3: Check every new fixture for personal data**

Run: `grep -nE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}|/(home|root|Users)/|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' src/test/resources/fixtures/*.ndjson src/test/resources/fixtures/transcripts/*.jsonl`
Expected: no output (ids are placeholders, paths are `<path>`). Also `git diff --stat` shows no change to the four existing fixtures.
- [ ] **Step 4: Write the fixture tests**

```kotlin
// FixtureDecodeTest.kt
@Test fun `permission mode fixture shows how the CLI acknowledges set_permission_mode`() {
    val messages = Fixtures.decode("permission-mode")
    assertEquals(3, messages.filterIsInstance<CliMessage.ControlResponse>().count { it.isSuccess })   // initialize + two acks
    assertEquals(listOf(PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS),
        messages.filterIsInstance<CliMessage.SystemInit>().map { it.permissionMode })   // if the recording differs, assert what it shows and note it in protocol.md 4.4
}
@Test fun `fork fixture resumes under a new session id`() {
    val ids = Fixtures.decode("fork").filterIsInstance<CliMessage.SystemInit>().map { it.sessionId }
    assertEquals(2, ids.size); assertNotEquals(ids[0], ids[1])
}
@Test fun `transcript fixtures decode line by line without throwing`() {
    for (name in listOf("history-reply", "history-permission", "fork")) Fixtures.transcript(name).readLines().filter { it.isNotBlank() }.forEach(Ndjson::decode)
}
```
- [ ] **Step 5: Run** — `./gradlew test --tests '*FixtureDecodeTest*'` → PASS.
- [ ] **Step 6: Commit** — `git add scripts src/test && git commit -m "test: record transcript fixtures and the permission-mode and fork scenarios"`

---

### Task 4: TranscriptReader

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReader.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt` (add `settled()`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReaderTest.kt`

**Interfaces:**
- Consumes: `Ndjson.decode`, `reduce`, `SessionEvent`, `Fixtures.transcript` (Task 3).
- Produces:
  ```kotlin
  data class TranscriptMeta(
      val sessionId: String, val customTitle: String?, val generatedTitle: String?, val firstPrompt: String?,
      val lastModel: String?, val lastEffort: String?, val lastPermissionMode: PermissionMode?, val cwd: String?, val lineCount: Int,
  ) { val title: String }   // precedence from Global Constraints, else sessionId
  data class LoadedTranscript(val state: ChatState, val meta: TranscriptMeta)
  object TranscriptReader {
      suspend fun read(path: Path): LoadedTranscript   // full history; calls ensureActive() per line; an IOException mid-file returns what was parsed
      fun meta(path: Path): TranscriptMeta             // title fields only, same skipping rules
  }
  internal fun ChatState.settled(): ChatState   // busy=false, ended=false, pendingPermissions=empty, every Assistant streaming=false
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `loads the same conversation as the stream fixture`() = runBlocking {
    val streamed = Fixtures.decode("history-permission").fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
    val loaded = TranscriptReader.read(Fixtures.transcript("history-permission"))
    assertEquals(streamed.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks }, loaded.state.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks })
    assertEquals(streamed.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError }, loaded.state.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError })
    assertEquals(2, loaded.state.entries.count { it is ChatEntry.User })          // the prompts the driver typed
    assertFalse(loaded.state.busy); assertEquals("claude-sonnet-5-5", loaded.meta.lastModel); assertEquals("medium", TranscriptReader.meta(Fixtures.transcript("history-reply")).lastEffort)
}
@Test fun `title precedence`(@TempDir dir: Path) {
    val f = dir.resolve("11111111-1111-4111-8111-111111111111.jsonl")
    f.writeText("""{"type":"user","uuid":"u1","permissionMode":"plan","message":{"role":"user","content":"first   prompt\nsecond line"}}""" + "\n")
    assertEquals("first   prompt", TranscriptReader.meta(f).title); assertEquals(PermissionMode.PLAN, TranscriptReader.meta(f).lastPermissionMode)
    f.appendText("""{"type":"ai-title","aiTitle":"Generated"}""" + "\n"); assertEquals("Generated", TranscriptReader.meta(f).title)
    f.appendText("""{"type":"summary","summary":"Later summary"}""" + "\n"); assertEquals("Later summary", TranscriptReader.meta(f).title)
    f.appendText("""{"type":"custom-title","customTitle":"Mine"}""" + "\n"); assertEquals("Mine", TranscriptReader.meta(f).title)
    f.appendText("""{"type":"ai-title","aiTitle":"Newer generated"}""" + "\n"); assertEquals("Mine", TranscriptReader.meta(f).title)
}
@Test fun `empty transcript falls back to the id and an 81-char prompt is trimmed to 80`(@TempDir dir: Path)
@Test fun `meta and sidechain lines are skipped`(@TempDir dir: Path)      // isMeta user line and isSidechain assistant line produce no entries
@Test fun `unknown types, non-object and malformed lines are skipped`(@TempDir dir: Path)   // "not json", "[1]", {"type":"attachment"}, a cut assistant line at the end: no throw, no entries
@Test fun `a transcript that ends mid-turn loads settled`(@TempDir dir: Path) = runBlocking {   // Review Focus 2: assistant tool_use with no user result after it
    ... val state = TranscriptReader.read(f).state
    assertFalse(state.busy); assertTrue(state.entries.filterIsInstance<ChatEntry.Assistant>().none { it.streaming })
    assertIs<AssistantBlock.ToolUse>(state.entries.filterIsInstance<ChatEntry.Assistant>().last().blocks.last())
}
@Test fun `a loaded history followed by a live turn keeps ids unique and toggles busy`() = runBlocking {
    var state = TranscriptReader.read(Fixtures.transcript("history-reply")).state
    state = reduce(state, SessionEvent.UserSent("live-u1", "again")); assertTrue(state.busy)
    state = reduce(state, SessionEvent.Cli(Ndjson.decode("""{"type":"assistant","uuid":"live-a1","message":{"id":"live-m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"pong again"}]}}""")))
    state = reduce(state, SessionEvent.Cli(Ndjson.decode("""{"type":"result","subtype":"success","is_error":false,"session_id":"x"}""")))
    assertFalse(state.busy); assertEquals(state.entries.size, state.entries.map { it.id }.toSet().size)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*TranscriptReaderTest*'`.
- [ ] **Step 3: Implement** — per line: `Json.parseToJsonElement` (catch `SerializationException` → skip; a non-object → skip); read `type`, `isMeta`, `isSidechain`; `user` → text blocks (a string or `text` blocks) joined with `\n` → `SessionEvent.UserSent(uuid ?: "line-$n", text)`, `tool_result` blocks → `SessionEvent.Cli(Ndjson.decode(line))`, `permissionMode` → `lastPermissionMode`; `assistant` → `SessionEvent.Cli(Ndjson.decode(line))`, `message.model` → `lastModel`, `effort` → `lastEffort`; `cwd` from the first line that has one; title lines → meta; everything else skipped. Return `state.settled().copy(permissionMode = meta.lastPermissionMode ?: state.permissionMode, effort = meta.lastEffort)`.
- [ ] **Step 4: Run to verify pass** — same command.
- [ ] **Step 5: Commit** — `git commit -m "feat: load a session's history from the CLI transcript"`

---

### Task 5: SessionIndex

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/SessionIndex.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/transcript/SessionIndexTest.kt`

**Interfaces:**
- Consumes: `TranscriptReader.meta`, `TranscriptMeta`.
- Produces:
  ```kotlin
  data class SessionSummary(val id: String, val title: String, val lastModified: Long, val firstPrompt: String?, val file: Path)
  class SessionIndex(
      private val folders: () -> List<Path>, private val projectPath: String,
      private val meta: (Path) -> TranscriptMeta = TranscriptReader::meta,
  ) {
      fun list(): List<SessionSummary>   // only <uuid>.jsonl, newest first, cached by (size, mtime); with more than one folder, entries whose meta.cwd is set and differs from projectPath are dropped
      fun invalidate(id: String)
      fun changes(): Flow<Unit>          // WatchService on each existing folder, debounced 500 ms; a missing folder is polled for existence every 2 s; failures are logged, never thrown
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `lists only session transcripts, newest first`(@TempDir dir: Path) {   // Review Focus 3
    write(dir, "11111111-1111-4111-8111-111111111111.jsonl", mtime = 1_000); write(dir, "22222222-2222-4222-8222-222222222222.jsonl", mtime = 2_000)
    write(dir, "agent-1234.jsonl", mtime = 3_000); write(dir, "notes.txt", mtime = 3_000); write(dir, "33333333-3333-4333-8333-333333333333.jsonl.tmp", mtime = 3_000)
    assertEquals(listOf("22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111"), SessionIndex({ listOf(dir) }, "/p").list().map { it.id })
}
@Test fun `unchanged files are not read again`(@TempDir dir: Path)   // counting meta reader: 1 read after two list() calls for one file; 2 after the file grows
@Test fun `a missing folder is an empty list`()
@Test fun `with several candidate folders, sessions of another project are dropped`(@TempDir a: Path, @TempDir b: Path)   // meta stub: file in b has cwd "/other" → dropped; cwd null → kept; with one folder a cwd mismatch is kept
@Test fun `changes emits after a file is written`(@TempDir dir: Path) = runBlocking { withTimeout(15_000) { ... first() after writing a new file ... } }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*SessionIndexTest*'`.
- [ ] **Step 3: Implement** — `java.nio.file.WatchService` (`ENTRY_CREATE|ENTRY_MODIFY|ENTRY_DELETE`) inside `callbackFlow` on `Dispatchers.IO`, then `debounce(500)`; file name regex `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jsonl$`.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat: index the sessions in a project's transcript folder"`

---

### Task 6: Requested permission mode and available models in the reducer

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/Reducer.kt`, `.../session/ChatState.kt`, `.../session/ClaudeSession.kt` (`setPermissionMode` dispatches first)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`

**Interfaces:**
- Produces: `SessionEvent.PermissionModeRequested(val mode: PermissionMode)` → `permissionMode = mode`; `ChatState.models: List<ModelOption>` filled from the `initialize` control response; `data class ModelOption(val value: String, val displayName: String, val description: String?)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a requested permission mode shows at once and the CLI confirms it`() {
    var state = reduce(ChatState(), SessionEvent.PermissionModeRequested(PermissionMode.PLAN))
    assertEquals(PermissionMode.PLAN, state.permissionMode)
    for (m in Fixtures.decode("permission-mode")) state = reduce(state, SessionEvent.Cli(m))
    assertEquals(PermissionMode.ACCEPT_EDITS, state.permissionMode)
}
@Test fun `the initialize response fills the model list`() {
    val state = Fixtures.decode("permission").fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
    assertEquals("default", state.models.first().value); assertTrue(state.models.size > 1)
}
```
- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*ReducerTest*'`.
- [ ] **Step 3: Implement** — in `reduceCli` for `ControlResponse`: `payload?.get("models")` as `JsonArray` → `ModelOption(value, displayName ?: value, description)`, skipping entries without `value`; keep the existing error notice path.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat: reflect a requested permission mode and keep the model list"`

---

### Task 7: SessionManager core

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/SessionHandle.kt`, `.../session/SessionManager.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/SessionManagerTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 4, 5, 6.
- Produces:
  ```kotlin
  typealias SessionId = String
  enum class SessionStatus { RUNNING, WAITING, IDLE, STOPPED }
  data class NewSessionOptions(val model: String? = null, val effort: String? = null, val permissionMode: PermissionMode? = null)
  class SessionHandle internal constructor(val id: SessionId) {
      val state: StateFlow<ChatState>; val status: StateFlow<SessionStatus>; val title: StateFlow<String>
      val attention: StateFlow<Boolean>; @Volatile var lastUsed: Long
  }
  class SessionManager(
      private val scope: CoroutineScope, private val index: SessionIndex, private val launcher: ProcessLauncher,
      private val binary: () -> String?, private val workingDirectory: Path, private val maxLive: () -> Int,
      private val clock: () -> Long = System::currentTimeMillis,
  ) {
      val sessions: StateFlow<List<SessionHandle>>; val selected: StateFlow<SessionId?>
      val summaries: StateFlow<List<SessionSummary>>    // index list merged with open handles: a handle without a file is listed first with lastModified = clock()
      fun create(options: NewSessionOptions): SessionHandle            // UUID, spawn with --session-id and the options
      suspend fun open(id: SessionId, spawn: Boolean = true): SessionHandle   // history via TranscriptReader; then --resume if spawn and the cap allows
      fun select(id: SessionId?)                                        // clears attention, bumps lastUsed
      fun send(id: SessionId, text: String); fun interrupt(id); fun allow(id, requestId, always); fun deny(id, requestId)
      fun setModel(id, model: String?); fun setPermissionMode(id, mode: PermissionMode)   // live: control request; stopped: kept for the next spawn; both dispatch to the state
      suspend fun stop(id: SessionId)     // ends the process, the row stays (status STOPPED)
      suspend fun close(id: SessionId)    // stop and drop the row
      suspend fun refreshIndex()
  }
  ```
- Status: no process or `ended` → STOPPED; pending permissions → WAITING; `busy` → RUNNING; else IDLE. `attention` turns true when a non-selected session becomes WAITING or goes RUNNING → IDLE; false on `select`. `lastUsed` is bumped on `select`, `send` and every status change. `title` = the renamed title (Task 8), else the index summary's title, else the first `ChatEntry.User` text on one line (80 chars), else the id. A missing binary puts the install notice (text moved from `TandemChat`) in the handle's state. Each handle guards its spawn with a `Mutex`; `send` on a STOPPED handle spawns `--resume` with `initialState = state.value.settled()` and queues the text until the process is up; a spawn that ends in error leaves the row STOPPED with the existing error notice and the next prompt spawns again.

- [ ] **Step 1: Write the failing tests** (launcher = `DefaultProcessLauncher` whose parent environment adds `TANDEM_FAKE_LOG`, `TANDEM_FAKE_FIXTURES` per test; binary = `src/test/resources/fake-claude.py`; helper `spawns(log)` parses the `__spawn__` lines)

```kotlin
@Test fun `create spawns with --session-id and the options`()       // argv contains "--session-id", handle.id (a UUID), "--model", "opus", "--effort", "high", "--permission-mode", "acceptEdits"; state.effort == "high"
@Test fun `open loads the history before the process answers`(@TempDir folder: Path)   // copy history-reply.jsonl to folder/<id>.jsonl; open(id); state has the User entry and the Assistant "pong" before init; spawn argv has "--resume", id
@Test fun `two quick prompts to a stopped session start one process`()   // Review Focus 4: create, stop(id), send twice; spawns(log).size == 2 overall and the second spawn has --resume; both user lines in the log
@Test fun `status follows the process`()      // STOPPED → IDLE after init → RUNNING after send → IDLE after result; "permission" fixture → WAITING
@Test fun `a failed spawn leaves the row stopped and the next prompt tries again`()   // TANDEM_FAKE_FIXTURES=missing,plain-reply: first send → ERROR notice, STOPPED; second send → 2 spawns, reply "pong"
@Test fun `picks on a stopped session apply at the next spawn`()   // stop(id); setModel("opus"); setPermissionMode(PLAN); send → argv has "--model", "opus", "--permission-mode", "plan"
@Test fun `close stops the process and drops the session`()        // no fake-claude.py descendant alive afterwards, sessions.value has no such id
@Test fun `a missing binary shows the install notice`()            // binary = { null }: state.entries.last() is Notice ERROR containing "not found"; status STOPPED
@Test fun `attention is raised for a background session and cleared on select`()   // fixtures "plain-reply,permission": select A; send on B → B.attention true when WAITING; select(B) → false
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*SessionManagerTest*'`.
- [ ] **Step 3: Implement.** - [ ] **Step 4: Run to verify pass** — same command.
- [ ] **Step 5: Commit** — `git commit -m "feat: manage several live sessions from one manager"`

---

### Task 8: Cap, rename, fork, delete

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/SessionManager.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/SessionManagerTest.kt`

**Interfaces:**
- Produces: `suspend fun rename(id, title: String)`, `suspend fun fork(id): SessionHandle`, `suspend fun delete(id)`; cap enforcement inside `create`, `open` and the spawn in `send`.
- Rules: before a spawn, while live sessions ≥ `maxLive()`, stop the least recently used handle whose status is IDLE (never RUNNING or WAITING); if none, spawn anyway and add an INFO notice "Max live sessions exceeded" to the new session. `rename`: blank titles are ignored; live → `ClaudeSession.rename`; stopped → append the `custom-title` line from Global Constraints; `handle.title` updates at once; then `index.invalidate(id)`. `fork`: spawn `--resume <id> --fork-session`; the new handle is keyed by the id from `init`; once init arrives Tandem renames it "<original title> (fork)" (spec open item 4: done whether or not the CLI writes a title) and selects it. A plain resume whose `init.session_id` differs from the requested id keeps the old row STOPPED, creates a new row for the new id, and adds a WARNING notice "Resumed as a new session <new id>" to it. `delete`: stop, remove `<id>.jsonl` and the directory `<id>/` if present, drop the row, select the next row if it was selected.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the cap stops the least recently used idle session only`()   // maxLive=2: create A, B; create C → A STOPPED; make B RUNNING ("interrupt" fixture streams and waits); create D → C stopped, B still RUNNING
@Test fun `the cap notice appears when nothing is idle`()                // maxLive=1 with B RUNNING: create C → C's state has an INFO notice containing "Max live sessions"
@Test fun `rename sends rename_session when live and appends a custom-title line when stopped`()   // log has {"subtype":"rename_session","title":"Alpha","source":"host"}; stopped: file's last line decodes with type custom-title, customTitle "Beta", sessionId id, uuid and timestamp present; handle.title.value == "Beta"
@Test fun `fork resumes with --fork-session, adopts the id from init and is named after the original`()   // argv has "--fork-session"; new handle id != old; old row unchanged; log has rename_session with title ending "(fork)"; selected == new id
@Test fun `a resume that comes back with another id becomes a new row with a warning`()   // Review Focus 5: TANDEM_FAKE_SESSION_ID=<other> in the launcher's parent environment
@Test fun `delete removes the transcript and its sidecar directory`(@TempDir folder: Path)   // <id>.jsonl and <id>/subagents/agent-x.jsonl gone; row gone; selection moved to the remaining row
```
- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*SessionManagerTest*'`. - [ ] **Step 3: Implement.** - [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat: cap live sessions and add rename, fork and delete"`

---

### Task 9: Settings, workspace state and the project service

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ide/TandemSettings.kt`, `.../ide/TandemSettingsConfigurable.kt`, `.../ide/TandemWorkspaceState.kt`, `.../ide/TandemSessions.kt`, `.../ide/SessionNotifier.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ide/ClaudeBinary.kt`, `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ide/TandemSettingsTest.kt`, `.../ide/TandemSessionsTest.kt`, `.../ide/SessionNotifierTest.kt`
- `TandemChat` and its test stay until Task 10 replaces the UI.

**Interfaces:**
- Produces:
  ```kotlin
  @Service(Service.Level.APP) @State(name = "TandemSettings", storages = [Storage("tandem.xml", roamingType = RoamingType.DISABLED)])
  class TandemSettings : SimplePersistentStateComponent<TandemSettings.State>(State()) {
      class State : BaseState() { var claudePath by string(); var maxLiveSessions by property(4) }
      companion object { fun getInstance(): TandemSettings }
  }
  class TandemSettingsConfigurable : BoundConfigurable("Tandem")   // plugin.xml: applicationConfigurable parentId="tools" id="dev.daarkolabs.tandem.settings"; rows: "Detected" label filled off the EDT from ClaudeBinary.find(), path textFieldWithBrowseButton, "Test" button running `<path> --version` on Dispatchers.IO with the output in a label, cap intTextField(1..16)
  @Service(Service.Level.PROJECT) @State(name = "TandemWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
  class TandemWorkspaceState : SimplePersistentStateComponent<TandemWorkspaceState.State>(State()) {
      class State : BaseState() { var openSessionIds by list<String>(); var selectedSessionId by string(); var sidebarCollapsed by property(false); var lastModel by string(); var lastEffort by string(); var lastPermissionMode by string() }
  }
  @Service(Service.Level.PROJECT)
  class TandemSessions(project: Project, scope: CoroutineScope) {
      val manager: SessionManager      // index = SessionIndex({ TranscriptFolder.candidates(projectsDir(configDir()), basePath) }, basePath); binary = ClaudeBinary::find; maxLive from settings; workingDirectory = project base path
      fun ensureStarted()              // idempotent, off the EDT: restores openSessionIds and selection with open(id, spawn = false), starts the notifier and the index watcher, mirrors sessions/selected into the workspace state, registers the JVM shutdown hook (moved from TandemChat), honours -Dtandem.autoPrompt by creating a session and sending it
      companion object { fun get(project: Project): TandemSessions }
  }
  class SessionNotifier(project, manager, scope)   // on attention false→true, and for the selected session when ToolWindowManager.getToolWindow("Tandem")?.isVisible != true: group "Tandem", WARNING "<title> needs your permission" or INFORMATION "<title> finished", action "Open" → manager.select(id) + toolWindow.show()
  ```
- `ClaudeBinary.find()` override order: `TandemSettings.getInstance().state.claudePath`, then the `tandem.claudePath` system property (test hook), then PATH and the usual locations.

- [ ] **Step 1: Write the failing tests** (`@TestApplication`, `openProjectAsync(dir).useProjectAsync { }` as in `TandemChatTest`)

```kotlin
// TandemSettingsTest
@Test fun `settings round-trip and the path override wins`()   // loadState(State().apply { claudePath = fake; maxLiveSessions = 2 }); ClaudeBinary.find() == fake; getState().maxLiveSessions == 2
@Test fun `the settings page writes the path and cap`()         // on Dispatchers.EDT: createPanel(); reset(); set the JBTextField texts; isModified; apply(); state updated
@Test fun `workspace state round-trips`()
// TandemSessionsTest
@Test fun `a created session streams the reply into its state`()
@Test fun `closing the project stops every live session`()        // two sessions; no fake-claude.py descendant alive after useProjectAsync returns
@Test fun `open sessions are restored from workspace state without spawning`()   // CLAUDE_CONFIG_DIR cannot be set per test: inject the folder through a test-only system property "tandem.configDir" read by TandemSessions; state.openSessionIds = [id with a copied transcript]; ensureStarted(); sessions has the id, status STOPPED, history loaded; the fake log has no __spawn__ line
// SessionNotifierTest
@Test fun `a background permission request posts a warning notification`()   // project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, ...) collects; fixtures "plain-reply,permission"; select A, send on B → type WARNING, content contains B's title
```
- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests 'dev.daarkolabs.tandem.ide.*'`.
- [ ] **Step 3: Implement.** Everything in `TandemSessions` runs on `Dispatchers.IO`/`Default`; the configurable touches Swing only inside `withContext(Dispatchers.EDT)`.
- [ ] **Step 4: Run to verify pass** — `./gradlew test`.
- [ ] **Step 5: Commit** — `git commit -m "feat: settings, workspace state and the session service"`

---

### Task 10: Tool window layout, sidebar, header

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/TandemToolWindow.kt`, `.../ui/SessionSidebar.kt`, `.../ui/NewSessionPopup.kt`, `.../ui/SessionHeader.kt`
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/ui/ChatScreen.kt` (becomes `ChatPane(project, handle, manager, rows)`: transcript, permission prompt, prompt box; the status bar moves into `SessionHeader`), `.../ui/TandemToolWindowFactory.kt`
- Delete: `src/main/kotlin/dev/daarkolabs/tandem/ide/TandemChat.kt`, `src/test/kotlin/dev/daarkolabs/tandem/ide/TandemChatTest.kt`
- Modify: `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`

**Interfaces:**
- Consumes: `TandemSessions`, `SessionManager`, `SessionHandle`, `TandemWorkspaceState`.
- Produces composables: `TandemToolWindow(project, sessions)`, `SessionSidebar(...)`, `SessionHeader(...)`, `NewSessionPopup(defaults: NewSessionOptions, onCreate)` (Task 11 adds its pickers; until then it has only a "Create" button).
- Semantics the Driver relies on: each sidebar row is a `Row` with `Modifier.semantics(mergeDescendants = true) { role = Role.Tab }` holding the title `Text`, the relative time, the status dot and, when `attention`, a `Badge("needs you")`; left click selects, right click opens a `ContextMenu` with "Rename", "Fork", "Delete…", "Copy resume command", "Close"; "Delete…" turns the row into an inline confirmation with buttons "Delete" and "Cancel" (no Swing dialog); a hover "x" `IconButton` closes. Above the rows: a "New session" `DefaultButton` and a filter `TextField` (placeholder "Filter"). The header holds the sidebar toggle `IconButton`, the title `Text` (double-click, or the menu's "Rename", swaps in a `TextField` whose state starts with the whole title selected; Enter confirms unless blank, Escape cancels) and the existing status line. Titles are plain `Text`, never Markdown. Layout: `BoxWithConstraints`; sidebar shown when `!collapsed && maxWidth >= 500.dp`; width 220 dp; `Divider(Orientation.Vertical)` between panes; the sidebar calls `manager.refreshIndex()` when it appears. One `ChatRowsPresenter` per open handle in a `remember` map keyed by id, dropped when the handle closes.

- [ ] **Step 1: Write the failing IDE test** (`runChat` gains `fixtures: List<String>`, `configDir: Path`, `delayMs: Int?` → `TANDEM_FAKE_FIXTURES`, `CLAUDE_CONFIG_DIR`, `TANDEM_FAKE_DELAY_MS`; `PROMPT_BOX` becomes `(//div[@class='ComposeNode' and @editabletext])[last()]` because the filter box is editable too; helpers `row(title)` = `//div[@class='ComposeNode' and @role='Tab' and contains(@text, '<title>')]`, `menuItem(text)` = `@visible_text='<text>'`, `newSession()` = click "New session" then "Create")

```kotlin
@Test fun `several sessions live side by side in the sidebar`() {
    val configDir = Files.createTempDirectory("tandem-config")
    val project = ...   // created before the IDE starts, as today
    val seeded = "33333333-3333-4333-8333-333333333333"
    configDir.resolve("projects").resolve(TranscriptFolder key of project.toString(), computed with the same regex).resolve("$seeded.jsonl")
        .writeText(Path.of(fakeClaude).resolveSibling("fixtures/transcripts/history-reply.jsonl").readText() + """{"type":"custom-title","customTitle":"Seeded","sessionId":"$seeded"}""" + "\n")
    val sent = runChat("sidebar", fixtures = listOf("plain-reply", "permission", "plain-reply"), configDir = configDir, delayMs = 8_000) {
        newSession(); typePrompt("Reply with the single word: pong"); waitForText("pong")
        newSession(); typePrompt("Create the fixture file")
        row("Reply with the single word: pong").click(Point(10, 10))      // switch away before the 8 s delayed can_use_tool arrives
        waitFor("badge on the background row", 1.minutes) { row("needs you").present() }
        row("Create the fixture file").click(Point(10, 10)); waitForText("Allow Bash?"); clickButton("Allow once"); waitForText("done.")
        waitFor("badge cleared", 30.seconds) { !row("needs you").present() }
        row("Create the fixture file").rightClick(Point(10, 10)); menuItem("Rename").click(Point(10, 10))
        ui.x("//div[@class='ComposeNode' and @editabletext and @focused='true']").keyboard { typeText("Alpha", 20); enter() }
        waitFor("row renamed", 30.seconds) { row("Alpha").present() }
        row("Seeded").click(Point(10, 10)); waitForText("pong")           // history from the seeded transcript, then --resume
        row("Seeded").rightClick(Point(10, 10)); menuItem("Delete…").click(Point(10, 10)); clickButton("Delete")
        waitFor("seeded row gone", 30.seconds) { !row("Seeded").present() }
    }
    val spawns = sent.filter { it.type == "__spawn__" }.map { it["argv"]!!.jsonArray.map { a -> a.jsonPrimitive.content } }
    assertEquals(3, spawns.size); assertEquals(2, spawns.count { "--session-id" in it }); assertEquals(1, spawns.count { "--resume" in it })
    assertTrue(sent.any { it["request"]?.jsonObject?.get("subtype")?.jsonPrimitive?.content == "rename_session" })
    assertFalse(configDir.walk().any { it.fileName.toString() == "$seeded.jsonl" })
}
```
The three existing IDE tests start with `newSession()` and otherwise stay as they are.

- [ ] **Step 2: Run** `xvfb-run ./gradlew integrationTest --tests '*several sessions*'` → fails (no "New session" button).
- [ ] **Step 3: Implement the composables, rewire the factory to `TandemSessions`, delete `TandemChat`, move its two platform tests' intent into `TandemSessionsTest` where not already covered.**
- [ ] **Step 4: Run `./gradlew test` and `xvfb-run ./gradlew integrationTest`.** On a selector failure dump the hierarchy (`error("DIAGNOSTIC")` in the test, read `out/ide-tests/.../ui-hierarchy/ui.html`) and adjust the XPath, as done for the prompt box.
- [ ] **Step 5: Commit** — `git commit -m "feat: session sidebar with several live sessions"`

---

### Task 11: Prompt bar pickers and the new-session popup

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/PromptBar.kt`
- Modify: `.../ui/ChatScreen.kt` (`ChatPane` uses `PromptBar`), `.../ui/NewSessionPopup.kt` (model, effort, permission mode, prefilled from the workspace state), `src/integrationTest/.../ToolWindowIdeTest.kt`

**Interfaces:**
- Produces: `PromptBar(handle, manager, workspace)`: `TextArea` + Send/Stop as today; `ModelPicker` = `ListComboBox` over `"Default model"` + `state.models.map { it.displayName }` (empty list → "Default model" and `activeModel`), selection → `manager.setModel(id, value)` with `null` for the default; `PermissionModePicker` = `ListComboBox` over `listOf("Default", "Accept edits", "Plan")` ↔ `PermissionMode.DEFAULT/ACCEPT_EDITS/PLAN` → `manager.setPermissionMode`; effort badge `Text("effort: <state.effort>")` when set. `NewSessionPopup` offers the same two pickers plus effort (`"low"`, `"medium"`, `"high"`, `"max"`, "Default effort" first). Every pick is written to `TandemWorkspaceState.lastModel/lastEffort/lastPermissionMode`.

- [ ] **Step 1: Write the failing IDE test** (`modelName(i)` reads `fixtures/permission-mode.ndjson` next to the fake CLI and returns `models[i].displayName` from the initialize response)

```kotlin
@Test fun `the pickers send set_permission_mode and set_model`() {
    val sent = runChat("pickers", fixtures = listOf("permission-mode")) {
        newSession(); typePrompt("Reply with the single word: one"); waitForText("one")
        ui.x("//div[@class='ComposeNode' and @visible_text='Default']").click(Point(10, 10)); menuItem("Plan").click(Point(10, 10))
        typePrompt("Reply with the single word: two"); waitForText("two")
        waitFor("status shows plan", 1.minutes) { ui.x("//div[@class='ComposeNode' and contains(@visible_text, 'mode: plan')]").present() }
        ui.x("//div[@class='ComposeNode' and @visible_text='Default model']").click(Point(10, 10)); menuItem(modelName(1)).click(Point(10, 10))
    }
    val requests = sent.filter { it.type == "control_request" }.map { it["request"]!!.jsonObject }
    assertEquals("plan", requests.first { it["subtype"]!!.jsonPrimitive.content == "set_permission_mode" }["mode"]!!.jsonPrimitive.content)
    assertTrue(requests.any { it["subtype"]!!.jsonPrimitive.content == "set_model" })
}
```
- [ ] **Step 2: Run** `xvfb-run ./gradlew integrationTest --tests '*pickers*'` → fails (no picker).
- [ ] **Step 3: Implement.** - [ ] **Step 4: Run** `./gradlew test` and `xvfb-run ./gradlew integrationTest` → all PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: model and permission-mode pickers in the prompt bar"`

---

### Task 12: Docs, contract check, pull request

**Files:**
- Modify: `docs/protocol.md` (section 4.4 gains `rename_session` and the recorded `set_permission_mode` acknowledgement; new section "11. Transcripts on disk": folder key, line types table, title precedence, the `custom-title` line Tandem appends, what delete removes, the `init` of 2.1.300 has no `effort`), `docs/design.md` (architecture block gains `SessionManager`/`SessionIndex`/`TranscriptReader`; Milestone 1 status), `CLAUDE.md` (Layout: `session/transcript/`; the settings page), `.github/workflows/cli-contract.yml`, `src/test/kotlin/dev/daarkolabs/tandem/protocol/ContractTest.kt`

**Interfaces:**
- Contract workflow: after recording, run `claude -p "Reply with the single word: pong" --output-format stream-json --verbose --safe-mode --session-id 11111111-1111-4111-8111-111111111111` with `CLAUDE_CODE_SESSION_ID=bogus` in the environment and check with a one-line Python assertion that the `system/init` line's `session_id` equals the passed id (spec open item 3); copy that run's transcript into `build/contract/transcripts/`. `ContractTest` gains `live transcripts load with the reader`: every `.jsonl` there loads with `runBlocking { TranscriptReader.read(it) }` and has at least one `User` and one `Assistant` entry.

- [ ] **Step 1: Extend `ContractTest`** (skipped without `tandem.contractDir`, as today) and run it against the fixtures: `./gradlew test --tests '*ContractTest*' -Dtandem.contractDir=$PWD/src/test/resources/fixtures` → PASS.
- [ ] **Step 2: Probe effort control once** (spec open item 5): add a `settings` scenario to `scripts/drive_cli.py` that sends `{"subtype":"update_settings","settings":{"effortLevel":"high"}}` after one prompt and records the response; note in `docs/protocol.md` 4.4 whether the CLI accepts it or answers with an error. No plugin code either way.
- [ ] **Step 3: Write the docs** and run the full suite: `./gradlew --no-build-cache clean test buildPlugin verifyPlugin` and `xvfb-run ./gradlew integrationTest` → all green, verifier "Compatible" for both IDEs, no internal API.
- [ ] **Step 4: Commit** — `git commit -m "docs: describe on-disk transcripts and the session manager"`; push the branch.
- [ ] **Step 5: Open a draft PR** `feat/sessions-and-pickers` → base `feat/protocol-layer`, title "feat: sessions sidebar, several live sessions, pickers", body with Context / Approach / Testing (test counts, IDE test names, verifier result, the open items' outcomes), no attribution footer. Watch CI; fix anything red before reporting done.
