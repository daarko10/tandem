package dev.daarkolabs.tandem.session

/**
 * Live processes running the fake CLI (`src/test/resources/fake-claude.py`), children of this test JVM. Where the JDK gives no
 * process arguments (Windows), every Python process under the JVM counts: the test JVM starts no other Python that outlives a test.
 */
fun fakeCliProcesses(): List<ProcessHandle> =
    ProcessHandle.current().descendants().filter { handle -> handle.isAlive && isFakeCli(handle.info()) }.toList()

private fun isFakeCli(info: ProcessHandle.Info): Boolean {
    val arguments: Array<String> = info.arguments().orElse(emptyArray())
    if (arguments.isNotEmpty()) return arguments.any { it.endsWith("fake-claude.py") }
    val name: String = info.command().orElse("").substringAfterLast('\\').substringAfterLast('/').lowercase()
    return name == "python.exe" || name == "python3.exe"
}

/**
 * The fake CLI processes a test started: those running that did not run yet when this was made. Another test's leftovers are not
 * this test's to judge. Make one per test (a property of the test class), before the test starts anything.
 */
class OwnFakeCliProcesses {
    private val earlier: Set<Long> = fakeCliProcesses().map { it.pid() }.toSet()

    fun list(): List<ProcessHandle> = fakeCliProcesses().filter { it.pid() !in earlier }
}
