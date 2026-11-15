package com.enamind.ai.news

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Новости и макроэкономика для ENA-MIND.
 *
 * Источники — публичные RSS (без ключей): CoinDesk, Cointelegraph, Decrypt, Bitcoin
 * Magazine. Опционально NewsAPI (ключ из настроек). Обязательные поля каждой новости:
 * источник, время публикации, время получения, активы, важность, возможное влияние,
 * актуальность. Дубликаты удаляются по нормализованному заголовку; устаревшие
 * (старше 48ч) не подаются как текущие.
 *
 * ВАЖНО: заголовок ≠ фактическое влияние на цену. Оценка impact — грубая эвристика
 * по ключевым словам, помеченная как таковая; в анализе она используется только как
 * мягкая поправка sentiment (вес <= 0.10, см. mm_analyst).
 */
class NewsRepo(private val newsApiKey: String) {

    companion object {
        private const val TAG = "NewsRepo"
        private val FEEDS = listOf(
            Feed("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/"),
            Feed("Cointelegraph", "https://cointelegraph.com/rss"),
            Feed("Decrypt", "https://decrypt.co/feed"),
            Feed("BitcoinMagazine", "https://bitcoinmagazine.com/feed")
        )
        private val BULLISH = listOf(
            "etf approval", "etf approved", "inflow", "inflows", "adoption", "partnership",
            "record high", "all-time high", "ath", "halving", "upgrade", "institutional buy",
            "accumulation", "bullish", "rally", "surge", "green light", "approval"
        )
        private val BEARISH = listOf(
            "hack", "hacked", "exploit", "crash", "plunge", "ban", "banned", "lawsuit",
            "sues", "sued", "fraud", "outflow", "outflows", "liquidation cascade",
            "bankrupt", "insolvency", "warning", "crackdown", "sell-off", "selloff",
            "dump", "bearish", "reject", "rejected", "delay"
        )
        private val HIGH_IMPORTANCE = listOf(
            "fed", "fomc", "sec", "etf", "cpi", "inflation", "rate cut", "rate hike",
            "regulation", "treasury", "ecb", "ban", "hack", "binance", "coinbase",
            "blackrock", "microsoft", "tesla", "michael saylor", "trump", "war"
        )
        private val MACRO = listOf(
            "fed", "fomc", "cpi", "inflation", "interest rate", "rate cut", "rate hike",
            "unemployment", "gdp", "ecb", "boj", "treasury", "yield", "dollar index"
        )
        private val ASSET_KEYWORDS = mapOf(
            "BTCUSDT" to listOf("bitcoin", "btc"),
            "ETHUSDT" to listOf("ethereum", "ether", "eth "),
            "SOLUSDT" to listOf("solana", "sol "),
            "XRPUSDT" to listOf("xrp", "ripple"),
            "DOGEUSDT" to listOf("dogecoin", "doge")
        )
    }

