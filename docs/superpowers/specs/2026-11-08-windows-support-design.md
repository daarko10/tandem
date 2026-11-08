# Windows support

Status: draft for review. Follows `docs/design.md` (Milestone 4, "Hardening: Windows/WSL"), `docs/launch.md` (the Windows
gate: "tested by one real user, or the README's first screen says macOS and Linux today; Windows in progress"), `CLAUDE.md`
(the hard rules, and the macOS section as the model for the facts a platform section records) and `docs/protocol.md` 1.3
and 11.1, which already name the Windows binary names and the `C--Users-dev-app` key. Time matters here: do not spend time
that can be avoided, and the earlier a correct result is obtained, the better.

## 1. Goal

Tandem runs on Windows with the CLI installed either by npm (`npm install -g @anthropic-ai/claude-code`, which writes the
shims `claude`, `claude.cmd` and `claude.ps1` into npm's global prefix, `%APPDATA%\npm`) or by the native installer
(`irm https://claude.ai/install.ps1 | iex`, which writes `%USERPROFILE%\.local\bin\claude.exe`), the unit, fixture and
platform tests green on `windows-latest` for every pull request that changes what Gradle reads, and the IDE test green on
`windows-latest` nightly and on demand. The README's status line ("macOS and Linux today; Windows in progress") changes
only after one real user has run Tandem on Windows and said so: CI proves the code, not the feel, and the gate in
`docs/launch.md` asks for a person.

What there is to go on: the audit of the code in section 3 (every finding names its file and line, what happens on
Windows, and the fix), and one run of `.github/workflows/windows-probe.yml` on `windows-latest`, which installed the CLI
both ways, ran the platform-sensitive JVM tests, started the fake CLI under Python and ran the CLI with no sign-in
(section 4). There is no Windows machine in the development sandbox; the probe is the only measurement, and section 3's
proof column says for each finding whether it comes from reading the code, from the probe run, or is still unknown.

## 2. Simplest working version, and what is out of scope

### 2.1 This round

1. The fake CLI runs on Windows (section 5) and the Gradle test tasks start it there, so the platform tests mean
   something on a Windows runner.
2. The code changes in section 3 that a Windows user hits in the first session: finding the CLI (R2, R3), starting the
   `.cmd` shim and stopping its whole tree (R4), the `! command` shell (R6), and the test-only Unix assumptions (R12 to R15, R17).
3. CI: a `build-windows` job in `build.yml` shaped like `build-macos`, and `windows-ide-test.yml` shaped like
   `macos-ide-test.yml` (section 6).
4. A "Windows" section in `CLAUDE.md` (section 7) and the Windows facts in `docs/protocol.md` 1.3 and 11.1, each marked
   measured or inferred.

### 2.2 Out of scope (with the reason)

- **WSL.** A `claude` installed inside WSL is a Linux binary in another operating system: paths (`/mnt/c/…` against
  `C:\…`), the transcript folder (inside the distribution's home) and the process tree (`wsl.exe`) differ in every finding
  below. The IDE's own WSL support is a separate product area (Remote Development). A user who wants WSL runs the IDE's
  WSL remote, which is the next item.
- **Remote Development (JetBrains Gateway, the IDE backend on another machine).** Tandem's tool window would run on the
  client and the CLI on the backend; nothing here is designed for that split. Milestone 4 lists it apart.
- **PowerShell as the `! command` shell.** The CLI's own shell on Windows is Git Bash (it refuses to run its Bash tool
  without it and reads `CLAUDE_CODE_GIT_BASH_PATH`), so `! command` follows the CLI (R6); a user who types PowerShell gets
  what a terminal's `!` gives in the CLI, which is bash.
- **The CLI's Windows sign-in.** Where the CLI keeps its credentials on Windows is the CLI's business (hard rule 3), as
  the macOS Keychain is.
- **Changing the README's claim.** Only the owner, after a user's report (section 1).

## 3. Findings and requirements

Each finding is a requirement, numbered R1 onwards: the file and line as of `main` at `2ec6d4d`, the behaviour on Windows,
the fix chosen, and the proof. Proof reads: **read** (from the code and the JDK's and CLI's documented behaviour),
**probe** (the run in section 4 showed it), **unknown** (a Windows tester must answer it; also listed in section 9).

### 3.1 Finding and starting the CLI

**R1. The `PATH` variable is `Path` on Windows.** `ide/ClaudeBinary.kt:51` reads `environment["PATH"]`. The platform's
`EnvironmentUtil.getEnvironmentMap()` and the JDK's `System.getenv()` both answer case-insensitively on Windows, so at run
time the lookup works; a plain `Map` (a test's, or a future caller's) would not. `environmentFor` (`:97`) already looks the
name up ignoring case. Fix: `detect` looks the key up the same way, in one helper both use. Proof: read; the probe's jshell
step printed whether `System.getenv().get("PATH")` is null on the runner (section 4).

**R2. The names and places `claude` is looked for.** `ide/ClaudeBinary.kt:74` tries `claude.exe`, `claude.cmd`, `claude`
on Windows, in each `PATH` entry and then in `installLocations` (`:65`): `%USERPROFILE%\.local\bin` (the native installer's
`claude.exe`) and `%USERPROFILE%\.claude\local`. Missing: npm's global prefix `%APPDATA%\npm`, where `claude.cmd` lands;
npm's installer puts it on the user `PATH`, but an IDE started by the Toolbox before `npm` was installed does not see it
until it restarts (R5). The native installer does **not** put `.local\bin` on the `PATH`: it tells the user to add it by
hand (probe: "Native installation exists but C:\Users\…\.local\bin is not in your PATH"), so for that install the
install-location fallback is the only way Tandem finds the CLI. The bare `claude` on Windows is npm's POSIX shim
(`#!/bin/sh`), which `CreateProcess` cannot run (probe: `CreateProcess error=193, %1 is not a valid Win32 application`),
so it must stay last and is never the answer when a `.cmd` or `.exe` is anywhere in the search; today the order within one
directory handles that. Fix: add `%APPDATA%\npm` (from the environment, else `<home>\AppData\Roaming\npm`) to the Windows
install locations after `.local\bin`; keep the name order; on Windows never return the bare `claude`. The settings page's
"found on PATH" phrase stays right: on Windows that is the IDE's `PATH` (R5). Proof: probe for the native installer's
location and its `PATH` note, for the three npm shims (`claude`, `claude.cmd`, `claude.ps1` in npm's prefix; no
`claude.exe` beside them) and for error 193; the `%APPDATA%\npm` default is npm's documentation (the runner's prefix is
its own `C:\npm\prefix`).

