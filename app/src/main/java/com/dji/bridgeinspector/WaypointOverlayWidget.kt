package com.dji.bridgeinspector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.filament.utils.degrees
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class WaypointOverlayWidget @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    // Arrow paints
    private val paintArrowBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED // red color
    }
    private val paintArrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
        color = Color.BLACK // Black outline
    }

    // Lane paints
    private val paintLaneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 200, 80) // Transparent Green
        style = Paint.Style.FILL
    }

    private val paintLaneBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 0, 100, 40) // Darker green
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintLaneCenterline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 255, 255) // Semi-transparent white
        strokeWidth = 8f
    }
    private var dashPhase = 0f

    private val laneFillPath = Path()
    private val laneCenterlinePath = Path()
    private val arrowPath = Path() // Re-used path for the arrow

    // Waypoint paints
    private val paintWaypointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED // Simple red circle
    }
    private val paintWaypointStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 5f
    }
    private val paintWaypointPulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    // Waypoint settings
    private val MIN_DRAW_DISTANCE_METERS = 2.5
    private val SHOW_DISTANCE_TEXT_THRESHOLD = 50.0
    private val ARROW_HEAD_LENGTH = 230f
    private val ARROW_MARGIN = ARROW_HEAD_LENGTH + 20f
    private val LANE_START_WIDTH_PERCENT = 0.3f // 30%
    private val LANE_END_WIDTH_OFFSCREEN_PX = 60f

    private data class ClampedEdgeResult(
        val x: Float,
        val y: Float,
        val angle: Float
    )

    private var projection: ProjectionResult? = null

    init {
        // Arrow shape
        arrowPath.apply {
            moveTo(0f, 0f)
            lineTo(-ARROW_HEAD_LENGTH, -ARROW_HEAD_LENGTH * 0.6f)
            lineTo(-ARROW_HEAD_LENGTH * 0.7f, 0f)
            lineTo(-ARROW_HEAD_LENGTH, ARROW_HEAD_LENGTH * 0.6f)
            close()
        }
    }

    fun update(result: ProjectionResult?) {
        projection = result
        invalidate() // Schedule a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Get snapshot of properties
        val proj = projection
        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        if (screenWidth == 0f || screenHeight == 0f) {
            // View not initialized, but keep animation loop alive
            invalidate()
            return
        }

        val centerX = screenWidth / 2
        val centerY = screenHeight / 2

        // We only draw if we have a valid projection and are not too close
        if (proj != null && proj.distanceToTarget > MIN_DRAW_DISTANCE_METERS) {

            if (proj.screenCoords != null) { // Case 1 & 2: IN-FRONT
                val x = proj.screenCoords.u.toFloat()
                val y = proj.screenCoords.v.toFloat()
                val r = proj.screenCoords.radius.toFloat()

                val buffer = 20f
                val isVisible = (x + r > -buffer && x - r < screenWidth + buffer &&
                        y + r > -buffer && y - r < screenHeight + buffer)

                if (isVisible) {
                    // Case 1: visible, on screen
                    val LANE_GROUND_OFFSET_PX = 150f
                    val laneY = (y + LANE_GROUND_OFFSET_PX).coerceAtMost(screenHeight - 1f)

                    drawGuidingLane(canvas, x, laneY, screenWidth, screenHeight, true, r)
                    drawBeaconWaypoint(canvas, x, y, r, proj.distanceToTarget)
                } else {
                    // Case 2: Off screen, to the sides
                    val dx = x - centerX
                    val dy = y - centerY
                    val result = calculateClampedEdgePoint(centerX, centerY, dx, dy, screenWidth, screenHeight) ?: return

                    drawGuidingLane(canvas, result.x, result.y, screenWidth, screenHeight, false, null)
                    drawArrowAt(canvas, result)
                }

            } else { // Case 3: BEHIND
                val cp = proj.pointInCameraFrame
                val targetDx = cp.x.toFloat()
                val targetDy = cp.y.toFloat()
                val result = calculateClampedEdgePoint(centerX, centerY, targetDx, targetDy, screenWidth, screenHeight) ?: return

                drawGuidingLane(canvas, result.x, result.y, screenWidth, screenHeight, false, null)
                drawArrowAt(canvas, result)
            }
        }

        invalidate()
    }

    private fun drawGuidingLane(
        canvas: Canvas,
        targetX: Float,
        targetY: Float,
        screenWidth: Float,
        screenHeight: Float,
        isTargetOnScreen: Boolean,
        beaconRadius: Float?
    ) {
        val centerX = screenWidth / 2
        val startWidth = screenWidth * LANE_START_WIDTH_PERCENT

        laneFillPath.reset()
        laneCenterlinePath.reset()

        if (isTargetOnScreen) {
            val endWidth = (beaconRadius ?: 50f) * 2.2f
            val p0x = centerX
            val p0y = screenHeight
            val p3x = targetX
            val p3y = targetY
            val yLerp = 0.4f
            val cp1x = p0x
            val cp1y = p0y - (p0y - p3y) * yLerp
            val cp2x = p3x
            val cp2y = p3y + (p0y - p3y) * yLerp
            val p0Lx = p0x - startWidth / 2
            val p0Rx = p0x + startWidth / 2
            val p3Lx = p3x - endWidth / 2
            val p3Rx = p3x + endWidth / 2
            val cp1Lx = cp1x - startWidth / 2
            val cp1Rx = cp1x + startWidth / 2
            val cp2Lx = cp2x - endWidth / 2
            val cp2Rx = cp2x + endWidth / 2
            laneFillPath.moveTo(p0Lx, p0y)
            laneFillPath.cubicTo(cp1Lx, cp1y, cp2Lx, cp2y, p3Lx, p3y)
            laneFillPath.lineTo(p3Rx, p3y)
            laneFillPath.cubicTo(cp2Rx, cp2y, cp1Rx, cp1y, p0Rx, p0y)
            laneFillPath.close()
            laneCenterlinePath.moveTo(p0x, p0y)
            laneCenterlinePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p3x, p3y)
        } else {
            val endWidth = LANE_END_WIDTH_OFFSCREEN_PX
            val p0Lx = centerX - startWidth / 2
            val p0Rx = centerX + startWidth / 2
            val p1Lx = targetX - endWidth / 2
            val p1Rx = targetX + endWidth / 2
            val cpxL = p0Lx
            val cpxR = p0Rx
            val cpy = targetY
            laneFillPath.moveTo(p0Lx, screenHeight)
            laneFillPath.quadTo(cpxL, cpy, p1Lx, targetY)
            laneFillPath.lineTo(p1Rx, targetY)
            laneFillPath.quadTo(cpxR, cpy, p0Rx, screenHeight)
            laneFillPath.close()
            laneCenterlinePath.moveTo(centerX, screenHeight)
            laneCenterlinePath.quadTo(centerX, targetY, targetX, targetY)
        }

        // Draw the green lane
        canvas.drawPath(laneFillPath, paintLaneFill)

        // Draw the border at the end of the lane
        canvas.drawPath(laneFillPath, paintLaneBorder)

        // Draw animated center line
        val cycleTime = System.currentTimeMillis() % 900L
        dashPhase = (cycleTime / 15f)

        paintLaneCenterline.pathEffect = DashPathEffect(floatArrayOf(30f, 30f), -dashPhase)
        canvas.drawPath(laneCenterlinePath, paintLaneCenterline)
    }

    // Draws the pulsing beacon waypoint and distance text
    private fun drawBeaconWaypoint(canvas: Canvas, x: Float, y: Float, r: Float, distance: Double) {
        val pulse = (sin(System.currentTimeMillis() / 350.0) + 1) / 2.0
        val pulseRadius = r * (1.1f + pulse * 0.6f).toFloat()
        paintWaypointPulse.alpha = (180 * (1.0 - pulse)).toInt()

        canvas.drawCircle(x, y, r, paintWaypointFill)
        canvas.drawCircle(x, y, r, paintWaypointStroke)
        canvas.drawCircle(x, y, pulseRadius, paintWaypointPulse)

        // Draws distance under waypoint
        if (distance < SHOW_DISTANCE_TEXT_THRESHOLD) {
            val distanceText = "%.1fm".format(distance)
            val textY = y + r + paintText.textSize + 10f
            canvas.drawText(distanceText, x, textY, paintText)
        }
    }


    // Calculates where arrow should point when waypoint is of screen
    private fun calculateClampedEdgePoint(
        centerX: Float, centerY: Float,
        targetDx: Float, targetDy: Float,
        screenW: Float, screenH: Float
    ): ClampedEdgeResult? {

        if (targetDx == 0f && targetDy == 0f) return null

        val dx = if (abs(targetDx) < 1e-6f) 1e-6f else targetDx
        val dy = if (abs(targetDy) < 1e-6f) 1e-6f else targetDy

        var minT = Float.MAX_VALUE

        val tRight = (screenW - ARROW_MARGIN - centerX) / dx
        val tLeft = (ARROW_MARGIN - centerX) / dx
        val tBottom = (screenH - ARROW_MARGIN - centerY) / dy
        val tTop = (ARROW_MARGIN - centerY) / dy

        if (tRight > 0) minT = minOf(minT, tRight)
        if (tLeft > 0) minT = minOf(minT, tLeft)
        if (tBottom > 0) minT = minOf(minT, tBottom)
        if (tTop > 0) minT = minOf(minT, tTop)

        if (minT == Float.MAX_VALUE) return null

        val clampedX = centerX + minT * dx
        val clampedY = centerY + minT * dy
        val angle = atan2(targetDy, targetDx)

        return ClampedEdgeResult(clampedX, clampedY, angle)
    }


    // Draw arrow at the clamped location
    private fun drawArrowAt(canvas: Canvas, result: ClampedEdgeResult) {
        canvas.save()
        canvas.translate(result.x, result.y)
        canvas.rotate(degrees(result.angle))

        canvas.drawPath(arrowPath, paintArrowBody)
        canvas.drawPath(arrowPath, paintArrowOutline)

        canvas.restore()
    }
}