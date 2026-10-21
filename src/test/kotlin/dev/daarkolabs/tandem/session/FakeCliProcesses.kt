package dev.daarkolabs.tandem.session

/** Live processes running the fake CLI (`src/test/resources/fake-claude.py`), children of this test JVM. */
fun fakeCliProcesses(): List<ProcessHandle> =
    ProcessHandle.current().descendants().filter { handle ->
        handle.isAlive && handle.info().arguments().orElse(emptyArray()).any { it.endsWith("fake-claude.py") }
    }.toList()

/**
 * The fake CLI processes a test started: those running that did not run yet when this was made. Another test's leftovers are not
 * this test's to judge. Make one per test (a property of the test class), before the test starts anything.
 */
class OwnFakeCliProcesses {
    private val earlier: Set<Long> = fakeCliProcesses().map { it.pid() }.toSet()

    fun list(): List<ProcessHandle> = fakeCliProcesses().filter { it.pid() !in earlier }
}
