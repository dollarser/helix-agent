package com.helix.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HXA-209 A-slice: the ADR-PERMISSIONS-001 §3 limited `rm -rf dir` command rule.
 *
 * The ADR mandates tests for path quoting, whitespace and multiple targets, and
 * forbids misjudging echo/comment/string text as execution. The match set is the
 * contract's: exactly the `-rf` token with a resolved `rm` command word and at
 * least one non-empty target; option variants, wrappers and other-language
 * equivalents are NOT matched (they fall to the mode's regular unknown-effect
 * rules), so those cases assert NotApplicable. Parenthesized subshells and
 * command substitutions ARE checked because their commands do execute.
 */
class RmCommandRuleTest {
    private val notApplicable = RmCommandRule.Verdict.NotApplicable

    private fun matchedArgvTargets(argv: List<String>): List<String>? =
        (RmCommandRule.checkArgv(argv) as? RmCommandRule.Verdict.RmRfDir)?.targets

    private fun matchedScriptTargets(script: String): List<String>? =
        (RmCommandRule.checkScript(script) as? RmCommandRule.Verdict.RmRfDir)?.targets

    private fun assertArgvMatch(
        argv: List<String>,
        vararg expected: String,
    ) {
        assertEquals(expected.toList(), matchedArgvTargets(argv))
    }

    private fun assertArgvNoMatch(argv: List<String>) {
        assertEquals(notApplicable, RmCommandRule.checkArgv(argv))
    }

    private fun assertScriptMatch(
        script: String,
        vararg expected: String,
    ) {
        assertEquals(expected.toList(), matchedScriptTargets(script))
    }

    private fun assertScriptNoMatch(script: String) {
        assertEquals(notApplicable, RmCommandRule.checkScript(script))
    }

    // --- argv form ---------------------------------------------------------

    @Test
    fun `argv explicit rm -rf with a target matches`() {
        assertArgvMatch(listOf("rm", "-rf", "/tmp/x"), "/tmp/x")
    }

    @Test
    fun `argv path prefixed command word resolves to rm`() {
        assertArgvMatch(listOf("/bin/rm", "-rf", "x"), "x")
    }

    @Test
    fun `argv multiple targets are all reported`() {
        assertArgvMatch(listOf("rm", "-rf", "a", "b", "c"), "a", "b", "c")
    }

    @Test
    fun `argv flags and targets may interleave`() {
        assertArgvMatch(listOf("rm", "-v", "-rf", "x"), "x")
    }

    @Test
    fun `argv a target after the option terminator is a target`() {
        assertArgvMatch(listOf("rm", "-rf", "--", "weird"), "weird")
    }

    @Test
    fun `argv -rf alone without a target does not match`() {
        assertArgvNoMatch(listOf("rm", "-rf"))
    }

    @Test
    fun `argv -rf after the option terminator is a target not a flag`() {
        assertArgvNoMatch(listOf("rm", "--", "-rf"))
    }

    @Test
    fun `argv option variant -fr is out of the contract match set`() {
        assertArgvNoMatch(listOf("rm", "-fr", "x"))
    }

    @Test
    fun `argv split options -r and -f are out of the contract match set`() {
        assertArgvNoMatch(listOf("rm", "-r", "-f", "x"))
    }

    @Test
    fun `argv combined variant -rfv is out of the contract match set`() {
        assertArgvNoMatch(listOf("rm", "-rfv", "x"))
    }

    @Test
    fun `argv long options are out of the contract match set`() {
        assertArgvNoMatch(listOf("rm", "--recursive", "--force", "x"))
    }

    @Test
    fun `argv a redirection is not a target`() {
        assertArgvNoMatch(listOf("rm", "-rf", "2>/dev/null"))
    }

    @Test
    fun `argv a trailing redirection does not mask the real target`() {
        assertArgvMatch(listOf("rm", "-rf", "x", "2>&1"), "x")
    }

    @Test
    fun `argv an empty target list and a non rm command do not match`() {
        assertArgvNoMatch(emptyList())
        assertArgvNoMatch(listOf("ls", "-l"))
        assertArgvNoMatch(listOf("rm", "-f", "x"))
    }

    // --- script form: match positions --------------------------------------

    @Test
    fun `script explicit rm -rf matches`() {
        assertScriptMatch("rm -rf /tmp/x", "/tmp/x")
    }

    @Test
    fun `script a quoted path with whitespace is one target`() {
        assertScriptMatch("rm -rf \"/tmp/my dir\"", "/tmp/my dir")
    }

    @Test
    fun `script a single quoted target is unquoted in the result`() {
        assertScriptMatch("rm -rf '/tmp/x'", "/tmp/x")
    }

    @Test
    fun `script extra whitespace between tokens does not hide the match`() {
        assertScriptMatch("rm  -rf   /tmp/x", "/tmp/x")
    }

    @Test
    fun `script multiple targets are all reported`() {
        assertScriptMatch("rm -rf a b c", "a", "b", "c")
    }

