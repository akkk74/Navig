package com.example.ui.objects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ListActFilter
import com.example.data.model.ListConnFilter
import com.example.data.model.ListTypeFilter
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType
import com.example.ui.ActiveModal
import com.example.ui.FleetUiState
import com.example.ui.FleetViewModel
import com.example.ui.components.GlassSurface
import com.example.ui.components.PulsingStatusDot
import com.example.ui.components.SegmentedPillRow
import com.example.ui.components.vehicleStatusColor
import com.example.ui.components.vehicleTypeIcon
import com.example.ui.theme.ExcavatorAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KruIndigo
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OnlineEmerald
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ObjectsScreen(
    state: FleetUiState,
    viewModel: FleetViewModel,
    bottomDockClearance: Dp,
    modifier: Modifier = Modifier
) {
    val ext = LocalFleetColors.current

    val filteredVehicles = remember(
        state.vehicles,
        state.listSearchQuery,
        state.listTypeFilter,
        state.listConnFilter,
        state.listActFilter,
        state.settings.favorites,
        state.settings.customNames,
        state.settings.mapTypeFilter
    ) {
        val q = state.listSearchQuery.trim().lowercase(Locale.ROOT)
        state.vehicles.filter { v ->
            if (state.settings.mapTypeFilter[v.type] == false) return@filter false

            val typeOk = when (state.listTypeFilter) {
                ListTypeFilter.ALL -> true
                ListTypeFilter.FAV -> state.isFavorite(v.id)
                ListTypeFilter.EXC -> v.type == VehicleType.EXCAVATOR
                ListTypeFilter.FUEL -> v.type == VehicleType.FUEL
                ListTypeFilter.OTHER -> v.type != VehicleType.EXCAVATOR && v.type != VehicleType.FUEL
            }
            if (!typeOk) return@filter false

            val connOk = when (state.listConnFilter) {
                ListConnFilter.ALL -> true
                ListConnFilter.ONLINE -> v.online
                ListConnFilter.OFFLINE -> !v.online
            }
            if (!connOk) return@filter false

            val actOk = when (state.listActFilter) {
                ListActFilter.ALL -> true
                ListActFilter.MOVING -> v.isMoving
                ListActFilter.STOPPED -> !v.isMoving
            }
            if (!actOk) return@filter false

            if (q.isEmpty()) true
            else state.displayName(v).lowercase(Locale.ROOT).contains(q) ||
                v.name.lowercase(Locale.ROOT).contains(q) ||
                v.address.lowercase(Locale.ROOT).contains(q)
        }.sortedWith(
            compareByDescending<Vehicle> { state.isFavorite(it.id) }
                .thenByDescending { it.isLoadedHaul }
                .thenByDescending { it.online }
                .thenBy { state.displayName(it) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Header Glass Card
        GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "Объекты", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "(${filteredVehicles.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = ext.mutedText
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ext.subtleSurface,
                            modifier = Modifier
                                .testTag("objects_filter_button")
                                .clickable { viewModel.openModal(ActiveModal.MapTypeFilter) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "Фильтр типов", modifier = Modifier.size(16.dp))
                                Text("Фильтр", style = MaterialTheme.typography.labelMedium)
                                if (state.activeFilterCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(KruBlue),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${state.activeFilterCount}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            modifier = Modifier
                                .testTag("objects_refresh_button")
                                .clickable { viewModel.manualRefresh() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Обновить список",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Обновить", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // Search input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ext.subtleSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = ext.mutedText, modifier = Modifier.size(18.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.listSearchQuery.isEmpty()) {
                            Text("Поиск по названию или адресу…", style = MaterialTheme.typography.bodyMedium, color = ext.mutedText)
                        }
                        BasicTextField(
                            value = state.listSearchQuery,
                            onValueChange = viewModel::onListSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("objects_search_input")
                        )
                    }
                    if (state.listSearchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Очистить",
                            tint = ext.mutedText,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { viewModel.onListSearchQueryChange("") }
                        )
                    }
                }

                // Type Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val chips = listOf(
                        ListTypeFilter.ALL to "Все типы",
                        ListTypeFilter.FAV to "Избранные",
                        ListTypeFilter.EXC to "Экскаваторы",
                        ListTypeFilter.FUEL to "АТЗ",
                        ListTypeFilter.OTHER to "Техника"
                    )
                    chips.forEach { (filter, label) ->
                        val active = state.listTypeFilter == filter
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) MaterialTheme.colorScheme.primary else ext.subtleSurface,
                            modifier = Modifier.clickable { viewModel.setListTypeFilter(filter) }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) Color.White else ext.mutedText,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                // Dual Connection + Movement Segmented Controls
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SegmentedPillRow(
                        items = listOf(
                            ListConnFilter.ALL to "Все",
                            ListConnFilter.ONLINE to "Связь",
                            ListConnFilter.OFFLINE to "Нет"
                        ),
                        selected = state.listConnFilter,
                        onSelect = viewModel::setListConnFilter,
                        modifier = Modifier.weight(1f)
                    )
                    SegmentedPillRow(
                        items = listOf(
                            ListActFilter.ALL to "Все",
                            ListActFilter.MOVING to "Едет",
                            ListActFilter.STOPPED to "Стоит"
                        ),
                        selected = state.listActFilter,
                        onSelect = viewModel::setListActFilter,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Vehicle Cards List
        if (filteredVehicles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.LocalShipping,
                        contentDescription = null,
                        tint = ext.mutedText.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = if (state.vehicles.isEmpty()) "Подключитесь к серверу в разделе «Настройки»"
                        else "По выбранным фильтрам техника не найдена",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ext.mutedText
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = bottomDockClearance + 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredVehicles, key = { it.id }) { v ->
                    VehicleListCard(
                        vehicle = v,
                        displayName = state.displayName(v),
                        isFavorite = state.isFavorite(v.id),
                        onSelect = { viewModel.selectVehicle(v.id, switchToMap = true) },
                        onToggleFavorite = { viewModel.toggleFavorite(v.id) },
                        onOpenMenu = { viewModel.openModal(ActiveModal.VehicleContext(v.id)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleListCard(
    vehicle: Vehicle,
    displayName: String,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val ext = LocalFleetColors.current
    val statusColor = vehicleStatusColor(vehicle)

    GlassSurface(
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Vehicle Type Icon Circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(statusColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = vehicleTypeIcon(vehicle.type),
                    contentDescription = vehicle.type.labelRu,
                    tint = statusColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PulsingStatusDot(color = statusColor, pulse = vehicle.isLoadedHaul, size = 8.dp)
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (vehicle.isLoadedHaul) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = LoadedHaulRed
                        ) {
                            Text(
                                text = "ГРУЖ",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = vehicle.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (vehicle.isMoving) "${vehicle.speed.roundToInt()} км/ч" else vehicle.duration,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = if (vehicle.isLoadedHaul) LoadedHaulRed else if (vehicle.isMoving) OnlineEmerald else ext.mutedText
                    )
                    if (vehicle.weight != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Default.Scale, contentDescription = null, tint = KruIndigo, modifier = Modifier.size(12.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f т", vehicle.weight),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = if (vehicle.isLoadedHaul) LoadedHaulRed else KruIndigo
                            )
                        }
                    }
                    if (vehicle.fuel.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = ExcavatorAmber, modifier = Modifier.size(12.dp))
                            Text(
                                text = vehicle.fuel,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = ExcavatorAmber,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = vehicle.lastTime.substringAfterLast(" "),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Normal),
                        color = ext.mutedText
                    )
                }
            }

            // Star & Context menu buttons
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = "Избранное",
                        tint = ExcavatorAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenMenu),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Действия",
                        tint = ext.mutedText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
