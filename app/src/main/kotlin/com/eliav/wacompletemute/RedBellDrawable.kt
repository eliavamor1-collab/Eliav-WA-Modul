package com.eliav.wacompletemute

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * תג פעמון אדום על רקע לבן עגול. מצויר בקוד (בלי משאבים) כי המודול רץ בתוך תהליך WhatsApp
 * ושם אין גישה למשאבי ה-APK של המודול. צורת הפעמון: Material "notifications" (Apache 2.0), רשת 24x24.
 */
class RedBellDrawable : Drawable() {

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.STROKE
    }
    private val bellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }

    private val bell = Path().apply {
        // פעמון: ידית (clapper)
        moveTo(12f, 22f)
        cubicTo(13.1f, 22f, 14f, 21.1f, 14f, 20f)
        lineTo(10f, 20f)
        cubicTo(10f, 21.1f, 10.89f, 22f, 12f, 22f)
        close()
        // גוף הפעמון
        moveTo(18f, 16f)
        lineTo(18f, 11f)
        cubicTo(18f, 7.93f, 16.36f, 5.36f, 13.5f, 4.68f)
        lineTo(13.5f, 4f)
        cubicTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f)
        cubicTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f)
        lineTo(10.5f, 4.68f)
        cubicTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f)
        lineTo(6f, 16f)
        lineTo(4f, 18f)
        lineTo(4f, 19f)
        lineTo(20f, 19f)
        lineTo(20f, 18f)
        lineTo(18f, 16f)
        close()
    }
    private val matrix = Matrix()
    private val scaled = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = size / 2f

        canvas.drawCircle(cx, cy, r, badgePaint)
        ringPaint.strokeWidth = maxOf(1f, size / 20f)
        canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth / 2f, ringPaint)

        val inner = size * 0.72f
        val scale = inner / 24f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(cx - inner / 2f, cy - inner / 2f)
        bell.transform(matrix, scaled)
        canvas.drawPath(scaled, bellPaint)
    }

    override fun setAlpha(alpha: Int) {
        badgePaint.alpha = alpha
        ringPaint.alpha = alpha
        bellPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bellPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
