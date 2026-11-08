package com.hanwoo.stockreport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainVM, nav: (Route) -> Unit) {
    val tabs = listOf("오늘" to "📊", "장부" to "📒", "리포트" to "🗂")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("주식 리포트", "추적 매매 장부", "지난 리포트")[vm.tab]) },
                actions = { TextButton(onClick = { vm.refresh() }) { Text("새로고침") } },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, emoji) ->
                    NavigationBarItem(
                        selected = vm.tab == i,
                        onClick = { vm.tab = i },
                        icon = { Text(emoji, fontSize = 20.sp) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val rep = vm.report
            when {
                rep == null && vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                rep == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.error ?: "리포트가 없습니다.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.refresh() }) { Text("다시 시도") }
                }
                else -> when (vm.tab) {
                    0 -> TodayTab(rep, vm, nav)
                    1 -> LedgerTab(rep)
                    else -> ReportsTab(vm, nav)
                }
            }
            if (vm.loading && rep != null) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (vm.error != null && rep != null) {
                Text(
                    vm.error ?: "", color = Up, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
        }
    }
}

// ── 오늘 ────────────────────────────────────────────────

@Composable
private fun TodayTab(rep: Report, vm: MainVM, nav: (Route) -> Unit) {
    val interest = rep.buy.count { it.verdict == "관심" }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DateBar(rep.date, vm) }
        item { MarketCard(rep.markets) }
        item { SectionTitle("🟢 매수 ${rep.buy.size}", "관심 $interest") }
        if (rep.buy.isEmpty()) item { Empty("해당 종목 없음") }
        items(rep.buy) { s -> StockRow(s) { nav(Route.Detail(s, "BUY")) } }
        item { SectionTitle("🔴 매도 ${rep.sell.size}", "") }
        if (rep.sell.isEmpty()) item { Empty("해당 종목 없음") }
        items(rep.sell) { s -> StockRow(s) { nav(Route.Detail(s, "SELL")) } }
        if (rep.mlMessage.isNotBlank()) item { MlCard(rep.mlMessage) }
        item {
            OutlinedButton(onClick = { nav(Route.Pdf(rep.pdf, "${rep.date} PDF")) }, modifier = Modifier.fillMaxWidth()) {
                Text("📄 PDF 리포트 보기")
            }
        }
        item {
            Text(
                "조건: 5가지 전략 중 2개↑ 당일 신호 + ML 분석 대상. 대상 외 제외 매수 ${rep.excludedBuy} / 매도 ${rep.excludedSell}\n생성 ${rep.generatedAt.replace('T', ' ')}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateBar(date: String, vm: MainVM) {
    val idx = vm.dates.indexOf(date)
    val older = if (idx >= 0) vm.dates.getOrNull(idx + 1) else null
    val newer = if (idx > 0) vm.dates.getOrNull(idx - 1) else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { older?.let { vm.open(it) } }, enabled = older != null) { Text("◀ 이전") }
        Text(date, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { newer?.let { vm.open(it) } }, enabled = newer != null) { Text("다음 ▶") }
    }
}

@Composable
private fun MarketCard(ms: List<MarketFlow>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "시장 투자자별 순매수 (억원) ${ms.firstOrNull()?.date?.let { "· $it" } ?: ""}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("", Modifier.weight(1f))
                listOf("외국인", "기관", "개인").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ms.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    listOf(m.foreign, m.inst, m.indiv).forEach { v -> ValueText(v, signedWon(v), Modifier.weight(1f)) }
                }
            }
            if (ms.isEmpty()) Text("조회 실패", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(sub, fontSize = 13.sp, color = Good)
    }
}

