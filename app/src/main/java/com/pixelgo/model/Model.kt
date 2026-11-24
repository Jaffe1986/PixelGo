package com.pixelgo.model

import com.pixelgo.editor.Timing

/** Maximum width/height of a canvas, in art pixels. */
const val MAX_CANVAS_SIZE = 256

/** Maximum number of layers in one project. */
const val MAX_LAYERS = 32

/** Animation speed range. */
const val MIN_FPS = 1
const val MAX_FPS = 60

/** How many ticks (1/fps) a single frame can be held on screen. */
const val MAX_HOLD = 999

/**
 * One layer. Its pixels are NOT stored here: each (layer, frame) pair is a "cel" held in a
 * [com.pixelgo.editor.CelStore], so animations can be far larger than memory.
 * [id] is stable for the lifetime of the project; undo entries and cel files refer to it.
 */
class Layer(val id: Long, var name: String, var visible: Boolean = true)

/**
 * One animation frame. [uid] is stable even when frames are reordered, so cel files and undo
 * entries never need renaming. [hold] = how many ticks (1/fps) the frame stays on screen.
 * Change holds through [PixelProject.setHold] so cached timing stays correct.
 */
class Frame(val uid: Long, hold: Int = 1) {
    var hold: Int = hold.coerceIn(1, MAX_HOLD)
        internal set
}

/**
 * Project metadata: size, layers (index 0 = bottom), frames, and editing state.
 * Pixel data lives in a separate CelStore keyed by (layer id, frame uid).
 */
