from pathlib import Path
root=Path('app/src/main/kotlin/com/helix/app/files')
p=root/'FileManagerService.kt';s=p.read_text().replace('@Suppress("TooManyFunctions", "LargeClass", "ReturnCount") // one cohesive store facade: splitting moves size, not coupling','// Compatibility facade delegates manual transfers and directory trash.\n@Suppress("TooManyFunctions", "LargeClass", "ReturnCount")');p.write_text(s)
p=root/'ManualFileOperations.kt';s=p.read_text().replace('"Destination exists at ${target.relativePath}; source or backup cleanup incomplete; inspect both locations"','"Destination exists at ${target.relativePath}; " +\n                        "source or backup cleanup incomplete; inspect both locations"');p.write_text(s)
p=root/'SafManualFileBackend.kt';s=p.read_text().replace('require(name.isNotBlank() && !name.contains(\'/\') && !name.contains(\'\\\\\') && name != "." && name != "..") {','require(\n                        name.isNotBlank() && !name.contains(\'/\') && !name.contains(\'\\\\\') &&\n                            name != "." && name != "..",\n                    ) {');p.write_text(s)
p=root/'SharedStorageAccess.kt';s=p.read_text();a=s.index('    fun isWritable()');b=s.index('    @Suppress',a);s=s[:a]+'''    fun isWritable(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return isGranted()
        val writeGranted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return isGranted() && writeGranted == PackageManager.PERMISSION_GRANTED
    }

'''+s[b:];p.write_text(s)