    private data class Feed(val name: String, val url: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Свежие новости (JSON-массив для цепочки и UI). limit — максимум; источники
     * опрашиваются параллельно, ошибки одного не роняют остальные.
     */
    suspend fun fetch(limit: Int = 20): JSONArray = withContext(Dispatchers.IO) {
        val items = ArrayList<JSONObject>()
        coroutineScope {
            FEEDS.forEach { feed ->
                launch {
                    try {
                        fetchRss(feed)?.let { synchronized(items) { items.addAll(it) } }
                    } catch (e: Exception) {
                        Log.w(TAG, "источник ${feed.name} недоступен: ${e.message}")
                    }
                }
            }
            if (newsApiKey.isNotBlank()) {
                launch {
                    try {
                        fetchNewsApi()?.let { synchronized(items) { items.addAll(it) } }
                    } catch (e: Exception) {
                        Log.w(TAG, "NewsAPI недоступен: ${e.message}")
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        // дедуп по нормализованному заголовку + отсев устаревших (48ч)
        val seen = HashSet<String>()
        val out = JSONArray()
        items.asSequence()
            .filter { it.optLong("published_ts", 0) == 0L || now - it.optLong("published_ts", 0) < 48 * 3600_000L }
            .sortedByDescending { it.optLong("published_ts", 0) }
            .forEach { n ->
                if (out.length() >= limit) return@forEach
                val key = n.optString("title").lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9а-яё]+"), " ")
                    .trim()
                if (key.length >= 8 && seen.add(key)) {
                    n.put("fetched_ts", now / 1000)
                    n.put("fetched_ms", now)
                    n.put("relevance", relevance(n))
                    out.put(n)
                }
            }
        out
    }

    /** RSS/Atom парсинг (title, link, pubDate, description). */
    private fun fetchRss(feed: Feed): List<JSONObject>? {
        val req = Request.Builder().url(feed.url)
            .header("User-Agent", "ena-mind/6-android").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(body.reader())
            val out = ArrayList<JSONObject>()
            var inItem = false
            var title = ""; var link = ""; var date = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase(Locale.ROOT)
                        if (name == "item" || name == "entry") { inItem = true; title = ""; link = ""; date = "" }
                        else if (inItem && (name == "title" || name == "link" || name == "pubdate" || name == "published" || name == "updated" || name == "date" || name == "dc:date")) {
                            when (name) {
                                "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                                "pubdate", "published", "updated", "date", "dc:date" -> if (date.isEmpty()) date = parser.nextText().trim()
                                "link" -> if (link.isEmpty()) {
                                    val href = parser.getAttributeValue(null, "href")
                                    link = if (!href.isNullOrBlank()) href else parser.nextText().trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase(Locale.ROOT)
                        if (name == "item" || name == "entry") {
                            inItem = false
                            if (title.isNotBlank()) out.add(buildItem(feed.name, title, link, date))
                        }
                    }
                }
                event = parser.next()
            }
            return out
        }
    }

    /** NewsAPI (если указан ключ в настройках): всё о крипте, сортировка по времени. */
    private fun fetchNewsApi(): List<JSONObject>? {
        val url = "https://newsapi.org/v2/everything?q=crypto+OR+bitcoin+OR+ethereum" +
            "&language=en&sortBy=publishedAt&pageSize=30&apiKey=$newsApiKey"
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            if (root.optString("status") != "ok") return null
            val out = ArrayList<JSONObject>()
            val arr = root.optJSONArray("articles") ?: return null
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val title = a.optString("title")
                if (title.isBlank() || title == "[Removed]") continue
                val srcName = a.optJSONObject("source")?.optString("name") ?: "NewsAPI"
                out.add(buildItem(srcName, title, a.optString("url", ""), a.optString("publishedAt", "")))
            }
            return out
        }
    }

    private fun buildItem(source: String, title: String, link: String, date: String): JSONObject {
        val t = title.replace("\n", " ").trim()
        val tl = t.lowercase(Locale.ROOT)
        val impact = when {
            BULLISH.any { tl.contains(it) } && !BEARISH.any { tl.contains(it) } -> 0.7
            BEARISH.any { tl.contains(it) } && !BULLISH.any { tl.contains(it) } -> -0.7
            BULLISH.any { tl.contains(it) } && BEARISH.any { tl.contains(it) } -> 0.0
            else -> 0.0
        }
        val importance = when {
            HIGH_IMPORTANCE.any { tl.contains(it) } -> "high"
            MACRO.any { tl.contains(it) } -> "high"
            else -> "medium"
        }
        val assets = JSONArray()
        ASSET_KEYWORDS.forEach { (sym, keys) -> if (keys.any { tl.contains(it) }) assets.put(sym) }
        return JSONObject()
            .put("source", source)
            .put("title", t)
            .put("url", link)
            .put("published_ts", parseDate(date))
            .put("importance", importance)
            .put("impact", impact)
            .put("impact_note", "эвристика по заголовку; заголовок != фактическое влияние на цену")
            .put("assets", assets)
    }

    /** Актуальность: свежесть (0..1, 24ч горизонт). */
    private fun relevance(n: JSONObject): Double {
        val ts = n.optLong("published_ts", 0)
        if (ts <= 0) return 0.5
        val ageH = (System.currentTimeMillis() - ts) / 3600_000.0
        return when {
            ageH <= 1 -> 1.0
            ageH <= 6 -> 0.8
            ageH <= 24 -> 0.5
            else -> 0.2
        }
    }

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(s)?.time ?: 0L
            } catch (_: Exception) {
            }
        }
        return 0L
    }
}
