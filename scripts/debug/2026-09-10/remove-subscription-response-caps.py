"""One-shot removal of transport-imposed response cutoffs; do not replay on modified source."""
from pathlib import Path
base=Path('runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client')
app=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app')
def edit(path, replacements):
 s=path.read_text()
 for old,new in replacements:
  assert old in s,(path,old)
  s=s.replace(old,new)
 path.write_text(s)
edit(base/'CliModelJobClient.kt', [('timeoutMs: Long = 120_000L','timeoutMs: Long = 0L'),('                        require(delivered.size + chunk.size <= 2048)\n','')])
edit(base/'CliModelJobAwaiter.kt', [('clock() - start <= timeoutMs','(timeoutMs <= 0L || clock() - start <= timeoutMs)')])
edit(app/'CodexSubscriptionModel.kt', [('.readTimeout(90, TimeUnit.SECONDS)','.readTimeout(0, TimeUnit.SECONDS)'),('.callTimeout(120, TimeUnit.SECONDS)','.callTimeout(0, TimeUnit.SECONDS)')])
edit(app/'SubscriptionNetworkForeground.kt', [('    private val timeout = Runnable { stop() }\n',''),('            handler.postDelayed(timeout, DEADLINE_MS)\n',''),('        handler.removeCallbacks(timeout)\n',''),('        const val DEADLINE_MS = 150_000L\n',''),('    private val handler = Handler(Looper.getMainLooper())\n',''),('import android.os.Handler\n',''),('import android.os.Looper\n','')])
edit(base/'CliPfdChannel.kt', [('limit: Int,','limit: Int? = null,'),('require(out.size() + count <= limit)','require(limit == null || out.size().toLong() + count <= limit)'),('require(bytes.size <= limit)','require(limit == null || bytes.size <= limit)')])
for path in [base/'CliModelJobWire.kt',base/'CliModelProgressClient.kt',base/'CliRequestPipe.kt',app/'CliRuntimeServiceBinder.kt']:
 s=path.read_text()
 for token in ['CliModelEventCodec.MAX_BYTES','CliModelRequestCodec.MAX_BYTES','com.helix.runtime.cli.client.CliModelEventCodec.MAX_BYTES']:
  s=s.replace(', '+token, '').replace('                            '+token+',\n','').replace('                    '+token+',\n','')
 s=s.replace('            require(payload.size <= CliModelRequestCodec.MAX_BYTES)\n','')
 path.write_text(s)
edit(base/'CliModelPayloadCodec.kt', [('require(events.isNotEmpty() && events.size <= MAX_EVENTS)','require(events.isNotEmpty())'),('require(rows.isNotEmpty() && rows.size <= MAX_EVENTS)','require(rows.isNotEmpty())'),('        require(bytes.size <= MAX_BYTES)\n',''),('require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)','require(bytes.isNotEmpty())'),('        require(bytes.size <= if (version == 3) MAX_BYTES else MAX_TEXT_BYTES) { "model request exceeds IPC limit" }\n',''),('        require(version == 3L || bytes.size <= MAX_TEXT_BYTES)\n','')])
edit(base/'CliModelProgressCodec.kt', [('        require(bytes.size <= CliModelEventCodec.MAX_BYTES)\n',''),('require(bytes.isNotEmpty() && bytes.size <= CliModelEventCodec.MAX_BYTES)','require(bytes.isNotEmpty())'),('        require(rows.size <= CliModelEventCodec.MAX_EVENTS)\n',''),('        require(events.size <= CliModelEventCodec.MAX_EVENTS)\n','')])
edit(app/'CodexJobProgress.kt', [('    private var bytes = 0\n',''),('        bytes = 0\n',''),('        val addedBytes = CliModelProgressCodec.encode(preview).size\n',''),('        require(events.size + preview.size <= CliModelEventCodec.MAX_EVENTS)\n',''),('        require(bytes + addedBytes <= CliModelEventCodec.MAX_BYTES)\n',''),('        bytes += addedBytes\n',''),('return events.drop(offset).take(32)','return events.subList(offset, minOf(events.size, offset + 32)).toList()')])
edit(app/'CodexPayloadFiles.kt', [(', CliModelRequestCodec.MAX_BYTES',''),(', CliModelEventCodec.MAX_BYTES',''),(' && it.size <= CliModelRequestCodec.MAX_BYTES',''),(' && it.size <= CliModelEventCodec.MAX_BYTES',''),('        limit: Int,\n',''),(' && bytes.size <= limit','')])
edit(app/'CodexPayloadJob.kt', [('incomingBytes in 1..CliModelRequestCodec.MAX_BYTES &&\n            records.canAcceptNew() &&\n            payloads.payloadBytes() + incomingBytes <= MAX_PAYLOAD_BYTES','incomingBytes > 0 && records.canAcceptNew()'),('require(offset in 0..CodexSubscriptionModel.MAX_EVENTS)','require(offset >= 0)')])
edit(Path('app/src/developer/kotlin/com/helix/app/provider/SubscriptionResultStore.kt'), [(' && artifact.size <= CliModelEventCodec.MAX_BYTES','')])
s=(app/'SubscriptionModelStream.kt').read_text(); start=s.index('    var total = 0L'); s=s[:start]+'''    while (events.none { it is ModelEvent.Completed || it is ModelEvent.Refusal || it is ModelEvent.Error }) {
        val count = subscriptionNetwork { response.body.source().read(buffer, 16 * 1024L) }
        if (count < 0) break
        val chunk = decoder.feed(buffer.readByteArray())
        events += chunk
        onEvents(chunk)
    }
    events += decoder.finish()
    return events
}
'''; (app/'SubscriptionModelStream.kt').write_text(s)
