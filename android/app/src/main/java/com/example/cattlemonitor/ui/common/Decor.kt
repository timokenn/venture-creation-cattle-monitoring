package com.example.cattlemonitor.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Quiet farm backdrop drawn behind every screen: survey-map contour lines in
 * the top corner and two rolling pasture hills with a fence line along the
 * bottom. Everything is drawn at low opacity in the theme's primary color so
 * it adds texture without competing with content (cards stay opaque on top).
 */
@Composable
fun PastureBackground(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxSize()) {
        drawContours(center = Offset(size.width * 1.02f, size.height * 0.02f), color = ink.copy(alpha = 0.07f))
        drawHills(ink)
    }
}

/** Wobbly concentric rings, like the elevation lines on a field map. */
private fun DrawScope.drawContours(center: Offset, color: androidx.compose.ui.graphics.Color) {
    val base = size.minDimension * 0.10f
    val step = size.minDimension * 0.075f
    val stroke = Stroke(width = 1.2f * density)
    for (ring in 0 until 7) {
        val r0 = base + ring * step
        val path = Path()
        val segments = 72
        for (i in 0..segments) {
            val t = (i.toFloat() / segments) * 2f * PI.toFloat()
            val wobble = 1f + 0.07f * sin(3f * t + ring * 0.6f) + 0.04f * sin(5f * t + ring * 1.3f)
            val x = center.x + r0 * wobble * cos(t)
            val y = center.y + r0 * wobble * sin(t) * 0.85f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color = color, style = stroke)
    }
}

/** Two layered hills along the bottom, with a post-and-rail fence on the front one. */
private fun DrawScope.drawHills(ink: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height

    fun backHill(x: Float) = h * 0.86f - h * 0.05f * sin((x / w) * PI.toFloat() * 1.1f + 0.4f)
    fun frontHill(x: Float) = h * 0.92f - h * 0.04f * sin((x / w) * PI.toFloat() * 1.4f + 2.2f)

    fun hillPath(y: (Float) -> Float) = Path().apply {
        moveTo(0f, h)
        val steps = 48
        for (i in 0..steps) {
            val x = w * i / steps
            lineTo(x, y(x))
        }
        lineTo(w, h)
        close()
    }

    drawPath(hillPath(::backHill), color = ink.copy(alpha = 0.05f))
    drawPath(hillPath(::frontHill), color = ink.copy(alpha = 0.07f))

    // Fence: posts every 26dp across the left part of the front hill, two rails.
    val fence = ink.copy(alpha = 0.16f)
    val postGap = 26f * density
    val postHeight = 16f * density
    val lift = 6f * density
    val startX = w * 0.06f
    val endX = w * 0.58f
    var x = startX
    var prevTop: Offset? = null
    var prevMid: Offset? = null
    while (x <= endX) {
        val ground = frontHill(x) - lift
        val top = Offset(x, ground - postHeight)
        val mid = Offset(x, ground - postHeight * 0.45f)
        drawLine(fence, Offset(x, ground), top, strokeWidth = 2f * density)
        prevTop?.let { drawLine(fence, it, top, strokeWidth = 1.2f * density) }
        prevMid?.let { drawLine(fence, it, mid, strokeWidth = 1.2f * density) }
        prevTop = top
        prevMid = mid
        x += postGap
    }
}
