#!/usr/bin/env python3
"""Resolve extraction visibility and separate irreversible privacy erasure from restorable trash."""
from pathlib import Path
R=Path(__file__).resolve().parents[3]
p=R/'app/src/main/kotlin/com/helix/app/files/FileManagerService.kt';p.write_text(p.read_text().replace('private fun moveOrCopy(', 'internal fun moveOrCopy('))
p=R/'runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsTransportPreparation.kt';p.write_text(p.read_text().replace('PATH_UNSAFE_CHARS','pathUnsafeChars'))
p=R/'core/workspace/src/main/kotlin/com/helix/core/workspace/WorkspaceTrashOperations.kt';s=p.read_text();a=s.index('    /**\n     * Irreversible');b=s.index('    private fun uniqueTrashEntryName',a);body=s[a:b]
h=s[:s.index('internal class')]
t=p.with_name('WorkspacePrivacyOperations.kt');assert not t.exists();t.write_text(h+'''internal class WorkspacePrivacyOperations(
    private val resolve: (String) -> Path,
    private val resolveContained: (FileScopePath, Path) -> Path,
    private val ensureLayout: (String) -> Unit,
) {
'''+body+'}\n')
s=s[:a]+s[b:];s=s.replace('    private val ensureLayout: (String) -> Unit,\n','');p.write_text(s)
p=p.with_name('WorkspaceArtifactStore.kt');s=p.read_text().replace('WorkspaceTrashOperations(::resolve, ::resolveContained, ::ensureLayout)','WorkspaceTrashOperations(::resolve, ::resolveContained)').replace('trashOperations.deletePermanentlyForPrivacy','privacyOperations.deletePermanentlyForPrivacy').replace('trashOperations.clearForPrivacy','privacyOperations.clearForPrivacy');s=s.replace('    private val trashOperations','    private val privacyOperations = WorkspacePrivacyOperations(::resolve, ::resolveContained, ::ensureLayout)\n\n    private val trashOperations');p.write_text(s)
p=R/'build/debug/2026-09-10/hxa184/organized-paths.txt';p.write_text(p.read_text()+'core/workspace/src/main/kotlin/com/helix/core/workspace/WorkspacePrivacyOperations.kt\n')
