package com.droiddeck.launcher.frontend

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the two 3DS screens go, in pixels of a [width] x [height] display; droiddeck-pad-defaults fits it to the session. */
data class ScreenRects(val width: Int, val height: Int, val top: Box, val bottom: Box) {
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    enum class Screen(val aspect: Float) { TOP(400f / 240f), BOTTOM(320f / 240f) }

    enum class Preset { SIDE_BY_SIDE, STACKED, LARGE_TOP }

    fun box(screen: Screen) = if (screen == Screen.TOP) top else bottom

    private fun with(screen: Screen, box: Box) = if (screen == Screen.TOP) copy(top = box) else copy(bottom = box)

    fun format() = listOf(width, height, top.x, top.y, top.w, top.h, bottom.x, bottom.y, bottom.w, bottom.h).joinToString(",")

    fun moved(screen: Screen, dx: Int, dy: Int): ScreenRects {
        val b = box(screen)
        return with(screen, b.copy(x = (b.x + dx).coerceIn(0, width - b.w), y = (b.y + dy).coerceIn(0, height - b.h)))
    }

    fun placed(screen: Screen, x: Int, y: Int): ScreenRects = box(screen).let { moved(screen, x - it.x, y - it.y) }

    /** [screen] at [w] pixels wide with its own shape, kept on the display and anchored at its top left where it fits. */
    fun resized(screen: Screen, w: Int): ScreenRects {
        val b = box(screen)
        val most = min(width.toFloat(), height * screen.aspect)
        val newW = w.toFloat().coerceIn(min(MIN_WIDTH.toFloat(), most), most)
        val newH = newW / screen.aspect
        val x = b.x.coerceAtMost((width - newW).roundToInt())
        val y = b.y.coerceAtMost((height - newH).roundToInt())
        return with(screen, Box(max(0, x), max(0, y), newW.roundToInt(), newH.roundToInt()))
    }

    fun scaled(screen: Screen, factor: Float) = resized(screen, (box(screen).w * factor).roundToInt())

    companion object {
        const val LAYOUT = "6"
        val PATTERN = Regex("""\d{1,5}(,\d{1,5}){9}""")
        private const val MIN_WIDTH = 80

        fun parse(value: String?): ScreenRects? {
            if (value == null || !PATTERN.matches(value)) return null
            val v = value.split(',').map { it.toInt() }
            val rects = ScreenRects(v[0], v[1], Box(v[2], v[3], v[4], v[5]), Box(v[6], v[7], v[8], v[9]))
            return rects.takeIf { r -> r.width > 0 && r.height > 0 && listOf(r.top, r.bottom).all { it.w > 0 && it.h > 0 } }
        }

        fun preset(width: Int, height: Int, preset: Preset): ScreenRects {
            val w = width.toFloat()
            val h = height.toFloat()
            fun box(x: Float, y: Float, bw: Float, bh: Float) = Box(x.roundToInt(), y.roundToInt(), bw.roundToInt(), bh.roundToInt())
            return when (preset) {
                Preset.SIDE_BY_SIDE -> {
                    val sh = min(h, w / (Screen.TOP.aspect + Screen.BOTTOM.aspect))
                    val tw = sh * Screen.TOP.aspect
                    val bw = sh * Screen.BOTTOM.aspect
                    val left = (w - tw - bw) / 2
                    val y = (h - sh) / 2
                    ScreenRects(width, height, box(left, y, tw, sh), box(left + tw, y, bw, sh))
                }
                Preset.STACKED -> {
                    val unit = min(w / 400f, h / 480f)
                    val y = (h - 480f * unit) / 2
                    ScreenRects(width, height, box((w - 400f * unit) / 2, y, 400f * unit, 240f * unit),
                        box((w - 320f * unit) / 2, y + 240f * unit, 320f * unit, 240f * unit))
                }
                Preset.LARGE_TOP -> {
                    val tw = min(h * Screen.TOP.aspect, w * .78f)
                    val th = tw / Screen.TOP.aspect
                    val bw = max(w - tw, w * .22f)
                    val bh = bw / Screen.BOTTOM.aspect
                    ScreenRects(width, height, box(0f, (h - th) / 2, tw, th), box(w - bw, h - bh, bw, bh))
                }
            }
        }

        fun default(width: Int, height: Int) = preset(width, height, if (width >= height) Preset.SIDE_BY_SIDE else Preset.STACKED)
    }
}
