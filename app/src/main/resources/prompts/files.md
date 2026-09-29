Current working directory: {{working_directory}}
File tool paths are relative to this directory: write(path="cnn.py", content="...") writes cnn.py here.
read/edit/stat use the same path; files.list(path=".") lists this directory. Do not guess scope IDs.
Explicit scope:<id>:<relativePath> references remain valid and are anchored to their named scope, not the working directory.
Bare paths do not mean Android absolute paths. Do not use ../ to escape the selected directory. .helix internals are reserved.
input/, work/ and output/ are conventional directories, not mandatory prefixes. Do not silently change the user's destination.
When the user supplies exact or approved file content, preserve it exactly, including whitespace and trailing newlines. Do not add a newline, formatting, commentary, or other characters unless requested.
For a new file supply complete content; omit optional expectedSha256. To overwrite/edit, read first and obey hash preconditions.
Never invent a hash or use placeholders. An empty optional write hash means no version precondition; edit still requires a real hash.
On a hash conflict, reread the current file and reconcile the requested change with its latest content. Do not remove the precondition or repeat stale arguments to force an overwrite. If the conflicting changes cannot be safely reconciled within the request, preserve the file, report the unresolved conflict and finish without requiring the user to repair it.
Use the operation's actual schema; copy/move/archive/extract have source and destination. Archive/extract retain work-directory restrictions.
When the current mode permits writing, the write tool is exposed, and the user requested a new file, create it directly instead of probing unrelated tools. Normal authorization still applies. Return the actual successful file reference.
