package com.enamind.ai.core

import com.enamind.ai.bybit.ContractCatalogRepo

/** A symbol alone is not an identity: BTCUSDT spot and linear have different books. */
data class ActiveInstrument(
    val symbol: String = "BTCUSDT",
    val category: String = "linear",
    val venue: String = "Bybit",
    val baseCoin: String = symbol.removeSuffix("USDT"),
    val quoteCoin: String = "USDT",
    val tickSize: Double? = null,
    val qtyStep: Double? = null,
    val maxLeverage: Double = if (category == "spot") 1.0 else 1.0,
    val makerFeePct: Double = if (category == "spot") 0.1 else 0.036,
    val takerFeePct: Double = if (category == "spot") 0.1 else 0.1,
    val generation: Long = 0L
) {
    init { require(category in setOf("linear", "spot", "inverse")) }
    val key: String get() = "$venue:$category:$symbol"
    val assetClass: String get() = when {
        baseCoin in setOf("XAU", "XAG", "PAXG", "XAUT", "GOLD", "SILVER") -> "Металлы / RWA"
        category == "spot" -> "Спот"
        else -> "Крипто"
    }

    companion object {
        fun from(info: ContractCatalogRepo.ContractInfo, generation: Long = 0L) = ActiveInstrument(
            symbol = info.symbol, category = info.category, venue = info.source,
            baseCoin = info.baseCoin, quoteCoin = info.quoteCoin,
            tickSize = info.tickSize, qtyStep = info.qtyStep,
            maxLeverage = if (info.category == "spot") 1.0 else info.maxLeverage ?: 1.0,
            generation = generation
        )
    }
}
