package com.pixelgo.data.video

import kotlin.math.max
import kotlin.math.min

/** Output size presets (by the length of the short side). */
enum class VideoSize(val label: String, val shortSide: Int) {
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    P2160("4K", 2160),
}

/** Frame shape: the canvas's own shape, or a fixed aspect ratio padded with the background. */
enum class VideoShape(val label: String, val ratioW: Int, val ratioH: Int) {
    CANVAS("Canvas", 0, 0),
    WIDE("16:9", 16, 9),
    TALL("9:16", 9, 16),
    SQUARE("1:1", 1, 1),
}

/**
 * Where the art goes in the video frame. [scale] is a whole, EVEN number so every art pixel
 * covers whole 2×2 colour blocks (H.264 stores colour at half resolution), which keeps edges
 * crisp. Sizes and offsets are even.
 */
data class VideoLayout(
    val width: Int,
    val height: Int,
    val scale: Int,
    val offsetX: Int,
    val offsetY: Int,
) {
    val contentWidth get() = width - 2 * offsetX
    val contentHeight get() = height - 2 * offsetY

    companion object {
        fun compute(artW: Int, artH: Int, size: VideoSize, shape: VideoShape): VideoLayout {
            val s = size.shortSide
            // The box the video should roughly fill.
            val (boxW, boxH) = if (shape == VideoShape.CANVAS) {
                if (artW >= artH) (s.toLong() * artW / artH).toInt() to s else s to (s.toLong() * artH / artW).toInt()
            } else {
                if (shape.ratioW >= shape.ratioH) s * shape.ratioW / shape.ratioH to s else s to s * shape.ratioH / shape.ratioW
            }
            // Largest even scale that fits; at least 2 (then the video grows past the box).
            var scale = min(boxW / artW, boxH / artH)
            if (scale % 2 == 1) scale -= 1
            scale = max(scale, 2)
            val cw = artW * scale
            val ch = artH * scale
            val w = even(if (shape == VideoShape.CANVAS) cw else max(boxW, cw))
            val h = even(if (shape == VideoShape.CANVAS) ch else max(boxH, ch))
            return VideoLayout(w, h, scale, evenDown((w - cw) / 2), evenDown((h - ch) / 2))
        }

        /** Same art placement inside a bigger frame (e.g. when the encoder needs ×16 sizes). */
        fun VideoLayout.padTo(newW: Int, newH: Int): VideoLayout {
            val cw = contentWidth
            val ch = contentHeight
            return VideoLayout(newW, newH, scale, evenDown((newW - cw) / 2), evenDown((newH - ch) / 2))
        }

        fun roundUp16(v: Int) = (v + 15) / 16 * 16
        private fun even(v: Int) = v + (v and 1)
        private fun evenDown(v: Int) = v - (v and 1)
    }
}
