package com.schedulewidget.mobile.pet

import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.apps.MusicHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import kotlin.random.Random

private val reactions = listOf("jumping", "waving")

@Composable
private fun rememberMusicPlaying(enabled: Boolean): Boolean =
    produceState(false, enabled) {
        if (enabled) MusicHub.state.map { it.isPlaying }.distinctUntilChanged().collect { value = it }
    }.value

/**
 * Draws a character: a builtin spritesheet ("builtin:<id>") animated with [animation], or a user image ("uri:<uri>")
 * drawn statically. Honors the system "remove animations" setting.
 */
@Composable
fun CharacterSprite(
    manifest: String,
    animation: String,
    height: Dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    react: Boolean = true,
    onDoubleTap: (() -> Unit)? = null,
    onTripleTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /** Increment to play a tap reaction from outside (the floating pet handles its own touches). */
    reactKey: Int = 0,
    /** false = no gesture handling here (caller handles touches). */
    interactive: Boolean = true,
    flipped: Boolean = false,
    animated: Boolean = true,
    /**
     * The drawn frame's painted bounds and pixels are published here. When [interactive], touches (the taps and [drag])
     * also land only on its painted silhouette, so transparent margins and holes pass them to what is behind.
     */
    painted: PaintedBounds? = null,
    drag: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lifecycleActive by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ ->
            lifecycleActive = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val canAnimate = animated && lifecycleActive
    var reduce by remember { mutableStateOf(Characters.reduceMotion(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = Characters.reduceMotion(context) }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val userUri = remember(manifest) { Characters.userUri(manifest) }
    val character = remember(manifest) {
        if (userUri != null) null else Characters.find(context, manifest) ?: Characters.find(context, Characters.DEFAULT)
    }
    val musicPlaying = rememberMusicPlaying(animation == "music" && canAnimate)
    val heightPx = with(LocalDensity.current) { height.toPx() }
    // Full-resolution sheet unless drawn really small, so frames are never upscaled from a half-size copy.
    val sample = if (!animated && height.value <= 80f) 4 else if (heightPx > Characters.CELL_H * 0.45f) 1 else 2
    // With [painted], silhouettes are scanned with the image (once per bitmap), so the pet never shows without them; keyed on
    // the manifest there so a new character never hit-tests or clamps with the previous one's image while loading.
    val loaded by key(manifest.takeIf { painted != null }) {
        produceState<Pair<Bitmap?, SpriteGeometry?>?>(null, manifest, sample) {
            value = withContext(Dispatchers.IO) {
                val image = when {
                    userUri != null -> Characters.userImage(context, userUri)
                    character != null && character.rows == 0 -> Characters.stillImage(character.image)
                    character != null -> Characters.sheet(context, character, sample)
                    else -> null
                }
                image to image?.takeIf { painted != null }?.let { spriteGeometry(it, character?.rows ?: 0) }
            }
        }
    }
    val bitmap = loaded?.first

    var reaction by remember { mutableStateOf<String?>(null) }
    var reactionIndex by remember { mutableIntStateOf(0) }
    val bounce = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(canAnimate, reduce) {
        if (!canAnimate || reduce) bounce.snapTo(0f)
    }
    fun playReaction() {
        if (!react || reduce || !canAnimate) return
        if (character != null && character.rows > 0) {
            reaction = reactions[reactionIndex % reactions.size]
            reactionIndex++
        } else {
            scope.launch {
                bounce.animateTo(-1f, tween(140))
                bounce.animateTo(0f, tween(220))
            }
        }
    }
    LaunchedEffect(reactKey) { if (reactKey > 0) playReaction() }
    val single by rememberUpdatedState {
        onTap?.invoke()
        playReaction()
    }
    val double by rememberUpdatedState(onDoubleTap)
    val triple by rememberUpdatedState(onTripleTap)
    val longPress by rememberUpdatedState(onLongPress)
    val counter = remember { TapCounter { n -> when (n) { 1 -> single(); 2 -> double?.invoke(); else -> triple?.invoke() } } }
    // A tap still waiting for its 2nd/3rd must not fire after the pet left the screen.
    DisposableEffect(counter) { onDispose { counter.cancel() } }
    val multi = onDoubleTap != null || onTripleTap != null
    val hasLongPress = onLongPress != null
    // Keys are only the gesture *shape*: callers pass fresh lambdas on recompositions (e.g. every data change after a
    // double tap), and keying on them restarted the detector mid-gesture, losing or splitting taps.
    val tapModifier = if (!interactive) null else Modifier.pointerInput(multi, hasLongPress) {
        detectTapGestures(
            onLongPress = if (hasLongPress) { _ -> counter.cancel(); longPress?.invoke() } else null,
            onTap = { if (multi) counter.tap() else single() },
        )
    }

    if (character == null || character.rows == 0) {
        val bmp = bitmap
        val aspect = bmp?.let { it.width.toFloat() / it.height } ?: 1f
        val image = remember(bmp) { bmp?.asImageBitmap() }
        SpriteSurface(
            modifier.wrapContentSize().size(height * aspect, height)
                .offset { IntOffset(0, (bounce.value * heightPx * 0.12f).roundToInt()) },
            "캐릭터", tapModifier, painted, drag, loaded, flipped, cell = { 0 }, paintedY = { bounce.value * 0.12f },
        ) {
            image?.let {
                if (flipped) scale(-1f, 1f, pivot = center) {
                    drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = quality(it.height, size.height))
                } else {
                    drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = quality(it.height, size.height))
                }
            }
        }
        return
    }

    var row by remember { mutableIntStateOf(0) }
    var col by remember { mutableIntStateOf(0) }
    LaunchedEffect(character, animation, reaction, reduce, canAnimate, musicPlaying) {
        col = 0
        val current = reaction
        if (!canAnimate || reduce) {
            row = Characters.spec(character, if (animation in setOf("random", "music", "typing")) "idle" else animation).row
            if (current != null) reaction = null
            return@LaunchedEffect
        }
        suspend fun play(name: String, loops: Int) {
            val spec = Characters.spec(character, name)
            row = spec.row
            var round = 0
            while (loops == 0 || round < loops) {
                for (c in 0 until spec.frames) {
                    col = c
                    delay(spec.durations[c].toLong())
                }
                round++
            }
        }
        if (current != null) {
            play(current, 2)
            reaction = null
            return@LaunchedEffect
        }
        if (animation == "typing") {
            val idle = Characters.spec(character, "idle")
            var seen = TypingReaction.event.value
            var inBurst = false
            var burstIndex = -1
            var typingAction = "failed"
            var nextFrame = 0
            var idleJob: Job? = launch { play("idle", 0) }
            var timeout: Job? = null
            TypingReaction.event.collect { count ->
                if (count == seen) return@collect
                val delta = (count.toLong() - seen).let { if (it > 0) it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 1 }
                seen = count
                val typingPool = listOf("keyboard1", "keyboard2").filter { it in character.extra }
                    .ifEmpty { listOf("failed", "jumping") }
                if (!inBurst) {
                    burstIndex = (burstIndex + 1) % typingPool.size
                    typingAction = typingPool[burstIndex]
                    nextFrame = 0
                    inBurst = true
                }
                timeout?.cancel()
                idleJob?.cancel()
                val spec = Characters.spec(character, typingAction)
                row = spec.row
                col = (nextFrame + delta - 1) % spec.frames
                nextFrame = (col + 1) % spec.frames
                timeout = launch {
                    delay(900)
                    inBurst = false
                    row = idle.row
                    col = 0
                    idleJob = launch { play("idle", 0) }
                }
            }
            return@LaunchedEffect
        }
        if (animation == "music") {
            val pool = listOf("listening", "grooving", "disliking", "immersed").filter { it in character.extra }
            if (!musicPlaying || pool.isEmpty()) {
                play("idle", 0)
            } else {
                var last: String? = null
                while (true) {
                    val next = pool.filter { it != last }.random()
                    last = next
                    play(next, 2)
                }
            }
        } else if (animation == "random") {
            val pool = Characters.animations.keys.toList() + character.extra.keys
            var last: String? = null
            while (true) {
                val next = pool.filter { it != last }.random()
                last = next
                play(next, if (next == "idle") 1 else 2 + Random.nextInt(2))
            }
        } else {
            play(animation, 0)
        }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    SpriteSurface(
        // wrapContentSize keeps the frame's own aspect ratio even inside fillMaxWidth/fillMaxSize parents.
        modifier.wrapContentSize().size(height * Characters.CELL_W.toFloat() / Characters.CELL_H, height),
        character.name, tapModifier, painted, drag, loaded, flipped, cell = { row * Characters.COLUMNS + col },
    ) {
        image?.let {
            if (flipped) scale(-1f, 1f, pivot = center) { drawFrame(it, character.rows, row, col) }
            else drawFrame(it, character.rows, row, col)
        }
    }
}

/**
 * Draws the sprite and, with [painted], publishes the drawn frame there. An interactive one ([touch] set) takes touches
 * ([touch] and the caller's [drag]) on a child clipped to that frame's silhouette: Compose hit-tests a clipped layer
 * against its outline, so transparent margins and holes pass touches to what is behind. Nothing is touchable while
 * loading, with no image or on a blank frame; an image whose pixels cannot be read is touchable on its whole box.
 */
@Composable
private fun SpriteSurface(
    modifier: Modifier,
    description: String,
    touch: Modifier?,
    painted: PaintedBounds?,
    drag: Modifier,
    loaded: Pair<Bitmap?, SpriteGeometry?>?,
    flipped: Boolean,
    cell: () -> Int,
    paintedY: () -> Float = { 0f },
    onDraw: DrawScope.() -> Unit,
) {
    if (painted == null) {
        Canvas(modifier.then(touch ?: Modifier).semantics { contentDescription = description }, onDraw)
        return
    }
    val geometry = loaded?.second
    LaunchedEffect(painted, loaded, flipped) {
        snapshotFlow { loaded?.let { PaintedFrame(it.first != null, geometry, cell(), flipped, paintedY()) } }
            .collect { painted.frame = it }
    }
    Box(modifier.drawBehind(onDraw).semantics { contentDescription = description }) {
        if (loaded?.first != null && touch != null) Box(
            Modifier.matchParentSize()
                .graphicsLayer { shape = geometry?.silhouette(cell(), flipped) ?: RectangleShape; clip = true }
                .then(drag)
                .then(touch),
        )
    }
}

private fun DrawScope.drawFrame(image: androidx.compose.ui.graphics.ImageBitmap, rows: Int, row: Int, col: Int) {
    val fw = image.width / Characters.COLUMNS
    val fh = image.height / rows
    if (fw <= 0 || fh <= 0 || row >= rows) return
    drawImage(
        image,
        srcOffset = IntOffset(col * fw, row * fh),
        srcSize = IntSize(fw, fh),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = quality(fh, size.height),
    )
}

/** Pixel-art sprites: nearest-neighbour when enlarging (stays sharp), smooth filtering when shrinking. */
private fun quality(srcPx: Int, dstPx: Float): FilterQuality =
    if (dstPx >= srcPx * 1.25f) FilterQuality.None else FilterQuality.High
