from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/AppFileServices.kt'); s=p.read_text(); s=s.replace('            sharedStorageGranted = sharedStorage::isGranted,','''            sharedStorageGranted = sharedStorage::isGranted,
            manual = com.helix.app.files.ManualFileOperations(
                backend = { id ->
                    if (id.startsWith(SafGrantStore.SCOPE_ID_PREFIX)) {
                        com.helix.app.files.SafManualFileBackend(context.contentResolver, safGrantStore, safTree, id)
                    } else {
                        com.helix.app.files.NioManualFileBackend(id, manualRoots, id == appScopeId) {
                            if (id == com.helix.app.files.SharedStorageAccess.SCOPE_ID) {
                                check(sharedStorage.isWritable()) { "Shared storage write permission is required" }
                            }
                        }
                    }
                },
                writable = { id ->
                    when {
                        id == appScopeId -> true
                        id == com.helix.app.files.SharedStorageAccess.SCOPE_ID -> sharedStorage.isWritable()
                        id.startsWith(SafGrantStore.SCOPE_ID_PREFIX) -> runCatching {
                            safTree.resolve(id, com.helix.feature.files.SafAccessMode.WRITE)
                        }.isSuccess
                        else -> false
                    }
                },
            ),'''); p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/SharedStorageAccess.kt'); s=p.read_text().replace('    @Suppress("DEPRECATION")','''    fun isWritable(): Boolean = isGranted() && (Build.VERSION.SDK_INT >= 30 ||
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)

    @Suppress("DEPRECATION")''');p.write_text(s)
p=Path('app/src/main/AndroidManifest.xml');s=p.read_text().replace('    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="29" />','    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="29" />\n    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/SharedStorageNavigation.kt'); s=p.read_text().replace('ActivityResultContracts.RequestPermission()', 'ActivityResultContracts.RequestMultiplePermissions()').replace('if (access.isGranted()) {\n            enter()', 'if (access.isWritable()) {\n            enter()').replace('legacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE)', 'legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text().replace('    private val sharedStorageGranted: () -> Boolean = { false },','    private val sharedStorageGranted: () -> Boolean = { false },\n    private val manual: ManualFileOperations? = null,');s=s.replace('FileSourceKind.ALL_FILES, false)', 'FileSourceKind.ALL_FILES, manual?.canWrite(SharedStorageAccess.SCOPE_ID) == true)');s=s.replace('FileSource(it.scopeId, it.displayName, FileSourceKind.SAF, supportsMutation = false)','FileSource(it.scopeId, it.displayName, FileSourceKind.SAF, supportsMutation = manual?.canWrite(it.scopeId) == true)');s=s.replace('        move: Boolean,\n    ): FileOpResult {','        move: Boolean,\n        shouldCancel: () -> Boolean = { false },\n    ): FileOpResult {\n        if (manual != null && (scopeId == workspaceScopeId || manual.canWrite(scopeId))) {\n            return manualResult(dstRel) { manual.transfer(scopeId, srcRel, scopeId, dstRel, move, overwrite, shouldCancel) }\n        }',1);s=s.replace('        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))\n        val rel = joinPath(parentRel, name)','        if (manual != null && (scopeId == workspaceScopeId || manual.canWrite(scopeId))) {\n            return manualResult(joinPath(parentRel, name)) { manual.mkdir(scopeId, parentRel, name) }\n        }\n        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))\n        val rel = joinPath(parentRel, name)');s=s.replace("val dir = baseRel.substringBeforeLast('/')", "val dir = baseRel.substringBeforeLast('/', \"\")");s=s.replace('if (!store.stat(FileScopePath(scopeId, candidate)).exists) return candidate','if (!(manual?.exists(scopeId, candidate) ?: store.stat(FileScopePath(scopeId, candidate)).exists)) return candidate');s=s.replace('        val fsp = FileScopePath(scopeId, relativePath)\n        return try {\n            store.moveToTrash(fsp)','        if (scopeId != workspaceScopeId && manual != null) return manualResult(relativePath) { manual.delete(scopeId, relativePath) }\n        val fsp = FileScopePath(scopeId, relativePath)\n        return try {\n            store.moveToTrash(fsp)');# SAF read-only guard before manual delete must move
s=s.replace('        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))\n        if (scopeId != workspaceScopeId && manual != null)', '        if (scopeId != workspaceScopeId && manual != null)');s=s.replace('processBatchItem(scopeId, srcRel, destinationDir, policy, move)','processBatchItem(scopeId, srcRel, destinationDir, policy, move, shouldCancel)');s=s.replace('        move: Boolean,\n    ): BatchItem {','        move: Boolean,\n        shouldCancel: () -> Boolean,\n    ): BatchItem {');s=s.replace('overwrite = true, move)', 'overwrite = true, move, shouldCancel)').replace('overwrite = false, move)', 'overwrite = false, move, shouldCancel)');
pos=s.index('    /** Creates a directory')
s=s[:pos]+'''    @Suppress("TooGenericExceptionCaught")
    private fun manualResult(destination: String, operation: () -> Unit): FileOpResult =
        try {
            operation()
            FileOpResult.Ok(destination, false)
        } catch (_: FileAlreadyExistsException) {
            FileOpResult.Conflict
        } catch (failure: Exception) {
            FileOpResult.Error(failure.message ?: loc(R.string.files_error_operation_failed))
        }

'''+s[pos:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesMutationDialogs.kt');s=p.read_text().replace("target.relativePath.substringBeforeLast('/')", 'target.relativePath.substringBeforeLast(\'/\', "")');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesScreenActions.kt');s=p.read_text().replace('            if (destDir.isBlank()) {','            if (destDir.isBlank() && currentSource.kind == com.helix.app.files.FileSourceKind.WORKSPACE) {');p.write_text(s)
