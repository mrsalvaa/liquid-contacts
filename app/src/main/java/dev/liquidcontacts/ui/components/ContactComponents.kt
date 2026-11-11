// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import dev.liquidcontacts.util.str
import dev.liquidcontacts.R
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.core.avatar.Avatars
import dev.liquidcontacts.ui.glass.ACCENT_HUES
import dev.liquidcontacts.ui.glass.GlassPalette
import dev.liquidcontacts.ui.glass.GlassSurface
import dev.liquidcontacts.ui.glass.GlassSwitch
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.muted
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.ui.glass.readableOn
import dev.liquidcontacts.ui.glass.styled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Componentes propios de Contactos, en el estilo de ui/glass. Se pueden copiar a phone y messages
 * (ver DESIGN.md). Las listas largas usan filas planas: el cristal va solo en los contenedores
 * (búsqueda, barra, avisos, diálogos), porque cientos de superficies que refractan a la vez no
 * llegan a 120 Hz.
 */

// ---------------------------------------------------------------- avatar

/** Miniaturas de fotos ya decodificadas (las de la lista se piden muchas veces al desplazar). */
private object Thumbnails {
    val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Reducida al tamaño en que se muestra: nítida, sin guardar en memoria la foto entera. */
    suspend fun load(resolver: ContentResolver, uri: String, sidePx: Int): ImageBitmap? {
        val key = "$uri#$sidePx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val parsed = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sidePx) sample *= 2
                resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }.getOrNull()?.let { bmp: Bitmap -> bmp.asImageBitmap().also { cache.put(key, it) } }
        }
    }

    /**
     * La clave de una foto en bytes: el mismo arreglo, sin retenerlo en el caché ni recorrerlo
     * entero (una foto de un .vcf puede pesar 1 MB y la fila se vuelve a pintar al desplazar).
     */
    fun keyOf(photo: ByteArray, sidePx: Int): String = "bytes:${System.identityHashCode(photo)}:${photo.size}#$sidePx"

    suspend fun load(photo: ByteArray, sidePx: Int): ImageBitmap? {
        val key = keyOf(photo, sidePx)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sidePx) sample *= 2
            BitmapFactory.decodeByteArray(photo, 0, photo.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }
}

/**
 * Avatar redondo: la foto si hay ([photoUri] de Android o [photo] en bytes) o las iniciales sobre
 * un color que sale siempre igual para el mismo nombre. Decorativo para TalkBack: el nombre ya lo
 * dice la fila.
 */
