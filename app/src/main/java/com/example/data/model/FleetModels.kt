package com.example.data.model

import androidx.compose.runtime.Immutable

enum class FleetTab {
    MAP,
    OBJECTS,
    SETTINGS
}

enum class ConnectionState {
    OFFLINE,
    CONNECTING,
    ONLINE,
    ERROR
}

enum class VehicleType(
    val key: String,
    val labelRu: String,
    val shortRu: String,
    val colorHex: String
) {
    BELAZ("belaz", "БелАЗ / Самосвал", "БелАЗ", "#10B981"),
    EXCAVATOR("excavator", "Экскаваторы", "Экск.", "#F59E0B"),
    BULLDOZER("bulldozer", "Бульдозеры", "Бульд.", "#EAB308"),
    GRADER("grader", "Грейдеры", "Грейд.", "#14B8A6"),
    FUEL("fuel", "АТЗ / Топливозаправщики", "АТЗ", "#06B6D4"),
    OTHER("other", "Прочая техника", "Проч.", "#6366F1")
}

@Immutable
data class Vehicle(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val course: Double?,
    val online: Boolean,
    val active: Boolean,
    val engineOff: Boolean?,
    val lastTime: String,
    val duration: String,
    val fuel: String,
    val weight: Double?,
    val rawFields: Map<String, String> = emptyMap(),
    val type: VehicleType = VehicleType.OTHER,
    val isLoaded: Boolean = false,
    val lastLoadZone: String? = null,
    val lastLoadLat: Double? = null,
    val lastLoadLng: Double? = null
) {
    val isMoving: Boolean get() = online && speed > 2.0
    val isLoadedHaul: Boolean get() = type == VehicleType.BELAZ && isLoaded
}

@Immutable
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val time: String,
    val weight: Double? = null,
    val fuel: Double? = null
)

@Immutable
data class TrackSummary(
    val objectId: String,
    val vehicleName: String,
    val points: List<TrackPoint>,
    val totalDistanceKm: Double,
    val maxSpeedKmh: Double,
    val stopCount: Int,
    val tripCount: Int,
    val movingMinutes: Int,
    val stoppedMinutes: Int,
    val currentCycleMin: Int? = null,
    val avgCycleMin: Int? = null,
    val method: String = "",
    val format: String = ""
)

@Immutable
data class SensorItem(
    val name: String,
    val value: String
)

enum class GeoZoneKind(val labelRu: String) {
    SERVER("Геозона сервера"),
    EXCAVATOR("Погрузка"),
    DUMP("Отвал / Разгрузка"),
    FUEL("Заправка / АЗС"),
    SHIFT_CHANGE("Пересменка / База"),
    CUSTOM("Своя зона")
}

@Immutable
data class LatLngPoint(
    val lat: Double,
    val lng: Double
)

@Immutable
data class GeoZone(
    val id: String,
    val key: String,
    val name: String,
    val shortName: String = name,
    val colorHex: String = "#2563EB",
    val kind: GeoZoneKind = GeoZoneKind.SERVER,
    val isCircle: Boolean = true,
    val centerLat: Double = 0.0,
    val centerLng: Double = 0.0,
    val radiusMeters: Double = 60.0,
    val polygon: List<LatLngPoint> = emptyList(),
    val excavatorId: String? = null
)

@Immutable
data class CustomZoneData(
    val id: String,
    val name: String,
    val colorHex: String,
    val isCircle: Boolean,
    val centerLat: Double = 0.0,
    val centerLng: Double = 0.0,
    val radiusMeters: Double = 80.0,
    val polygon: List<LatLngPoint> = emptyList()
)

@Immutable
data class FleetNotification(
    val id: String,
    val vehId: String,
    val vehName: String,
    val kind: String, // "fuel", "refuel", "shift_change", "info"
    val isFav: Boolean,
    val title: String,
    val body: String,
    val timeStr: String,
    val tsMs: Long
)

@Immutable
data class AutoZoneObservation(
    val lat: Double,
    val lng: Double,
    val tsMs: Long,
    val tag: String = ""
)

@Immutable
data class AutoZonesDb(
    val version: Int = 1,
    val lastAnalyzeTs: Long = 0L,
    val excObs: List<AutoZoneObservation> = emptyList(),
    val dumpObs: List<AutoZoneObservation> = emptyList(),
    val fuelObs: List<AutoZoneObservation> = emptyList(),
    val shiftObs: List<AutoZoneObservation> = emptyList()
)

@Immutable
data class ExcavatorQueueInfo(
    val excavator: Vehicle,
    val zone: GeoZone,
    val belazList: List<Vehicle>,
    val waitTimesMin: Map<String, Int>
)

@Immutable
data class AppSettings(
    val server: String = "https://webask.ugmk.com/ASK-BUR/NavisolServiceMobile.asmx",
    val login: String = "",
    val password: String = "",
    val autoRefresh: Boolean = true,
    val refreshIntervalSec: Int = 5,
    val selectedIntervalSec: Double = 2.0,
    val smoothMotion: Boolean = true,
    val predictMotion: Boolean = true,
    val fps: Int = 30,
    val ecoMode: Boolean = true,
    val simpleIcons: Boolean = false,
    val followSelected: Boolean = true,
    val showLabels: Boolean = true,
    val showOffline: Boolean = true,
    val showZones: Boolean = false,
    val showDynamicZones: Boolean = true,
    val dynZoneRadiusMeters: Int = 60,
    val darkMode: Boolean = false,
    val autoConnect: Boolean = false,
    val uiScale: Float = 1f,
    val favorites: List<String> = emptyList(),
    val quickExcavatorIds: List<String> = emptyList(),
    val customNames: Map<String, String> = emptyMap(),
    val customZones: List<CustomZoneData> = emptyList(),
    val mapTypeFilter: Map<VehicleType, Boolean> = VehicleType.entries.associateWith { true }
)

@Immutable
data class PerfMetrics(
    val netMs: Long = 0L,
    val parseMs: Long = 0L,
    val bytes: Int = 0
)

enum class ListTypeFilter { ALL, FAV, EXC, FUEL, OTHER }
enum class ListConnFilter { ALL, ONLINE, OFFLINE }
enum class ListActFilter { ALL, MOVING, STOPPED }
enum class DrawZoneMode { CIRCLE, POLYGON }
