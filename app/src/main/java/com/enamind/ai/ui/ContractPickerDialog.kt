package com.enamind.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.core.AppState
import com.enamind.ai.core.Settings

/**
 * Поиск и выбор любого доступного USDT-контракта биржи с закреплением избранного.
 */
@Composable
fun ContractPickerDialog(
    state: AppState,
    currentSymbol: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val catalogState by state.contractCatalog.state.collectAsState()
    val favorites by state.favoriteSymbols.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Все") }
    val active by state.activeInstrument.collectAsState()

    BackHandler(onBack = onDismiss)

    val itemsToShow = remember(catalogState.contracts, favorites, query, category) {
        val baseList: List<ContractCatalogRepo.ContractInfo> = if (catalogState.contracts.isNotEmpty()) {
            catalogState.contracts
        } else {
            Settings.SYMBOLS.map {
                ContractCatalogRepo.ContractInfo(
                    symbol = it,
                    baseCoin = it.removeSuffix("USDT"),
                    quoteCoin = "USDT",
                    tickSize = null,
                    minOrderQty = null,
                    maxLeverage = null,
                    source = "базовый"
                )
            }
        }
        val cleanQuery = query.trim().uppercase()
        val filtered = if (cleanQuery.isBlank()) {
            baseList
        } else {
            baseList.filter {
                it.symbol.contains(cleanQuery) || it.baseCoin.contains(cleanQuery)
            }
        }
        filtered.filter { c -> when (category) {
            "Крипто" -> c.assetClass == "Крипто" && c.category == "linear"
            "Металлы / RWA" -> c.assetClass == "Металлы / RWA"
            "Спот" -> c.category == "spot"
            "Инверсные" -> c.category == "inverse"
            "Избранное" -> c.key in favorites || c.symbol in favorites
            else -> true
        } }.sortedWith(
            compareByDescending<ContractCatalogRepo.ContractInfo> { favorites.contains(it.symbol) }
                .thenByDescending { it.symbol == currentSymbol }
                .thenBy { it.symbol }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 580.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Все контракты биржи",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${catalogState.contracts.size} пар · источник: ${catalogState.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (catalogState.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(
                            onClick = { state.refreshContractsCatalog(force = true) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("contracts_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Обновить список контрактов",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("contracts_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Закрыть выбор контракта",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contracts_search_input"),
                    singleLine = true,
                    placeholder = { Text("Поиск пары (BTC, SUI, WIF, ONDO…)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                    )
                )

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Все", "Крипто", "Металлы / RWA", "Спот", "Инверсные", "Избранное").forEach { label ->
                        FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                catalogState.error?.let { err ->
                    Text(
                        err,
                        fontSize = 11.sp,
                        color = enaAmberText()
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (itemsToShow.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "По запросу '$query' контракты не найдены",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(itemsToShow, key = { it.key }) { c ->
                            val isSelected = c.key == active.key
                            val isFav = favorites.contains(c.key) || favorites.contains(c.symbol)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (state.selectInstrument(c)) onSelect(c.symbol)
                                        onDismiss()
                                    }
                                    .testTag("contract_row_${c.symbol}"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.background
                                },
                                border = BorderStroke(
                                    0.8.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                c.symbol,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    c.source,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        val metaParts = buildList {
                                            c.tickSize?.let { add("шаг $it") }
                                            c.maxLeverage?.let { add("до ${it.toInt()}x") }
                                        }
                                        if (metaParts.isNotEmpty()) {
                                            Text(
                                                metaParts.joinToString(" · "),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { state.toggleFavoriteSymbol(c.key) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("contract_fav_${c.symbol}")
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                            contentDescription = if (isFav) "Убрать из избранного" else "В избранное",
                                            tint = if (isFav) enaAmberText() else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
