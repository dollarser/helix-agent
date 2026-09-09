from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text().replace('manual != null && (scopeId == workspaceScopeId || manual.canWrite(scopeId))','manual != null');s=s.replace('private fun manualResult(destination: String, operation: () -> Unit)', 'private fun manualResult(destination: String, operation: () -> Boolean)');s=s.replace('            operation()\n            FileOpResult.Ok(destination, false)','            FileOpResult.Ok(destination, operation())');s=s.replace('manual.mkdir(scopeId, parentRel, name) }','manual.mkdir(scopeId, parentRel, name); false }').replace('manual.delete(scopeId, relativePath) }', 'manual.delete(scopeId, relativePath); false }');s=s.replace('    // --- Trash (', '    private val directoryTrash = ManualWorkspaceTrash(roots, workspaceScopeId)\n\n    // --- Trash (');s=s.replace('        val fsp = FileScopePath(scopeId, relativePath)\n        return try {\n            store.moveToTrash(fsp)','''        val fsp = FileScopePath(scopeId, relativePath)
        return try {
            if (scopeId == workspaceScopeId && directoryTrash.isDirectory(relativePath)) {
                directoryTrash.trash(relativePath)
                return FileOpResult.Ok(relativePath, false)
            }
            store.moveToTrash(fsp)''');s=s.replace('            val out = store.restoreFromTrash(ref)','''            if (scopeId == workspaceScopeId && directoryTrash.isDirectory(ref.relativePath)) {
                val original = requireNotNull(decodeTrashEntryName(entryName))
                directoryTrash.restore(entryName, original)
                return FileOpResult.Ok(original, false)
            }
            val out = store.restoreFromTrash(ref)''');s=s.replace('            val out = store.purgeTrashEntry(ref)','''            if (scopeId == workspaceScopeId && directoryTrash.isDirectory(ref.relativePath)) {
                requireNotNull(decodeTrashEntryName(entryName))
                directoryTrash.purge(entryName)
                return FileOpResult.Ok(ref.relativePath, false)
            }
            val out = store.purgeTrashEntry(ref)''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/ManualFileOperations.kt');s=p.read_text().replace('        require(name.isNotBlank()', '        check(canWrite(scope)) { "Storage is read-only or permission was revoked" }\n        require(name.isNotBlank()',1).replace('    ) {\n        val source = FileScopePath', '    ): Boolean {\n        check(canWrite(targetScope) && (!move || canWrite(sourceScope))) { "Storage is read-only or permission was revoked" }\n        val source = FileScopePath',1).replace('            if (backedUp) deleteTree(to, backup, { false }, 0)','            if (backedUp) deleteTree(to, backup, { false }, 0)\n            return existed').replace('        require(!FileScopePath(scope, path).isRoot)', '        check(canWrite(scope)) { "Storage is read-only or permission was revoked" }\n        require(!FileScopePath(scope, path).isRoot)');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesScreenState.kt');s=p.read_text().replace('    var trashOpen', '    var permanentDelete: List<String>? by mutableStateOf(null)\n    var trashOpen',1);p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesScreenActions.kt');s=p.read_text().replace('    fun startTrash(sourceRels: List<String>) {','''    fun requestDelete(sourceRels: List<String>) {
        if (state.currentSource.kind == com.helix.app.files.FileSourceKind.WORKSPACE) startTrash(sourceRels)
        else state.permanentDelete = sourceRels
    }

    fun startTrash(sourceRels: List<String>) {''');p.write_text(s)
for f in ['FilesPreviewDialog.kt','FilesScreenLayout.kt']:
 p=Path('app/src/main/kotlin/com/helix/app/ui')/f;s=p.read_text().replace('startTrash(', 'requestDelete(');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesScreenLayout.kt');s=p.read_text().replace('                        val selectedRels = selected.toList()','''                        val selectedRels = selected.toList()
                        if (selectedRels.size == 1) {
                            TextButton(onClick = { renameTarget = entries.firstOrNull { it.relativePath == selectedRels.single() } },
                                modifier = Modifier.testTag("files-batch-rename")) { Text(str(R.string.files_rename)) }
                        }''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesLocationBar.kt');s=p.read_text().replace('                    TextButton(\n                        {\n                            choose {\n                                state.trashOpen', '                    if (state.currentSource.kind == com.helix.app.files.FileSourceKind.WORKSPACE) TextButton(\n                        {\n                            choose {\n                                state.trashOpen');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesMutationDialogs.kt');s=p.read_text();pos=s.index('            renameTarget?.let');s=s[:pos]+'''            permanentDelete?.let { paths ->
                AlertDialog(
                    onDismissRequest = { permanentDelete = null },
                    title = { Text(str(R.string.files_permanent_delete_title)) },
                    text = { Text(str(R.string.files_permanent_delete_message, paths.size)) },
                    confirmButton = { TextButton(onClick = { permanentDelete = null; startTrash(paths) },
                        modifier = Modifier.testTag("files-permanent-delete-confirm")) { Text(str(R.string.files_delete)) } },
                    dismissButton = { TextButton(onClick = { permanentDelete = null }) { Text(str(R.string.common_cancel)) } },
                )
            }

'''+s[pos:];p.write_text(s)
for folder,title,message in [('values','Permanently delete?','Delete %1$d selected items and their contents? Shared storage and SAF do not use the Workspace recycle bin.'),('values-zh-rCN','永久删除？','删除所选 %1$d 项及其内容？共享存储和 SAF 不使用 Workspace 回收站。')]:
 p=Path('app/src/main/res')/folder/'strings.xml';s=p.read_text().replace('</resources>',f'    <string name="files_permanent_delete_title">{title}</string>\n    <string name="files_permanent_delete_message">{message}</string>\n</resources>');p.write_text(s)
