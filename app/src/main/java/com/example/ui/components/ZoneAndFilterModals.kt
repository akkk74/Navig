package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.GeoZone
import com.example.data.model.GeoZoneKind
import com.example.data.model.VehicleType
import com.example.domain.GeoAnalytics
import com.example.ui.FleetUiState
import com.example.ui.FleetViewModel
import com.example.ui.theme.ExcavatorAmber
import com.example.ui.theme.ExcavatorOrange
import com.example.ui.theme.FuelCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KruIndigo
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.ShiftViolet
import kotlin.math.roundToInt

private val ZONE_SWATCHES = listOf(
    "#2563EB" to Color(0xFF2563EB),
    "#16A34A" to Color(0xFF16A34A),
    "#F97316" to Color(0xFFF97316),
    "#EF4444" to Color(0xFFEF4444),
    "#A855F7" to Color(0xFFA855F7),
    "#06B6D4" to Color(0xFF06B6D4)
)

@Composable
fun ZoneContentsDialog(
    zone: GeoZone,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val nowMs = remember { System.currentTimeMillis() }
    val inside = remember(zone, state.vehicles) {
        state.vehicles.filter { v ->
            v.online &&
                (zone.kind != GeoZoneKind.EXCAVATOR || v.type == VehicleType.BELAZ) &&
                GeoAnalytics.isVehicleInZone(v, zone)
        }
    }
    val presence = state.zonePresence[zone.key].orEmpty()

    ModalGlassContainer(
        title = zone.name,
        subtitle = "${zone.kind.labelRu} · техники внутри: ${inside.size}",
        onDismiss = viewModel::closeModal
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (inside.isEmpty()) {
                    Text(
                        text = "Сейчас внутри этой зоны нет активной техники",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ext.mutedText,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    inside.forEach { v ->
                        val enterMs = presence[v.id] ?: nowMs
                        val mins = ((nowMs - enterMs).coerceAtLeast(0L) / 60000L).toInt()
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ext.subtleSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.closeModal()
                                    viewModel.selectVehicle(v.id, switchToMap = true)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PulsingStatusDot(color = vehicleStatusColor(v), pulse = v.isLoadedHaul, size = 9.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(state.displayName(v), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        text = if (v.isLoadedHaul) "ГРУЖЁНЫЙ · ${v.speed.roundToInt()} км/ч" else v.duration,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (v.isLoadedHaul) LoadedHaulRed else ext.mutedText
                                    )
                                }
                                Text(
                                    text = if (mins > 0) "$mins мин" else "<1 мин",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            if (zone.kind == GeoZoneKind.CUSTOM) {
                HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ext.subtleSurface,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable { viewModel.startEditCustomZoneMeta(zone.id) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Название / цвет", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = KruIndigo.copy(alpha = 0.14f),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable { viewModel.startReshapeCustomZone(zone.id) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Polyline, contentDescription = null, tint = KruIndigo, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Контур зоны", style = MaterialTheme.typography.labelMedium, color = KruIndigo)
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LoadedHaulRed.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clickable { viewModel.deleteCustomZone(zone.id) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = LoadedHaulRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Удалить зону", style = MaterialTheme.typography.labelLarge, color = LoadedHaulRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SaveCustomZoneDialog(
    editingZoneId: String?,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val existing = remember(editingZoneId, state.settings.customZones) {
        editingZoneId?.let { id -> state.settings.customZones.firstOrNull { it.id == id } }
    }
    var zoneName by remember(editingZoneId) { mutableStateOf(existing?.name ?: "") }
    var selectedHex by remember(editingZoneId) { mutableStateOf(existing?.colorHex ?: "#2563EB") }

    ModalGlassContainer(
        title = if (existing != null) "Редактировать зону" else "Подписать зону",
        subtitle = "Укажите название и цвет на карте",
        onDismiss = viewModel::closeModal
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = zoneName,
                onValueChange = { zoneName = it },
                label = { Text("Название зоны") },
                placeholder = { Text("Например: Отвал №2, Ремонт, АЗС…") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цвет зоны", style = MaterialTheme.typography.labelMedium, color = LocalFleetColors.current.mutedText)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ZONE_SWATCHES.forEach { (hex, color) ->
                        val active = selectedHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (active) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { selectedHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (active) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { viewModel.saveCustomZone(zoneName, selectedHex) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KruBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Сохранить зону", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun QuickExcavatorsConfigDialog(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val excavators = remember(state.vehicles) {
        state.vehicles.filter { it.type == VehicleType.EXCAVATOR }.sortedBy { state.displayName(it) }
    }

    ModalGlassContainer(
        title = "Быстрые экскаваторы",
        subtitle = "Отметьте экскаваторы для выезжающей панели очередей",
        onDismiss = viewModel::closeModal
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (excavators.isEmpty()) {
                    Text(
                        text = "Экскаваторы не найдены в текущем списке техники",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ext.mutedText,
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    excavators.forEach { exc ->
                        val checked = exc.id in state.settings.quickExcavatorIds
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ext.subtleSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleQuickExcavator(exc.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { viewModel.toggleQuickExcavator(exc.id) })
                                Text(
                                    text = state.displayName(exc),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.selectAllQuickExcavators() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Отметить всех", style = MaterialTheme.typography.labelMedium, color = OnlineEmerald)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.clearQuickExcavators() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Сбросить выбор", style = MaterialTheme.typography.labelMedium, color = ExcavatorAmber)
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ext.subtleSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clickable { viewModel.resetRenamedExcavators() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Сбросить переименованные экскаваторы", style = MaterialTheme.typography.labelMedium, color = LoadedHaulRed)
                    }
                }
                Button(
                    onClick = viewModel::closeModal,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KruBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Готово", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun MapTypeFilterDialog(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val counts = remember(state.vehicles) {
        VehicleType.entries.associateWith { vt -> state.vehicles.count { it.type == vt } }
    }

    ModalGlassContainer(
        title = "Фильтр отображения",
        subtitle = "Какие типы техники показывать на карте и в списке",
        onDismiss = viewModel::closeModal
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VehicleType.entries.forEach { vt ->
                    val enabled = state.settings.mapTypeFilter[vt] ?: true
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleMapType(vt) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = vehicleTypeIcon(vt),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(vt.labelRu, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "В парке: ${counts[vt] ?: 0}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ext.mutedText
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { viewModel.toggleMapType(vt) },
                                colors = SwitchDefaults.colors(checkedTrackColor = OnlineEmerald, checkedThumbColor = Color.White)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.setAllMapTypes(true) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Выбрать все", style = MaterialTheme.typography.labelMedium, color = OnlineEmerald)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.setAllMapTypes(false) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Снять все", style = MaterialTheme.typography.labelMedium, color = LoadedHaulRed)
                        }
                    }
                }
                Button(
                    onClick = viewModel::closeModal,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KruBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Готово", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun NotificationsDialog(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current

    ModalGlassContainer(
        title = "Уведомления",
        subtitle = "Избранные, заправки и пересменки техники",
        onDismiss = viewModel::closeModal,
        headerExtra = {
            if (state.notifications.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ext.subtleSurface,
                    modifier = Modifier.clickable { viewModel.clearNotifications() }
                ) {
                    Text(
                        text = "Очистить",
                        style = MaterialTheme.typography.labelSmall,
                        color = LoadedHaulRed,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.notifications.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = ext.mutedText, modifier = Modifier.size(36.dp))
                    Text("Пока нет уведомлений", style = MaterialTheme.typography.bodyMedium, color = ext.mutedText)
                }
            } else {
                state.notifications.forEach { n ->
                    val accent = when (n.kind) {
                        "fuel" -> FuelCyan
                        "refuel" -> OnlineEmerald
                        "shift_change" -> ShiftViolet
                        else -> ExcavatorOrange
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.closeModal()
                                if (n.vehId.isNotBlank()) viewModel.selectVehicle(n.vehId, switchToMap = true)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (n.kind == "fuel" || n.kind == "refuel") Icons.Default.LocalGasStation else Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(
                                        text = n.timeStr,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        color = ext.mutedText
                                    )
                                }
                                Text(n.body, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                            }
                        }
                    }
                }
            }
        }
    }
}
