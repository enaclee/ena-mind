package com.enamind.ai.core

import java.util.concurrent.atomic.AtomicLong

/**
 * FeedStatus v8.1 (ТЗ §1.3): «WebSocket подключён» ≠ «данные поступают».
 *
 * Шесть честных состояний потока по каждой ТЕМЕ отдельно:
 *  - CONNECTING         — соединение устанавливается;
 *  - CONNECTED_NO_DATA  — соединение открыто, валидных сообщений по теме ещё нет;
 *  - LIVE               — пришло валидное сообщение по теме (не просто connect);
 *  - STALE              — данные есть, но возраст превысил порог темы;
 *  - DISCONNECTED       — соединение закрыто (ждём reconnect);
 *  - ERROR              — ошибка сети/парса (текст ошибки хранится).
 *
 * age_sec, счётчики событий и текст ошибки — всё доступно UI и движку.
 * Потокобезопасно: обновляется из OkHttp-потоков, читается из Compose.
 */
object FeedStatus {
    const val CONNECTING = "CONNECTING"
    const val CONNECTED_NO_DATA = "CONNECTED_NO_DATA"
    const val LIVE = "LIVE"
    const val STALE = "STALE"
    const val DISCONNECTED = "DISCONNECTED"
    const val ERROR = "ERROR"

    /** Возраст (сек), после которого LIVE деградирует до STALE — по типу данных. */
    fun staleAfterSec(kind: Kind): Long = when (kind) {
        Kind.TICKER -> 30
        Kind.TRADES -> 20
        Kind.BOOK -> 15
        Kind.KLINE -> 180
        Kind.LIQUIDATION -> 900 // редкие события — сами по себе не «живые»
    }

    enum class Kind { TICKER, TRADES, BOOK, KLINE, LIQUIDATION }

    /**
     * Состояние одной темы: статус + метрики доставки.
     * Все поля — примитивы/String: объект передаётся в Compose-состояние целиком
     * (copy), поэтому композиция стабильна и не падает на конкурентных мутациях.
     */
    data class Topic(
        val status: String = CONNECTING,
        val lastMsgTsMs: Long = 0L,
        val events: Long = 0L,
        val error: String = "",
        val subscribed: Boolean = false
    ) {
        val ageSec: Long get() = if (lastMsgTsMs == 0L) -1L else (System.currentTimeMillis() - lastMsgTsMs) / 1000
    }

    /**
     * Многотемный трекер одного соединения. Обновления атомарны (synchronized),
     * чтение — снимок Map (immutable copy) для Compose.
     */
    class Tracker(val kinds: Set<Kind>) {
        private val lock = Any()
        private val state = HashMap<Kind, Topic>()

        init { synchronized(lock) { kinds.forEach { state[it] = Topic() } } }

        fun onConnecting() = updateAll(CONNECTING, subscribed = false)

        fun onOpen() = updateAll(CONNECTED_NO_DATA, subscribed = true)

        fun onFailure(err: String?) = updateAll(ERROR, err = err ?: "ошибка сети")

        fun onClosed(reason: String?) = updateAll(DISCONNECTED, err = reason ?: "")

        /** Валидное сообщение по теме: единственный путь к LIVE. */
        fun onValidMessage(kind: Kind) {
            synchronized(lock) {
                val t = state[kind] ?: Topic()
                state[kind] = t.copy(
                    status = LIVE,
                    lastMsgTsMs = System.currentTimeMillis(),
                    events = t.events + 1,
                    error = ""
                )
            }
        }

        /** STALE-детектор: вызывается периодически из движка/UI-тика. */
        fun refreshStale() {
            synchronized(lock) {
                for ((kind, t) in state) {
                    if (t.status == LIVE && t.ageSec > staleAfterSec(kind)) {
                        state[kind] = t.copy(status = STALE)
                    }
                }
            }
        }

        fun snapshot(): Map<Kind, Topic> = synchronized(lock) { HashMap(state) }

        /** Честный сводный статус соединения (худший из критичных тем). */
        fun overall(): Topic {
            val snap = snapshot()
            if (snap.isEmpty()) return Topic(status = CONNECTING)
            // порядок «худшести»: ERROR/DISCONNECTED > STALE > CONNECTING/NO_DATA > LIVE
            val rank = mapOf(ERROR to 0, DISCONNECTED to 1, STALE to 2, CONNECTING to 3, CONNECTED_NO_DATA to 4, LIVE to 5)
            val worst = snap.values.minByOrNull { rank[it.status] ?: 3 } ?: Topic()
            val last = snap.values.filter { it.lastMsgTsMs > 0 }.maxOfOrNull { it.lastMsgTsMs } ?: 0L
            return worst.copy(lastMsgTsMs = last)
        }

        private fun updateAll(status: String, err: String = "", subscribed: Boolean = false) {
            synchronized(lock) {
                for (kind in kinds) {
                    val t = state[kind] ?: Topic()
                    state[kind] = t.copy(
                        status = status,
                        error = if (status == ERROR) err else t.error,
                        subscribed = subscribed
                    )
                }
            }
        }
    }
}
