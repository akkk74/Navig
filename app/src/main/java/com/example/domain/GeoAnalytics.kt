package com.example.domain

import com.example.data.model.AutoZoneObservation
import com.example.data.model.AutoZonesDb
import com.example.data.model.ExcavatorQueueInfo
import com.example.data.model.GeoZone
import com.example.data.model.GeoZoneKind
import com.example.data.model.LatLngPoint
import com.example.data.model.TrackPoint
import com.example.data.model.TrackSummary
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object GeoAnalytics {

    const val LOADED_WEIGHT_THRESHOLD_TONS = 15.0
    const val AZ_RETENTION_MS = 7L * 24L * 3600L * 1000L

    private val excavatorRe = Regex(
        """экск|экг|эш|эо|excav|hitachi|pc\d|r9\d|liebherr\s*r|komatsu\s*pc|wk-|p&h|bucyrus|cat\s*60|sany\s*sy|hyundai\s*r|doosan|volvo\s*ec|погрузчик|фронт|колесн.*погр|wa\d|988|992|l\d{3}""",
        RegexOption.IGNORE_CASE
    )
    private val fuelRe = Regex(
        """атз|топлив|заправ|бензовоз|гсм|маслозапр|мз-""",
        RegexOption.IGNORE_CASE
    )
    private val bulldozerRe = Regex(
        """бульд|б10|б-10|дэт|четра|чтз|т-\d{2}|т\d{2}\.|komatsu\s*d|cat\s*d\d|shantui|sd\d{2}|liebherr\s*pr|dressta|d375|d275|d155|d9|d10|d11""",
        RegexOption.IGNORE_CASE
    )
    private val graderRe = Regex(
        """грейд|аг-|дз-|гс-|cat\s*14|cat\s*16|cat\s*24|gd\d|komatsu\s*gd|xcmg\s*gr""",
        RegexOption.IGNORE_CASE
    )
    private val belazRe = Regex(
        """белаз|belaz|самосвал|а/с|а\.с|75\d{2}|hd785|hd\d{3}|cat\s*7\d{2}|komatsu\s*hd|тонар|tonar|shacman|howo|sany\s*skt|lgmg|камаз|volvo\s*fm|scania|маз|урал""",
        RegexOption.IGNORE_CASE
    )

    fun classifyVehicleType(name: String, rawFields: Map<String, String> = emptyMap()): VehicleType {
        val combined = buildString {
            append(name)
            rawFields["Type"]?.let { append(' ').append(it) }
            rawFields["Model"]?.let { append(' ').append(it) }
            rawFields["Category"]?.let { append(' ').append(it) }
            rawFields["GroupName"]?.let { append(' ').append(it) }
        }
        return when {
            excavatorRe.containsMatchIn(combined) -> VehicleType.EXCAVATOR
            fuelRe.containsMatchIn(combined) -> VehicleType.FUEL
            bulldozerRe.containsMatchIn(combined) -> VehicleType.BULLDOZER
            graderRe.containsMatchIn(combined) -> VehicleType.GRADER
            belazRe.containsMatchIn(combined) -> VehicleType.BELAZ
            else -> VehicleType.OTHER
        }
    }

    fun isLoadedByTelemetry(
        type: VehicleType,
        weight: Double?,
        rawFields: Map<String, String>
    ): Boolean {
        if (type != VehicleType.BELAZ) return false
        if (weight != null && weight >= LOADED_WEIGHT_THRESHOLD_TONS) return true
        for ((k, v) in rawFields) {
            val kl = k.lowercase(Locale.ROOT)
            val vl = v.lowercase(Locale.ROOT)
            if (Regex("""loaded|isloaded|gruzh|груж""").containsMatchIn(kl)) {
                if (Regex("""^(1|true|yes|да|груж)""").containsMatchIn(vl)) return true
            }
        }
        return false
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lng2 - lng1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return ((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0
    }

    fun isPointInPolygon(lat: Double, lng: Double, polygon: List<LatLngPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val yi = polygon[i].lat
            val xi = polygon[i].lng
            val yj = polygon[j].lat
            val xj = polygon[j].lng
            val intersect = ((yi > lat) != (yj > lat)) &&
                (lng < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { it != 0.0 } ?: 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun isVehicleInZone(v: Vehicle, z: GeoZone): Boolean {
        return if (z.isCircle) {
            distanceMeters(v.lat, v.lng, z.centerLat, z.centerLng) <= z.radiusMeters
        } else {
            isPointInPolygon(v.lat, v.lng, z.polygon)
        }
    }

    fun classifyAnyZoneKind(z: GeoZone): GeoZoneKind {
        if (z.kind == GeoZoneKind.SHIFT_CHANGE || z.kind == GeoZoneKind.FUEL) return z.kind
        val nm = "${z.shortName} ${z.name}".lowercase(Locale.ROOT)
        return when {
            Regex("""азс|заправ|топлив|гсм|атз""").containsMatchIn(nm) -> GeoZoneKind.FUEL
            Regex("""пересмен|смен|стоянк|баз|гараж|атп|площадк|псм|кпп""").containsMatchIn(nm) -> GeoZoneKind.SHIFT_CHANGE
            else -> z.kind
        }
    }

    fun parseFuelLiters(fuelStr: String): Double? {
        if (fuelStr.isBlank()) return null
        val m = Regex("""\d+(?:[.,]\d+)?""").find(fuelStr) ?: return null
        return m.value.replace(",", ".").toDoubleOrNull()
    }

    fun buildDynamicZones(
        vehicles: List<Vehicle>,
        customNames: Map<String, String>,
        baseRadiusMeters: Int,
        azDb: AutoZonesDb
    ): List<GeoZone> {
        val result = mutableListOf<GeoZone>()
        val radius = baseRadiusMeters.coerceIn(30, 300).toDouble()

        // 1. Live excavator loading zones
        vehicles.filter { it.type == VehicleType.EXCAVATOR && it.online }.forEach { exc ->
            val displayName = customNames[exc.id]?.takeIf { it.isNotBlank() } ?: exc.name
            result.add(
                GeoZone(
                    id = "dyn_exc_${exc.id}",
                    key = "dyn_exc_${exc.id}",
                    name = "Погрузка · $displayName",
                    shortName = displayName,
                    colorHex = "#F97316",
                    kind = GeoZoneKind.EXCAVATOR,
                    isCircle = true,
                    centerLat = exc.lat,
                    centerLng = exc.lng,
                    radiusMeters = radius,
                    excavatorId = exc.id
                )
            )
        }

        // 2. Clustered learned zones (dump, fuel, shift_change) from 7-day DB
        clusterObservations(azDb.dumpObs, 120.0, 2).forEachIndexed { idx, (lat, lng, count) ->
            result.add(
                GeoZone(
                    id = "az_dump_$idx",
                    key = "az_dump_$idx",
                    name = "Отвал #${idx + 1} ($count)",
                    shortName = "Отвал #${idx + 1}",
                    colorHex = "#8B5CF6",
                    kind = GeoZoneKind.DUMP,
                    isCircle = true,
                    centerLat = lat,
                    centerLng = lng,
                    radiusMeters = 90.0
                )
            )
        }
        clusterObservations(azDb.fuelObs, 80.0, 1).forEachIndexed { idx, (lat, lng, count) ->
            result.add(
                GeoZone(
                    id = "az_fuel_$idx",
                    key = "az_fuel_$idx",
                    name = "АЗС / Заправка #${idx + 1} ($count)",
                    shortName = "АЗС #${idx + 1}",
                    colorHex = "#06B6D4",
                    kind = GeoZoneKind.FUEL,
                    isCircle = true,
                    centerLat = lat,
                    centerLng = lng,
                    radiusMeters = 65.0
                )
            )
        }
        clusterObservations(azDb.shiftObs, 140.0, 2).forEachIndexed { idx, (lat, lng, count) ->
            result.add(
                GeoZone(
                    id = "az_shift_$idx",
                    key = "az_shift_$idx",
                    name = "Пересменка #${idx + 1} ($count)",
                    shortName = "Пересменка #${idx + 1}",
                    colorHex = "#6366F1",
                    kind = GeoZoneKind.SHIFT_CHANGE,
                    isCircle = true,
                    centerLat = lat,
                    centerLng = lng,
                    radiusMeters = 100.0
                )
            )
        }

        return result
    }

    private fun clusterObservations(
        obs: List<AutoZoneObservation>,
        radiusMeters: Double,
        minCount: Int
    ): List<Triple<Double, Double, Int>> {
        if (obs.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val valid = obs.filter { now - it.tsMs <= AZ_RETENTION_MS }
        val used = BooleanArray(valid.size)
        val clusters = mutableListOf<Triple<Double, Double, Int>>()

        for (i in valid.indices) {
            if (used[i]) continue
            val seed = valid[i]
            val group = mutableListOf(seed)
            used[i] = true
            for (j in i + 1 until valid.size) {
                if (!used[j] && distanceMeters(seed.lat, seed.lng, valid[j].lat, valid[j].lng) <= radiusMeters) {
                    group.add(valid[j])
                    used[j] = true
                }
            }
            if (group.size >= minCount) {
                clusters.add(
                    Triple(
                        group.map { it.lat }.average(),
                        group.map { it.lng }.average(),
                        group.size
                    )
                )
            }
        }
        return clusters.sortedByDescending { it.third }.take(12)
    }

    fun computeExcavatorQueues(
        vehicles: List<Vehicle>,
        dynamicZones: List<GeoZone>,
        zonePresence: Map<String, Map<String, Long>>
    ): List<ExcavatorQueueInfo> {
        val now = System.currentTimeMillis()
        val excZones = dynamicZones.filter { it.kind == GeoZoneKind.EXCAVATOR && it.excavatorId != null }
        val belazVehicles = vehicles.filter { it.type == VehicleType.BELAZ && it.online }
        val vehById = vehicles.associateBy { it.id }

        return excZones.mapNotNull { z ->
            val exc = vehById[z.excavatorId] ?: return@mapNotNull null
            val inside = belazVehicles.filter { isVehicleInZone(it, z) }
            val presenceMap = zonePresence[z.key].orEmpty()
            val waits = inside.associate { b ->
                val enterMs = presenceMap[b.id] ?: now
                b.id to (((now - enterMs).coerceAtLeast(0L)) / 60000L).toInt()
            }
            ExcavatorQueueInfo(
                excavator = exc,
                zone = z,
                belazList = inside.sortedByDescending { waits[it.id] ?: 0 },
                waitTimesMin = waits
            )
        }.sortedWith(compareByDescending<ExcavatorQueueInfo> { it.belazList.size }.thenBy { it.excavator.name })
    }

    fun computeTrackSummary(
        objectId: String,
        vehicleName: String,
        points: List<TrackPoint>,
        method: String = "",
        format: String = ""
    ): TrackSummary {
        if (points.isEmpty()) {
            return TrackSummary(objectId, vehicleName, emptyList(), 0.0, 0.0, 0, 0, 0, 0, method = method, format = format)
        }
        var totalMeters = 0.0
        var maxSpd = 0.0
        var stops = 0
        var inStop = false
        var stopConsecutive = 0
        var trips = 0
        var wasLoaded = false
        val cycleTimestamps = mutableListOf<Long>()
        var movingPts = 0
        var stoppedPts = 0

        for (i in points.indices) {
            val p = points[i]
            if (p.speed > maxSpd) maxSpd = p.speed
            if (i > 0) {
                val prev = points[i - 1]
                val seg = distanceMeters(prev.lat, prev.lng, p.lat, p.lng)
                if (seg < 5000.0) totalMeters += seg
            }
            if (p.speed <= 2.0) {
                stoppedPts++
                stopConsecutive++
                if (!inStop && stopConsecutive >= 3) {
                    inStop = true
                    stops++
                }
            } else {
                movingPts++
                inStop = false
                stopConsecutive = 0
            }

            val w = p.weight
            if (w != null) {
                if (!wasLoaded && w >= LOADED_WEIGHT_THRESHOLD_TONS) {
                    wasLoaded = true
                } else if (wasLoaded && w < 8.0) {
                    wasLoaded = false
                    trips++
                    parseTrackTimestamp(p.time)?.let { cycleTimestamps.add(it) }
                }
            }
        }

        val firstMs = parseTrackTimestamp(points.first().time)
        val lastMs = parseTrackTimestamp(points.last().time)
        val spanMin = if (firstMs != null && lastMs != null && lastMs > firstMs) {
            ((lastMs - firstMs) / 60000L).toInt()
        } else {
            (points.size / 2).coerceAtLeast(1)
        }
        val totalPts = (movingPts + stoppedPts).coerceAtLeast(1)
        val movingMin = (spanMin * movingPts.toDouble() / totalPts).roundToInt()
        val stoppedMin = (spanMin - movingMin).coerceAtLeast(0)

        var avgCycle: Int? = null
        var curCycle: Int? = null
        if (cycleTimestamps.size >= 2) {
            val diffs = (1 until cycleTimestamps.size).map {
                ((cycleTimestamps[it] - cycleTimestamps[it - 1]) / 60000L).toInt().coerceIn(3, 180)
            }
            avgCycle = diffs.average().roundToInt()
            curCycle = diffs.lastOrNull()
        }

        return TrackSummary(
            objectId = objectId,
            vehicleName = vehicleName,
            points = points,
            totalDistanceKm = totalMeters / 1000.0,
            maxSpeedKmh = maxSpd,
            stopCount = stops,
            tripCount = trips,
            movingMinutes = movingMin,
            stoppedMinutes = stoppedMin,
            currentCycleMin = curCycle,
            avgCycleMin = avgCycle,
            method = method,
            format = format
        )
    }

    private fun parseTrackTimestamp(timeStr: String): Long? {
        if (timeStr.isBlank()) return null
        val patterns = listOf(
            "dd.MM.yyyy HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "dd.MM.yyyy HH:mm"
        )
        for (p in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(p, Locale.US)
                val d = sdf.parse(timeStr.trim())
                if (d != null) return d.time
            }
        }
        return null
    }
}
