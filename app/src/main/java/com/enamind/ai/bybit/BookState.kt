package com.enamind.ai.bybit

/**
 * BookState — корректная книга заявок = snapshot + последовательные delta.
 *
 * Правила честности:
 *  - delta применяется только к синхронизированной книге;
 *  - потеря sequence (u <= последний применённый или разрыв по seq) -> книга
 *    помечается DESYNC и ждёт нового snapshot (мы не строим «примерную» книгу);
 *  - размер ограничен (top-N), уровни с qty=0 удаляются;
 *  - при каждом обновлении можно получить точные LevelDelta (prevQty -> newQty),
 *    чтобы отличать реальные исполнения по сделке от снятия/отмены ликвидности.
 */
class BookState(private val maxLevels: Int = 200) {

    data class LevelDelta(
        val price: Double,
        val prevQty: Double,
        val newQty: Double,
        val deltaQty: Double,
        val side: String, // "BID" | "ASK"
        val tsMs: Long,
        val seqU: Long
    )

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
     * Snapshot от биржи: полная замена состояния.
     */
    fun snapshot(bidsRaw: List<Pair<Double, Double>>, asksRaw: List<Pair<Double, Double>>, u: Long) {
        snapshotWithDeltas(bidsRaw, asksRaw, u, System.currentTimeMillis())
    }

    /**
     * Snapshot с возвратом начальных изменений уровней относительно предыдущего состояния.
     */
    fun snapshotWithDeltas(
        bidsRaw: List<Pair<Double, Double>>,
        asksRaw: List<Pair<Double, Double>>,
        u: Long,
        tsMs: Long
    ): List<LevelDelta> {
        val deltas = ArrayList<LevelDelta>(bidsRaw.size + asksRaw.size)
        val newBids = HashMap<Double, Double>(bidsRaw.size * 2)
        val newAsks = HashMap<Double, Double>(asksRaw.size * 2)
        for ((p, q) in bidsRaw) {
            if (!p.isFinite() || !q.isFinite() || p <= 0.0 || q <= 0.0) continue
            val prev = bids[p] ?: 0.0
            newBids[p] = q
            if (prev != q) {
                deltas.add(LevelDelta(p, prev, q, q - prev, "BID", tsMs, u))
            }
        }
        for ((p, q) in asksRaw) {
            if (!p.isFinite() || !q.isFinite() || p <= 0.0 || q <= 0.0) continue
            val prev = asks[p] ?: 0.0
            newAsks[p] = q
            if (prev != q) {
                deltas.add(LevelDelta(p, prev, q, q - prev, "ASK", tsMs, u))
            }
        }
        bids = newBids
        asks = newAsks
        trim()
        lastU = u
        synced = true
        updates++
        return deltas
    }

    /**
     * Delta от биржи. Возвращает true если применена или повторно доставлена.
     */
    fun delta(bidsRaw: List<Pair<Double, Double>>, asksRaw: List<Pair<Double, Double>>, u: Long, seq: Long): Boolean {
        return deltaWithChanges(bidsRaw, asksRaw, u, seq, System.currentTimeMillis()).first
    }

    /**
     * Delta с точным списком LevelDelta (prevQty -> newQty) для анализа DOM.
     */
    fun deltaWithChanges(
        bidsRaw: List<Pair<Double, Double>>,
        asksRaw: List<Pair<Double, Double>>,
        u: Long,
        seq: Long,
        tsMs: Long
    ): Pair<Boolean, List<LevelDelta>> {
        if (!synced) return false to emptyList()
        if (u == 1L) {
            synced = false
            desyncCount++
            return false to emptyList()
        }
        if (u <= lastU) return true to emptyList() // повторная доставка — игнорируем
        if (u - lastU > MAX_SEQ_GAP) {
            synced = false
            desyncCount++
            return false to emptyList()
        }
        val changes = ArrayList<LevelDelta>(bidsRaw.size + asksRaw.size)
        applySide(bids, bidsRaw, "BID", tsMs, u, changes)
        applySide(asks, asksRaw, "ASK", tsMs, u, changes)
        trim()
        lastU = u
        updates++
        return true to changes
    }

    fun desync() {
        synced = false
        desyncCount++
    }

    fun snapshot(): Book = Book(
        bids = bids.entries.sortedByDescending { it.key }.take(maxLevels).map { it.key to it.value },
        asks = asks.entries.sortedBy { it.key }.take(maxLevels).map { it.key to it.value },
        synced = synced,
        seq = lastU,
        updates = updates,
        desyncCount = desyncCount
    )

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

    private fun applySide(
        map: HashMap<Double, Double>,
        rows: List<Pair<Double, Double>>,
        side: String,
        tsMs: Long,
        seqU: Long,
        out: MutableList<LevelDelta>
    ) {
        for ((price, rawQty) in rows) {
            if (!price.isFinite() || !rawQty.isFinite() || price <= 0.0) continue
            val newQty = if (rawQty <= 0.0) 0.0 else rawQty
            val prevQty = map[price] ?: 0.0
            if (newQty <= 0.0) map.remove(price) else map[price] = newQty
            if (prevQty != newQty) {
                out.add(
                    LevelDelta(
                        price = price,
                        prevQty = prevQty,
                        newQty = newQty,
                        deltaQty = newQty - prevQty,
                        side = side,
                        tsMs = tsMs,
                        seqU = seqU
                    )
                )
            }
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
        const val MAX_SEQ_GAP = 2_000_000L
    }
}
