package io.github.yahiaangelo.filmsimulator.frame

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Composites frame + caption + date stamp around an already processed JPEG. */
object FrameRenderer {

    /** Returns [photo] untouched when [spec] has nothing to draw. */
    fun render(photo: ByteArray, spec: FrameSpec, quality: Int): ByteArray {
        if (!spec.isActive) return photo
        val image = runCatching { Image.makeFromEncoded(photo) }.getOrNull() ?: return photo
        val w = image.width
        val h = image.height
        val t = spec.thickness.coerceIn(0.5f, 2f)
        val hasCaption = spec.caption.isNotBlank()

        var pad = 0f
        var bottom = 0f
        when (spec.style) {
            FrameStyle.NONE -> {}
            FrameStyle.THIN -> { pad = 8f / 280f; bottom = 8f / 280f }
            FrameStyle.MATTE -> { pad = 26f / 280f; bottom = 62f / 280f }
            FrameStyle.POLAROID -> { pad = 14f / 280f; bottom = 76f / 280f }
            FrameStyle.FILM -> { pad = 24f / 280f; bottom = 40f / 280f }
        }
        var padPx = (pad * w * t).roundToInt()
        var bottomPx = (bottom * w * t).roundToInt()
        if (hasCaption && bottomPx < padPx + (0.11f * w).roundToInt()) {
            bottomPx = padPx + (0.11f * w).roundToInt()
        }
        if (spec.style == FrameStyle.NONE) {
            padPx = 0
            if (!hasCaption) bottomPx = 0
        }

        val outW = w + 2 * padPx
        val outH = h + padPx + bottomPx
        val surface = Surface.makeRaster(ImageInfo(outW, outH, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        val canvas = surface.canvas

        val frameColor = if (spec.style == FrameStyle.FILM) 0xFF0E0E0E.toInt() else spec.color
        canvas.drawRect(
            Rect(0f, 0f, outW.toFloat(), outH.toFloat()),
            Paint().apply { color = frameColor },
        )
        canvas.drawImageRect(
            image,
            Rect(0f, 0f, w.toFloat(), h.toFloat()),
            Rect(padPx.toFloat(), padPx.toFloat(), (padPx + w).toFloat(), (padPx + h).toFloat()),
        )

        if (spec.style == FrameStyle.FILM) drawSprockets(canvas, outW, outH, padPx, bottomPx)

        if (hasCaption) {
            val stripTop = (padPx + h).toFloat()
            val stripH = bottomPx.toFloat()
            val ink = if (spec.style == FrameStyle.FILM && spec.inkColor == InkColors[0]) InkColors[4] else spec.inkColor
            drawCaption(canvas, spec, ink, outW.toFloat(), stripTop, stripH, padPx.toFloat())
        }

        if (spec.dateStamp) {
            val size = max(12f, w * 0.032f)
            val font = Font(typeface("monospace", FontStyle.BOLD), size)
            val paint = Paint().apply { color = 0xFFFF8A1F.toInt(); isAntiAlias = true }
            val text = dateText()
            val tw = font.measureTextWidth(text)
            canvas.drawString(
                text,
                (padPx + w) - tw - size,
                (padPx + h) - size,
                font,
                paint,
            )
        }

        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: photo
    }

    private fun typeface(family: String, style: FontStyle): Typeface? =
        runCatching { FontMgr.default.matchFamilyStyle(family, style) }.getOrNull()

    private fun drawSprockets(canvas: org.jetbrains.skia.Canvas, outW: Int, outH: Int, pad: Int, bottom: Int) {
        val paint = Paint().apply { color = 0xFFE9E4D3.toInt(); isAntiAlias = true }
        val hole = max(3f, pad * 0.32f)
        val gap = hole * 2.2f
        val y1 = pad * 0.34f
        var x = gap
        while (x < outW - gap) {
            canvas.drawRect(Rect(x, y1, x + hole, y1 + hole * 0.8f), paint)
            canvas.drawRect(Rect(x, outH - hole * 1.4f, x + hole, outH - hole * 0.6f), paint)
            x += gap
        }
    }

    private fun drawCaption(
        canvas: org.jetbrains.skia.Canvas,
        spec: FrameSpec,
        ink: Int,
        outW: Float,
        stripTop: Float,
        stripH: Float,
        pad: Float,
    ) {
        val maxWidth = outW - 2 * max(pad, outW * 0.04f)
        var size = stripH * 0.46f
        val (family, style) = when (spec.penFont) {
            PenFont.HAND -> "cursive" to FontStyle.ITALIC
            PenFont.SERIF -> "serif" to FontStyle.ITALIC
            PenFont.TYPEWRITER -> "monospace" to FontStyle.NORMAL
        }
        val face = typeface(family, style)
        var font = Font(face, size)
        if (spec.penFont != PenFont.TYPEWRITER) font.skewX = -0.18f
        var tw = font.measureTextWidth(spec.caption)
        if (tw > maxWidth) {
            size *= maxWidth / tw
            font = Font(face, size)
            if (spec.penFont != PenFont.TYPEWRITER) font.skewX = -0.18f
            tw = font.measureTextWidth(spec.caption)
        }
        val baseline = stripTop + stripH / 2f + size * 0.28f
        val x = (outW - tw) / 2f

        canvas.save()
        canvas.rotate(-1.5f, outW / 2f, stripTop + stripH / 2f)
        val paint = Paint().apply { color = ink; isAntiAlias = true }
        canvas.drawString(spec.caption, x, baseline, font, paint)
        if (spec.penFont == PenFont.HAND) {
            // Nacisk pióra: drugi, minimalnie przesunięty przebieg + lekkie rozlanie atramentu.
            val nib = Paint().apply {
                color = (ink and 0x00FFFFFF) or 0x66000000
                isAntiAlias = true
            }
            canvas.drawString(spec.caption, x + size * 0.025f, baseline + size * 0.012f, font, nib)
        }
        canvas.restore()
    }

    @OptIn(ExperimentalTime::class)
    private fun dateText(): String {
        val days = Clock.System.now().epochSeconds.floorDiv(86400L)
        // Hinnant civil_from_days
        val z = days + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y0 = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = if (m <= 2) y0 + 1 else y0
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "'${two(y % 100)} ${two(m)} ${two(d)}"
    }
}
