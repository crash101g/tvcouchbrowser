package app.tvcouchbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Draws the on-screen pointer used in cursor mode. Position is in this view's pixels. */
class CursorView(context: Context) : View(context) {
    var cx = 0f
        private set
    var cy = 0f
        private set

    private val d = resources.displayMetrics.density
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 26 * d); lineTo(7 * d, 20 * d); lineTo(12 * d, 31 * d)
        lineTo(17 * d, 29 * d); lineTo(12 * d, 18 * d); lineTo(21 * d, 18 * d); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.FILL }

    init {
        isFocusable = false
        setWillNotDraw(false)
    }

    fun moveTo(x: Float, y: Float) {
        cx = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cy = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    fun center() = moveTo(width / 2f, height / 2f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw == 0) moveTo(w / 2f, h / 2f) else moveTo(cx, cy)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(cx + 2 * d, cy + 3 * d); canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.save()
        canvas.translate(cx, cy); canvas.drawPath(arrow, stroke); canvas.drawPath(arrow, fill)
        canvas.restore()
    }
}