class PixelProject(
    val id: String,
    var name: String,
    val width: Int,
    val height: Int,
    layers: List<Layer>,
    frames: List<Frame>,
    var activeLayerIndex: Int = 0,
    currentFrameUid: Long = frames.first().uid,
    var nextLayerId: Long = (layers.maxOfOrNull { it.id } ?: 0L) + 1,
    var nextFrameUid: Long = (frames.maxOfOrNull { it.uid } ?: 0L) + 1,
    fps: Int = 8,
) {
    init {
        require(layers.isNotEmpty()) { "A project needs at least one layer" }
        require(frames.isNotEmpty()) { "A project needs at least one frame" }
    }

    val size: Int get() = width * height

    val layers: MutableList<Layer> = layers.toMutableList()

    private val frameList: MutableList<Frame> = frames.toMutableList()

    /** Frames in playback order. Mutate only through the frame methods below. */
    val frames: List<Frame> get() = frameList

    var fps: Int = fps.coerceIn(MIN_FPS, MAX_FPS)
        set(value) {
            field = value.coerceIn(MIN_FPS, MAX_FPS)
        }

    /** Bumped when frames are added, removed, reordered, or a hold changes. */
    var framesVersion = 0L
        private set

    /** Bumped when layers are added, removed, reordered, merged, or visibility changes. */
    var layersVersion = 0L
        private set

    fun touchLayers() {
        layersVersion++
    }

    /** The frame being viewed/edited, tracked by uid so it survives reordering. */
    var currentFrameUid: Long = currentFrameUid
        set(value) {
            field = if (indexOfFrame(value) >= 0) value else frameList.first().uid
        }

    val currentFrameIndex: Int get() = indexOfFrame(currentFrameUid).coerceAtLeast(0)

    val activeLayer: Layer? get() = layers.getOrNull(activeLayerIndex)

    fun indexOfLayer(id: Long): Int = layers.indexOfFirst { it.id == id }

    // ---- Frame lookup + timing caches ------------------------------------------------------

    private var indexCache: HashMap<Long, Int>? = null
    private var timingCache: Timing? = null

    fun indexOfFrame(uid: Long): Int {
        val cache = indexCache ?: HashMap<Long, Int>(frameList.size * 2).also { m ->
            frameList.forEachIndexed { i, f -> m[f.uid] = i }
            indexCache = m
        }
        return cache[uid] ?: -1
    }

    /** Frame start times (in ticks), total length, and tick → frame lookup. */
    val timing: Timing
        get() = timingCache ?: Timing(IntArray(frameList.size) { frameList[it].hold }).also { timingCache = it }

    private fun framesChanged() {
        framesVersion++
        indexCache = null
        timingCache = null
    }

    // ---- Frame list changes (used by the engine and undo history) --------------------------

    fun newLayer(name: String): Layer = Layer(nextLayerId++, name, true)

    fun newFrame(hold: Int = 1): Frame = Frame(nextFrameUid++, hold)

    fun insertFrames(index: Int, frames: List<Frame>) {
        frameList.addAll(index, frames)
        framesChanged()
    }

    fun removeFrames(index: Int, count: Int): List<Frame> {
        val range = frameList.subList(index, index + count)
        val removed = range.toList()
        range.clear()
        framesChanged()
        if (indexOfFrame(currentFrameUid) < 0) {
            currentFrameUid = frameList[index.coerceAtMost(frameList.lastIndex)].uid
        }
        return removed
    }

    /** Moves [count] frames starting at [from] so the first lands at [to] (index after removal). */
    fun moveFrames(from: Int, count: Int, to: Int) {
        val range = frameList.subList(from, from + count)
        val moved = range.toList()
        range.clear()
        frameList.addAll(to, moved)
        framesChanged()
    }

    fun reverseFrames(from: Int, count: Int) {
        frameList.subList(from, from + count).reverse()
        framesChanged()
    }

    fun setHold(frame: Frame, hold: Int) {
        frame.hold = hold.coerceIn(1, MAX_HOLD)
        framesChanged()
    }

    // ---- Audio ---------------------------------------------------------------------------

    val tracks: MutableList<SoundTrack> = mutableListOf()
    val clips: MutableList<AudioClip> = mutableListOf()

    /** Sounds stored with the project (some may only be referenced by undo history). */
    val assets: MutableList<AudioAsset> = mutableListOf()

    var nextTrackId: Long = 1
    var nextClipId: Long = 1
    var nextAssetId: Long = 1

    /**
     * When true, the animation repeats until the last sound ends (preview and video export);
     * otherwise everything stops at the end of the animation.
     */
    var loopToAudio: Boolean = false

    /** Bumped when tracks or clips change (for the UI). */
    var audioVersion = 0L
        private set

    fun touchAudio() {
        audioVersion++
    }

    fun indexOfTrack(id: Long): Int = tracks.indexOfFirst { it.id == id }
    fun indexOfClip(id: Long): Int = clips.indexOfFirst { it.id == id }
    fun asset(id: Long): AudioAsset? = assets.firstOrNull { it.id == id }

    /** Where the last (non-muted) sound ends, in ms (0 if there is none). */
    fun audioEndMs(): Long {
        val muted = tracks.filter { it.muted }.map { it.id }.toSet()
        return clips.filter { it.trackId !in muted }.maxOfOrNull { it.endMs } ?: 0L
    }

    /** Length of one pass of the animation, in ms. */
    fun animationMs(): Long = timing.totalTicks * 1000L / fps

    /** Length of the whole piece (preview / export), in ms. */
    fun totalMs(): Long = if (loopToAudio) maxOf(animationMs(), audioEndMs()) else animationMs()

    /** Ids of visible layers, bottom to top. */
    fun visibleLayerIds(): LongArray = layers.filter { it.visible }.map { it.id }.toLongArray()

    fun frameUids(): LongArray = LongArray(frameList.size) { frameList[it].uid }

    companion object {
        fun create(id: String, name: String, width: Int, height: Int): PixelProject = PixelProject(
            id = id, name = name, width = width, height = height,
            layers = listOf(Layer(1, "Layer 1")),
            frames = listOf(Frame(1)),
            activeLayerIndex = 0, currentFrameUid = 1, nextLayerId = 2, nextFrameUid = 2,
        )
    }
}

/** A named, user-editable list of colors (ARGB ints). */
data class Palette(
    val id: String,
    val name: String,
    val colors: List<Int>,
)
