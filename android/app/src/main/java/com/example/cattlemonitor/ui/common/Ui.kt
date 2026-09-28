package com.example.cattlemonitor.ui.common

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.theme.Ink
import com.example.cattlemonitor.ui.theme.InkSoft
import com.example.cattlemonitor.ui.theme.Line
import com.example.cattlemonitor.ui.theme.StatusAlert
import com.example.cattlemonitor.ui.theme.StatusNormal
import com.example.cattlemonitor.ui.theme.statusColor

/** Notched ear-tag shape — the app's status motif, echoing an actual cattle ear tag. */
private val EarTagShape = GenericShape { size, _ ->
    val notch = size.height * 0.35f
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(notch, size.height)
    lineTo(0f, size.height - notch)
    close()
}

@Composable
fun StatusBadge(status: CowStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(EarTagShape)
            .background(statusColor(status)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
@Composable
fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.copy(alpha = 0.5f))
    }
}

/**
 * Line chart with an optional shaded "baseline band" — the visual expression
 * of this app's core idea: readings are judged against this cow's own normal
 * range, not a fixed threshold — plus an optional labeled axis (e.g. °C)
 * with gridlines, time labels along the bottom, and a callout on the
 * latest reading. Indices in [suspicious] are drawn as hollow circles:
 * sensor-suspicious data is shown, but visibly not trusted. Entries in
 * [alertMarkers] get vertical markers: they line up with the readings whose
 * timestamps triggered an alert, so you can see exactly when it started.
 */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    baselineValue: Double? = null,
    baselineBandFraction: Float = 0.12f,
    showAxis: Boolean = false,
    valueSuffix: String = "",
    xAxisLabels: List<String> = emptyList(),
    suspicious: Set<Int> = emptySet(),
    alertMarkers: Set<Int> = emptySet(),
) {
    val axisColorArgb = InkSoft.toArgb()
    val gridColor = Line
    Canvas(modifier = modifier.fillMaxWidth().height(if (showAxis) 168.dp else 150.dp)) {
        if (values.size < 2) return@Canvas

        val rawMin = minOf(values.min(), baselineValue ?: values.min())
        val rawMax = maxOf(values.max(), baselineValue ?: values.max())
        val padAmount = ((rawMax - rawMin).takeIf { it > 1e-9 } ?: 1.0) * 0.18
        val minV = rawMin - padAmount
        val maxV = rawMax + padAmount
        val range = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0

        val leftMargin = if (showAxis) 32.dp.toPx() else 0f
        val bottomMargin = if (showAxis && xAxisLabels.isNotEmpty()) 16.dp.toPx() else 0f
        val plotWidth = size.width - leftMargin
        val plotHeight = size.height - bottomMargin
        val stepX = plotWidth / (values.size - 1)

        fun yFor(v: Double) = plotHeight - ((v - minV) / range * plotHeight).toFloat()
        fun xFor(i: Int) = leftMargin + i * stepX

        val labelPaint = Paint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            // explicit receiver: DrawScope.color (Compose val) would otherwise shadow Paint's
            this.setColor(axisColorArgb)
        }

        if (showAxis) {
            val gridSteps = 4
            labelPaint.textAlign = Paint.Align.LEFT
            for (g in 0..gridSteps) {
                val v = minV + range * g / gridSteps
                val y = yFor(v)
                drawLine(
                    color = gridColor,
                    start = Offset(leftMargin, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(v),
                    0f,
                    y + 3.dp.toPx(),
                    labelPaint,
                )
            }
            if (xAxisLabels.isNotEmpty()) {
                labelPaint.textAlign = Paint.Align.CENTER
                val last = (xAxisLabels.size - 1).coerceAtLeast(1)
                xAxisLabels.forEachIndexed { idx, label ->
                    val fx = leftMargin + plotWidth * idx / last
                    drawContext.canvas.nativeCanvas.drawText(label, fx, size.height, labelPaint)
                }
            }
        }

        baselineValue?.let { base ->
            val bandHalf = range * baselineBandFraction
            val topY = yFor(base + bandHalf)
            val botY = yFor(base - bandHalf)
            drawRect(
                color = StatusNormal.copy(alpha = 0.12f),
                topLeft = Offset(leftMargin, topY),
                size = Size(plotWidth, botY - topY),
            )
            drawLine(
                color = StatusNormal.copy(alpha = 0.6f),
                start = Offset(leftMargin, yFor(base)),
                end = Offset(size.width, yFor(base)),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }

        // Alert event markers: vertical hairlines behind the data line.
        alertMarkers.forEach { i ->
            if (i in values.indices) {
                drawLine(
                    color = StatusAlert.copy(alpha = 0.55f),
                    start = Offset(xFor(i), 0f),
                    end = Offset(xFor(i), plotHeight),
                    strokeWidth = 2f,
                )
            }
        }

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = xFor(i)
            val y = yFor(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color = color, radius = 2.dp.toPx(), center = Offset(x, y))
        }
        drawPath(path, color = color, style = Stroke(width = 4f))

        suspicious.forEach { i ->
            if (i in values.indices) {
                drawCircle(
                    color = color.copy(alpha = 0.85f),
                    radius = 6.dp.toPx(),
                    center = Offset(xFor(i), yFor(values[i])),
                    style = Stroke(width = 3f),
                )
            }
        }

        if (showAxis) {
            val lastX = xFor(values.size - 1)
            val lastY = yFor(values.last())
            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.setColor(color.toArgb())
            labelPaint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f%s".format(values.last(), valueSuffix),
                lastX,
                (lastY - 8.dp.toPx()).coerceAtLeast(labelPaint.textSize),
                labelPaint,
            )
        }
    }
}