@Composable
fun ContactAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, photoUri: String? = null, photo: ByteArray? = null) {
    val p = LocalGlass.current
    val resolver = LocalContext.current.contentResolver
    val sidePx = with(LocalDensity.current) { size.roundToPx() }
    val cached = photo?.let { Thumbnails.cache.get(Thumbnails.keyOf(it, sidePx)) } ?: photoUri.takeIf { photo == null }?.let { Thumbnails.cache.get("$it#$sidePx") }
    val image by produceState(cached, photoUri, photo, sidePx) {
        value = when {
            photo != null -> Thumbnails.load(photo, sidePx)
            photoUri != null -> Thumbnails.load(resolver, photoUri, sidePx)
            else -> null
        }
    }
    Box(modifier.size(size).clip(CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, null, Modifier.size(size), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        } else {
            val bg = avatarColor(name, p.dark)
            val fg = readableOn(bg)
            Box(Modifier.size(size).background(bg), contentAlignment = Alignment.Center) {
                val initials = Avatars.initials(name)
                if (initials.isEmpty()) {
                    Icon(styled(Icons.Rounded.Person), null, Modifier.size(size * 0.55f), tint = fg)
                } else {
                    Text(initials, color = fg, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * El avatar grande de la ficha cuando no hay foto: un círculo de cristal con borde fino y una
 * persona en blanco (cabeza redonda y el cuerpo como una lente), el estilo Liquid de la suite. El
 * mismo dibujo que los avisos de Liquid Messages (NotificationAvatar).
 */
@Composable
fun GlassPersonAvatar(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val p = LocalGlass.current
    // En claro, un velo gris para que la persona blanca no se pierda sobre el cristal claro.
    val veil = if (p.dark || p.solid) null else androidx.compose.ui.graphics.Color(0xA67C8296)
    dev.liquidcontacts.ui.glass.GlassSurface(
        modifier.size(size).clearAndSetSemantics { },
        shape = CircleShape,
        tint = veil,
        frost = 4.dp,
        shadowRadius = 12.dp,
    ) {
        val person = if (p.solid && !p.dark) p.contentSecondary else androidx.compose.ui.graphics.Color.White
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val s = this.size.width
            drawCircle(person, radius = s * 0.175f, center = Offset(s / 2, s * 0.385f))
            val left = s * 0.19f
            val right = s * 0.81f
            val waist = s * 0.82f
            val body = androidx.compose.ui.graphics.Path().apply {
                moveTo(left, waist)
                arcTo(androidx.compose.ui.geometry.Rect(left, s * 0.635f, right, waist + (waist - s * 0.635f)), 180f, 180f, false)
                arcTo(androidx.compose.ui.geometry.Rect(left, waist - s * 0.09f, right, waist + s * 0.09f), 0f, 180f, false)
                close()
            }
            drawPath(body, person)
        }
    }
}

/** Color del avatar: uno de los 9 tonos de énfasis (sin el gris), apagado como el resto de la suite. */
fun avatarColor(name: String, dark: Boolean): Color {
    val hues = ACCENT_HUES.dropLast(1)
    val hue = hues[Avatars.colorIndex(name, hues.size)]
    return muted(hue.tones[if (dark) 2 else 0])
}

// ---------------------------------------------------------------- filas

/**
 * Fila plana de la lista (sin cristal propio): avatar, nombre y un dato secundario. Alto mínimo
 * de 64 dp; toda la fila es el botón, y TalkBack lee nombre, dato y si es favorito.
 */
@Composable
fun ContactRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    photoUri: String? = null,
    /** La foto en bytes, para lo que todavía no está en la agenda (un .vcf por importar). */
    photo: ByteArray? = null,
    starred: Boolean = false,
    starredLabel: String = "",
    trailing: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null,
    onPhotoLongClick: (() -> Unit)? = null,
    photoLabel: String? = null,
    selectLabel: String? = null,
    /** null = la lista no está en selección múltiple. */
    selected: Boolean? = null,
) {
    val p = LocalGlass.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCorner)
            .combinedClickable(remember { MutableInteractionSource() }, null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(name, subtitle, starredLabel.takeIf { starred && it.isNotEmpty() }).joinToString(", ")
                if (selected != null) this.selected = selected
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatar = @Composable {
            if (selected == true) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(p.accentFg), contentAlignment = Alignment.Center) {
                    Icon(styled(Icons.Rounded.Check), null, Modifier.size(26.dp), tint = readableOn(p.accentFg))
                }
            } else {
                ContactAvatar(name, photoUri = photoUri, photo = photo)
            }
        }
        if (onPhotoClick != null) {
            // Botón propio de 48 dp: tocar la foto no es lo mismo que tocar la fila.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        remember { MutableInteractionSource() },
                        null,
                        role = Role.Button,
                        onClickLabel = photoLabel,
                        onLongClickLabel = selectLabel,
                        onLongClick = onPhotoLongClick,
                        onClick = onPhotoClick,
                    )
                    .semantics { contentDescription = photoLabel.orEmpty() },
                contentAlignment = Alignment.Center,
            ) { avatar() }
            Spacer(Modifier.width(12.dp))
        } else {
            avatar()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 14.sp, color = p.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (starred) {
            Icon(styled(Icons.Rounded.Star), null, Modifier.padding(start = 8.dp).size(18.dp), tint = p.accentFg)
        }
        trailing?.invoke()
    }
}

private val RoundedCorner = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

