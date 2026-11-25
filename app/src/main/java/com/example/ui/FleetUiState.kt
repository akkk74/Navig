package com.example.ui

import androidx.compose.runtime.Immutable
import com.example.data.model.AppSettings
import com.example.data.model.AutoZonesDb
import com.example.data.model.ConnectionState
import com.example.data.model.DrawZoneMode
import com.example.data.model.ExcavatorQueueInfo
import com.example.data.model.FleetNotification
import com.example.data.model.FleetTab
import com.example.data.model.GeoZone
import com.example.data.model.LatLngPoint
import com.example.data.model.ListActFilter
import com.example.data.model.ListConnFilter
import com.example.data.model.ListTypeFilter
import com.example.data.model.PerfMetrics
import com.example.data.model.SensorItem
import com.example.data.model.TrackSummary
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType

enum class ToastTone { INFO, SUCCESS, WARNING, ERROR }

@Immutable
data class ToastMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val tone: ToastTone = ToastTone.INFO
)

sealed interface MapCameraCommand {
    val nonce: Long

    data class FocusPoint(
        val lat: Double,
        val lng: Double,
        val zoom: Double = 16.0,
        val preserveZoom: Boolean = false,
        val animate: Boolean = true,
        override val nonce: Long = System.nanoTime()
    ) : MapCameraCommand

    data class FitBounds(
        val points: List<LatLngPoint>,
        override val nonce: Long = System.nanoTime()
    ) : MapCameraCommand
}

sealed interface ActiveModal {
    data object None : ActiveModal
    data class VehicleContext(val vehicleId: String) : ActiveModal
    data class VehicleInfo(val vehicleId: String) : ActiveModal
    data class RoutePicker(val vehicleId: String) : ActiveModal
    data class RenameVehicle(val vehicleId: String) : ActiveModal
    data object FleetSummary : ActiveModal
    data class ZoneContents(val zone: GeoZone) : ActiveModal
    data class SaveCustomZone(val editingZoneId: String?) : ActiveModal
    data object QuickExcavatorsConfig : ActiveModal
    data object MapTypeFilter : ActiveModal
    data object Notifications : ActiveModal
}

@Immutable
data class FleetUiState(
    val activeTab: FleetTab = FleetTab.SETTINGS,
    val settings: AppSettings = AppSettings(),
    val connectionState: ConnectionState = ConnectionState.OFFLINE,
    val isManualRefreshing: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val followDetailsExpanded: Boolean = true,
    val isSatellite: Boolean = false,
    val myLocation: LatLngPoint? = null,

    // Search & Filters
    val mapSearchQuery: String = "",
    val listSearchQuery: String = "",
    val listTypeFilter: ListTypeFilter = ListTypeFilter.ALL,
    val listConnFilter: ListConnFilter = ListConnFilter.ALL,
    val listActFilter: ListActFilter = ListActFilter.ALL,

    // Quick excavator strip & popover
    val quickExcRowExpanded: Boolean = false,
    val quickExcPopoverOpen: Boolean = false,
    val excavatorQueues: List<ExcavatorQueueInfo> = emptyList(),

    // Zones
    val serverZones: List<GeoZone> = emptyList(),
    val dynamicZones: List<GeoZone> = emptyList(),
    val zonePresence: Map<String, Map<String, Long>> = emptyMap(),
    val autoZonesDb: AutoZonesDb = AutoZonesDb(),
    val isScanningAutoZones: Boolean = false,

    // Draw custom zone mode
    val isDrawingZone: Boolean = false,
    val drawZoneMode: DrawZoneMode = DrawZoneMode.CIRCLE,
    val drawCircleCenter: LatLngPoint? = null,
    val drawCircleRadius: Int = 80,
    val drawPolygonPoints: List<LatLngPoint> = emptyList(),
    val editingZoneId: String? = null,

    // Track / Route History
    val activeTrack: TrackSummary? = null,
    val isLoadingRoute: Boolean = false,
    val routeDebugText: String = "",
    val isPlayingRoute: Boolean = false,
    val playbackIndex: Int = 0,
    val playbackSpeed: Int = 1,
    val playbackFollow: Boolean = true,
    val shiftStatsCache: Map<String, TrackSummary> = emptyMap(),
    val liveShiftDistanceKm: Map<String, Double> = emptyMap(),
    val loadingShiftForId: String? = null,
    val currentShiftLabel: String = com.example.domain.GeoAnalytics.currentShiftWindow().shortLabel,

    // Sensors cache
    val sensorsCache: Map<String, List<SensorItem>> = emptyMap(),
    val loadingSensorsForId: String? = null,

    // Notifications
    val notifications: List<FleetNotification> = emptyList(),
    val unreadNotifications: Int = 0,

    // Diagnostics & Feedback
    val connectionLogs: List<String> = listOf("Нажмите «Подключить» для входа на сервер."),
    val perfMetrics: PerfMetrics = PerfMetrics(),
    val activeModal: ActiveModal = ActiveModal.None,
    val cameraCommand: MapCameraCommand? = null,
    val toast: ToastMessage? = null
) {
    fun displayName(v: Vehicle): String =
        settings.customNames[v.id]?.takeIf { it.isNotBlank() } ?: v.name

    fun isFavorite(vehicleId: String): Boolean = vehicleId in settings.favorites

    val selectedVehicle: Vehicle?
        get() = selectedVehicleId?.let { id -> vehicles.firstOrNull { it.id == id } }

    val visibleMapVehicles: List<Vehicle>
        get() = vehicles.filter { v ->
            val typeOk = settings.mapTypeFilter[v.type] ?: true
            val onlineOk = settings.showOffline || v.online
            typeOk && onlineOk
        }

    val activeFilterCount: Int
        get() = VehicleType.entries.count { settings.mapTypeFilter[it] == false }
}
