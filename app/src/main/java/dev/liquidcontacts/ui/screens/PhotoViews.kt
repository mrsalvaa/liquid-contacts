// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.R
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** La foto de perfil dentro de una ventana, sobre lo que ya estaba. Se agranda con dos dedos o con doble toque. */
@Composable
fun PhotoWindow(contactId: Long, name: String, dismiss: () -> Unit) {
    val photo by produceState<ImageBitmap?>(null, contactId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Repo.contacts.fullPhoto(contactId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    val shape = remember { RoundedCornerShape(20.dp) }
    photo?.let { image ->
        Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height).clip(shape)) {
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
                filterQuality = FilterQuality.High,
            )
        }
    } ?: Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
    Spacer(Modifier.height(14.dp))
    GlassButton(str(R.string.close), dismiss, Modifier.fillMaxWidth())
}

/**
 * Para acomodar la foto en 1:1: se mueve y se agranda dentro del cuadrado; el círculo muestra cómo
 * se va a ver en la lista. Se recorta recién al aceptar, del original.
 */
@Composable
fun PhotoCropper(source: Bitmap, maxSide: Int, onDone: (ByteArray?) -> Unit, onCancel: () -> Unit) {
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    val image = remember(source) { source.asImageBitmap() }
    val hint = str(R.string.photo_crop_hint)
    var side by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }

    fun scaleFor(view: Int): Float = view.toFloat() / min(source.width, source.height) * zoom
    fun clamp(o: Offset, view: Int): Offset {
        val k = scaleFor(view)
        val mx = max(0f, (source.width * k - view) / 2)
        val my = max(0f, (source.height * k - view) / 2)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    val gestures = rememberTransformableState { change, pan, _ ->
        zoom = (zoom * change).coerceIn(1f, 6f)
        offset = clamp(offset + pan, side)
    }

    Text(hint, fontSize = 14.sp, color = p.contentSecondary)
    Spacer(Modifier.height(12.dp))
    ComposeCanvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { side = it.width }
            .semantics { contentDescription = hint }
            .transformable(gestures),
    ) {
        val view = size.width
        val k = view / min(source.width, source.height) * zoom
        val w = source.width * k
        val h = source.height * k
        drawImage(
            image,
            dstOffset = IntOffset(((view - w) / 2 + offset.x).roundToInt(), ((view - h) / 2 + offset.y).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.High,
        )
        val circle = Path().apply { addOval(Rect(Offset.Zero, Size(view, view))) }
        clipPath(circle, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.45f)) }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassButton(str(R.string.cancel), onCancel, Modifier.weight(1f))
        GlassButton(
            str(R.string.photo_crop_use),
            {
                saving = true
                val view = side.toFloat()
                val k = scaleFor(side)
                val cropSide = view / k
                val left = source.width / 2f - offset.x / k - cropSide / 2
                val top = source.height / 2f - offset.y / k - cropSide / 2
                scope.launch {
                    onDone(withContext(Dispatchers.Default) { runCatching { cropToJpeg(source, left, top, cropSide, maxSide) }.getOrNull() })
                }
            },
            Modifier.weight(1f),
            prominent = true,
            enabled = !saving && side > 0,
        )
    }
}

/** La foto tal como viene, sin pasarse de lo que hace falta para recortarla bien. */
fun decodeForCrop(resolver: ContentResolver, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_DECODE) decoder.setTargetSampleSize(longest / MAX_DECODE)
    }

/** Sin agrandar: una foto chica queda de su tamaño. */
private fun cropToJpeg(source: Bitmap, left: Float, top: Float, cropSide: Float, maxSide: Int): ByteArray {
    val out = min(maxSide, cropSide.roundToInt()).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
    val k = out / cropSide
    val matrix = Matrix().apply {
        postTranslate(-left, -top)
        postScale(k, k)
    }
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return ByteArrayOutputStream().use { s ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, s)
        s.toByteArray()
    }
}

private const val MAX_DECODE = 2160
private const val JPEG_QUALITY = 95
