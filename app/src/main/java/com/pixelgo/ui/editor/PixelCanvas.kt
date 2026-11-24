package com.pixelgo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixelgo.ui.common.drawCheckerboard
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val GuideColor = Color(0xFF00E5FF)
private val MirrorColor = Color(0xFFFF4FD8)
private val OnionPrev = Color(0xFFFF6060)
private val OnionNext = Color(0xFF60A8FF)

/**
 * The drawing surface. One finger draws; two fingers pinch-zoom and pan.
 * If a second finger lands mid-stroke the stroke is cancelled so zooming leaves no stray pixels.
 */
@Composable
fun PixelCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val vp = vm.viewport
    val artW = vm.width
    val artH = vm.height

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { vp.ensureFit(it.width, it.height, artW, artH) }
            .pointerInput(vm) {
                fun toArt(p: Offset): Pair<Int, Int> = Pair(
                    floor((p.x - vp.offset.x) / vp.scale).toInt(),
                    floor((p.y - vp.offset.y) / vp.scale).toInt(),
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var drawing = true
                    toArt(down.position).let { (x, y) -> vm.onPress(x, y) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            if (drawing) {
                                vm.onCancel()
                                drawing = false
                            }
                            vp.transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                            event.changes.forEach { it.consume() }
                        } else if (drawing) {
                            val c = event.changes.first { it.pressed }
                            toArt(c.position).let { (x, y) -> vm.onMove(x, y) }
                            c.consume()
                        }
                    }
                    if (drawing) vm.onRelease()
                }
            }
    ) {
        drawRect(Color(0xFF15151A))
        val scale = vp.scale
        if (scale <= 0f) return@Canvas
        vm.renderTick // read so the canvas redraws when pixels change

        val origin = IntOffset(vp.offset.x.roundToInt(), vp.offset.y.roundToInt())
        val dst = IntSize(max(1, (artW * scale).roundToInt()), max(1, (artH * scale).roundToInt()))
        val sx = dst.width.toFloat() / artW
        val sy = dst.height.toFloat() / artH
        val rect = Rect(Offset(origin.x.toFloat(), origin.y.toFloat()), Size(dst.width.toFloat(), dst.height.toFloat()))
        val screen = Rect(Offset.Zero, size)

        // Transparency checkerboard: 2x2 cells per art pixel when zoomed in (so it doesn't look
        // like the pixel grid), otherwise a fixed size.
        drawCheckerboard(rect, if (sx >= 24f) sx / 2f else 12f, screen)
        val playing = vm.isPlaying
        if (playing) {
            vm.playImage?.let {
                drawImage(it, dstOffset = origin, dstSize = dst, filterQuality = FilterQuality.None)
            }
        } else {
            // Onion skin: previous frame tinted red, next frame tinted blue, under the current one.
            vm.onionPrev?.let {
                drawImage(
                    it, dstOffset = origin, dstSize = dst, alpha = 0.35f,
                    colorFilter = ColorFilter.tint(OnionPrev, BlendMode.Modulate),
                    filterQuality = FilterQuality.None,
                )
            }
            vm.onionNext?.let {
                drawImage(
                    it, dstOffset = origin, dstSize = dst, alpha = 0.25f,
                    colorFilter = ColorFilter.tint(OnionNext, BlendMode.Modulate),
                    filterQuality = FilterQuality.None,
                )
            }
            drawImage(vm.image, dstOffset = origin, dstSize = dst, filterQuality = FilterQuality.None)
        }

        val visible = rect.intersect(screen)
        if (!visible.isEmpty) {
            // Pixel grid
            if (vm.showGrid && sx >= 6f) {
                val c0 = max(0, floor((visible.left - rect.left) / sx).toInt())
                val c1 = min(artW, ceil((visible.right - rect.left) / sx).toInt())
                val r0 = max(0, floor((visible.top - rect.top) / sy).toInt())
                val r1 = min(artH, ceil((visible.bottom - rect.top) / sy).toInt())
                for (c in c0..c1) {
                    val x = rect.left + c * sx
                    val major = c % 8 == 0
                    drawLine(
                        if (major) Color(0x80000000) else Color(0x40000000),
                        Offset(x, visible.top), Offset(x, visible.bottom), 1f,
                    )
                }
                for (r in r0..r1) {
                    val y = rect.top + r * sy
                    val major = r % 8 == 0
                    drawLine(
                        if (major) Color(0x80000000) else Color(0x40000000),
                        Offset(visible.left, y), Offset(visible.right, y), 1f,
                    )
                }
            }

            val cx = rect.left + artW / 2f * sx
            val cy = rect.top + artH / 2f * sy
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            // Mirror axes (dashed) so it's clear symmetry is on.
            if (vm.mirrorX) drawLine(MirrorColor, Offset(cx, visible.top), Offset(cx, visible.bottom), 3f, pathEffect = dash)
            if (vm.mirrorY) drawLine(MirrorColor, Offset(visible.left, cy), Offset(visible.right, cy), 3f, pathEffect = dash)
            // Center guides (solid)
            if (vm.guideVertical) drawLine(GuideColor, Offset(cx, visible.top), Offset(cx, visible.bottom), 2f)
            if (vm.guideHorizontal) drawLine(GuideColor, Offset(visible.left, cy), Offset(visible.right, cy), 2f)
        }

        drawRect(Color(0x66FFFFFF), rect.topLeft, rect.size, style = Stroke(1.5f))

        // Floating (moving / pasted) cells
        val f = vm.engine?.floating
        val fImg = vm.floatingImage
        if (playing) {
            // no overlays while previewing
        } else if (f != null && fImg != null) {
            val fx = rect.left + f.x * sx
            val fy = rect.top + f.y * sy
            drawImage(
                fImg,
                dstOffset = IntOffset(fx.roundToInt(), fy.roundToInt()),
                dstSize = IntSize(
                    max(1, (f.clip.width * sx).roundToInt()),
                    max(1, (f.clip.height * sy).roundToInt()),
                ),
                filterQuality = FilterQuality.None,
            )
            drawEdges(vm.floatingEdges, fx, fy, sx, sy)
        } else if (vm.selectionEdges.isNotEmpty()) {
            drawEdges(vm.selectionEdges, rect.left, rect.top, sx, sy)
        }
    }
}

private val AntsDash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))

/** "Marching ants" outline: segments are (x0,y0,x1,y1) groups in cell-edge coordinates. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdges(
    edges: IntArray, ox: Float, oy: Float, sx: Float, sy: Float,
) {
    var k = 0
    while (k + 3 < edges.size) {
        val a = Offset(ox + edges[k] * sx, oy + edges[k + 1] * sy)
        val b = Offset(ox + edges[k + 2] * sx, oy + edges[k + 3] * sy)
        drawLine(Color.Black, a, b, 3f)
        drawLine(Color.White, a, b, 3f, pathEffect = AntsDash)
        k += 4
    }
}
