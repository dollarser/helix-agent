package com.helix.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.helix.app.R
import com.helix.app.chat.ChatImageRef
import com.helix.app.chat.ChatImageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ImagePreviewState(
    val loading: Boolean = true,
    val bitmap: ImageBitmap? = null,
)

@Composable
private fun previewState(
    reference: ChatImageRef,
    load: suspend (ChatImageRef) -> ByteArray?,
    edge: Int,
) = key(reference, edge) {
    val currentLoad by rememberUpdatedState(load)
    produceState(ImagePreviewState(), reference, edge) {
        val bytes = currentLoad(reference)
        val bitmap = withContext(Dispatchers.Default) { bytes?.let { decodePreviewBitmap(it, edge) } }
        value = ImagePreviewState(false, bitmap)
    }
}

@Composable
@Suppress("FunctionName")
internal fun ChatImageTile(
    image: ChatImageUi,
    load: suspend (ChatImageRef) -> ByteArray?,
    onOpen: () -> Unit,
) {
    val state by previewState(image.reference, load, 192)
    Box(
        Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(R.string.chat_image_preview), onClick = onOpen)
            .testTag("chat-image-${image.reference.attachmentId}"),
        contentAlignment = Alignment.Center,
    ) {
        PreviewContent(state, image.fileName, ContentScale.Crop, Modifier.fillMaxSize())
    }
}

@Composable
@Suppress("FunctionName")
internal fun ChatMessageImages(
    images: List<ChatImageUi>,
    load: suspend (ChatImageRef) -> ByteArray?,
) {
    if (images.isEmpty()) return
    var selected by remember(images) { mutableStateOf<ChatImageUi?>(null) }
    Row(
        Modifier.padding(12.dp).indicatedHorizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image -> key(image.reference) { ChatImageTile(image, load) { selected = image } } }
    }
    selected?.let { ChatImageDialog(it, load) { selected = null } }
}

@Composable
@Suppress("FunctionName")
internal fun ChatImageDialog(
    image: ChatImageUi,
    load: suspend (ChatImageRef) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    val state by previewState(image.reference, load, 2048)
    var scale by remember(image.reference) { mutableFloatStateOf(1f) }
    var offset by remember(image.reference) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transform =
        rememberTransformableState { _, zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            val bounds = previewPanBounds(state.bitmap?.width ?: 0, state.bitmap?.height ?: 0, viewport, scale)
            offset =
                Offset(
                    (offset.x + pan.x).coerceIn(-bounds.x, bounds.x),
                    (offset.y + pan.y).coerceIn(-bounds.y, bounds.y),
                )
        }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PreviewSystemBars()
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xff101314))
                .systemBarsPadding()
                .testTag("chat-image-preview"),
        ) {
            ImagePreviewHeader(image.fileName, onDismiss)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .transformable(transform),
                contentAlignment = Alignment.Center,
            ) {
                PreviewContent(
                    state,
                    image.fileName,
                    ContentScale.Fit,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        val bounds =
                            previewPanBounds(state.bitmap?.width ?: 0, state.bitmap?.height ?: 0, viewport, scale)
                        translationX = offset.x.coerceIn(-bounds.x, bounds.x)
                        translationY = offset.y.coerceIn(-bounds.y, bounds.y)
                    },
                    textColor = Color.LightGray,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun PreviewSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as DialogWindowProvider).window
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ImagePreviewHeader(
    fileName: String,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fileName, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onDismiss, Modifier.testTag("chat-image-close")) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.files_close), tint = Color.White)
        }
    }
}

/** Letterboxed images must not pan out of view along an axis that still fits in the viewport. */
internal fun previewPanBounds(
    width: Int,
    height: Int,
    viewport: IntSize,
    scale: Float,
): Offset {
    if (width <= 0 || height <= 0) return Offset.Zero
    val fit = minOf(viewport.width.toFloat() / width, viewport.height.toFloat() / height)
    return Offset(
        maxOf(0f, (width * fit * scale - viewport.width) / 2f),
        maxOf(0f, (height * fit * scale - viewport.height) / 2f),
    )
}

@Composable
@Suppress("FunctionName")
private fun PreviewContent(
    state: ImagePreviewState,
    name: String,
    scale: ContentScale,
    modifier: Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    when {
        state.loading -> {
            CircularProgressIndicator(Modifier.size(24.dp).testTag("chat-image-loading"))
        }

        state.bitmap != null -> {
            Image(state.bitmap, name, modifier.testTag("chat-image-loaded"), contentScale = scale)
        }

        else -> {
            Text(
                stringResource(R.string.chat_image_unavailable),
                Modifier.padding(8.dp).testTag("chat-image-unavailable"),
                color = textColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
