package dev.andrcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.SeekBar

object Palette {
    val background = Color.rgb(16, 21, 19)
    val surface = Color.rgb(25, 32, 28)
    val raised = Color.rgb(33, 41, 36)
    val border = Color.rgb(48, 59, 51)
    val text = Color.rgb(237, 242, 236)
    val secondary = Color.rgb(155, 171, 159)
    val mint = Color.rgb(184, 232, 195)
    val amber = Color.rgb(237, 194, 128)
    val darkMint = Color.rgb(44, 64, 49)
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

/** Keeps SeekBar's touch, keyboard and accessibility range behavior. */
class PercentSlider @JvmOverloads constructor(context: Context, limit: Int = 200) : SeekBar(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    init {
        max = limit
        splitTrack = false
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        thumb = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize(context.dp(28), context.dp(28))
        }
        thumbOffset = context.dp(14)
        progressDrawable = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        minimumHeight = context.dp(56)
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val y = height / 2f
        val total = right - left
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        fun x(value: Float) = if (rtl) right - total * value else left + total * value
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8 * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Palette.border
        canvas.drawLine(left, y, right, y, paint)
        if (max == 200) {
            paint.color = Color.rgb(67, 57, 40)
            canvas.drawLine(x(0.5f), y, x(1f), y, paint)
        }
        val fraction = progress.toFloat() / max
        if (progress > 0) {
            paint.color = Palette.mint
            canvas.drawLine(x(0f), y, x(if (max == 200) fraction.coerceAtMost(.5f) else fraction), y, paint)
            if (max == 200 && fraction > .5f) {
                paint.color = Palette.amber
                canvas.drawLine(x(.5f), y, x(fraction), y, paint)
            }
        }
        if (max == 200) {
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeWidth = 2 * density
            paint.color = Palette.background
            canvas.drawLine(x(.5f), y - 4 * density, x(.5f), y + 4 * density, paint)
        }
        val thumbX = x(fraction)
        paint.style = Paint.Style.FILL
        paint.color = if (progress > 100 && max == 200) Palette.amber else Palette.mint
        if (isPressed || isFocused) {
            paint.alpha = 35
            canvas.drawCircle(thumbX, y, 22 * density, paint)
            paint.alpha = 255
        }
        canvas.drawCircle(thumbX, y, 11 * density, paint)
        paint.color = Palette.surface
        canvas.drawCircle(thumbX, y, 3 * density, paint)
    }
}

/** Small, consistent vector icons drawn locally; no image or network dependency. */
class MixerIcon @JvmOverloads constructor(context: Context, var kind: String = "mixer", var tint: Int = Palette.mint) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        val side = minOf(contentWidth, contentHeight).toFloat().coerceAtLeast(0f)
        canvas.translate(paddingLeft + (contentWidth - side) / 2, paddingTop + (contentHeight - side) / 2)
        canvas.scale(side / 24, side / 24)
        paint.color = tint
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        fun path(vararg points: Float) {
            val p = iconPath.apply {
                reset()
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
            }
            canvas.drawPath(p, paint)
        }
        when (kind) {
            "mixer" -> {
                line(5f, 3f, 5f, 21f); line(12f, 3f, 12f, 21f); line(19f, 3f, 19f, 21f)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(2f, 7f, 8f, 11f, 1f, 1f, paint)
                canvas.drawRoundRect(9f, 14f, 15f, 18f, 1f, 1f, paint)
                canvas.drawRoundRect(16f, 5f, 22f, 9f, 1f, 1f, paint)
            }
            "volume", "mute" -> {
                path(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, 3f, 9f)
                if (kind == "volume") {
                    canvas.drawArc(9f, 7f, 19f, 17f, -52f, 104f, false, paint)
                    canvas.drawArc(6f, 3f, 23f, 21f, -49f, 98f, false, paint)
                } else { line(17f, 9f, 22f, 15f); line(22f, 9f, 17f, 15f) }
            }
            "bolt" -> path(14f, 2f, 5f, 13f, 11f, 13f, 10f, 22f, 20f, 10f, 13f, 10f, 14f, 2f)
            "bell" -> {
                path(5f, 17f, 7f, 14f, 7f, 9f)
                canvas.drawArc(7f, 4f, 17f, 14f, 180f, 180f, false, paint)
                path(17f, 9f, 17f, 14f, 19f, 17f, 5f, 17f)
                canvas.drawArc(10f, 17f, 14f, 21f, 0f, 180f, false, paint)
                line(12f, 2f, 12f, 4f)
            }
            "alarm" -> {
                canvas.drawCircle(12f, 13f, 7f, paint)
                path(12f, 9f, 12f, 13f, 15f, 15f)
                line(4f, 5f, 7f, 3f); line(17f, 3f, 20f, 5f)
                line(6f, 19f, 4f, 21f); line(18f, 19f, 20f, 21f)
            }
            "music" -> {
                path(9f, 17f, 9f, 5f, 19f, 3f, 19f, 15f)
                canvas.drawOval(3f, 15f, 9f, 20f, paint)
                canvas.drawOval(13f, 13f, 19f, 18f, paint)
            }
            "headphones" -> {
                canvas.drawArc(4f, 3f, 20f, 20f, 180f, 180f, false, paint)
                canvas.drawRoundRect(3f, 11f, 7f, 20f, 2f, 2f, paint)
                canvas.drawRoundRect(17f, 11f, 21f, 20f, 2f, 2f, paint)
            }
            "help" -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawArc(9f, 6f, 15f, 12f, 180f, 255f, false, paint)
                line(12f, 12f, 12f, 14f); canvas.drawPoint(12f, 18f, paint)
            }
            "chevron" -> path(9f, 6f, 15f, 12f, 9f, 18f)
            "plus" -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            "minus" -> line(5f, 12f, 19f, 12f)
            "play" -> { paint.style = Paint.Style.FILL; path(8f, 4f, 20f, 12f, 8f, 20f, 8f, 4f) }
            "stop" -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint) }
        }
        canvas.restore()
    }
}
