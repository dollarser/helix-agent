from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/SafManualFileBackend.kt');s=p.read_text().replace('        val created = metadata(uri)','        val created = requireNotNull(lookup(path)) { "Created document is absent from its parent" }\n        check(sameDocument(created.uri, uri)) { "Provider returned a different document" }');s=s.replace('        check(metadata(result).name == target.name && lookup(destination) != null && lookup(path) == null) {','        val renamed = requireNotNull(lookup(destination)) { "Renamed document is absent from its parent" }\n        check(sameDocument(renamed.uri, result) && lookup(path) == null) {');s=s.replace('    companion object {','''    private fun sameDocument(expected: Uri, actual: Uri): Boolean =
        expected.authority == actual.authority &&
            DocumentsContract.getDocumentId(expected) == DocumentsContract.getDocumentId(actual)

    companion object {''');p.write_text(s)
p=Path('app/src/androidTest/java/com/helix/app/test/ManualFilesTestProvider.java');s=p.read_text().replace('Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME |','Document.FLAG_SUPPORTS_WRITE | (item.getName().startsWith("no-rename") ? 0 : Document.FLAG_SUPPORTS_RENAME) |');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/files/ManualSafFileDeviceTest.kt');s=p.read_text();pos=s.index('    private var writable');s=s[:pos]+'''    @Test fun unsupportedRenameRetainsTheSource() {
        withBackend { backend, ops, id ->
            backend.create("no-rename.txt", false)
            backend.write("no-rename.txt").use { it.write("preserve".toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) {
                ops.transfer(id, "no-rename.txt", id, "new.txt", true, false)
            }
            assertEquals("preserve", backend.read("no-rename.txt").bufferedReader().use { it.readText() })
            assertFalse(ops.exists(id, "new.txt"))
        }
    }

'''+s[pos:];p.write_text(s)
p=Path('docs/architecture/provider-mcp-skills-modes.md');s=p.read_text().replace('child delegation/JSON Workflow 在 ADR-0009 接受前不可用','child delegation/JSON Workflow 须通过已接受 ADR-0009 的生产启用门禁后才可用');p.write_text(s)
