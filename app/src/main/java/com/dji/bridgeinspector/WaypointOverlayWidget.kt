package com.dji.bridgeinspector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.component1
import androidx.core.graphics.component2

class WaypointOverlayWidget @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.FILL
        color = Color.RED
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 12f
        style = Paint.Style.STROKE
        color = Color.RED

    }

    private var pos: PointF? = null      // (u,v) in screen space
    private var r: Float = 0F   // radius of waypoint

    /** Call from MainActivity each time you have a new projected point. */
    fun update(point: PointF?, radius: Float) {
        pos = point               // can be null when no active waypoint
        r = radius
        invalidate()              // schedule a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val p = pos ?: return     // nothing to draw -> return early

        val (x, y) = p
        // simple cross-hair, 30 px wide
        canvas.drawCircle(x, y, r, paint)
//        canvas.drawLine(x, y - 15, x, y + 15, paint)
        // optional text
//        canvas.drawText("HELLO WORLD", x + 20, y - 20, paintText)
    }


}