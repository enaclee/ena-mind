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
    val maxLeverage: Double = 1.0,
    val makerFeePct: Double = if (category == "spot") 0.1 else 0.036,
    val takerFeePct: Double = if (category == "spot") 0.1 else 0.1,
    val assetClass: String = if (baseCoin in setOf("XAU", "XAG", "PAXG", "XAUT", "GOLD", "SILVER")) "Металлы / RWA" else "Крипто",
    val generation: Long = 0L
) {
    init { require(category in setOf("linear", "spot", "inverse")) }
    val key: String get() = "$venue:$category:$symbol"
    fun baseQuantity(price: Double, nativeQuantity: Double): Double =
        if (!price.isFinite() || price <= 0 || !nativeQuantity.isFinite()) 0.0
        else if (category == "inverse") nativeQuantity / price else nativeQuantity

    companion object {
        fun from(info: ContractCatalogRepo.ContractInfo, generation: Long = 0L) = ActiveInstrument(
            symbol = info.symbol, category = info.category, venue = info.source,
            baseCoin = info.baseCoin, quoteCoin = info.quoteCoin,
            tickSize = info.tickSize, qtyStep = info.qtyStep,
            maxLeverage = if (info.category == "spot") 1.0 else info.maxLeverage ?: 1.0,
            makerFeePct = if (info.category == "spot") .1 else com.enamind.ai.bot.BybitV5TradingClient.defaultFeeProfile(info.symbol).makerFeePct,
            takerFeePct = if (info.category == "spot") .1 else com.enamind.ai.bot.BybitV5TradingClient.defaultFeeProfile(info.symbol).takerFeePct,
            assetClass = info.assetClass, generation = generation
        )
    }
}
