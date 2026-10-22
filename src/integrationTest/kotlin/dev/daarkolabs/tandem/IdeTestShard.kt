package dev.daarkolabs.tandem

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Splits a class's IDE tests across CI jobs, each of which starts its own IDEs. With `-Dtandem.ideTestShard=<index>/<total>`
 * (the index from 0, as GitHub's `strategy.job-index`), a test runs only when its position among the class's `@Test`
 * methods, sorted by name, is `index` modulo `total`: every test runs in exactly one shard, and a new test joins one with no
 * list to keep up. Without the property, or with it empty, every test runs. A malformed value, or an index outside
 * `0 until total`, fails the tests instead of skipping them.
 */
class IdeTestShard : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val value = System.getProperty(PROPERTY).orEmpty()
        if (value.isEmpty()) return ConditionEvaluationResult.enabled("no $PROPERTY: every IDE test runs")
        val (index, total) = parse(value)
        val method = context.testMethod.orElse(null) ?: return ConditionEvaluationResult.enabled("shard $value")
        val testClass = context.requiredTestClass
        val names = testClass.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.sorted()
        val position = names.indexOf(method.name)
        check(position >= 0) { "${method.name} is not a @Test method declared in ${testClass.name}" }
        val shard = position % total
        return if (shard == index) {
            ConditionEvaluationResult.enabled("shard $value runs test ${position + 1} of ${names.size}")
        } else {
            ConditionEvaluationResult.disabled("test ${position + 1} of ${names.size} runs in shard $shard/$total, not $value")
        }
    }

    companion object {
        const val PROPERTY: String = "tandem.ideTestShard"

        /** `<index>/<total>` as (index, total); throws for anything else, so a typo never skips tests silently. */
        fun parse(value: String): Pair<Int, Int> {
            val match = requireNotNull(Regex("""(\d{1,4})/(\d{1,4})""").matchEntire(value)) {
                "$PROPERTY must be <index>/<total>, such as 0/3, not '$value'"
            }
            val index = match.groupValues[1].toInt()
            val total = match.groupValues[2].toInt()
            require(total >= 1 && index in 0 until total) { "$PROPERTY=$value: the index must be at least 0 and below the total" }
            return index to total
        }
    }
}
