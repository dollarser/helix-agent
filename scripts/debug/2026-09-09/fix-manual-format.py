from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text().replace('operation: () -> Unit,','operation: () -> Boolean,');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/SafManualFileBackend.kt');s=p.read_text();# split named lambda return ambiguity formatter
s=s.replace('''            node = entries(node.uri).filter { it.name == part }.let {
                require(it.size <= 1) { "Provider returned duplicate names" }
                it.singleOrNull() ?: return null
            }''','''            val matches = entries(node.uri).filter { it.name == part }
            require(matches.size <= 1) { "Provider returned duplicate names" }
            node = matches.singleOrNull() ?: return null''');p.write_text(s)