**R3. `canExecute()` is true for every existing file on Windows.** `ide/ClaudeBinary.kt:57` and `:79` take a file that
`isFile && canExecute()`. The JDK's Windows file system answers `canExecute` true for any file the user can read, so a path
saved in Settings | Tools | Tandem that names `claude.ps1`, the POSIX `claude` shim or `fake-claude.py` is accepted and the
spawn then fails with error 193, after the user was told the path runs. Fix: on Windows, `isExecutable` also requires an
extension of `PATHEXT` (default `.COM;.EXE;.BAT;.CMD;…`, compared ignoring case; `.exe`, `.cmd`, `.bat` and `.com` when
`PATHEXT` is unset), and the settings page says "<path> is not something Windows can run (no .exe, .cmd or .bat)" instead
of "runs". Proof: probe (the jshell step printed `canExecute` for `claude`, `claude.cmd`, `claude.ps1`).

**R4. Spawning `claude.cmd` and stopping its tree.** `session/ClaudeSession.kt:187` starts `ProcessBuilder(argv)` with the
found path as `argv[0]`. The JDK runs a `.cmd` or `.bat` through `cmd.exe` itself (it quotes the arguments for `cmd.exe`
and refuses an argument that holds a double quote; since the April 2024 JDK updates a trailing backslash before the
closing quote, as in `--add-dir "D:\"`, is escaped too), so no `cmd.exe /c` is needed (probe:
`ProcessBuilder(claude.cmd --version)` exited 0 with `2.1.319 (Claude Code)`); `claude.exe` is spawned as is. What npm
installs today is not a Node script: `@anthropic-ai/claude-code` 2.1.319 ships a native
`node_modules\@anthropic-ai\claude-code\bin\claude.exe`, and all three shims only call it (probe: the text of `claude`,
`claude.cmd` and `claude.ps1`). The process Tandem holds through the shim is `cmd.exe`, with `claude.exe` and a
`conhost.exe` as its children; `Process.destroy()` on the shim is `TerminateProcess` on `cmd.exe` alone and leaves both
running (probe: `descendants=2`, `after destroy(shim): shim alive=false children alive=2`, then
`destroyForcibly` on each: `children alive=0`). `terminate()` (`:1093`) already lists `p.descendants()` before closing
stdin, closes stdin (the CLI's own graceful exit; the pipe's read end is inherited by `claude.exe`), then destroys the shim
and every listed descendant still alive; `killNow()` (`:955`) does the same at once. So the tree is killed today; what is
lost on Windows is the graceful signal (`destroy()` is forcible there), which the stdin close replaces. Fix: when the
found file is `claude.cmd`, run `<its directory>\node_modules\@anthropic-ai\claude-code\bin\claude.exe` when that file
exists (the shim's own target: no `cmd.exe`, no `conhost.exe`, the process Tandem holds is the CLI), else the `.cmd` as
today with the tree kill; the settings page shows the `.exe` it will run. Add a platform test that starts a `.cmd` shim
(the fake CLI's, section 5) and checks that no `python` of its own outlives a stop (R13). A drive root as an added folder
(`--add-dir D:\`) is refused by `checkFolder` with a reason, since the root's trailing backslash is the one argument that
cannot be passed to a `.cmd` cleanly. Proof: probe for all of it except the `.exe` resolution, which is the fix.

**R5. There is no login-shell environment on Windows.** `ide/ShellEnvironment.kt:184` reads
`EnvironmentUtil.getEnvironmentMap()`, which on Windows is the IDE's own environment (the platform runs no shell there):
the user and system variables from the registry as they were when the IDE started, plus what the Toolbox or a terminal
added. That is where a terminal finds `claude` too, so it is the right environment; a variable set after the IDE started
(npm's or the installer's `PATH` entry, `CLAUDE_CONFIG_DIR`) reaches Tandem at the next IDE start, as it reaches Explorer.
Fix: none in code; the doc comment and `CLAUDE.md` say so, and R2's install locations cover the `PATH` not yet seen.
`cliEnvironment` (`session/CliEnvironment.kt`) and `environmentFor`'s `Path` handling need nothing. Proof: read.

**R6. The `! command` shell.** `session/ShellRunner.kt:60` runs `%ComSpec% /c <command>` on Windows, marked unverified.
The CLI on Windows runs its Bash tool and the terminal's `!` escape in Git Bash: it finds `bash.exe` from Git for Windows,
or takes `CLAUDE_CODE_GIT_BASH_PATH`, and refuses to run without one. A `! command` in Tandem that ran under `cmd.exe`
would give Claude output from a different shell than the one it uses itself, and `ls`, `grep`, `&&` would behave
otherwise. Decision: on Windows `Shells.argv` is `<bash> -lc <command>` with `<bash>` the first of
`CLAUDE_CODE_GIT_BASH_PATH`; `bash.exe` in a `PATH` entry whose path does not end in `\System32` (that one is WSL's
launcher, which is not Git Bash); `%ProgramFiles%\Git\bin\bash.exe`; `%ProgramFiles(x86)%\Git\bin\bash.exe`;
`%LOCALAPPDATA%\Programs\Git\bin\bash.exe`. With none, `%ComSpec% /c` as today and the row's first line says
`Ran in cmd.exe: Git Bash was not found`, so the user knows why Claude's and the row's shells differ (hard rule 5). The
10-minute cap, the output polling and the tree kill (`:216`) are platform-neutral; `descendants()` covers bash's children.
Proof: read for the CLI's requirement (its own error text names git-bash; the CLI's documentation names the variable);
unknown for the CLI's behaviour under Tandem's stream-json spawn without Git Bash (section 9).

**R7. Stdout, stdin and line endings.** `ClaudeSession.kt:978` reads stdout as UTF-8 with `readLine()`, which ends a line
at `\n` or `\r\n`, and `:1060` writes stdin as UTF-8; Node writes UTF-8 to a pipe whatever the console code page. stderr
(`:1054`) uses the default charset, UTF-8 on the JetBrains Runtime (JEP 400). `TranscriptFiles.endsInsideLine` looks for
`\n` as the last byte, which holds for `\r\n` too. Fix: none; `bufferedReader(Charsets.UTF_8)` on stderr for symmetry.
Proof: read.

### 3.2 Transcripts

**R8. The folder key, the drive letter and the case-insensitive volume.** `session/transcript/TranscriptFolder.kt:27` keys
`C:\Users\dev\app` as `C--Users-dev-app` (every non-alphanumeric character to `-`), from
`ide/TandemSessions.kt:75`'s `physical(directory)`, a `toRealPath()` that on Windows gives native separators and the
volume's own case for every segment, the drive letter upper-case. The CLI keys on `process.cwd()`, whose drive letter is
the case the parent gave it; a lower-case `c:\…` would give `c--Users-dev-app`. For a key under 200 characters nothing
breaks: NTFS ignores case, so `projectsDir.resolve("C--Users-dev-app")` opens `c--Users-dev-app` too, and a transcript's
`cwd` in other case is the project by `Files.isSameFile` (`SessionIndex.kt:188`). For a cut key, `candidates` (`:66`)
already takes a folder that matches ignoring case when `isSameFile` agrees. `configDir` (`:36`) takes `CLAUDE_CONFIG_DIR`
from the environment of R5, else `user.home\.claude`, and `user.home` is `%USERPROFILE%` on the JetBrains Runtime. Fix:
none in code; `TranscriptFolderTest` gains the `C:\Users\dev\app` case from `docs/protocol.md` 11.1 (it is a string test,
so it runs everywhere); which case a lower-case `c:\…` shell gives is a tester question (section 9). Proof: probe for the
CLI's spelling: started in `D:\a\tandem\tandem` (its `init.cwd`, backslashes, upper-case drive), CLI 2.1.319 wrote the
folder `%USERPROFILE%\.claude\projects\D--a-tandem-tandem` and names it in `init.memory_paths.auto`; its
`additional_directories` lists the same directory as `D:/a/tandem/tandem`, forward slashes, so a transcript's or init's
path may come in either form and Tandem's comparisons go through `Path`, never strings, which they do. Probe also for the
transcript tests on NTFS: `TranscriptFolderTest` 19/19, `TranscriptFilesTest` 3/3, `TranscriptReaderTest` 64/65 and
`SessionIndexTest` 39/42 pass; the four failures are test assumptions, R17.

**R9. The watch service, and deleting what the CLI has open.** `SessionIndex.kt:361` asks for the JDK's
`SensitivityWatchEventModifier.HIGH`; on Windows the watch service is native (`ReadDirectoryChangesW`) and the modifier
is accepted and ignored, so a terminal session shows up within a second. The watch holds a handle on each project folder,
which stops nothing Tandem does (it deletes `<id>.jsonl` and `<id>\`, never the folder). A file another process holds open
without `FILE_SHARE_DELETE` cannot be deleted on Windows. `SessionManager.delete` (`:669`) stops the session's own process
before it touches the files, so the only writer that can hold a transcript is a terminal's CLI mid-write; its
`AccessDeniedException` is the `IOException` `delete` already throws to the sidebar's caller. `SessionIndexTest.kt:615`
assumes Linux for "a watch service that reports deletions"; Windows reports them too. Fix: widen that assumption to Linux
or Windows; no code change; the delete-of-a-terminal-session-mid-turn case is a tester question. Proof: read; probe for
`SessionIndexTest` on Windows (the watch tests pass).

### 3.3 Edits and paths from the CLI

**R10. Backslash paths, forward-slash base paths, and the real-path comparison.** `ide/edits/IdeEditTools.kt:310` takes the
call's `file_path` with `Path.of`, which on Windows accepts `C:\x\y` and `C:/x/y` alike, resolves it with `toRealPath()`
(volume case), and compares with `startsWith` against `project.basePath` (`C:/Users/dev/app`, forward slashes, also
through `Path.of` and `toRealPath`) and the added folders; `WindowsPath.startsWith` and `equals` ignore case. `realPath`
(`:339`) walks up to the deepest existing ancestor for a new file. `chat/ToolLabels.kt:77` and
`chat/ContextBreakdownModel.kt:113` treat `/` and `\` as separators for the rows. `EditMatcher.normalizeNewlines`
(`session/edits/EditMatcher.kt:160`) reads the model's `\r\n` as `\n` for the `Document`, which the IDE writes back with
the file's own separator. No `file://` URL is built or parsed in `ide/`. What is not covered: a `file_path` in MSYS form
(`/c/Users/dev/app/x.kt`), which `Path.of` reads as drive-relative and `isAbsolute` rejects with the tool's
"not absolute" answer; the CLI on native Windows passes Windows paths in its tool calls, and the MSYS form appears only
in text a Bash command printed. Fix: none until a tester sees the MSYS form in a tool call (section 9); then map
`/<drive>/…` to `<drive>:\…` before `Path.of`. `@path` mentions (`ide/context/ProjectFiles.kt`) are built from VFS paths
with `/`, which the CLI's Node resolves on Windows; a tester confirms the CLI reads the file. Proof: read; unknown for the
two CLI-side forms.

### 3.4 Chat and keys

**R11. Key caps and glyphs.** `ui/cards/CardKeys.kt:70`, `chat/QueuedSends.kt:45` and `:81`, `ui/PromptBar.kt:506` and
`:788` show `⌘` only when `SystemInfo.isMac`; elsewhere `Ctrl↩` and `Ctrl+Enter`. The `Add files or photos` action's
default shortcut is Ctrl+Alt+Shift+O outside the Mac keymaps (`plugin.xml`). The sidebar's Backspace-deletes rule is Mac
only (`SessionSidebar.kt:354`). The orbs and the working row's band are drawn (`ui/rows/Spark.kt`, `Shimmer.kt`), not
glyphs, so no font fallback is involved; the one glyph shown on Windows is `↩` (U+21A9) in `Ctrl↩`, which Inter, the
IDE's default UI font, has. Fix: none; a tester confirms `Ctrl↩` renders and Ctrl+S accepts in the diff (section 9).
Proof: read.

### 3.5 Tests, the fake CLI and the build

**R12. The fake CLI cannot start on Windows.** `src/test/resources/fake-claude.py:142` imports `fcntl` (not on Windows:
`ModuleNotFoundError`), `:204` calls `select.select` on stdin (Windows `select` takes sockets only: `OSError`), and `:323`
writes its lines with `sys.stdout.write` in text mode (the console code page, `cp1252` on an English Windows: a fixture
with a character outside it raises `UnicodeEncodeError`; and `\n` becomes `\r\n`, which `readLine` accepts). Rule (o)
escapes the working directory once for JSON (`:178`) and says a `\\` inside a `partial_json` string would come out
malformed, which "does not occur in a Linux or macOS temporary directory"; on Windows every path has backslashes. Fix:
section 5. Proof: probe: `python -I fake-claude.py` exits 1 with `ModuleNotFoundError: No module named 'fcntl'` before its
first line, under `python` and `python3` alike; `select.select([0], …)` on its own raises `OSError: [WinError 10093]`;
Python's `sys.stdout.encoding` is `cp1252`; and `FakeClaudeTest` is 0/7 on the runner for the same import.

**R13. The test tasks point `tandem.claudePath` at `fake-claude.py` itself.** `build.gradle.kts:213` and `:237`: on
Windows `CreateProcess` cannot run a `.py` (no shebang support; file associations do not apply), `ClaudeBinary.isExecutable`
says it can (R3), and every platform test's session ends with "Could not start …: error=193". Fix: `src/test/resources/
fake-claude.cmd` (`@python -I "%~dp0fake-claude.py" %*`, with `python`, which the Windows Python installs under that name
and the Microsoft Store alias does not shadow the way it shadows `python3`); the three Gradle tasks pick the `.cmd` when
`System.getProperty("os.name")` starts with `Windows`. This also makes the Windows platform tests spawn exactly what npm's
install spawns, a `.cmd` shim through `cmd.exe` (R4). `FakeCliProcesses` (`src/test/kotlin/…/session/FakeCliProcesses.kt:6`)
finds the fake by an argument ending in `fake-claude.py`; on Windows `ProcessHandle.Info.arguments()` is empty for most
processes, so it finds nothing and `awaitNoOwnFakeCli` waits for nothing. Fix: the fake writes its own pid into the log's
`__spawn__` line, and the test helper takes the pids from the log when the handle's arguments are empty. Proof: read
(`CreateProcess` and the JDK's `ProcessHandle` on Windows: the jshell step read the children's `command()`, which Windows
gives, not their arguments); probe for the runner's Pythons: `python.exe` and `python3.exe` both in the tool cache ahead
of the Store aliases, 3.12.10, and `LongSessionTest`'s `python3` passed there; a developer's machine without the tool
cache has only the alias under `python3`, hence the picker (R14).

**R14. Tests that write `#!/bin/sh` scripts or run `sh -c`.** `ide/ClaudeBinaryTest.kt:25,112,158` make `claude` out of
shell scripts with `setExecutable`; `ide/TandemSessionsTest.kt:398` builds the "interpreter beside claude" case the same
way; `session/ScriptedProcess.kt:63`, `session/ClaudeSessionTest.kt:73,186,615,633,652,696,725,1079`,
`session/SessionHandleTest.kt:94` and `session/SessionManagerTest.kt:1505,1536,1558,1573,1597,2339` start
`sh -c "cat > /dev/null"` and similar. `ShellRunnerTest`, `SessionManagerTest`'s shell tests and `FolderCheckTest`'s link
test already `assumeTrue` their platform. On Windows `sh` is not on `PATH` outside Git Bash's own `bin`, and even where it
is, `/dev/null` and `cat > file` behave. Fix: one test helper, `Processes.sink()` and `Processes.script(stdout, exit)`,
that uses `python -c` on every platform (Python is a test prerequisite already) in place of `sh -c`; `ClaudeBinaryTest`
and `TandemSessionsTest` write a `.cmd` on Windows (`@echo 2.0.0 (Claude Code)`; the interpreter-beside case as
`claude.cmd` calling `tandem-test-node.cmd` by `%~dp0`) and the `#!/bin/sh` pair elsewhere. `session/transcript/
LongSessionTest.kt:29` runs `python3`, which on a developer's Windows is the Store alias: the helper picks `python3`,
`python`, then `py -3`, whichever answers `--version`. Proof: probe: `ClaudeBinaryTest` 12/21, the nine red being the
six `version*` tests (`#!/bin/sh` scripts), `PATH order wins…` and `nothing found…` (a `PATH` joined with `:`; Windows
splits on `;`) and `non-executable files are skipped` (R3: `canExecute` is true); `ShellRunnerTest` 1/1 run and 15
skipped by its own assumption; `FolderCheckTest` 4/4 and 1 skipped. `ClaudeSessionTest`, `SessionHandleTest` and
`SessionManagerTest` were not in the probe's filter.

**R15. Line endings in the checkout.** Git for Windows defaults to `core.autocrlf=true`, which checks the fixtures
(`*.ndjson`, `transcripts/*.jsonl`), `fake-claude.py`, the scripts and `gradlew` out with `\r\n`. The decoders read lines
by `readLine` and the JSON parsers take a trailing `\r` as whitespace, so most tests pass either way; a byte-exact test
(`TranscriptFilesTest`'s appended line, `make_long_reply`'s output) or a Python script's `#!` line does not. Fix: a
`.gitattributes` with `* text=auto`, `*.ndjson text eol=lf`, `*.jsonl text eol=lf`, `*.py text eol=lf`, `*.sh text eol=lf`,
`gradlew text eol=lf`, `*.cmd text eol=crlf`, `*.bat text eol=crlf`, so every checkout is the same and `cmd.exe` gets the
endings it parses correctly. Proof: probe: the runner's Git has `core.autocrlf=true` in its system config and checked out
`fake-claude.py` with 730 CR bytes, `plain-reply.ndjson` with 15 and `gradlew` with 248; against that checkout
`FixtureDecodeTest` 102/102, `RobustnessTest` 19/19, `TranscriptFilesTest` 3/3 and `ChatRowsBuilderTest` 91/91 passed, so
the decoders and the fixture readers tolerate `\r\n`; `.gitattributes` is for the byte-exact cases and the scripts, not
for the decoders.

**R16. The build and the IDE test need no X server on Windows.** `build.gradle.kts` has no Unix assumption left (the GC
log path is quoted for a drive letter, `:218`); `xvfb-run` appears only in docs and `build.yml`; the IDE Starter runs the
Windows IDE build with the runner's desktop session, as it does on `macos-latest`; `scripts/*.sh` are used by no Gradle
task. Fix: none in the build; section 6 for the jobs. Proof: probe: `gradlew.bat test` downloaded the platform, compiled
everything and ran 451 tests in 5 min 59 s (19 failed, 17 skipped, every failure named above); unknown for the IDE Starter
on `windows-latest` until `windows-ide-test.yml` runs once.

**R17. Test assumptions the Windows file system breaks.** Three transcript tests failed on the runner for reasons that are
not product bugs. `TranscriptReaderTest.kt:831` and `SessionIndexTest.kt:282` make an unopenable file as "a path below a
plain file", which Unix refuses with `ENOTDIR` (an `IOException` the reader reports) and Windows with
`ERROR_PATH_NOT_FOUND`, which the JDK maps to `NoSuchFileException`: the reader takes that as "no such session" and the
`meta` call throws it on instead of falling back. `SessionIndexTest.kt:407` expects `<root>/link/../<name>` to be resolved
through the link (`<root>/real/<name>`, another directory) because that is what the Unix file system says; Win32 collapses
`..` lexically before it follows a link, so on Windows the file system says it is the project, and `isProjectPath`'s rule
("what the file system says") gives the opposite verdict, correctly. Fix: the two unopenable-file tests make the file
unopenable in a way both systems share (a directory named `<id>.jsonl`), and `:407` runs on Unix only, with the Windows
answer pinned in a sibling test. The `..`-before-links fact goes into `CLAUDE.md` (section 7). Proof: probe.

## 4. The probe run

`.github/workflows/windows-probe.yml` on the branch `feat/windows`, `workflow_dispatch` (and, while the file is not on the
default branch and so cannot be dispatched, a push to that branch). It gives no credential to anything, and its results are
in the run's log and the artifact `windows-probe-test-reports`:

(a) `npm install -g @anthropic-ai/claude-code`, then `where claude`, `where claude.cmd`, `where claude.exe`, the files in
    `%APPDATA%\npm`, the text of the three shims, and `claude --version`; at the end the native installer and the files in
    `%USERPROFILE%\.local\bin`.
(b) `gradlew.bat test --continue` with `*Transcript*`, `*CliEnvironment*`, `*ClaudeBinary*`, `*SessionIndex*`,
    `*ShellRunner*`, `*EditMatcher*`, `*FolderCheck*`, `*ToolLabels*`, `*FixtureDecode*`, `*Robustness*`, `*FakeClaude*`,
    `*LongSession*`, `*ContextBreakdownModel*`, `*ChatRowsBuilder*`, the report uploaded whatever is red.
(c) `python -I src/test/resources/fake-claude.py --session-id …` with `TANDEM_FAKE_FIXTURE=plain-reply` and two host frames
    on stdin, its first three stdout lines and its stderr; `import fcntl` and `select` on stdin on their own.
(d) `claude --version`, then `claude -p "Reply with PONG" --output-format stream-json --verbose` with no sign-in and no key,
    cut at 90 s, exit code, stdout and stderr.
And a jshell step for R1, R3 and R4: the JDK's `canExecute` for each shim, `ProcessBuilder(claude.cmd --version)`, and the
shim's descendants before and after `destroy()` on the shim alone.

The run's URL and what each step showed are recorded in section 4.1 once the run has finished; the proof column of
section 3 refers to it.

### 4.1 Results

Run: https://github.com/daarko10/tandem/actions/runs/42448947151 (`windows-latest`, 2026-11-08, Node 22.23.4, npm 10.9.9,
Git for Windows 2.56 with `core.autocrlf=true`, Python 3.12.10 as `python` and `python3`, Zulu JDK 21.0.13; CLI 2.1.319).
Artifact: `windows-probe-test-reports`.

(a) **Where the CLI lands.** `npm install -g` wrote `claude`, `claude.cmd` and `claude.ps1` into npm's prefix (the runner's
`C:\npm\prefix`) and the binary into `<prefix>\node_modules\@anthropic-ai\claude-code\bin\claude.exe`; all three shims
call that `.exe`, so there is no `node` process. `where claude` answers `claude` then `claude.cmd`; `where claude.exe`
finds nothing on the `PATH`. The native installer wrote `%USERPROFILE%\.local\bin\claude.exe` (345 MB) and said in its
own output that the folder is not on the `PATH`; `where claude` still found nothing there. Both answer `--version` with
`2.1.319 (Claude Code)`.

(b) **The tests.** 451 ran, 19 failed, 17 skipped, in 5 min 59 s of Gradle. Green: `ChatRowsBuilderTest` 91,
`ContextBreakdownModelTest` 15, `ToolLabelsTest` 33, `FixtureDecodeTest` 102, `RobustnessTest` 19, `CliEnvironmentTest` 1,
`EditMatcherTest` 11, `LongSessionTest` 1, `TranscriptFilesTest` 3, `TranscriptFolderTest` 19, `FolderCheckTest` 4 (1
skipped), `ShellRunnerTest` 1 (15 skipped). Red: `ClaudeBinaryTest` 9 of 21 (R3, R14), `FakeClaudeTest` 7 of 7 (R12),
`SessionIndexTest` 2 of 42 and `TranscriptReaderTest` 1 of 65 (R17).

(c) **The fake CLI.** It does not start: `ModuleNotFoundError: No module named 'fcntl'` under `python` and `python3`, exit
1, nothing on stdout (R12).

(d) **The CLI with no sign-in.** `claude --version` prints `2.1.319 (Claude Code)`. `claude -p "Reply with PONG"
--output-format stream-json --verbose` exits 1 after 212 ms with three lines: a `system/init` (`apiKeySource: "none"`,
`cwd: "D:\\a\\tandem\\tandem"`, `model: "claude-opus-5-5"`, and new fields worth a note in `docs/protocol.md` 5.1 when the
recording is promoted: `powershell_path`, `messaging_socket_path` as a named pipe `\\\\.\\pipe\\LOCAL\\cc-msg-…`,
`memory_paths.auto` under `projects\\D--a-tandem-tandem\\memory\\`), a synthetic `assistant` line (`model: "<synthetic>"`,
`error: "authentication_failed"`, `is_api_error_message: true`, text `Not logged in · Please run /login`), and a `result`
with `is_error: true`, `terminal_reason: "api_error"`, `subtype: "success"`: the shape of the `invalid-model` fixture, which
Tandem already decodes and shows as an error. stderr had one line, the CLI's own note that the workspace is not trusted
and its `permissions.allow` entries are ignored. The run created `%USERPROFILE%\.claude` with `projects\D--a-tandem-tandem`.
Tandem's notice for this shape must say "sign in with `claude` in a terminal", never offer a login (hard rule 3). The
recording is in the artifact as `probe-prompt.ndjson` and can be promoted to a fixture after redaction (the runner's paths
and user name are in it).

(e) **The JDK and the shims (jshell).** `canExecute()` is true for `claude`, `claude.cmd` and `claude.ps1` alike (R3).
`ProcessBuilder("…\claude.cmd", "--version")` works, exit 0; `ProcessBuilder("…\claude", …)` throws `CreateProcess
error=193` (R2, R4). The shim started with the stream-json flags had two descendants, `conhost.exe` and `claude.exe`;
`destroy()` on the shim ended `cmd.exe` and left both alive; `destroyForcibly()` on each ended them (R4). `user.home` is
`%USERPROFILE%`; the JVM's default `file.encoding` is UTF-8 while `stdout.encoding` and `native.encoding` are `Cp1252`
(R7: the pipes are read with an explicit charset, so this does not matter to Tandem); `System.getenv().get("PATH")` works
(the runner's variable is spelt `PATH`, so this does not settle R1's `Path` case).

(f) **The checkout.** `core.autocrlf=true` from Git for Windows' system config; `fake-claude.py` had 730 CR bytes,
`plain-reply.ndjson` 15, `gradlew` 248 (R15).

## 5. The fake CLI's Windows mode

`src/test/resources/fake-claude.py` keeps one code path for every platform where it can, and names the Windows difference
where it cannot:

1. **The log lock.** `fcntl.flock` (`:241`) becomes a small `locked(file)` helper: `fcntl` where it imports, else
   `msvcrt.locking(fd, msvcrt.LK_LOCK, 1)` on the first byte (the same exclusive, blocking semantics for the counting of
   lines that two processes starting together need). Nothing else uses `fcntl`.
2. **Reading stdin with a timeout.** `select.select([0], …)` (`:204`) goes; a daemon thread reads `sys.stdin.buffer` in
   chunks into a `queue.Queue`, and `next_host_line` takes from the queue with the remaining time as its timeout. One path
   on every platform, so Linux and macOS test what Windows runs.
3. **Output bytes.** `emit` writes `(line + "\n").encode("utf-8")` to `sys.stdout.buffer` and flushes: UTF-8 whatever the
   console code page, `\n` whatever the platform. Where the fake opens files for text (`:507`, the log), `encoding="utf-8"`
   and `newline="\n"`.
4. **Rule (o) on Windows.** The working directory's `\` is escaped once for a JSON string today; inside a `stream_event`'s
   `partial_json`, a string within a string, it must be escaped twice. The fake escapes for the depth of the placeholder:
   `json.dumps(json.dumps(cwd)[1:-1])[1:-1]` inside `partial_json`, once elsewhere; a test fixture (`ide-edit`) replayed
   on Windows is the check, and the rule's docstring names the case.
5. **`fake-claude.cmd`** beside it (R13), one line, and the Gradle tasks choose it on Windows. `TANDEM_FAKE_GATE_FILE`,
   `TANDEM_FAKE_RESULT_GATE_FILE`, `TANDEM_FAKE_LOG` and `TANDEM_FAKE_EDIT_DIR` are plain paths the tests write with
   `Path.toString()`, native separators, which `os.path` reads.
6. **The pid in `__spawn__`** (R13) so that the test helpers can find the fake's processes where the JDK cannot read a
   process's arguments.

`scripts/` (the recorders, `drive_cli.py`, the MCP probe servers) stay Linux and macOS tools: they need a signed-in CLI
and a shell, and the nightly runs them on Linux. Their tests are not run on Windows.

## 6. CI

**`build-windows` in `build.yml`**, after `build-macos` and shaped like it: `needs: [ changes ]`, the same `if`,
`runs-on: windows-latest`, `timeout-minutes: 60`, checkout, `setup-java` (zulu 21), `setup-gradle`, then
`./gradlew check` under `shell: bash` (Git Bash runs the wrapper script on the runner, and `tee` and the grep-based
"Report build problems" step work unchanged); the hang watch uses `jcmd -l` instead of `pgrep` to find the test JVM. No
`free-disk-space` step: the action is Linux-only, and the Windows runner's drive holds the platform download, the Gradle
caches and the test IDE as the macOS runner's does. The `changes` job's skip pattern is unchanged. The job's name in the
ruleset's required checks is the owner's step, as `build-macos` was.

**`windows-ide-test.yml`**, a copy of `macos-ide-test.yml` with `runs-on: windows-latest`, the label `windows-ide-test`, a
different nightly minute (`cron: "17 4 * * *"`), and `./gradlew integrationTest` with no `xvfb-run`: the runner has a
desktop session. What the Starter needs on Windows, to be confirmed by the first run: long paths (the runner image enables
`LongPathsEnabled`; the job sets `git config --global core.longpaths true` before checkout for the IDE's own unpacking
under `out/ide-tests`), the Windows IDE distribution (the Starter picks it by platform), and `python` on `PATH` for the
fake (`actions/setup-python` is not needed on the image, which has one; the job prints `python --version` first as the
macOS job does). The report steps are the same bash steps. Until that first run is green, the job is allowed to be red
without blocking anything: it is nightly and by label only, like the macOS one.

The Linux `ide-test` shards and the Plugin Verifier stay as they are: the verifier's verdict does not depend on the host.

## 7. The "Windows" section for `CLAUDE.md`

To be added after the macOS section, in the same voice, once the code of section 3 is in (each line is a fact a later
reader needs; the ones marked inferred become measured when a tester confirms them):

- `claude` is found with the IDE's own environment: Windows has no login shell for the platform to run, so
  `ShellEnvironment` is the process environment, the registry's user and system variables as they were when the IDE
  started. A `PATH` entry or `CLAUDE_CONFIG_DIR` set after that reaches Tandem at the next IDE start. After the settings
  path and `tandem.claudePath`, `ClaudeBinary.detect` searches that `PATH` for `claude.exe`, `claude.cmd`, then `claude`,
  then `%USERPROFILE%\.local\bin` (the native installer's `claude.exe`), `%USERPROFILE%\.claude\local` and `%APPDATA%\npm`
  (npm's `claude.cmd`). The bare `claude` there is npm's POSIX shim and is never run. A configured path must end in an
  extension of `PATHEXT`: `canExecute` is true for every file on Windows.
- The npm package ships a native `node_modules\@anthropic-ai\claude-code\bin\claude.exe` and its `claude.cmd` only calls
  it (2.1.319); Tandem runs that `.exe` when it finds the `.cmd`. A `.cmd` run as such goes through `cmd.exe`, which the
  JDK arranges by itself; the process Tandem holds is then `cmd.exe` with `claude.exe` and `conhost.exe` as children. Stop
  closes stdin (the CLI's graceful exit), then kills the shim and every descendant it listed first; `Process.destroy()` is
  `TerminateProcess` on Windows, never a signal. A drive root cannot be an added folder.
- Win32 collapses `..` lexically before it follows a link, so `<root>\link\..\x` is `<root>\x` on Windows and the link's
  target's sibling on Unix; `isProjectPath` believes the file system on each, and the tests say which they assume.
- The CLI runs its Bash tool in Git Bash and reads `CLAUDE_CODE_GIT_BASH_PATH`; so does Tandem's `! command` (that
  variable, then `bash.exe` on `PATH` outside `System32`, then Git for Windows' usual folders), falling back to `cmd.exe /c`
  with a note in the row. Tandem passes the variable through like every other one.
- The CLI keys transcripts on `process.cwd()` with its drive letter: `C:\Users\dev\app` is `C--Users-dev-app`
  (`docs/protocol.md` 11.1). NTFS ignores case, so a key or a `cwd` in other case finds the same folder; Tandem compares
  paths with `Files.isSameFile` as on macOS. `user.home` is `%USERPROFILE%`.
- The JDK's `WatchService` is native on Windows: a session started in a terminal shows up within a second. A file another
  process holds open cannot be deleted; deleting a session whose CLI is writing shows that error in the chat.
- Tests: the platform tests spawn `src/test/resources/fake-claude.cmd` (the Gradle tasks pick it on Windows), which runs
  `python`, never `python3` (the Microsoft Store alias). Test helpers start processes with `python -c`, not `sh -c`.
  `.gitattributes` keeps fixtures, scripts and `gradlew` at LF and `*.cmd` at CRLF whatever `core.autocrlf` says. The
  recording scripts under `scripts/` are not run on Windows.
- CI: the `build-windows` job in `build.yml` runs `./gradlew check` on `windows-latest` on the same terms as
  `build-macos`. The IDE test runs on Windows in `windows-ide-test.yml`: nightly, by hand
  (`gh workflow run windows-ide-test.yml --ref <branch>` once the workflow is on `main`), and on a pull request labelled
  `windows-ide-test`. On Windows, run `gradlew.bat integrationTest` without `xvfb-run`.
- `docs/superpowers/specs/2026-11-08-windows-support-design.md` has the audit and the probe run; `.github/workflows/
  windows-probe.yml` reruns the probe by hand.

## 8. Task slices (each green on its own; dependencies in brackets)

1. `.gitattributes` (R15) and the fake CLI's Windows mode with `fake-claude.cmd` and the Gradle task switch (R12, R13);
   `FakeClaudeTest` passes on Linux unchanged. Sonnet-tier mechanical work with a Fable review of rule (o).
2. Test helpers: `Processes.sink()`/`script()` on `python -c`, the Python picker, the `.cmd` variants in
   `ClaudeBinaryTest` and `TandemSessionsTest`, the pid in `__spawn__` and `FakeCliProcesses` (R13, R14). [1]
3. `ClaudeBinary` on Windows: case-insensitive `PATH` key, `%APPDATA%\npm`, no bare `claude`, the `.cmd` shim resolved to
   npm's `bin\claude.exe`, `PATHEXT` in `isExecutable` and the settings page's wording; `checkFolder` refuses a drive root;
   `TranscriptFolderTest`'s `C:\Users\dev\app` case; the R17 tests; `SessionIndexTest`'s widened assumption (R1 to R4, R8,
   R9, R17). Opus tier.
3a. The probe's `probe-prompt.ndjson` redacted and promoted to the fixture `not-logged-in` (CLI 2.1.319, Windows), with the
   notice text for `authentication_failed` checked against hard rule 3, and `docs/protocol.md` 5.1 noting
   `powershell_path`, `messaging_socket_path` and `memory_paths`. Sonnet tier, under `docs/launch.md` tactic 4.
4. `Shells.argv` with Git Bash and the row's note (R6), with the Linux and macOS behaviour unchanged. Opus tier.
5. `build-windows` in `build.yml` (section 6); green on a pull request before merging. [1, 2, 3]
6. `windows-ide-test.yml`, run once by label on the pull request; its red, if any, becomes the next slice's list. [5]
7. Docs: the `CLAUDE.md` section (7), `docs/protocol.md` 1.3 and 11.1 with the measured facts, `docs/design.md`'s
   Milestone 4 line. The README's status line stays until a user's report.

Reviewers for each slice run on Opus; the final whole-branch review on Fable (the session's top model).

## 9. Open questions a Windows tester must answer

1. Whether a session started from a shell whose cwd is `c:\…` (lower-case drive) gives `c--Users-dev-app` (R8; the probe
   proved `D:\a\tandem\tandem` gives `D--a-tandem-tandem`), and what the transcript's `cwd` lines spell (`init.cwd` had
   backslashes, `additional_directories` forward slashes).
2. The form of `file_path` in the CLI's `edit_file`/`write_file` calls on native Windows: `C:\Users\…` (expected),
   `C:/Users/…`, or the MSYS `/c/Users/…` (R10); and whether an `@path` mention with forward slashes reads the file.
3. What the CLI does under Tandem's spawn when Git Bash is missing: a `result` error, a stderr line and exit code 1, or a
   working session whose Bash tool fails per call (R6). Tandem must show whichever it is (hard rule 5).
4. Stop while the CLI runs a tool: does any `node` or tool process outlive it (R4, with `tasklist` after Stop).
5. Deleting a session from the sidebar while its CLI is mid-turn (R9): the error text in the chat, and whether the file is
   gone after the turn.
6. The keys: Ctrl+Enter queues, Shift+Tab cycles the mode, Ctrl+S accepts in the diff, Ctrl+Alt+Shift+O opens the picker,
   Delete asks to delete a session; and `Ctrl↩` renders (R11).
7. Whether npm's `claude.cmd` is found by an IDE started from the Toolbox (R2, R5), and whether the native installer's
   `claude.exe` survives its own auto-update while a session runs.
8. The MCP sign-in: the browser opens (`BrowserUtil`) and the callback completes.
9. Feel: the owner's `runIde` checklist on a Windows machine, as for macOS.
