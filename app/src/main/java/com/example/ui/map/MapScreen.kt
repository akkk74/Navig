package com.example.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionState
import com.example.data.model.DrawZoneMode
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType
import com.example.ui.ActiveModal
import com.example.ui.FleetUiState
import com.example.ui.FleetViewModel
import com.example.ui.components.GlassSurface
import com.example.ui.components.PulsingStatusDot
import com.example.ui.components.SegmentedPillRow
import com.example.ui.components.TelemetryPill
import com.example.ui.components.vehicleStatusColor
import com.example.ui.theme.ExcavatorAmber
import com.example.ui.theme.ExcavatorOrange
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KruIndigo
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OfflineSlate
import com.example.ui.theme.OnlineEmerald
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MapScreen(
    state: FleetUiState,
    viewModel: FleetViewModel,
    isMapVisible: Boolean,
    onRequestMyLocation: () -> Unit,
    bottomDockClearance: Dp,
    modifier: Modifier = Modifier
) {
    val ext = LocalFleetColors.current

    val searchMatches = remember(state.mapSearchQuery, state.vehicles, state.settings.customNames) {
        val q = state.mapSearchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) emptyList()
        else state.vehicles.filter { v ->
            state.displayName(v).lowercase(Locale.ROOT).contains(q) ||
                v.name.lowercase(Locale.ROOT).contains(q) ||
                v.address.lowercase(Locale.ROOT).contains(q)
        }.take(8)
    }

    val favoriteVehicles = remember(state.vehicles, state.settings.favorites) {
        state.settings.favorites.mapNotNull { fid -> state.vehicles.firstOrNull { it.id == fid } }
    }

    val quickExcQueues = remember(state.excavatorQueues, state.settings.quickExcavatorIds) {
        if (state.settings.quickExcavatorIds.isEmpty()) state.excavatorQueues
        else state.excavatorQueues.filter { it.excavator.id in state.settings.quickExcavatorIds }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Native Interactive Quarry Map
        QuarryMapCanvas(
            state = state,
            isMapVisible = isMapVisible,
            onVehicleTap = { v -> viewModel.selectVehicle(v.id) },
            onZoneTap = { z -> viewModel.openModal(ActiveModal.ZoneContents(z)) },
            onDrawTap = { lat, lng -> viewModel.onMapTapWhileDrawing(lat, lng) },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Controls Stack (Search + Bell + Refresh + Live Status Badge + Favorites + Quick Excavators)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Search Input
                GlassSurface(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Поиск техники",
                            tint = ext.mutedText,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (state.mapSearchQuery.isEmpty()) {
                                Text(
                                    text = "Поиск техники…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ext.mutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = state.mapSearchQuery,
                                onValueChange = viewModel::onMapSearchQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("map_search_input")
                            )
                        }
                        if (state.mapSearchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Очистить поиск",
                                tint = ext.mutedText,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.onMapSearchQueryChange("") }
                            )
                        }
                    }
                }

                // Notifications Button with badge
                Box {
                    GlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("map_notifications_button")
                            .clickable { viewModel.openModal(ActiveModal.Notifications) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Уведомления",
                                tint = ExcavatorAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (state.unreadNotifications > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(LoadedHaulRed),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.unreadNotifications > 99) "99" else state.unreadNotifications.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White
                            )
                        }
                    }
                }

                // Refresh Button
                GlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("map_refresh_button")
                        .clickable { viewModel.manualRefresh() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isManualRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить позиции",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Live Fleet Status Badge (opens Summary & Queues modal)
                val badgeBg = when (state.connectionState) {
                    ConnectionState.ONLINE -> OnlineEmerald
                    ConnectionState.CONNECTING -> KruBlue
                    ConnectionState.ERROR -> LoadedHaulRed
                    ConnectionState.OFFLINE -> OfflineSlate
                }
                val onlineCount = state.vehicles.count { it.online }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = badgeBg,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("status_summary_badge")
                        .clickable { viewModel.openModal(ActiveModal.FleetSummary) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (state.connectionState == ConnectionState.ONLINE) {
                            PulsingStatusDot(color = Color.White, pulse = !state.settings.ecoMode, size = 7.dp)
                        }
                        Text(
                            text = when (state.connectionState) {
                                ConnectionState.ONLINE -> "$onlineCount"
                                ConnectionState.CONNECTING -> "…"
                                ConnectionState.ERROR -> "Сбой"
                                ConnectionState.OFFLINE -> "Офлайн"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = Color.White
                        )
                    }
                }
            }

            // Live Search Dropdown
            AnimatedVisibility(visible = searchMatches.isNotEmpty()) {
                GlassSurface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(searchMatches, key = { it.id }) { v ->
                            val statusColor = vehicleStatusColor(v)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectVehicle(v.id) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.displayName(v),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (v.isLoadedHaul) "ГРУЖЁНЫЙ · ${v.duration}" else v.duration,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (v.isLoadedHaul) LoadedHaulRed else ext.mutedText,
                                        maxLines = 1
                                    )
                                }
                                if (v.weight != null) {
                                    Text(
                                        text = "${v.weight.roundToInt()} т",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                        color = if (v.isLoadedHaul) LoadedHaulRed else KruIndigo
                                    )
                                }
                            }
                            HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.35f))
                        }
                    }
                }
            }

            // Favorites Quick Strip
            if (favoriteVehicles.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(favoriteVehicles, key = { it.id }) { v ->
                        val isSel = v.id == state.selectedVehicleId
                        GlassSurface(
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .border(
                                    width = if (isSel) 1.5.dp else 0.dp,
                                    color = if (isSel) KruBlue else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.selectVehicle(v.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PulsingStatusDot(
                                    color = vehicleStatusColor(v),
                                    pulse = v.isLoadedHaul,
                                    size = 8.dp
                                )
                                Text(
                                    text = state.displayName(v),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                                if (v.speed > 2.0) {
                                    Text(
                                        text = "${v.speed.roundToInt()}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        color = if (v.isLoadedHaul) LoadedHaulRed else OnlineEmerald
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Collapsible Quick Excavators Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                AnimatedVisibility(
                    visible = state.quickExcRowExpanded && quickExcQueues.isNotEmpty(),
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickExcQueues.forEach { q ->
                            GlassSurface(
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 4.dp,
                                modifier = Modifier.clickable { viewModel.selectVehicle(q.excavator.id) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = state.displayName(q.excavator),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ExcavatorOrange,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "БелАЗ: ${q.belazList.size} шт.",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        color = if (q.belazList.size >= 3) LoadedHaulRed else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Expand/Collapse Arrow Pill
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = KruBlue.copy(alpha = if (ext.isDark) 0.28f else 0.16f),
                    border = BorderStroke(1.dp, KruBlue.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .size(width = 34.dp, height = 44.dp)
                        .testTag("quick_exc_row_toggle")
                        .clickable { viewModel.toggleQuickExcRow() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.quickExcRowExpanded) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                            contentDescription = "Показать или скрыть плитки экскаваторов",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Quick Excavator Queue Button & Popover (right aligned)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
                AnimatedVisibility(
                    visible = state.quickExcPopoverOpen,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                ) {
                    GlassSurface(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .widthIn(max = 248.dp)
                            .padding(end = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ОЧЕРЕДИ У ЭКСКАВАТОРОВ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ExcavatorOrange
                                )
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Настроить список экскаваторов",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { viewModel.openModal(ActiveModal.QuickExcavatorsConfig) }
                                )
                            }
                            if (state.excavatorQueues.isEmpty()) {
                                Text(
                                    text = "Активные экскаваторы не найдены",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ext.mutedText
                                )
                            } else {
                                state.excavatorQueues.take(6).forEach { q ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(ext.subtleSurface)
                                            .clickable { viewModel.selectVehicle(q.excavator.id) }
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = state.displayName(q.excavator),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = if (q.belazList.size >= 3) LoadedHaulRed else ExcavatorOrange
                                        ) {
                                            Text(
                                                text = "${q.belazList.size}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                GlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("quick_exc_popover_button")
                        .combinedClickable(
                            onClick = { viewModel.toggleQuickExcPopover() },
                            onLongClick = { viewModel.openModal(ActiveModal.QuickExcavatorsConfig) }
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PrecisionManufacturing,
                            contentDescription = "Очереди у экскаваторов",
                            tint = ExcavatorOrange,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // 3. Right-Side Vertical One-Handed Map Controls
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapRailActionButton(
                icon = if (state.isSatellite) Icons.Default.Layers else Icons.Default.Public,
                label = if (state.isSatellite) "Схема" else "Спутн.",
                tint = MaterialTheme.colorScheme.onSurface,
                tag = "map_layer_toggle",
                onClick = viewModel::toggleSatelliteLayer
            )
            MapRailActionButton(
                icon = Icons.Default.FitScreen,
                label = "Все",
                tint = MaterialTheme.colorScheme.onSurface,
                tag = "map_fit_all",
                onClick = viewModel::fitAllVehicles
            )
            MapRailActionButton(
                icon = Icons.Default.MyLocation,
                label = "Я",
                tint = KruBlue,
                tag = "map_my_location",
                onClick = onRequestMyLocation
            )
            MapRailActionButton(
                icon = Icons.Default.Create,
                label = "Зона",
                tint = if (state.isDrawingZone) LoadedHaulRed else KruIndigo,
                tag = "map_draw_zone",
                onClick = viewModel::toggleDrawZoneMode
            )
        }

        // 4. Bottom Floating Contextual Sheet (Draw Zone / Track Playback / Selected Vehicle)
        // Applies navigationBarsPadding() so it always sits cleanly above FloatingLiquidGlassDock on all nav bar modes
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = bottomDockClearance)
        ) {
            when {
                state.isDrawingZone -> DrawZoneSheet(state = state, viewModel = viewModel)
                state.activeTrack != null -> TrackPlaybackSheet(state = state, viewModel = viewModel)
                state.selectedVehicle != null -> SelectedVehicleSheet(
                    vehicle = state.selectedVehicle!!,
                    state = state,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun MapRailActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    tag: String,
    onClick: () -> Unit
) {
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .size(48.dp)
            .testTag(tag)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(1.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = tint,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DrawZoneSheet(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Новая зона", style = MaterialTheme.typography.titleSmall)
                    val hint = if (state.drawZoneMode == DrawZoneMode.CIRCLE) {
                        if (state.drawCircleCenter == null) "Нажмите на карту для центра круга"
                        else "Центр задан · радиус ${state.drawCircleRadius} м"
                    } else {
                        "Точек контура: ${state.drawPolygonPoints.size} (нужно от 3)"
                    }
                    Text(text = hint, style = MaterialTheme.typography.bodySmall, color = KruIndigo)
                }
                SegmentedPillRow(
                    items = listOf(DrawZoneMode.CIRCLE to "Круг", DrawZoneMode.POLYGON to "Контур"),
                    selected = state.drawZoneMode,
                    onSelect = viewModel::setDrawZoneMode,
                    modifier = Modifier.width(146.dp)
                )
            }

            if (state.drawZoneMode == DrawZoneMode.CIRCLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Радиус:", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                    Slider(
                        value = state.drawCircleRadius.toFloat(),
                        onValueChange = { viewModel.setDrawCircleRadius(it.roundToInt()) },
                        valueRange = 20f..500f,
                        colors = SliderDefaults.colors(thumbColor = KruIndigo, activeTrackColor = KruIndigo),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${state.drawCircleRadius} м",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = KruIndigo
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ext.subtleSurface,
                    modifier = Modifier
                        .height(42.dp)
                        .clickable { viewModel.cancelDrawZoneMode() }
                ) {
                    Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("Отмена", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (state.drawZoneMode == DrawZoneMode.POLYGON && state.drawPolygonPoints.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ExcavatorAmber.copy(alpha = 0.18f),
                        modifier = Modifier
                            .height(42.dp)
                            .clickable { viewModel.undoDrawPolygonPoint() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить точку", tint = ExcavatorAmber, modifier = Modifier.size(16.dp))
                            Text("Назад", style = MaterialTheme.typography.labelLarge, color = ExcavatorAmber)
                        }
                    }
                }
                Button(
                    onClick = viewModel::openSaveCustomZoneModal,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KruIndigo),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("draw_zone_save_button")
                ) {
                    Text("Подписать и сохранить", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TrackPlaybackSheet(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val track = state.activeTrack ?: return
    val ext = LocalFleetColors.current
    val curPt = track.points.getOrNull(state.playbackIndex)

    GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Маршрут · ${track.vehicleName}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "В пути: ${track.movingMinutes} мин · Стоянки: ${track.stoppedMinutes} мин · ${track.points.size} тчк",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.mutedText,
                        maxLines = 1
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = ext.subtleSurface,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("close_track_button")
                        .clickable { viewModel.clearActiveTrack() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть трек", modifier = Modifier.size(16.dp))
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TelemetryPill(
                    label = "Пробег",
                    value = String.format(Locale.US, "%.1f км", track.totalDistanceKm),
                    accent = KruBlue,
                    modifier = Modifier.weight(1f)
                )
                TelemetryPill(
                    label = "Рейсы",
                    value = "${track.tripCount}",
                    accent = ExcavatorOrange,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TelemetryPill(
                    label = "Макс. скор.",
                    value = "${track.maxSpeedKmh.roundToInt()} км/ч",
                    accent = OnlineEmerald,
                    modifier = Modifier.weight(1f)
                )
                TelemetryPill(
                    label = "Стоянки",
                    value = "${track.stopCount}",
                    accent = KruIndigo,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = KruBlue,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("track_play_pause_button")
                        .clickable { viewModel.togglePlayback() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.isPlayingRoute) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Воспроизвести маршрут",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = ext.subtleSurface,
                    modifier = Modifier
                        .height(34.dp)
                        .clickable { viewModel.cyclePlaybackSpeed() }
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "${state.playbackSpeed}x",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = if (state.playbackFollow) KruBlue else ext.subtleSurface,
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { viewModel.togglePlaybackFollow() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = "Следить за точкой",
                            tint = if (state.playbackFollow) Color.White else ext.mutedText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Slider(
                    value = state.playbackIndex.toFloat(),
                    onValueChange = { viewModel.seekPlayback(it.roundToInt()) },
                    valueRange = 0f..(track.points.lastIndex.coerceAtLeast(1).toFloat()),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = curPt?.time?.substringAfterLast(" ")?.take(8) ?: "--:--",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun SelectedVehicleSheet(
    vehicle: Vehicle,
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val ext = LocalFleetColors.current
    val isFav = state.isFavorite(vehicle.id)
    val excQueue = state.excavatorQueues.firstOrNull { it.excavator.id == vehicle.id }
    val shiftSummary = state.shiftStatsCache[vehicle.id]
    val sensors = state.sensorsCache[vehicle.id].orEmpty()
    val fuelText = vehicle.fuel.ifBlank {
        sensors.firstOrNull { Regex("""топл|дут|бак|fuel""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.value ?: "—"
    }

    GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.panToSelectedNow() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PulsingStatusDot(
                            color = vehicleStatusColor(vehicle),
                            pulse = vehicle.isLoadedHaul,
                            size = 10.dp
                        )
                        Text(
                            text = state.displayName(vehicle),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val meta = buildString {
                        append(vehicle.lastTime)
                        append(" · ")
                        append(if (vehicle.isLoadedHaul) "ГРУЖЁНЫЙ ХОД" else vehicle.duration)
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vehicle.isLoadedHaul) LoadedHaulRed else ext.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Last loading place pill for BelAZ
                if (!vehicle.lastLoadZone.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ExcavatorOrange.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, ExcavatorOrange.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { viewModel.focusLastLoadPlaceForSelected() }
                    ) {
                        Text(
                            text = vehicle.lastLoadZone,
                            style = MaterialTheme.typography.labelSmall,
                            color = ExcavatorOrange,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 98.dp)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                // Action icon row: Follow, Fav, Menu, Close
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SmallCircleIconBtn(
                        icon = Icons.Default.GpsFixed,
                        contentDescription = "Вести объект",
                        bg = if (state.settings.followSelected) KruBlue else ext.subtleSurface,
                        tint = if (state.settings.followSelected) Color.White else ext.mutedText,
                        onClick = { viewModel.toggleFollowSelected() }
                    )
                    SmallCircleIconBtn(
                        icon = if (isFav) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = "В избранное",
                        bg = ext.subtleSurface,
                        tint = ExcavatorAmber,
                        onClick = { viewModel.toggleFavorite(vehicle.id) }
                    )
                    SmallCircleIconBtn(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = "Меню объекта",
                        bg = ext.subtleSurface,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { viewModel.openModal(ActiveModal.VehicleContext(vehicle.id)) }
                    )
                    SmallCircleIconBtn(
                        icon = Icons.Default.Close,
                        contentDescription = "Закрыть панель",
                        bg = ext.subtleSurface,
                        tint = ext.mutedText,
                        onClick = viewModel::clearSelection
                    )
                }
            }

            // Collapsible Detailed Telemetry Grid
            AnimatedVisibility(
                visible = state.followDetailsExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

                    if (vehicle.type == VehicleType.EXCAVATOR && excQueue != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TelemetryPill(
                                label = "БелАЗов в радиусе ${state.settings.dynZoneRadiusMeters}м",
                                value = "${excQueue.belazList.size} шт.",
                                accent = ExcavatorOrange,
                                icon = Icons.Default.LocalShipping,
                                onClick = { viewModel.openModal(ActiveModal.ZoneContents(excQueue.zone)) },
                                modifier = Modifier.weight(2f)
                            )
                            TelemetryPill(
                                label = "Скорость",
                                value = "${vehicle.speed.roundToInt()} км/ч",
                                accent = OnlineEmerald,
                                icon = Icons.Default.Speed,
                                onClick = { viewModel.openModal(ActiveModal.VehicleInfo(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TelemetryPill(
                                label = "Топливо",
                                value = fuelText,
                                accent = ExcavatorAmber,
                                icon = Icons.Default.LocalGasStation,
                                onClick = { viewModel.openModal(ActiveModal.VehicleInfo(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryPill(
                                label = "Вес",
                                value = vehicle.weight?.let { String.format(Locale.US, "%.1f т", it) } ?: "—",
                                accent = if (vehicle.isLoadedHaul) LoadedHaulRed else KruIndigo,
                                icon = Icons.Default.Scale,
                                onClick = { viewModel.openModal(ActiveModal.VehicleInfo(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryPill(
                                label = "Скорость",
                                value = "${vehicle.speed.roundToInt()} км/ч",
                                accent = if (vehicle.isLoadedHaul) LoadedHaulRed else OnlineEmerald,
                                icon = Icons.Default.Speed,
                                onClick = { viewModel.openModal(ActiveModal.VehicleInfo(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Shift Kilometers + Trips + Cycle Row (Shift 1: 07:30-19:30, Shift 2: 19:30-07:30)
                    if (vehicle.type != VehicleType.EXCAVATOR) {
                        val isLoadingShift = state.loadingShiftForId == vehicle.id
                        val liveKm = state.liveShiftDistanceKm[vehicle.id] ?: 0.0
                        val bestKm = maxOf(shiftSummary?.totalDistanceKm ?: 0.0, liveKm)
                        val distanceText = when {
                            bestKm > 0.0 -> String.format(Locale.US, "%.1f км", bestKm)
                            isLoadingShift -> "…"
                            shiftSummary != null -> "0.0 км"
                            else -> "0.0 км"
                        }
                        val tripsText = when {
                            shiftSummary != null -> shiftSummary.tripCount.toString()
                            isLoadingShift -> "…"
                            else -> "—"
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TelemetryPill(
                                label = "Пробег (${state.currentShiftLabel})",
                                value = distanceText,
                                accent = KruBlue,
                                icon = Icons.Default.Route,
                                onClick = { viewModel.openModal(ActiveModal.RoutePicker(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryPill(
                                label = "Рейсы (${state.currentShiftLabel})",
                                value = tripsText,
                                accent = KruBlue,
                                onClick = { viewModel.openModal(ActiveModal.RoutePicker(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryPill(
                                label = "Ср. круг",
                                value = shiftSummary?.avgCycleMin?.let { "$it мин" } ?: "—",
                                accent = KruIndigo,
                                icon = Icons.Default.Timer,
                                onClick = { viewModel.openModal(ActiveModal.RoutePicker(vehicle.id)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Expand/Collapse Telemetry Pill Button
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = KruBlue.copy(alpha = if (ext.isDark) 0.22f else 0.12f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clickable { viewModel.toggleFollowDetailsExpanded() }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (state.followDetailsExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = "Показатели",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (state.followDetailsExpanded) "Скрыть показатели" else "Показатели",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallCircleIconBtn(
    icon: ImageVector,
    contentDescription: String,
    bg: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = bg,
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(17.dp))
        }
    }
}
