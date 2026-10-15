# Skills

These skills come from JetBrains' [intellij-community](https://github.com/JetBrains/intellij-community/tree/master/.agents/skills)
repository (Apache License 2.0, copyright JetBrains s.r.o.). They are copied unchanged so they can be updated by re-copying.

They were written for the IntelliJ monorepo. When a skill mentions `tests.cmd`, `bt.cmd`, Bazel or `.iml` files,
translate to this repo: `./gradlew test` for tests, `build.gradle.kts` for dependencies.

| Skill | Why it is here |
|---|---|
| platform-coroutines-structured-concurrency | Scope ownership and cancellation. Prevents EDT freezes and leaked CLI processes. |
| kotlin-ui-swing-component-architecture | State flow and EDT work in UI components. |
| jewel-markdown | Streaming chat rendering. The biggest technical risk. |
| ide-diagnostics-mcp | Find freezes and blocked threads in a running IDE. |
| writing-tests, driver-ui-tests | JUnit with EDT fixtures; UI tests with IDE Starter. |
| code-style | Kotlin style used across the platform. |
| kotlin-ui-dsl | Settings pages. |
| actions | Editor actions ("Send selection to Tandem"). |
| ui-accessibility, icons | Keyboard, focus, labels; New UI icons. |
| debugging | IDE failure debugging techniques. |

To refresh: sparse-clone intellij-community with `.agents/skills` and copy the folders above over these.