/** Letra que encabeza cada grupo de la lista (encabezado para TalkBack). */
@Composable
fun LetterHeader(letter: String, modifier: Modifier = Modifier) {
    Text(
        letter,
        modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
        color = LocalGlass.current.accentFg,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
}

// ---------------------------------------------------------------- índice alfabético

/**
 * Índice lateral para desplazarse rápido: tocar o arrastrar sobre las letras salta a la primera
 * de esa letra, con una burbuja de cristal que muestra cuál. Las letras sin contactos se ven
 * tenues. Para TalkBack es una lista de botones, uno por letra con contactos.
 */
@Composable
fun AlphabetIndex(
    letters: List<String>,
    available: Set<String>,
    onLetter: (String) -> Unit,
    modifier: Modifier = Modifier,
    jumpLabel: (String) -> String = { it },
) {
    val p = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    var height by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableStateOf<String?>(null) }
    fun pick(y: Float) {
        if (height.height == 0) return
        val i = (y / height.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        // La más cercana con contactos, hacia arriba y después hacia abajo.
        val letter = (i downTo 0).map { letters[it] }.firstOrNull { it in available }
            ?: (i until letters.size).map { letters[it] }.firstOrNull { it in available }
            ?: return
        if (letter != active) {
            active = letter
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onLetter(letter)
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier) {
        // Si no entran todas las letras (pantalla chica, aviso arriba), se muestran algunas con
        // puntos entre medio; al arrastrar se recorren igual todas.
        val fit = with(density) { (height.height / 22.dp.toPx()).toInt() }.coerceAtLeast(3)
        val shownLetters: List<String?> = if (height.height == 0 || fit >= letters.size) {
            letters
        } else {
            val step = (letters.size + fit / 2 - 1) / (fit / 2)
            letters.indices.filter { it % step == 0 || it == letters.lastIndex }.flatMap { listOf(letters[it], null) }.dropLast(1)
        }
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .onSizeChanged { height = it }
                .pointerInput(letters, available) {
                    detectTapGestures(onPress = { pick(it.y); tryAwaitRelease(); active = null })
                }
                .pointerInput(letters, available) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ -> pick(change.position.y) }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            shownLetters.forEach { l ->
                if (l == null) {
                    Text("•", color = p.contentSecondary.copy(alpha = 0.5f), fontSize = 9.sp)
                    return@forEach
                }
                val has = l in available
                Text(
                    l,
                    Modifier.semantics {
                        if (has) {
                            contentDescription = jumpLabel(l)
                            role = Role.Button
                            onClick {
                                onLetter(l)
                                true
                            }
                        }
                    },
                    color = if (has) p.accentFg else p.contentSecondary.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // La burbuja queda armada y solo se muestra al usar el índice: crear una superficie de
        // cristal en cada toque costaba un cuadro entero.
        var last by remember { mutableStateOf("") }
        active?.let { last = it }
        GlassSurface(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 44.dp)
                .size(64.dp)
                .graphicsLayer { alpha = if (active != null) 1f else 0f }
                .clearAndSetSemantics { },
            shape = CircleShape,
            strong = true,
            frost = 12.dp,
        ) {
            Text(last, Modifier.align(Alignment.Center), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- búsqueda

/** Campo de búsqueda: píldora de cristal con lupa y botón para borrar (48 dp). */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val p = LocalGlass.current
    GlassSurface(modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape, frost = 12.dp, refraction = false) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(styled(Icons.Rounded.Search), null, Modifier.size(22.dp), tint = p.contentSecondary)
            Spacer(Modifier.width(10.dp))
            val style = TextStyle(fontSize = 17.sp, color = p.content)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(p.accentFg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = style, color = p.contentSecondary)
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onValueChange("") }
                        .semantics { contentDescription = clearLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(styled(Icons.Rounded.Close), null, Modifier.size(20.dp), tint = p.contentSecondary)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- avisos

/**
 * Aviso discreto dentro de la lista (no es un diálogo): un texto, una acción y una cruz para
 * descartarlo. Toda la tarjeta lleva a la acción.
 */
@Composable
fun NoticeCard(
    text: String,
    action: String,
    onAction: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    GlassSurface(modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), frost = 12.dp, onClick = onAction) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(text, fontSize = 14.sp, color = p.content)
                Text(action, Modifier.padding(top = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.accentFg)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = dismissLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(styled(Icons.Rounded.Close), null, Modifier.size(20.dp), tint = p.contentSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------- deshacer

/** Aviso con una acción para revertir lo que se acaba de hacer. */
class UndoSpec(val text: String, val actionLabel: String, val onUndo: () -> Unit)

val LocalUndo = staticCompositionLocalOf<(UndoSpec) -> Unit> { {} }

/**
 * Píldora de cristal con "Deshacer" abajo de la pantalla. Dura [UNDO_MS] o lo que pida la
 * accesibilidad del sistema ("Tiempo para actuar"); cuando vence, la copia en memoria se suelta.
 * Deslizarla hacia cualquier lado la saca antes de tiempo: lo hecho queda hecho.
 */
@Composable
fun BoxScope.UndoHost(spec: UndoSpec?, onDone: () -> Unit) {
    val context = LocalContext.current
    var last by remember { mutableStateOf(spec) }
    if (spec != null) last = spec
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(spec) {
        if (spec != null) {
            drag.snapTo(Offset.Zero)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            delay(
                manager.getRecommendedTimeoutMillis(
                    UNDO_MS,
                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
                ).toLong(),
            )
            onDone()
        }
    }
    AnimatedVisibility(
        spec != null,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val p = LocalGlass.current
        val dismissAt = with(LocalDensity.current) { UNDO_DISMISS_AT.toPx() }
        val dismissLabel = str(R.string.dismiss)
        GlassSurface(
            Modifier
                .fillMaxWidth()
                // El gesto va afuera del corrimiento: si no, la píldora al moverse cambiaría la lectura del dedo.
                .pointerInput(dismissAt) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (drag.value.getDistance() >= dismissAt) {
                                    // Sigue en la misma dirección hasta irse.
                                    drag.animateTo(drag.value * 3f, tween(140))
                                    done()
                                } else {
                                    drag.animateTo(Offset.Zero, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(Offset.Zero) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + amount) }
                    }
                }
                .offset { IntOffset(drag.value.x.roundToInt(), drag.value.y.roundToInt()) }
                .graphicsLayer { alpha = (1f - drag.value.getDistance() / (dismissAt * 2.5f)).coerceIn(0f, 1f) }
                // Con TalkBack no se puede deslizar.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(dismissLabel) {
                            done()
                            true
                        },
                    )
                },
            shape = CircleShape,
            strong = true,
            frost = 12.dp,
        ) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    last?.text.orEmpty(),
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    last?.actionLabel.orEmpty(),
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            last?.onUndo?.invoke()
                            onDone()
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    color = p.accentFg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private const val UNDO_MS = 10_000

/** Cuánto hay que deslizar la píldora, para cualquier lado, para que se vaya. */
private val UNDO_DISMISS_AT = 64.dp

// ---------------------------------------------------------------- interruptor con nombre

/**
 * Fila con interruptor: para TalkBack es una sola cosa ("Con año, interruptor, desactivado"). Con
 * un SettingRow suelto, el interruptor se anunciaría sin decir qué activa.
 */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    SettingRow(
        title,
        modifier.semantics(mergeDescendants = true) { },
        subtitle = subtitle,
        // Toda la fila lo cambia, no solo el interruptor.
        onClick = { onChange(!checked) },
        trailing = { GlassSwitch(checked, onChange) },
    )
}

// ---------------------------------------------------------------- acciones destructivas

/**
 * Rojo de los botones destructivos. Excepción local a ui/glass: su `danger` es translúcido y con
 * texto blanco no llega a 4,5:1 en tema claro. Este da más de 4,5:1 con blanco en claro y en
 * oscuro, medido sobre la pantalla (ver DESIGN.md).
 */
val DangerFill = Color(0xFF9F1818)

/** Texto de error sobre una tarjeta: el `danger` de ui/glass no llega a 4,5:1 en tema claro. */
val GlassPalette.dangerText: Color get() = if (dark) danger else Color(0xFFB3261E)

/** Como GlassButton(danger = true), con [DangerFill] opaco. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    GlassSurface(
        modifier
            .heightIn(min = 54.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
            },
        shape = CircleShape,
        strong = true,
        tint = DangerFill,
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(styled(icon), null, Modifier.size(20.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
