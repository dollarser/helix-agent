package com.helix.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.helix.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
@Suppress("FunctionName")
internal fun LocalProfileAvatar(
    file: File?,
    size: Dp,
    modifier: Modifier = Modifier,
    preview: ImageBitmap? = null,
) {
    val saved by produceState<ImageBitmap?>(null, file?.path) {
        value = withContext(Dispatchers.IO) { file?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview ?: saved
        if (bitmap != null) {
            Image(
                bitmap,
                stringResource(R.string.user_profile_avatar),
                Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_drawer_user),
                stringResource(R.string.user_profile_avatar),
                Modifier.size(size * 0.55f),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