@Composable
private fun Empty(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockRow(s: StockItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text("  ${s.symbol}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                val r1 = s.ret["1일"]
                Text("${won(s.price)}원", fontWeight = FontWeight.SemiBold)
                if (r1 != null) ValueText(r1, " ${pct(r1)}", size = 12)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.verdict?.let { Tag("점검 $it", verdictColor(it)) }
                Tag(s.opinion.ifBlank { "ML -" }, opinionColor(s.opinion))
                Tag("ML 상위 ${"%.0f".format(s.pct * 100)}%")
                Tag("신호 ${s.strategies.size}/5")
            }
            s.flow?.let { f ->
                Row(Modifier.padding(top = 6.dp)) {
                    listOf("외국인", "기관", "개인").forEach { k ->
                        Text("$k ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ValueText(f.amount[k], eok(f.amount[k]), size = 12)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            if (s.risk.isNotEmpty()) {
                Text("− " + s.risk.joinToString(", "), fontSize = 12.sp, color = Warn, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun MlCard(msg: String) {
    var open by remember { mutableStateOf(false) }
    Card(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("🤖 ML 포트폴리오 안내 ${if (open) "▲" else "▼"}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(msg, fontSize = 13.sp, maxLines = if (open) 100 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ── 장부 ────────────────────────────────────────────────

@Composable
private fun LedgerTab(rep: Report) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val sm = rep.summary
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${rep.date} 기준", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (sm == null) {
                        Text("아직 기록된 종목이 없습니다.", Modifier.padding(top = 6.dp))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            SummaryCell("보유", "${sm.openN}종목", sm.openAvg, Modifier.weight(1f))
                            SummaryCell("매도 완료", "${sm.closedN}종목", if (sm.closedN > 0) sm.closedAvg else null, Modifier.weight(1f))
                            SummaryCell("승률", if (sm.closedN > 0) "%.0f%%".format(sm.winRate * 100) else "-", null, Modifier.weight(1f))
                            SummaryCell("전체", "평균", sm.allAvg, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        val trades = rep.trades.sortedWith(compareBy<Trade> { !it.open }.thenByDescending { it.buyDate })
        items(trades) { TradeCard(it) }
        item {
            Text(
                "매수: 매수 목록 중 ML 동의 & 점검 관심 종목을 당일 종가로 기록\n매도: 보유 종목에 2개↑ 전략 매도 합의가 나오면 당일 종가로 청산\n보유 중이면 매도종가 = 당일 종가(평가), 매도일자 공란 · 수익은 1주 기준",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCell(title: String, main: String, ret: Double?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(main, fontWeight = FontWeight.Medium)
        if (ret != null) ValueText(ret, pct(ret, 2), size = 13)
    }
}

@Composable
private fun TradeCard(t: Trade) {
    var open by remember { mutableStateOf(false) }
    Card(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                Text("  ${t.symbol}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Tag(if (t.open) "보유" else "매도", if (t.open) Purple else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("매수 ${t.buyDate}  ${won(t.buyPrice)}원", fontSize = 13.sp)
                    Text(
                        "매도 ${t.sellDate ?: "—"}  ${won(t.evalPrice)}원${if (t.open) " (당일 종가)" else ""}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    ValueText(t.ret, pct(t.ret, 2), size = 17)
                    ValueText(t.profit, "${signedWon(t.profit)}원", size = 12)
                }
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                PriceChart(t.closes, if (t.ret >= 0) Up else Down, height = 160.dp, ref = t.buyPrice, refLabel = "매수 ${won(t.buyPrice)}")
            }
        }
    }
}

// ── 지난 리포트 ─────────────────────────────────────────

@Composable
private fun ReportsTab(vm: MainVM, nav: (Route) -> Unit) {
    val uri = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("평일 오후 4시 PC에서 분석 → 업로드. 앱은 1시간마다 새 리포트를 확인해 알림을 보냅니다.", fontSize = 13.sp)
                    TextButton(onClick = { uri.openUri(APK_URL) }) { Text("앱 최신 버전 받기") }
                }
            }
        }
        items(vm.dates) { d ->
            ListItem(
                headlineContent = { Text(d) },
                supportingContent = { if (d == vm.report?.date) Text("보는 중", color = Good) },
                trailingContent = {
                    TextButton(onClick = { nav(Route.Pdf("reports/${d.take(4)}/report_${d.replace("-", "")}.pdf", "$d PDF")) }) {
                        Text("PDF")
                    }
                },
                modifier = Modifier.clickable { vm.open(d); vm.tab = 0 },
            )
            HorizontalDivider()
        }
    }
}
