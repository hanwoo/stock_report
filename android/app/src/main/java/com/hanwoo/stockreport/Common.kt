package com.hanwoo.stockreport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

// ── 포맷 ────────────────────────────────────────────────

fun won(v: Double): String = "%,.0f".format(v)
fun signedWon(v: Double): String = "%+,.0f".format(v)
fun eok(v: Double?): String = if (v == null) "-" else "%+,.1f억".format(v / 1e8)
fun pct(v: Double, digits: Int = 1): String = "%+.${digits}f%%".format(v * 100)

// ── 색 (한국식: 상승/순매수 빨강, 하락/순매도 파랑) ─────────

val Up = Color(0xFFD32F2F)
val Down = Color(0xFF1565C0)
val Good = Color(0xFF2E7D32)
val Warn = Color(0xFFEF6C00)
val Purple = Color(0xFF6A4BB0)

fun pn(v: Double?): Color? = when {
    v == null -> null
    v > 0 -> Up
    v < 0 -> Down
    else -> null
}

fun verdictColor(v: String?): Color = when (v) {
    "관심" -> Good
    "주의", "재확인" -> Warn
    "제외", "매도 강화" -> Up
    else -> Color.Gray
}

fun opinionColor(op: String): Color = when (op) {
    "ML 동의" -> Good
    "ML 반대" -> Up
    else -> Color.Gray
}

// ── 공용 컴포넌트 ───────────────────────────────────────

@Composable
fun Tag(text: String, color: Color = Color.Gray) {
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(6.dp)) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

data class Kv(val k: String, val v: String, val color: Color? = null)

@Composable
fun InfoCard(title: String, rows: List<Kv>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(r.k, Modifier.width(88.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Text(
                        r.v, Modifier.weight(1f), fontSize = 14.sp,
                        color = r.color ?: MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (r.color != null) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
fun ValueText(v: Double?, text: String, modifier: Modifier = Modifier, size: Int = 14) {
    Text(
        text, modifier, color = pn(v) ?: MaterialTheme.colorScheme.onSurface,
        fontSize = size.sp, textAlign = TextAlign.End,
    )
}

// ── 종가 그래프 ─────────────────────────────────────────

@Composable
fun PriceChart(
    points: List<Pair<String, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    ref: Double? = null,
    refLabel: String = "",
    showMa: Boolean = false,
) {
    if (points.size < 2) {
        Text("그래프 데이터 없음", modifier.padding(8.dp), color = Color.Gray)
        return
    }
    val values = points.map { it.second }
    val ma: List<Double>? = if (showMa) values.indices.map { i ->
        val from = max(0, i - 59)
        values.subList(from, i + 1).average()
    } else null
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val grid = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier.fillMaxWidth().height(height)) {
        val padL = 4.dp.toPx()
        val padR = 52.dp.toPx()
        val padT = 8.dp.toPx()
        val padB = 18.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        var lo = values.min()
        var hi = values.max()
        ma?.let { lo = min(lo, it.min()); hi = max(hi, it.max()) }
        ref?.let { lo = min(lo, it); hi = max(hi, it) }
        if (hi - lo < 1e-9) { hi += 1.0; lo -= 1.0 }
        val n = values.size
        fun x(i: Int) = padL + w * i / (n - 1)
        fun y(v: Double) = padT + h * (1f - ((v - lo) / (hi - lo)).toFloat())

        val paint = android.graphics.Paint().apply {
            this.color = textColor
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            val yy = y(v)
            drawLine(grid, Offset(padL, yy), Offset(padL + w, yy), 1f)
            drawContext.canvas.nativeCanvas.drawText("%,.0f".format(v), padL + w + 4.dp.toPx(), yy + 4.dp.toPx(), paint)
        }
        listOf(0, n / 2, n - 1).distinct().forEach { i ->
            val d = points[i].first
            val label = if (d.length >= 10) d.substring(2, 7).replace('-', '.') else d
            val tw = paint.measureText(label)
            val tx = (x(i) - tw / 2).coerceIn(0f, size.width - tw)
            drawContext.canvas.nativeCanvas.drawText(label, tx, size.height - 3.dp.toPx(), paint)
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        ma?.let { m ->
            val p = Path()
            m.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
            drawPath(p, Color.Gray.copy(alpha = 0.7f), style = Stroke(1.dp.toPx(), pathEffect = dash))
        }
        ref?.let { r ->
            drawLine(Purple, Offset(padL, y(r)), Offset(padL + w, y(r)), 1.5.dp.toPx(), pathEffect = dash)
            if (refLabel.isNotEmpty()) {
                paint.color = Purple.toArgb()
                drawContext.canvas.nativeCanvas.drawText(refLabel, padL + 4.dp.toPx(), y(r) - 4.dp.toPx(), paint)
            }
        }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        drawCircle(color, 3.5.dp.toPx(), Offset(x(n - 1), y(values.last())))
    }
}