    @Test
    fun `script an escaped whitespace stays inside one target`() {
        assertScriptMatch("rm -rf /tmp/my\\ dir", "/tmp/my dir")
    }

    @Test
    fun `script a line continuation joins the target onto the same command`() {
        assertScriptMatch("rm -rf \\\n/tmp/x", "/tmp/x")
    }

    @Test
    fun `script a target that may or may not be a directory still asks`() {
        assertScriptMatch("rm -rf maybeadir", "maybeadir")
    }

    @Test
    fun `script a variable target is a non-empty operand without expansion`() {
        assertScriptMatch("rm -rf \$dir", "\$dir")
        assertScriptMatch("rm -rf \"\$HOME/x\"", "\$HOME/x")
    }

    @Test
    fun `script a leading assignment does not hide the resolved command`() {
        assertScriptMatch("FOO=1 rm -rf x", "x")
    }

    @Test
    fun `script a quoted command name still resolves to rm`() {
        assertScriptMatch("\"rm\" -rf x", "x")
        assertScriptMatch("'/bin/rm' -rf x", "x")
    }

    @Test
    fun `script compound commands separated by semicolon or operator chains check each segment`() {
        assertScriptMatch("cd /tmp; rm -rf build", "build")
        assertScriptMatch("ls && rm -rf x", "x")
        assertScriptMatch("false || rm -rf y", "y")
    }

    @Test
    fun `script pipeline and background segments are checked`() {
        assertScriptMatch("cat f | rm -rf z", "z")
        assertScriptMatch("rm -rf a & rm -rf b", "a")
    }

    @Test
    fun `script keywords before the command word are skipped`() {
        assertScriptMatch("if [ -d x ]; then rm -rf x; fi", "x")
        assertScriptMatch("while rm -rf x; do :; done", "x")
    }

    @Test
    fun `script subshell segments are clearly parseable and checked`() {
        assertScriptMatch("(rm -rf x)", "x")
        assertScriptMatch("cd /tmp && (rm -rf a; rm -rf b)", "a")
    }

    @Test
    fun `script the first matching segment wins but later segments are also scanned`() {
        assertScriptMatch("echo ok; rm -rf x", "x")
    }

    @Test
    fun `script an inline comment after the command does not affect the match`() {
        assertScriptMatch("rm -rf x # remove x", "x")
    }

    @Test
    fun `script carriage returns are normalized`() {
        assertScriptMatch("rm -rf x\r\n", "x")
    }

    // --- script form: the contract's required non-matches -------------------

    @Test
    fun `script a full line comment is never a match`() {
        assertScriptNoMatch("# rm -rf /tmp")
    }

    @Test
    fun `script an inline comment is never a match`() {
        assertScriptNoMatch("ls # rm -rf /tmp")
    }

    @Test
    fun `script echo with a quoted string is never a match`() {
        assertScriptNoMatch("echo \"rm -rf /tmp/x\"")
        assertScriptNoMatch("echo 'rm -rf /tmp/x'")
    }

    @Test
    fun `script echo with an unquoted rm is an echo operand not a command`() {
        assertScriptNoMatch("echo rm -rf /tmp/x")
    }

    @Test
    fun `script printf format text is never a match`() {
        assertScriptNoMatch("printf 'rm -rf %s\\n' x")
    }

    @Test
    fun `script a heredoc body is string data never a match`() {
        assertScriptNoMatch("cat <<EOF\nrm -rf /tmp\nEOF\n")
    }

    @Test
    fun `script a quoted heredoc delimiter body is never a match`() {
        assertScriptNoMatch("cat <<'EOF'\nrm -rf x\nEOF\n")
    }

    @Test
    fun `script a heredoc body does not shadow a real command after it`() {
        assertScriptMatch("cat <<EOF\nrm -rf shadowed\nEOF\nrm -rf real", "real")
    }

    @Test
    fun `script a command substitution executes so its rm is checked`() {
        assertScriptMatch("\$(rm -rf x)", "x")
        assertScriptMatch("echo \$(rm -rf x)", "x")
    }

    @Test
    fun `script a wrapper command is not the rm rule's contract scope`() {
        assertScriptNoMatch("sudo rm -rf x")
    }

    @Test
    fun `script option variants fall to the mode's regular unknown effect rules`() {
        assertScriptNoMatch("rm -fr x")
        assertScriptNoMatch("rm -r -f x")
        assertScriptNoMatch("rm -rfv x")
        assertScriptNoMatch("rm --recursive --force x")
    }

    @Test
    fun `script rm -rf without a target on that line does not match`() {
        assertScriptNoMatch("rm -rf\nx")
        assertScriptNoMatch("rm -rf 2>/dev/null")
        assertScriptNoMatch("")
        assertScriptNoMatch("   \n  \n")
    }

    @Test
    fun `script a bare redirection operator consumes its file operand too`() {
        assertScriptNoMatch("rm -rf > /x")
        assertScriptMatch("rm -rf x 2>&1", "x")
    }
}
