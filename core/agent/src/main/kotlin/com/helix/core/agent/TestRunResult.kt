package com.helix.core.agent

/**
 * A structured reading of a test/build command's output (P1, research doc section 44
 * "structured test result"). The agent runs the test command through an exec tool (bash /
 * code.linux.run); [parse] recovers the aggregate result from the raw log so the model (and the
 * tool timeline) see a clean "N passed, M failed" summary instead of re-reading the whole log.
 *
 * Pure + fail-open: [parse] returns null when no recognized aggregate line is present, so a
 * non-test command (or an unrecognized runner) is left untouched. Only a small set of common,
 * high-signal aggregate lines is recognized (Maven Surefire, Gradle, Jest, and a conservative
 * generic form) — a miss means "no structured result", never a wrong one. When several aggregate
 * lines appear (a multi-module build), the LAST is the total and wins.
 */
data class TestRunResult(
    val status: Status,
    val total: Int?,
    val passed: Int?,
    val failed: Int?,
    val errors: Int?,
) {
    enum class Status {
        PASSED,
        FAILED,
    }

    /** The concise, bounded line appended to the model-visible tool summary. */
    val line: String
        get() {
            val base = if (status == Status.PASSED) "Test run: PASSED" else "Test run: FAILED"
            val counts =
                listOfNotNull(
                    total?.let { "total=$it" },
                    passed?.let { "passed=$it" },
                    failed?.let { "failed=$it" },
                    errors?.let { "errors=$it" },
                ).joinToString(", ")
            return if (counts.isEmpty()) base else "$base ($counts)"
        }

    companion object {
        private val MAVEN = Regex("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)(?:, Skipped: (\\d+))?")
        private val GRADLE = Regex("(\\d+) tests completed(?:, (\\d+) failed)?")
        private val JEST = Regex("Tests:\\s+(\\d+) failed, (\\d+) passed, (\\d+) total")
        private val GENERIC = Regex("(\\d+) passed, (\\d+) failed")

        /**
         * Reads the aggregate test result out of [output], or null when no recognized line is
         * present. The LAST matching aggregate line wins (the multi-module total).
         */
        fun parse(output: String): TestRunResult? = maven(output) ?: gradle(output) ?: jest(output) ?: generic(output)

        private fun maven(output: String): TestRunResult? =
            last(MAVEN, output)?.let { m ->
                val (total, failed, errors) = triple(m)
                TestRunResult(failStatus(failed, errors), total, passedOf(total, failed, errors), failed, errors)
            }

        private fun gradle(output: String): TestRunResult? =
            last(GRADLE, output)?.let { m ->
                val total = m.groupValues[1].toInt()
                val failed = m.groupValues[2].toIntOrNull() ?: 0
                TestRunResult(failStatus(failed, 0), total, total - failed, failed, 0)
            }

        private fun jest(output: String): TestRunResult? =
            last(JEST, output)?.let { m ->
                val (failed, passed, total) = triple(m)
                TestRunResult(failStatus(failed, 0), total, passed, failed, 0)
            }

        // The conservative generic form must sit on a line that also mentions "test", so an
        // unrelated "X passed, Y failed" in other tool output is not mistaken for a test run.
        private fun generic(output: String): TestRunResult? =
            output
                .lineSequence()
                .filter { it.contains("test", ignoreCase = true) }
                .mapNotNull { line -> GENERIC.find(line) }
                .lastOrNull()
                ?.let { m ->
                    val passed = m.groupValues[1].toInt()
                    val failed = m.groupValues[2].toInt()
                    TestRunResult(failStatus(failed, 0), passed + failed, passed, failed, 0)
                }

        private fun last(
            regex: Regex,
            output: String,
        ): MatchResult? = regex.findAll(output).lastOrNull()

        private fun triple(m: MatchResult): Triple<Int, Int, Int> =
            Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())

        private fun failStatus(
            failed: Int,
            errors: Int,
        ): Status = if (failed > 0 || errors > 0) Status.FAILED else Status.PASSED

        private fun passedOf(
            total: Int,
            failed: Int,
            errors: Int,
        ): Int = (total - failed - errors).coerceAtLeast(0)
    }
}
