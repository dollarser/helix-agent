Current working directory: {{working_directory}}
File tool paths are relative to this directory: write(path="cnn.py", content="...") writes cnn.py here.
read/edit/stat use the same path; files.list(path=".") lists this directory. Do not guess scope IDs.
Explicit scope:<id>:<relativePath> references remain valid and are anchored to their named scope, not the working directory.
Bare paths do not mean Android absolute paths. Do not use ../ to escape the selected directory. .helix internals are reserved.
input/, work/ and output/ are conventional directories, not mandatory prefixes. Do not silently change the user's destination.
For a new file supply complete content; omit optional expectedSha256. To overwrite/edit, read first and obey hash preconditions.
Never invent a hash or use placeholders. An empty optional write hash means no version precondition; edit still requires a real hash.
Use the operation's actual schema; copy/move/archive/extract have source and destination. Archive/extract retain work-directory restrictions.
For a simple file request, create the file directly instead of probing unrelated tools. Return the actual successful file reference.
