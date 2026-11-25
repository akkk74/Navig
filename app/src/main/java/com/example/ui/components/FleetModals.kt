package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Vehicle
import com.example.domain.GeoAnalytics
import com.example.ui.ActiveModal
import com.example.ui.FleetUiState
import com.example.ui.FleetViewModel
import com.example.ui.ToastTone
import com.example.ui.theme.ExcavatorAmber
import com.example.ui.theme.ExcavatorOrange
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KruIndigo
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OfflineSlate
import com.example.ui.theme.OnlineEmerald
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FleetModalHost(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    when (val modal = state.activeModal) {
        ActiveModal.None -> Unit
        is ActiveModal.VehicleContext -> {
            val v = state.vehicles.firstOrNull { it.id == modal.vehicleId } ?: return
            VehicleContextDialog(vehicle = v, state = state, viewModel = viewModel)
        }
        is ActiveModal.VehicleInfo -> {
            val v = state.vehicles.firstOrNull { it.id == modal.vehicleId } ?: return
            VehicleInfoDialog(vehicle = v, state = state, viewModel = viewModel)
        }
        is ActiveModal.RoutePicker -> {
            val v = state.vehicles.firstOrNull { it.id == modal.vehicleId } ?: return
            RoutePickerDialog(vehicle = v, state = state, viewModel = viewModel)
        }
        is ActiveModal.RenameVehicle -> {
            val v = state.vehicles.firstOrNull { it.id == modal.vehicleId } ?: return
            RenameVehicleDialog(vehicle = v, state = state, viewModel = viewModel)
        }
        ActiveModal.FleetSummary -> FleetSummaryDialog(state = state, viewModel = viewModel)
        is ActiveModal.ZoneContents -> ZoneContentsDialog(zone = modal.zone, state = state, viewModel = viewModel)
        is ActiveModal.SaveCustomZone -> SaveCustomZoneDialog(editingZoneId = modal.editingZoneId, state = state, viewModel = viewModel)
        ActiveModal.QuickExcavatorsConfig -> QuickExcavatorsConfigDialog(state = state, viewModel = viewModel)
        ActiveModal.MapTypeFilter -> MapTypeFilterDialog(state = state, viewModel = viewModel)
        ActiveModal.Notifications -> NotificationsDialog(state = state, viewModel = viewModel)
    }
}

@Composable
internal fun ModalGlassContainer(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    headerExtra: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val ext = LocalFleetColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        GlassSurface(
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .heightIn(max = 620.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(ext.mutedText.copy(alpha = 0.35f))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = ext.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    headerExtra?.invoke()
                    Surface(
                        shape = CircleShape,
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(onClick = onDismiss)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.45f))
                content()
            }
        }
    }
}

