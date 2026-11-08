package com.hanwoo.stockreport

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// ── 종목 상세 ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(s: StockItem, side: String, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val buy = side == "BUY"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${s.name} ${s.symbol}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${won(s.price)}원", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        s.ret["1일"]?.let { ValueText(it, "  ${pct(it)}", size = 15) }
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(if (buy) "매수 신호" else "매도 신호", if (buy) Good else Up)
                        s.verdict?.let { Tag("점검 $it", verdictColor(it)) }
                        Tag(s.opinion.ifBlank { "ML -" }, opinionColor(s.opinion))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("지난 1년 종가 (점선: 60일선)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        PriceChart(s.closes, if (buy) Color(0xFF1A8A3A) else Color(0xFFC62828), height = 220.dp, showMa = true)
                    }
                }
            }
            item {
                InfoCard(
                    "신호 · ML",
                    listOf(
                        Kv("신호 전략", s.strategies.joinToString(", ")),
                        Kv("ML 순위", "상위 ${"%.0f".format(s.pct * 100)}% (10거래일 상대수익 예측)"),
                        Kv("ML 의견", s.opinion, opinionColor(s.opinion)),
                    ),
                )
            }
            s.flow?.let { f ->
                item {
                    InfoCard(
                        "당일 순매수 추정 (${f.date})",
                        listOf("외국인", "기관", "개인").map { k ->
                            val q = f.qty[k]
                            Kv(k, "${eok(f.amount[k])}" + (q?.let { "  (${signedWon(it)}주)" } ?: ""), pn(f.amount[k]))
                        },
                    )
                }
            }
            item {
                val v = s.valuation
                val rows = mutableListOf(
                    Kv("PER", v["PER"]?.let { "%.1f배".format(it) } ?: "-"),
                    Kv("PBR", v["PBR"]?.let { "%.2f배".format(it) } ?: "-"),
                    Kv("배당수익률", v["배당"]?.let { "%.2f%%".format(it) } ?: "-"),
                )
                val lo = v["52저"]
                val hi = v["52고"]
                if (lo != null && hi != null) rows += Kv("52주 범위", "${won(lo)} ~ ${won(hi)}")
                if (s.ret.isNotEmpty()) rows += Kv("수익률", s.ret.entries.joinToString("  ") { "${it.key} ${pct(it.value)}" })
                InfoCard("밸류 · 흐름", rows)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("점검: ${s.verdict ?: "-"}", style = MaterialTheme.typography.titleSmall, color = verdictColor(s.verdict))
                        if (s.risk.isEmpty() && s.good.isEmpty()) Text("특이 공시·기사 없음", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        s.risk.forEach { Text("− $it", color = Warn, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                        s.good.forEach { Text("+ $it", color = Good, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text("최근 기사 (7일)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp))
                        if (s.news.isEmpty()) Text("관련 기사 없음", fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                        s.news.forEachIndexed { i, n ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                            Column(
                                Modifier.fillMaxWidth().clickable(enabled = n.url.isNotBlank()) { uri.openUri(n.url) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(n.title, fontSize = 14.sp, maxLines = 2)
                                val mark = when (n.tone) { "+" -> " · 호재"; "-" -> " · 악재"; else -> "" }
                                Text(
                                    "${n.date.take(2)}/${n.date.drop(2)} ${n.office}$mark", fontSize = 12.sp,
                                    color = when (n.tone) { "+" -> Up; "-" -> Down; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "※ 지표·ML·키워드 규칙 기반 참고 자료이며 수익을 보장하지 않습니다.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── PDF 보기 ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(path: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var file by remember(path) { mutableStateOf<File?>(null) }
    var err by remember(path) { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(path) {
        try {
            file = Repo.pdf(ctx, path)
        } catch (e: Exception) {
            err = "다운로드 실패: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 18.sp) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
                actions = {
                    TextButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("−", fontSize = 20.sp) }
                    Text("${(zoom * 100).toInt()}%", fontSize = 12.sp)
                    TextButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(3f) }, enabled = zoom < 3f) { Text("+", fontSize = 20.sp) }
                    TextButton(onClick = { uri.openUri(WEB + path) }) { Text("웹") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFF9E9E9E))) {
            val f = file
            when {
                err != null -> Text(err ?: "", Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
                f == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> PdfPages(f, zoom)
            }
        }
    }
}

@Composable
private fun PdfPages(file: File, zoom: Float) {
    val renderer = remember(file) { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
    val mutex = remember(file) { Mutex() }
    DisposableEffect(renderer) {
        onDispose { runCatching { renderer.close() } }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = maxWidth * zoom
        val px = with(LocalDensity.current) { pageWidth.toPx() }.toInt().coerceIn(200, 2400)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(
                Modifier.width(pageWidth).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(renderer.pageCount) { i -> PdfPage(renderer, mutex, i, px, pageWidth) }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, px: Int, width: Dp) {
    var bmp by remember(index, px) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, px) {
        bmp = withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val page = renderer.openPage(index)
                    try {
                        val h = (px.toFloat() * page.height / page.width).toInt()
                        Bitmap.createBitmap(px, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    } finally {
                        page.close()
                    }
                }.getOrNull()
            }
        }
    }
    val b = bmp
    if (b == null) {
        Box(Modifier.width(width).aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Image(
            b.asImageBitmap(), contentDescription = "page ${index + 1}",
            modifier = Modifier.width(width).aspectRatio(b.width.toFloat() / b.height),
            contentScale = ContentScale.FillWidth,
        )
    }
}
