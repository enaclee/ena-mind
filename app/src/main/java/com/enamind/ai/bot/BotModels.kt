package com.enamind.ai.bot

import com.enamind.ai.core.NetworkHub
import java.util.Locale

enum class BotMode(
    val id: String,
    val labelRu: String,
    val shortLabel: String,
    val restBaseUrl: String,
    val wsPrivateUrl: String,
    val isLiveExchange: Boolean,
    val requiresKeys: Boolean
) {
    PAPER(
        id = "paper",
        labelRu = "Симулятор (Paper)",
        shortLabel = "PAPER",
        restBaseUrl = "",
        wsPrivateUrl = "",
        isLiveExchange = false,
        requiresKeys = false
    ),
    BYBIT_DEMO(
        id = "bybit_demo",
        labelRu = "Bybit Demo",
        shortLabel = "DEMO",
        restBaseUrl = "https://api-demo.bybit.com",
        wsPrivateUrl = "wss://stream-demo.bybit.com/v5/private",
        isLiveExchange = true,
        requiresKeys = true
    ),
    BYBIT_TESTNET(
        id = "bybit_testnet",
        labelRu = "Bybit Testnet",
        shortLabel = "TESTNET",
        restBaseUrl = "https://api-testnet.bybit.com",
        wsPrivateUrl = "wss://stream-testnet.bybit.com/v5/private",
        isLiveExchange = true,
        requiresKeys = true
    ),
    BYBIT_MAINNET(
        id = "bybit_mainnet",
        labelRu = "Bybit Mainnet (Реал)",
        shortLabel = "MAINNET",
        restBaseUrl = "https://api.bybit.com",
        wsPrivateUrl = "wss://stream.bybit.com/v5/private",
        isLiveExchange = true,
        requiresKeys = true
    );

    companion object {
        fun fromId(id: String?): BotMode =
            entries.firstOrNull { it.id == id } ?: PAPER
    }
}

enum class MarginMode(val labelRu: String, val bybitUtaValue: String, val classicTradeMode: Int) {
    ISOLATED("Изолированная", "ISOLATED_MARGIN", 1),
    CROSS("Кросс", "REGULAR_MARGIN", 0);

    companion object {
        fun fromName(name: String?): MarginMode =
            entries.firstOrNull { it.name == name } ?: ISOLATED
    }
}

enum class TargetBasis(val labelRu: String) {
    NET_ROE_PCT("% к марже (ROE)"),
    PRICE_MOVE_PCT("% движения цены");

    companion object {
        fun fromName(name: String?): TargetBasis =
            entries.firstOrNull { it.name == name } ?: NET_ROE_PCT
    }
}

enum class DirectionPolicy(val labelRu: String) {
    BOTH("Long + Short"),
    LONG_ONLY("Только Long"),
    SHORT_ONLY("Только Short");

    companion object {
        fun fromName(name: String?): DirectionPolicy =
            entries.firstOrNull { it.name == name } ?: BOTH
    }
}

enum class HudDisplayMode(val labelRu: String) {
    OUTSIDE_APP_ONLY("Только поверх других окон"),
    ALWAYS("Всегда");

    companion object {
        fun fromName(name: String?): HudDisplayMode =
            entries.firstOrNull { it.name == name } ?: OUTSIDE_APP_ONLY
    }
}

enum class TimeoutBehavior(val labelRu: String) {
    KEEP_EXCHANGE_PROTECTION("Оставить под защитой биржи"),
    CLOSE_MARKET("Закрыть по рынку");

    companion object {
        fun fromName(name: String?): TimeoutBehavior =
            entries.firstOrNull { it.name == name } ?: KEEP_EXCHANGE_PROTECTION
    }
}

enum class TradeSide(val labelRu: String, val bybitSide: String, val oppositeBybitSide: String) {
    BUY("LONG", "Buy", "Sell"),
    SELL("SHORT", "Sell", "Buy");

    companion object {
        fun fromName(name: String?): TradeSide =
            if (name == SELL.name || name.equals("Sell", ignoreCase = true)) SELL else BUY
    }
}

enum class HudGlowZone {
    NEUTRAL, // Нет завершённых сделок (нейтральный контур)
    RED,     // Winrate < 50%
    YELLOW,  // 50% <= Winrate <= 65%
    GREEN;   // Winrate > 65%

    companion object {
        fun fromStats(totalTrades: Int, winratePct: Double): HudGlowZone = when {
            totalTrades <= 0 -> NEUTRAL
            winratePct < 50.0 -> RED
            winratePct <= 65.0 -> YELLOW
            else -> GREEN
        }
    }
}

data class BybitCredentialSlot(
    val apiKey: String = "",
    val apiSecret: String = "",
    val accountLabel: String = ""
) {
    val hasKeys: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()

    fun maskedKey(): String {
        val t = apiKey.trim()
        return when {
            t.isBlank() -> "не задан"
            t.length <= 8 -> "${t.take(2)}***"
            else -> "${t.take(4)}…${t.takeLast(4)}"
        }
    }
}

