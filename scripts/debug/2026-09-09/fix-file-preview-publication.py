from pathlib import Path
r=Path('app/src/main/kotlin/com/helix/app/ui')
p=r/'FilesScreenState.kt';s=p.read_text().replace('    var previewText by mutableStateOf<String?>(null)\n    var previewImage by mutableStateOf<ImageBitmap?>(null)\n    var fileInfo by mutableStateOf<FileMeta?>(null)','    var preview by mutableStateOf<FilePreviewState>(FilePreviewState.Loading)');s=s.replace('import androidx.compose.ui.graphics.ImageBitmap\n','').replace('import com.helix.app.files.FileManagerService.FileMeta\n','');p.write_text(s)
(r/'FilePreviewState.kt').write_text('''package com.helix.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.helix.app.files.FileManagerService.FileMeta

/** One observable result; text/image/metadata cannot describe different loading phases. */
internal sealed interface FilePreviewState {
    data object Loading : FilePreviewState
    data class Ready(val text: String?, val image: ImageBitmap?, val info: FileMeta) : FilePreviewState
    data class Failed(val message: String) : FilePreviewState
}
''')
p=r/'FilesScreenEffects.kt';s=p.read_text();a=s.index('    with(actions) {',s.index('internal fun FilesPreviewEffects'));b=s.index('\n@Composable',a);s=s[:a]+'''    val file = state.openFile
    val scopeId = state.selectedScopeId
    LaunchedEffect(file?.relativePath, scopeId) {
        if (file == null || file.isDirectory) return@LaunchedEffect
        state.preview = FilePreviewState.Loading
        val loaded = withContext(Dispatchers.IO) {
            try {
                val text = actions.fileManager.previewText(scopeId, file.relativePath)
                val image = if (text == null) {
                    val bytes = actions.fileManager.previewImageBytes(scopeId, file.relativePath)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } else null
                FilePreviewState.Ready(text, image, actions.fileManager.fileInfo(scopeId, file.relativePath))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                FilePreviewState.Failed(failure.message ?: actions.str(R.string.files_read_directory_error))
            }
        }
        // IO completion may resume on a test/effect dispatcher. Publish on Android's UI dispatcher.
        withContext(Dispatchers.Main.immediate) {
            if (state.openFile?.relativePath == file.relativePath && state.selectedScopeId == scopeId) {
                state.preview = loaded
            }
        }
    }
}
''' + s[b:];s=s.replace('internal fun FilesPreviewEffects(', '@Suppress("TooGenericExceptionCaught") // Read failure is shown; cancellation still propagates.\ninternal fun FilesPreviewEffects(');# existing Suppress annotation duplicate merge
s=s.replace('@Suppress("FunctionName")\n@Suppress("TooGenericExceptionCaught")', '@Suppress("FunctionName", "TooGenericExceptionCaught")');p.write_text(s)
p=r/'FilesPreviewDialog.kt';s=p.read_text().replace('                android.util.Log.d("HelixFilePreview", "render text=${previewText != null} info=${fileInfo != null}")','''                val currentPreview = preview
                val loaded = currentPreview as? FilePreviewState.Ready''');s=s.replace('''                                previewImage != null -> {''','''                                currentPreview is FilePreviewState.Loading -> {
                                    Text(str(R.string.egress_loading), modifier = Modifier.testTag("files-preview-loading"))
                                }
                                currentPreview is FilePreviewState.Failed -> {
                                    Text(currentPreview.message, modifier = Modifier.testTag("files-preview-error"))
                                }
                                loaded?.image != null -> {''');s=s.replace('bitmap = previewImage!!','bitmap = loaded.image').replace('previewText != null ->','loaded?.text != null ->').replace('previewText.orEmpty()','loaded.text').replace('fileInfo?.let { meta ->','loaded?.info?.let { meta ->');p.write_text(s)
