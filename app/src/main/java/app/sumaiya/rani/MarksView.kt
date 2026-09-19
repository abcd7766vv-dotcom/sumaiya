package app.sumaiya.rani

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Mark(val x: Float, val y: Float, val label: String)

class MarksView(context: Context) : View(context) {
    var marks: List<Mark> = emptyList()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = max(24f, min(w, h) * 0.05f)
        val red = Color.parseColor("#E5192B")
        stroke.style = Paint.Style.STROKE
        stroke.color = red
        stroke.strokeWidth = max(5f, r / 5f)
        stroke.strokeCap = Paint.Cap.ROUND
        fill.style = Paint.Style.FILL
        fill.color = red
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = true
        text.textSize = r * 0.9f

        marks.forEachIndexed { i, m ->
            val cx = max(r, min(w - r, m.x * w))
            val cy = max(r, min(h - r, m.y * h))
            val sx = max(r, min(w - r, cx + (if (cx < w / 2f) 1f else -1f) * r * 3.2f))
            val rawSy = if (cy - r * 3.2f >= r) cy - r * 3.2f else cy + r * 3.2f
            val sy = max(r, min(h - r, rawSy))
            val ang = atan2(cy - sy, cx - sx)
            val ex = cx - cos(ang) * r * 1.15f
            val ey = cy - sin(ang) * r * 1.15f

            canvas.drawLine(sx, sy, ex, ey, stroke)
            val ah = r * 0.7f
            val p = Path()
            p.moveTo(ex, ey)
            p.lineTo(ex - cos(ang - 0.5f) * ah, ey - sin(ang - 0.5f) * ah)
            p.lineTo(ex - cos(ang + 0.5f) * ah, ey - sin(ang + 0.5f) * ah)
            p.close()
            canvas.drawPath(p, fill)
            canvas.drawCircle(cx, cy, r, stroke)
            canvas.drawCircle(sx, sy, r * 0.55f, fill)
            canvas.drawText((i + 1).toString(), sx, sy + r * 0.3f, text)
        }
    }
}