data class BotConfig(
    val mode: BotMode = BotMode.PAPER,
    val symbol: String = "BTCUSDT",
    val capitalUsdt: Double = 50.0,
    val leverage: Int = 10,
    val marginMode: MarginMode = MarginMode.ISOLATED,
    val targetBasis: TargetBasis = TargetBasis.NET_ROE_PCT,
    val directionPolicy: DirectionPolicy = DirectionPolicy.BOTH,
    val minNetProfitPct: Double = 0.5,
    val maxProfitPct: Double = 3.5,
    val trailingGivebackPct: Double = 0.35,
    val maxHoldWaitMin: Int = 30,
    // По решению пользователя: разрешение отключать Stop-Loss во всех режимах (Paper/Demo/Testnet/Mainnet)
    val stopLossEnabled: Boolean = false,
    val stopLossPct: Double = 1.5,
    // Дублировать защитный безубыточный/трейлинг-стоп на сервере Bybit после выхода >= minNetProfitPct
    val serverTrailingGuardEnabled: Boolean = true,
    val dailyMaxLossPct: Double = 3.0,
    val confirmSignalSec: Int = 6,
    val requireGlowConsensus: Boolean = true,
    val requireRadarNotFlat: Boolean = true,
    val allowCounterAbsorption: Boolean = false,
    // Плавающий HUD поверх окон можно включить заранее до запуска бота
    val hudEnabled: Boolean = false,
    val hudDisplayMode: HudDisplayMode = HudDisplayMode.OUTSIDE_APP_ONLY,
    val timeoutBehavior: TimeoutBehavior = TimeoutBehavior.KEEP_EXCHANGE_PROTECTION,
    val paperStartBalanceUsdt: Double = 1000.0
)

data class ActivePosition(
    val id: String,
    val mode: BotMode,
    val symbol: String,
    val side: TradeSide,
    val qty: Double,
    val entryPrice: Double,
    val markPrice: Double,
    val leverage: Int,
    val marginMode: MarginMode,
    val usedMarginUsdt: Double,
    val openedAtMs: Long,
    val feeRatePct: Double, // суммарная комиссия вход+выход в % от объёма (напр. 0.11%)
    val estFeeUsdt: Double,
    val grossPnlUsdt: Double,
    val netPnlUsdt: Double,
    val netPnlPct: Double,
    val peakNetPnlPct: Double,
    val trailingFloorPct: Double? = null,
    val tpMinPrice: Double,
    val tpMaxPrice: Double,
    val trailFloorPrice: Double? = null,
    val stopLossPrice: Double? = null,
    val estLiquidationPrice: Double? = null,
    val serverTrailingStopPrice: Double? = null,
    val entryReason: String = ""
)

data class BotTradeRecord(
    val id: String,
    val mode: BotMode,
    val symbol: String,
    val side: TradeSide,
    val qty: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val leverage: Int,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val grossPnlUsdt: Double,
    val feesUsdt: Double,
    val netPnlUsdt: Double,
    val netPnlPct: Double,
    val exitReason: String,
    val won: Boolean
)

data class ModeWinrateStats(
    val mode: BotMode,
    val totalTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winratePct: Double = 0.0,
    val totalNetPnlUsdt: Double = 0.0,
    val dailyRealizedLossUsdt: Double = 0.0,
    val lastDayUtc: String = ""
) {
    val glowZone: HudGlowZone get() = HudGlowZone.fromStats(totalTrades, winratePct)
}

data class BotTradeMarker(
    val tsMs: Long,
    val price: Double,
    val side: TradeSide,
    val isEntry: Boolean,
    val won: Boolean? = null
)

data class BotChartOverlay(
    val symbol: String = "",
    val entryPrice: Double? = null,
    val side: TradeSide? = null,
    val tpMinPrice: Double? = null,
    val tpMaxPrice: Double? = null,
    val trailFloorPrice: Double? = null,
    val stopLossPrice: Double? = null,
    val estLiquidationPrice: Double? = null,
    val markers: List<BotTradeMarker> = emptyList()
)

data class BotStatusNotification(
    val title: String,
    val body: String,
    val tsMs: Long = System.currentTimeMillis()
)

data class BotUiState(
    val running: Boolean = false,
    val closing: Boolean = false,
    val config: BotConfig = BotConfig(),
    val statusText: String = "Остановлен · готов к запуску",
    val walletBalanceUsdt: Double? = null,
    val feeRateSidePct: Double = 0.055,
    val exchangeConnected: Boolean = false,
    val isUtaAccount: Boolean? = null,
    val activePosition: ActivePosition? = null,
    val statsByMode: Map<BotMode, ModeWinrateStats> = BotMode.entries.associateWith { ModeWinrateStats(it) },
    val recentTrades: List<BotTradeRecord> = emptyList(),
    val chartOverlay: BotChartOverlay = BotChartOverlay(),
    val lastError: String? = null,
    val lastCheckNote: String = "",
    val mainnetConfirmedSession: Boolean = false,
    val networkHealthOk: Boolean = true
) {
    val activeModeStats: ModeWinrateStats
        get() = statsByMode[config.mode] ?: ModeWinrateStats(config.mode)

    val isNetworkRateLimited: Boolean
        get() = NetworkHub.isAnyExchangeCooldown()

    fun formattedBalance(): String = walletBalanceUsdt?.let {
        String.format(Locale.US, "%.2f USDT", it)
    } ?: "—"
}
