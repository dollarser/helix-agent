"""Retain current smoke failure for UI without persisting throwable or credentials."""
from pathlib import Path
root=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app')
p=root/'CodexModelJob.kt';s=p.read_text().replace('private var activeJobId: String? = null','private var activeJobId: String? = null\n    private var lastFailure: Pair<String, Throwable>? = null')
s=s.replace('    fun cancel(jobId: String):','    fun failure(jobId: String): Throwable? = synchronized(lock) { lastFailure?.takeIf { it.first == jobId }?.second }\n\n    fun cancel(jobId: String):')
s=s.replace('                terminal(live, CodexModelJobState.FAILED)','                lastFailure = pending.jobId to requireNotNull(result.exceptionOrNull())\n                terminal(live, CodexModelJobState.FAILED)');p.write_text(s)
p=root/'CodexSmokeJobProbe.kt';s=p.read_text().replace('?.let { return it }','?.let {\n                if (it.state == CodexModelJobState.FAILED) runner.failure(jobId)?.let { failure -> throw failure }\n                return it\n            }');p.write_text(s)
p=root/'CodexModelCatalog.kt';s=p.read_text().replace('} catch (_: IOException) {','} catch (failure: IOException) {\n            android.util.Log.w("HelixSubscriptionIo", "phase=catalog reason=${failure.javaClass.simpleName}")')
s=s.replace('            } else {\n                CliModelCatalog.Failed(','            } else {\n                android.util.Log.w("HelixSubscriptionIo", "phase=catalog httpStatus=${response.code}")\n                CliModelCatalog.Failed(');p.write_text(s)
