package com.hanwoo.stockreport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val BASE = "https://raw.githubusercontent.com/hanwoo/stock_report/main/"
const val WEB = "https://github.com/hanwoo/stock_report/blob/main/"
const val APK_URL = "https://github.com/hanwoo/stock_report/releases/download/app/stock-report.apk"

data class MarketFlow(val name: String, val date: String, val foreign: Double, val inst: Double, val indiv: Double)
data class News(val date: String, val office: String, val title: String, val url: String, val tone: String)
data class Flow(val date: String, val amount: Map<String, Double>, val qty: Map<String, Double>)

data class StockItem(
    val symbol: String,
    val name: String,
    val price: Double,
    val strategies: List<String>,
    val pct: Double,
    val opinion: String,
    val verdict: String?,
    val risk: List<String>,
    val good: List<String>,
    val news: List<News>,
    val valuation: Map<String, Double>,
    val ret: Map<String, Double>,
    val flow: Flow?,
    val closes: List<Pair<String, Double>>,
)

data class Trade(
    val symbol: String,
    val name: String,
    val buyDate: String,
    val buyPrice: Double,
    val sellDate: String?,
    val evalPrice: Double,
    val profit: Double,
    val ret: Double,
    val open: Boolean,
    val closes: List<Pair<String, Double>>,
)

data class TradeSummary(
    val openN: Int, val openAvg: Double, val closedN: Int, val closedAvg: Double,
    val winRate: Double, val allAvg: Double,
)

data class Report(
    val date: String,
    val generatedAt: String,
    val pdf: String,
    val markets: List<MarketFlow>,
    val mlMessage: String,
    val excludedBuy: Int,
    val excludedSell: Int,
    val buy: List<StockItem>,
    val sell: List<StockItem>,
    val trades: List<Trade>,
    val summary: TradeSummary?,
)

// ── JSON 파싱 ───────────────────────────────────────────

private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
private fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject?.doubles(): Map<String, Double> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, Double>()
    keys().forEach { k -> if (!isNull(k)) optDouble(k).takeIf { !it.isNaN() }?.let { out[k] = it } }
    return out
}

private fun JSONArray?.pairs(): List<Pair<String, Double>> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        val a = optJSONArray(i) ?: return@mapNotNull null
        val v = a.optDouble(1)
        if (v.isNaN()) null else a.optString(0) to v
    }
}

private fun stock(o: JSONObject) = StockItem(
    symbol = o.optString("symbol"),
    name = o.optString("name"),
    price = o.optDouble("price", 0.0),
    strategies = o.optJSONArray("strategies").strings(),
    pct = o.optDouble("pct", 0.0),
    opinion = o.str("opinion") ?: "",
    verdict = o.str("verdict"),
    risk = o.optJSONArray("risk").strings(),
    good = o.optJSONArray("good").strings(),
    news = o.optJSONArray("news").objs().map {
        News(it.optString("date"), it.optString("office"), it.optString("title"), it.optString("url"), it.optString("tone"))
    },
    valuation = o.optJSONObject("val").doubles(),
    ret = o.optJSONObject("ret").doubles(),
    flow = o.optJSONObject("flow")?.let {
        Flow(it.optString("date"), it.optJSONObject("amount").doubles(), it.optJSONObject("qty").doubles())
    },
    closes = o.optJSONArray("closes").pairs(),
)

fun parseReport(s: String): Report {
    val o = JSONObject(s)
    val m = o.optJSONObject("market")
    val markets = m?.keys()?.asSequence()?.mapNotNull { k ->
        m.optJSONObject(k)?.let {
            MarketFlow(k, it.optString("date"), it.optDouble("외국인", 0.0), it.optDouble("기관", 0.0), it.optDouble("개인", 0.0))
        }
    }?.toList() ?: emptyList()
    val ex = o.optJSONObject("excluded")
    val trades = o.optJSONArray("trades").objs().map {
        Trade(
            symbol = it.optString("symbol"), name = it.optString("name"),
            buyDate = it.optString("buy_date"), buyPrice = it.optDouble("buy_price", 0.0),
            sellDate = it.str("sell_date"), evalPrice = it.optDouble("eval_price", 0.0),
            profit = it.optDouble("profit", 0.0), ret = it.optDouble("ret", 0.0),
            open = it.optBoolean("open", true), closes = it.optJSONArray("closes").pairs(),
        )
    }
    val sm = o.optJSONObject("trades_summary")?.let {
        TradeSummary(
            it.optInt("open_n"), it.optDouble("open_avg", 0.0), it.optInt("closed_n"),
            it.optDouble("closed_avg", 0.0), it.optDouble("win_rate", 0.0), it.optDouble("all_avg", 0.0),
        )
    }
    return Report(
        date = o.optString("date"),
        generatedAt = o.optString("generated_at"),
        pdf = o.optString("pdf"),
        markets = markets,
        mlMessage = o.optJSONObject("ml")?.str("message") ?: "",
        excludedBuy = ex?.optInt("BUY") ?: 0,
        excludedSell = ex?.optInt("SELL") ?: 0,
        buy = o.optJSONArray("buy").objs().map(::stock),
        sell = o.optJSONArray("sell").objs().map(::stock),
        trades = trades,
        summary = sm,
    )
}

private fun parseIndex(s: String): List<String> = JSONObject(s).optJSONArray("dates").strings()

// ── 네트워크 + 캐시 ─────────────────────────────────────

object Repo {
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode != 200) {
            val code = c.responseCode
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
    }

    private fun getText(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            c.disconnect()
        }
    }

    suspend fun index(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "index.json")
        try {
            val s = getText(BASE + "data/index.json?t=" + System.currentTimeMillis())
            f.writeText(s)
            parseIndex(s)
        } catch (e: Exception) {
            if (f.exists()) parseIndex(f.readText()) else throw e
        }
    }

    suspend fun report(ctx: Context, date: String, force: Boolean): Report = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "r_$date.json")
        if (!force && f.exists()) {
            return@withContext parseReport(f.readText())
        }
        try {
            val s = getText(BASE + "data/$date.json?t=" + System.currentTimeMillis())
            f.writeText(s)
            parseReport(s)
        } catch (e: Exception) {
            if (f.exists()) parseReport(f.readText()) else throw e
        }
    }

    suspend fun pdf(ctx: Context, path: String): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, path.substringAfterLast('/'))
        val tmp = File(ctx.cacheDir, f.name + ".part")
        try {
            val c = open(BASE + path + "?t=" + System.currentTimeMillis())
            try {
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            } finally {
                c.disconnect()
            }
            tmp.renameTo(f.also { it.delete() })
            f
        } catch (e: Exception) {
            if (f.exists()) f else throw e
        }
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun seen(ctx: Context): String? = sp(ctx).getString("seen", null)
    fun setSeen(ctx: Context, d: String) = sp(ctx).edit().putString("seen", d).apply()
}