@Composable
private fun VehicleContextDialog(
    vehicle: Vehicle,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val clipboard = LocalClipboardManager.current
    val isFav = state.isFavorite(vehicle.id)
    val coordsText = String.format(Locale.US, "%.5f, %.5f", vehicle.lat, vehicle.lng)

    ModalGlassContainer(
        title = state.displayName(vehicle),
        subtitle = "${vehicle.type.labelRu} · ${if (vehicle.isLoadedHaul) "ГРУЖЁНЫЙ ХОД" else vehicle.duration}",
        onDismiss = viewModel::closeModal,
        headerExtra = {
            Surface(
                shape = CircleShape,
                color = ExcavatorAmber.copy(alpha = 0.16f),
                modifier = Modifier
                    .size(34.dp)
                    .clickable { viewModel.toggleFavorite(vehicle.id) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isFav) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = "В избранное",
                        tint = ExcavatorAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionMenuRow(
                icon = Icons.Default.Route,
                title = "История маршрута",
                subtitle = "Трек за период, стоянки, рейсы и воспроизведение",
                accent = KruBlue,
                filled = true,
                onClick = { viewModel.openModal(ActiveModal.RoutePicker(vehicle.id)) }
            )
            ActionMenuRow(
                icon = Icons.Default.Info,
                title = "Информация и датчики",
                subtitle = "Топливо, вес, геозоны и все поля с сервера",
                accent = MaterialTheme.colorScheme.onSurface,
                filled = false,
                onClick = { viewModel.openModal(ActiveModal.VehicleInfo(vehicle.id)) }
            )
            ActionMenuRow(
                icon = Icons.Default.Edit,
                title = "Переименовать объект",
                subtitle = "Локальное название на карте и в списке",
                accent = MaterialTheme.colorScheme.onSurface,
                filled = false,
                onClick = { viewModel.openModal(ActiveModal.RenameVehicle(vehicle.id)) }
            )
            ActionMenuRow(
                icon = Icons.Default.ContentCopy,
                title = "Скопировать координаты",
                subtitle = coordsText,
                accent = MaterialTheme.colorScheme.onSurface,
                filled = false,
                onClick = {
                    clipboard.setText(AnnotatedString(coordsText))
                    viewModel.closeModal()
                    viewModel.showToast("Координаты скопированы: $coordsText", ToastTone.SUCCESS)
                }
            )
        }
    }
}

@Composable
private fun ActionMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit
) {
    val ext = LocalFleetColors.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (filled) KruBlue else ext.subtleSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (filled) Color.White else accent,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (filled) Color.White else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (filled) Color.White.copy(alpha = 0.85f) else ext.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VehicleInfoDialog(
    vehicle: Vehicle,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val sensors = state.sensorsCache[vehicle.id].orEmpty()
    val loadingSensors = state.loadingSensorsForId == vehicle.id
    val inZones = remember(vehicle, state.dynamicZones, state.serverZones) {
        (state.dynamicZones + state.serverZones).filter { GeoAnalytics.isVehicleInZone(vehicle, it) }
    }

    ModalGlassContainer(
        title = state.displayName(vehicle),
        subtitle = "ID: ${vehicle.id} · ${vehicle.type.labelRu}",
        onDismiss = viewModel::closeModal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Core Telemetry Summary
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryPill(
                    label = "Статус",
                    value = if (vehicle.isLoadedHaul) "ГРУЖЁНЫЙ" else if (vehicle.online) vehicle.duration else "Нет связи",
                    accent = if (vehicle.isLoadedHaul) LoadedHaulRed else if (vehicle.online) OnlineEmerald else OfflineSlate,
                    modifier = Modifier.weight(1f)
                )
                TelemetryPill(
                    label = "Скорость",
                    value = "${vehicle.speed.roundToInt()} км/ч",
                    accent = KruBlue,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryPill(
                    label = "Вес груза",
                    value = vehicle.weight?.let { String.format(Locale.US, "%.1f т", it) } ?: "—",
                    accent = if (vehicle.isLoadedHaul) LoadedHaulRed else KruIndigo,
                    modifier = Modifier.weight(1f)
                )
                TelemetryPill(
                    label = "Топливо",
                    value = vehicle.fuel.ifBlank { "—" },
                    accent = ExcavatorAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            InfoKeyValueRow("Координаты", String.format(Locale.US, "%.5f, %.5f", vehicle.lat, vehicle.lng))
            InfoKeyValueRow("Адрес / Участок", vehicle.address)
            InfoKeyValueRow("Последний пакет", vehicle.lastTime)
            if (vehicle.course != null) {
                InfoKeyValueRow("Курс", "${vehicle.course.roundToInt()}°")
            }
            if (inZones.isNotEmpty()) {
                InfoKeyValueRow("В геозонах", inZones.joinToString(", ") { it.shortName })
            }

            HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

            Text("ДАТЧИКИ (GetSensorsMobile)", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
            if (loadingSensors) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("Загрузка датчиков…", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                }
            } else if (sensors.isEmpty()) {
                Text("Дополнительные датчики не переданы сервером", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
            } else {
                sensors.forEach { s -> InfoKeyValueRow(s.name, s.value) }
            }

            if (vehicle.rawFields.isNotEmpty()) {
                HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))
                Text("ПОЛЯ С СЕРВЕРА (${vehicle.rawFields.size})", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
                vehicle.rawFields.entries.take(30).forEach { (k, v) ->
                    InfoKeyValueRow(k, v)
                }
            }
        }
    }
}

@Composable
private fun InfoKeyValueRow(label: String, value: String) {
    val ext = LocalFleetColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ext.subtleSurface)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ext.mutedText,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@Composable
private fun RoutePickerDialog(
    vehicle: Vehicle,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val clipboard = LocalClipboardManager.current
    val initialShift = remember { GeoAnalytics.currentShiftWindow() }
    var fromMs by remember { mutableLongStateOf(initialShift.startMs) }
    var toMs by remember { mutableLongStateOf(initialShift.endMs) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")) }

    fun applyPreset(preset: String) {
        val nowMs = System.currentTimeMillis()
        when (preset) {
            "3h" -> {
                fromMs = nowMs - 3L * 3600L * 1000L
                toMs = nowMs
            }
            "shift" -> {
                val sw = GeoAnalytics.currentShiftWindow(nowMs)
                fromMs = sw.startMs
                toMs = sw.endMs
            }
            "prev_shift" -> {
                val psw = GeoAnalytics.previousShiftWindow(nowMs)
                fromMs = psw.startMs
                toMs = psw.endMs
            }
            "today" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                fromMs = cal.timeInMillis
                toMs = nowMs
            }
        }
    }

    ModalGlassContainer(
        title = "История маршрута",
        subtitle = "${state.displayName(vehicle)} · ${initialShift.label}",
        onDismiss = viewModel::closeModal
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("shift" to "Тек. смена", "prev_shift" to "Прошл. см.", "3h" to "3 часа", "today" to "Сегодня").forEach { (id, lbl) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { applyPreset(id) }
                    ) {
                        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(lbl, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }

            // From / To adjusters (+/- 1 hour steps)
            DateStepRow(
                label = "От",
                formatted = fmt.format(Date(fromMs)),
                onMinus = { fromMs -= 3600_000L },
                onPlus = { if (fromMs + 3600_000L < toMs) fromMs += 3600_000L }
            )
            DateStepRow(
                label = "До",
                formatted = fmt.format(Date(toMs)),
                onMinus = { if (toMs - 3600_000L > fromMs) toMs -= 3600_000L },
                onPlus = { toMs += 3600_000L }
            )

            Button(
                onClick = { viewModel.requestRouteHistory(vehicle.id, fromMs, toMs) },
                enabled = !state.isLoadingRoute,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KruBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("build_route_button")
            ) {
                if (state.isLoadingRoute) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Загрузка трека…")
                } else {
                    Text("Показать маршрут", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (state.routeDebugText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = state.routeDebugText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp),
                        color = ExcavatorAmber
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ext.subtleSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(state.routeDebugText))
                            viewModel.showToast("Диагностика скопирована")
                        }
                ) {
                    Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Скопировать ответ сервера", style = MaterialTheme.typography.labelMedium, color = ExcavatorAmber)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateStepRow(
    label: String,
    formatted: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val ext = LocalFleetColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ext.subtleSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = ext.mutedText)
            Text(
                text = formatted,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable(onClick = onMinus)
            ) {
                Text("-1ч", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable(onClick = onPlus)
            ) {
                Text("+1ч", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun RenameVehicleDialog(
    vehicle: Vehicle,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    var text by remember(vehicle.id) { mutableStateOf(state.displayName(vehicle)) }

    ModalGlassContainer(
        title = "Переименовать объект",
        subtitle = "Исходное имя: ${vehicle.name}",
        onDismiss = viewModel::closeModal
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Новое название") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ext.subtleSurface,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable { viewModel.renameVehicle(vehicle.id, "") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Сбросить имя", style = MaterialTheme.typography.labelLarge, color = ExcavatorAmber)
                    }
                }
                Button(
                    onClick = { viewModel.renameVehicle(vehicle.id, text) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KruBlue),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("Сохранить", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun FleetSummaryDialog(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val total = state.vehicles.size
    val online = state.vehicles.count { it.online }
    val moving = state.vehicles.count { it.isMoving }
    val loaded = state.vehicles.count { it.isLoadedHaul }
    val stopped = state.vehicles.count { it.online && !it.isMoving }
    val offline = total - online

    ModalGlassContainer(
        title = "Сводка парка и очереди",
        subtitle = "Всего объектов: $total · Онлайн: $online",
        onDismiss = viewModel::closeModal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryPill("Гружёный ход", "$loaded", LoadedHaulRed, modifier = Modifier.weight(1f))
                TelemetryPill("В движении", "$moving", OnlineEmerald, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryPill("Стоянка", "$stopped", KruBlue, modifier = Modifier.weight(1f))
                TelemetryPill("Нет связи", "$offline", OfflineSlate, modifier = Modifier.weight(1f))
            }

            HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

            Text("ОЧЕРЕДИ У ЭКСКАВАТОРОВ", style = MaterialTheme.typography.labelMedium, color = ExcavatorOrange)
            if (state.excavatorQueues.isEmpty()) {
                Text("Нет активных экскаваторов на линии", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
            } else {
                state.excavatorQueues.forEach { q ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.closeModal()
                                viewModel.selectVehicle(q.excavator.id, switchToMap = true)
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(state.displayName(q.excavator), style = MaterialTheme.typography.titleSmall)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (q.belazList.size >= 3) LoadedHaulRed else ExcavatorOrange
                                ) {
                                    Text(
                                        text = "${q.belazList.size} БелАЗ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (q.belazList.isNotEmpty()) {
                                q.belazList.forEach { b ->
                                    val mins = q.waitTimesMin[b.id] ?: 0
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = if (b.isLoadedHaul) LoadedHaulRed else KruBlue, modifier = Modifier.size(14.dp))
                                            Text(state.displayName(b), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(
                                            text = if (mins > 0) "$mins мин" else "<1 мин",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                            color = ext.mutedText
                                        )
                                    }
                                }
                            } else {
                                Text("Очереди нет", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                            }
                        }
                    }
                }
            }
        }
    }
}
