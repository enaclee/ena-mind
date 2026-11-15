package com.enamind.ai.bybit

/**
 * BookState v8.1 (ТЗ §14, §44.1): корректная книга = snapshot + delta.
 *
 * Bybit linear public orderbook шлёт snapshot, затем delta с u (update id).
 * Правила честности:
 *  - delta применяется только к синхронизированной книге;
 *  - потеря sequence (u <= последний применённый или gap по seq) -> книга
 *    помечается DESYNC и ждёт нового snapshot (мы не строим «примерную» книгу);
 *  - размер — ограничен (top-N), уровни с qty=0 удаляются;
 *  - частичная книга никогда не выдаётся как синхронизированная.
 *
 * Потокобезопасность: все вызовы из OkHttp-потока одного соединения,
 * snapshot() возвращает immutable копию для Compose/движка.
 */
class BookState(private val maxLevels: Int = 200) {

    data class Book(
        val bids: List<Pair<Double, Double>>,
        val asks: List<Pair<Double, Double>>,
        val synced: Boolean,
        val seq: Long,
        val updates: Long,
        val desyncCount: Long
    )

    // bids — по убыванию цены, asks — по возрастанию
    private var bids = HashMap<Double, Double>()
    private var asks = HashMap<Double, Double>()
    private var synced = false
    private var lastU = -1L
    private var updates = 0L
    private var desyncCount = 0L

    /**
     * Snapshot от биржи: полная замена состояния. u берем из msg «u».
     */
    fun snapshot(bidsRaw: List<Pair<Double, Double>>, asksRaw: List<Pair<Double, Double>>, u: Long) {
        bids = HashMap()
        asks = HashMap()
        applySide(bids, bidsRaw)
        applySide(asks, asksRaw)
        trim()
        lastU = u
        synced = true
        updates++
    }

    /**
     * Delta от биржи. В Bybit V5 per-symbol update id (u) инкрементируется монотонно;
     * seq — глобальный cross-stream идентификатор и несопоставим с u.
     * Если u == 1 (рестарт сервиса) или разрыв > MAX_SEQ_GAP -> DESYNC (ждём snapshot).
     */
    fun delta(bidsRaw: List<Pair<Double, Double>>, asksRaw: List<Pair<Double, Double>>, u: Long, seq: Long): Boolean {
        if (!synced) return false
        if (u == 1L) {
            synced = false
            desyncCount++
            return false
        }
        if (u <= lastU) return true // повторная доставка — игнорируем
        if (u - lastU > MAX_SEQ_GAP) {
            synced = false
            desyncCount++
            return false
        }
        applySide(bids, bidsRaw)
        applySide(asks, asksRaw)
        trim()
        lastU = u
        updates++
        return true
    }

    fun desync() { synced = false; desyncCount++ }

    fun snapshot(): Book = Book(
        bids = bids.entries.sortedByDescending { it.key }.take(maxLevels).map { it.key to it.value },
        asks = asks.entries.sortedBy { it.key }.take(maxLevels).map { it.key to it.value },
        synced = synced,
        seq = lastU,
        updates = updates,
        desyncCount = desyncCount
    )

    // ---------------- расчёты (детерминированные, для движка) ----------------

    /** Дисбаланс верхней части книги в процентах [-100..100]. */
    fun imbalancePct(levels: Int = 25): Double {
        val b = bids.entries.sortedByDescending { it.key }.take(levels).sumOf { it.value }
        val a = asks.entries.sortedBy { it.key }.take(levels).sumOf { it.value }
        if (b + a <= 0) return 0.0
        return 100.0 * (b - a) / (b + a)
    }

    /** Спред в процентах от mid. */
    fun spreadPct(): Double {
        val bb = bids.keys.maxOrNull() ?: return 0.0
        val ba = asks.keys.minOrNull() ?: return 0.0
        val mid = (bb + ba) / 2.0
        return if (mid > 0) 100.0 * (ba - bb) / mid else 0.0
    }

    private fun applySide(map: HashMap<Double, Double>, rows: List<Pair<Double, Double>>) {
        for ((price, qty) in rows) {
            if (qty <= 0.0) map.remove(price) else map[price] = qty
        }
    }

    private fun trim() {
        if (bids.size <= maxLevels && asks.size <= maxLevels) return
        bids = bids.entries.sortedByDescending { it.key }.take(maxLevels)
            .associate { it.key to it.value } as HashMap<Double, Double>
        asks = asks.entries.sortedBy { it.key }.take(maxLevels)
            .associate { it.key to it.value } as HashMap<Double, Double>
    }

    companion object {
        // Допустимый разрыв sequence (окно консолидации Bybit) — больше него
        // считаем книгу потерянной и ждём snapshot.
        const val MAX_SEQ_GAP = 2_000_000L
    }
}
