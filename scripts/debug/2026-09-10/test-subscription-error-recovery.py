"""Add focused regressions and run scoped checks; no unrelated emulator work."""
from pathlib import Path
import subprocess
p=Path('runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexSmokeStreamTest.kt');s=p.read_text();insert='''
    @Test fun completionDoesNotReadAgainEvenIfSocketWouldFail() {
        val body = Buffer().writeUtf8(delta + completed)
        val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (body.size == 0L) throw java.io.IOException("socket closed after completed response")
                return body.read(sink, minOf(byteCount, 1L))
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }
        assertEquals("HELIX_OK", CodexSmokeStream.read(okio.buffer(source)))
    }
'''.replace('okio.buffer(source)','source.buffer()')
s=s.replace('import okio.Buffer','import okio.Buffer\nimport okio.buffer').replace('    private fun failure(body: String)',insert+'\n    private fun failure(body: String)');p.write_text(s)
p=Path('runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexModelJobTest.kt');s=p.read_text();insert='''
    @Test fun probePreservesNetworkFailureWithoutWritingItToJournal() {
        val root = Files.createTempDirectory("codex-job-network").toFile()
        val failure = CodexSmokeNetworkException("models", java.net.UnknownHostException("private detail"))
        CodexModelJobRunner(CodexModelJobStore(root), { throw failure }, {}).use { runner ->
            val actual = org.junit.Assert.assertThrows(CodexSmokeNetworkException::class.java) {
                CodexSmokeJobProbe.run(runner, "job_000000000009", hash)
            }
            org.junit.Assert.assertSame(failure, actual)
            org.junit.Assert.assertNull(runner.failure("job_000000000010"))
        }
        assertTrue(root.walkTopDown().filter { it.isFile }.none { it.readText().contains("private detail") })
    }
''';s=s.replace('    private fun await(',insert+'\n    private fun await(');p.write_text(s)
paths=[str(p) for p in Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app').glob('Codex*.kt') if p.name in ['CodexModelJob.kt','CodexSmokeJobProbe.kt','CodexModelCatalog.kt','CodexSubscriptionSmoke.kt','CodexSmokeStream.kt','CodexLoginFailure.kt']]+['runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexSmokeStreamTest.kt','runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexModelJobTest.kt']
out=Path('build/debug/2026-09-10/subscription-error-recovery');out.mkdir(parents=True,exist_ok=True)
cmds=[['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(str(Path(p).resolve()) for p in paths)],['./gradlew',':runtime:cli-app:testDebugUnitTest','--tests','*CodexSmoke*','--tests','*CodexModelJobTest',':runtime:cli-app:assembleDebug',':app:assembleDeveloperDebug',':app:assembleDeveloperDebugAndroidTest']]
for i,cmd in enumerate(cmds):
 with (out/f'{i}.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
 print(i,r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
