// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.liquidcontacts.R
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.ui.glass.GlassIconButton
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La foto de perfil a pantalla completa. Se agranda con dos dedos o tocando dos veces. */
@Composable
fun PhotoScreen(contactId: Long, name: String, padding: PaddingValues, onBack: () -> Unit) {
    // null = cargando; Failure = no hay foto o no se pudo leer.
    val photo by produceState<Result<ImageBitmap>?>(null, contactId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Repo.contacts.fullPhoto(contactId) ?: error("sin foto")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: error("foto ilegible")
            }
        }
    }
    LaunchedEffect(photo) { if (photo?.isFailure == true) onBack() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        photo?.getOrNull()?.let { image ->
            Image(
                image,
                str(R.string.photo_of, name),
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        })
                    }
                    .transformable(zoom)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
        GlassIconButton(Icons.Rounded.Close, str(R.string.close), onBack, Modifier.align(Alignment.TopStart).padding(padding).padding(12.dp))
    }
}
