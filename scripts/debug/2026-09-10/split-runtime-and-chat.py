#!/usr/bin/env python3
"""HXA-183 one-shot follow-up extraction; preserve lifecycle owners and public seams."""
from pathlib import Path
import textwrap
import re
ROOT=Path(__file__).resolve().parents[3]
def patch(rel, old, new):
    p=ROOT/rel; s=p.read_text(); assert old in s, (rel,old[:70]); p.write_text(s.replace(old,new))
ui='app/src/main/kotlin/com/helix/app/ui/'
patch(ui+'ProviderRow.kt','/**\n * The parse failure is INTENTIONALLY converted to null: the form only needs a\n','')
patch(ui+'ProviderFormDialog.kt',' * cleartext hint','/**\n * The parse failure is INTENTIONALLY converted to null: the form only needs a\n * cleartext hint')
files='app/src/main/kotlin/com/helix/app/files/'
patch(files+'FileManagerImports.kt','     * Imports ONE','    /**\n     * Imports ONE')
patch(files+'FileManagerImports.kt','    /**\n    /** Reclaims','    /** Reclaims')
patch(files+'FileManagerExports.kt','     * `input/`','    /**\n     * Exports ONE workspace file to [target]. The source must be inside\n     * `input/`')

# The nested public constructor remains a compatibility facade; execution owns its dependencies.
p=ROOT/'app/src/developer/kotlin/com/helix/app/proot/LinuxRunTool.kt'; s=p.read_text()
start=s.index('    /**\n     * The production [LinuxExecutor]'); end=s.index('    @Suppress("SwallowedException", "TooGenericExceptionCaught")\n    private fun sha256Hex',start)
body=textwrap.dedent(s[start:end]).replace('class ProductionLinuxExecutor(', 'internal class LinuxJobExecution(')
header=s[:s.index('/**')]
imports=''.join('import com.helix.app.proot.LinuxRunTool.'+n+'\n' for n in ['LinuxExecutor','ParsedLinuxCall','MAX_IMPORT_BYTES','failed','sha256Hex'])
(p.parent/'LinuxJobExecution.kt').write_text(header+imports+'\n'+body)
wrapper='''    /** Compatibility constructor; all job execution is owned by [LinuxJobExecution]. */
    class ProductionLinuxExecutor(
        client: ProotJobClient,
        gate: () -> LinuxRuntimeGate,
        store: WorkspaceArtifactStore,
        scratchRoot: File,
        jobIdProvider: () -> String,
        knownSecretValues: () -> Set<String>,
        beforeSubmit: (ParsedLinuxCall, ProotJobSpec) -> Unit,
        persistVerifiedResult: (ParsedLinuxCall, ProotJobRecord, File) -> Unit,
    ) : LinuxExecutor by LinuxJobExecution(
        client, gate, store, scratchRoot, jobIdProvider, knownSecretValues,
        beforeSubmit, persistVerifiedResult,
    )

'''
s=s[:start]+wrapper+s[end:]
s=s.replace('private const val MAX_IMPORT_BYTES','internal const val MAX_IMPORT_BYTES').replace('private fun failed(', 'internal fun failed(').replace('private fun sha256Hex(', 'internal fun sha256Hex(')
p.write_text(s)

# Archive/capture implementation has no terminal-state or cancellation ownership.
p=ROOT/'runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt'; s=p.read_text(); header=s[:s.index('/**')]
start=s.index('private interface CaptureBudget'); end=s.index('private fun sha256Of',start)
body=s[start:end].replace('private interface','internal interface').replace('private class','internal class')
(p.parent/'ProotOutputCapture.kt').write_text(header+body)
s=s[:start]+s[end:]
start=s.index('    /**\n     * Writes the output archive'); end=s.index('    // ---',start)
body=textwrap.dedent(s[start:end]).replace('private fun buildOutputArchive','internal fun buildOutputArchive')
(p.parent/'ProotOutputArchive.kt').write_text(header+body)
s=s[:start]+s[end:]
# File hashing is shared with archive construction, while byte hashing remains local.
s=s.replace('private fun sha256OfFile(', 'internal fun sha256OfFile(')
s=s.replace(' * The runner IS the job lifecycle (submit, launch, capture, watchdog, terminal,\n * journal, sweep) in one process-wide object; splitting it only moves coupling.', ' * The runner owns lifecycle, watchdog and terminal publication in one process-wide\n * object. Bounded capture and archive encoding are independent helpers.')
p.write_text(s)

# Attachment retry/materialization reads are independent of admission and UI state.
p=ROOT/'app/src/main/kotlin/com/helix/app/chat/ChatService.kt'; s=p.read_text(); header=s[:s.index('/**')]
start=s.index('    private sealed interface RetryStagedCheck'); end=s.index('    // ---',start)
body=s[start:end].replace('private sealed interface RetryStagedCheck','sealed interface RetryStagedCheck').replace('private suspend fun retryStagedFor','suspend fun retryStagedFor')
(p.parent/'ChatAttachmentRetry.kt').write_text(header+'''/** Resolves immutable attachment bindings for retry; never changes admission or pending UI state. */
internal class ChatAttachmentRetry(
    private val storage: HelixStorage,
    private val attachmentStaging: AttachmentStagingSupport,
) {
'''+body+'}\n')
s=s[:start]+s[end:]
# Remove the old detached KDoc, which is now documented on the helper.
doc=s.rfind('    /**',0,start)
if 'RetryStagedCheck' in s[doc:start]: s=s[:doc]+s[start:]
s=s.replace('retryStagedFor(session.id, targetId)', 'attachmentRetry.retryStagedFor(session.id, targetId)')
s=re.sub(r'(?<![.\w])retryStagedFor\(', 'attachmentRetry.retryStagedFor(', s)
s=s.replace('import android.', 'import com.helix.app.chat.ChatAttachmentRetry.RetryStagedCheck\nimport android.',1)
s=s.replace('    private val labels =', '    private val attachmentRetry = ChatAttachmentRetry(storage, attachmentStaging)\n    private val labels =')
p.write_text(s)
