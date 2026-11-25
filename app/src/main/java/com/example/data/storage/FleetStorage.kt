package com.example.data.storage

import android.content.Context
import com.example.data.model.AppSettings
import com.example.data.model.AutoZoneObservation
import com.example.data.model.AutoZonesDb
import com.example.data.model.CustomZoneData
import com.example.data.model.FleetNotification
import com.example.data.model.LatLngPoint
import com.example.data.model.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FleetStorage(private val context: Context) {

    private val settingsFile get() = File(context.filesDir, "sputnik_mobile_settings.json")
    private val autoZonesFile get() = File(context.filesDir, "sputnik_autozones_db.json")
    private val notifsFile get() = File(context.filesDir, "sputnik_notifications.json")

    suspend fun loadSettings(): AppSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) return@withContext AppSettings()
        runCatching {
            val obj = JSONObject(settingsFile.readText())
            val favs = mutableListOf<String>()
            obj.optJSONArray("favorites")?.let { arr ->
                for (i in 0 until arr.length()) favs.add(arr.optString(i))
            }
            val quickExc = mutableListOf<String>()
            obj.optJSONArray("quickExcavatorIds")?.let { arr ->
                for (i in 0 until arr.length()) quickExc.add(arr.optString(i))
            }
            val customNames = LinkedHashMap<String, String>()
            obj.optJSONObject("customNames")?.let { mapObj ->
                val keys = mapObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = mapObj.optString(k)
                    if (v.isNotBlank()) customNames[k] = v
                }
            }
            val customZones = mutableListOf<CustomZoneData>()
            obj.optJSONArray("customZones")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val zObj = arr.optJSONObject(i) ?: continue
                    val poly = mutableListOf<LatLngPoint>()
                    zObj.optJSONArray("polygon")?.let { pArr ->
                        for (j in 0 until pArr.length()) {
                            val pt = pArr.optJSONObject(j) ?: continue
                            poly.add(LatLngPoint(pt.optDouble("lat"), pt.optDouble("lng")))
                        }
                    }
                    customZones.add(
                        CustomZoneData(
                            id = zObj.optString("id", "cz_$i"),
                            name = zObj.optString("name", "Зона #${i + 1}"),
                            colorHex = zObj.optString("colorHex", "#2563EB"),
                            isCircle = zObj.optBoolean("isCircle", true),
                            centerLat = zObj.optDouble("centerLat", 0.0),
                            centerLng = zObj.optDouble("centerLng", 0.0),
                            radiusMeters = zObj.optDouble("radiusMeters", 80.0),
                            polygon = poly
                        )
                    )
                }
            }
            val filterMap = LinkedHashMap<VehicleType, Boolean>()
            val filterObj = obj.optJSONObject("mapTypeFilter")
            for (vt in VehicleType.entries) {
                filterMap[vt] = filterObj?.optBoolean(vt.key, true) ?: true
            }

            AppSettings(
                server = obj.optString("server", AppSettings().server),
                login = obj.optString("login", ""),
                password = obj.optString("password", ""),
                autoRefresh = obj.optBoolean("autoRefresh", true),
                refreshIntervalSec = obj.optInt("refreshIntervalSec", 5),
                selectedIntervalSec = obj.optDouble("selectedIntervalSec", 2.0),
                smoothMotion = obj.optBoolean("smoothMotion", true),
                predictMotion = obj.optBoolean("predictMotion", true),
                fps = obj.optInt("fps", 30),
                ecoMode = obj.optBoolean("ecoMode", true),
                simpleIcons = obj.optBoolean("simpleIcons", false),
                followSelected = obj.optBoolean("followSelected", true),
                showLabels = obj.optBoolean("showLabels", true),
                showOffline = obj.optBoolean("showOffline", true),
                showZones = obj.optBoolean("showZones", false),
                showDynamicZones = obj.optBoolean("showDynamicZones", true),
                dynZoneRadiusMeters = obj.optInt("dynZoneRadiusMeters", 60),
                darkMode = obj.optBoolean("darkMode", false),
                autoConnect = obj.optBoolean("autoConnect", false),
                uiScale = obj.optDouble("uiScale", 1.0).toFloat(),
                favorites = favs,
                quickExcavatorIds = quickExc,
                customNames = customNames,
                customZones = customZones,
                mapTypeFilter = filterMap
            )
        }.getOrDefault(AppSettings())
    }

    suspend fun saveSettings(s: AppSettings) = withContext(Dispatchers.IO) {
        runCatching {
            val obj = JSONObject().apply {
                put("server", s.server)
                put("login", s.login)
                put("password", s.password)
                put("autoRefresh", s.autoRefresh)
                put("refreshIntervalSec", s.refreshIntervalSec)
                put("selectedIntervalSec", s.selectedIntervalSec)
                put("smoothMotion", s.smoothMotion)
                put("predictMotion", s.predictMotion)
                put("fps", s.fps)
                put("ecoMode", s.ecoMode)
                put("simpleIcons", s.simpleIcons)
                put("followSelected", s.followSelected)
                put("showLabels", s.showLabels)
                put("showOffline", s.showOffline)
                put("showZones", s.showZones)
                put("showDynamicZones", s.showDynamicZones)
                put("dynZoneRadiusMeters", s.dynZoneRadiusMeters)
                put("darkMode", s.darkMode)
                put("autoConnect", s.autoConnect)
                put("uiScale", s.uiScale.toDouble())
                put("favorites", JSONArray(s.favorites))
                put("quickExcavatorIds", JSONArray(s.quickExcavatorIds))
                put("customNames", JSONObject(s.customNames))
                put("customZones", JSONArray().apply {
                    s.customZones.forEach { z ->
                        put(JSONObject().apply {
                            put("id", z.id)
                            put("name", z.name)
                            put("colorHex", z.colorHex)
                            put("isCircle", z.isCircle)
                            put("centerLat", z.centerLat)
                            put("centerLng", z.centerLng)
                            put("radiusMeters", z.radiusMeters)
                            put("polygon", JSONArray().apply {
                                z.polygon.forEach { pt ->
                                    put(JSONObject().put("lat", pt.lat).put("lng", pt.lng))
                                }
                            })
                        })
                    }
                })
                put("mapTypeFilter", JSONObject().apply {
                    s.mapTypeFilter.forEach { (vt, enabled) -> put(vt.key, enabled) }
                })
            }
            settingsFile.writeText(obj.toString(2))
        }
    }

    suspend fun loadAutoZonesDb(): AutoZonesDb = withContext(Dispatchers.IO) {
        if (!autoZonesFile.exists()) return@withContext AutoZonesDb()
        runCatching {
            val obj = JSONObject(autoZonesFile.readText())
            fun parseObs(key: String): List<AutoZoneObservation> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            AutoZoneObservation(
                                lat = o.optDouble("lat"),
                                lng = o.optDouble("lng"),
                                tsMs = o.optLong("tsMs"),
                                tag = o.optString("tag", "")
                            )
                        )
                    }
                }
            }
            AutoZonesDb(
                version = obj.optInt("version", 1),
                lastAnalyzeTs = obj.optLong("lastAnalyzeTs", 0L),
                excObs = parseObs("excObs"),
                dumpObs = parseObs("dumpObs"),
                fuelObs = parseObs("fuelObs"),
                shiftObs = parseObs("shiftObs")
            )
        }.getOrDefault(AutoZonesDb())
    }

    suspend fun saveAutoZonesDb(db: AutoZonesDb) = withContext(Dispatchers.IO) {
        runCatching {
            fun encodeObs(list: List<AutoZoneObservation>): JSONArray = JSONArray().apply {
                list.forEach { o ->
                    put(
                        JSONObject()
                            .put("lat", o.lat)
                            .put("lng", o.lng)
                            .put("tsMs", o.tsMs)
                            .put("tag", o.tag)
                    )
                }
            }
            val obj = JSONObject()
                .put("version", db.version)
                .put("lastAnalyzeTs", db.lastAnalyzeTs)
                .put("excObs", encodeObs(db.excObs))
                .put("dumpObs", encodeObs(db.dumpObs))
                .put("fuelObs", encodeObs(db.fuelObs))
                .put("shiftObs", encodeObs(db.shiftObs))
            autoZonesFile.writeText(obj.toString())
        }
    }

    suspend fun loadNotifications(): Pair<List<FleetNotification>, Int> = withContext(Dispatchers.IO) {
        if (!notifsFile.exists()) return@withContext emptyList<FleetNotification>() to 0
        runCatching {
            val obj = JSONObject(notifsFile.readText())
            val unread = obj.optInt("unread", 0)
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val list = buildList {
                for (i in 0 until minOf(arr.length(), 100)) {
                    val n = arr.optJSONObject(i) ?: continue
                    add(
                        FleetNotification(
                            id = n.optString("id"),
                            vehId = n.optString("vehId"),
                            vehName = n.optString("vehName"),
                            kind = n.optString("kind", "info"),
                            isFav = n.optBoolean("isFav", false),
                            title = n.optString("title"),
                            body = n.optString("body"),
                            timeStr = n.optString("timeStr"),
                            tsMs = n.optLong("tsMs")
                        )
                    )
                }
            }
            list to unread
        }.getOrDefault(emptyList<FleetNotification>() to 0)
    }

    suspend fun saveNotifications(items: List<FleetNotification>, unread: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray().apply {
                items.take(100).forEach { n ->
                    put(
                        JSONObject()
                            .put("id", n.id)
                            .put("vehId", n.vehId)
                            .put("vehName", n.vehName)
                            .put("kind", n.kind)
                            .put("isFav", n.isFav)
                            .put("title", n.title)
                            .put("body", n.body)
                            .put("timeStr", n.timeStr)
                            .put("tsMs", n.tsMs)
                    )
                }
            }
            val obj = JSONObject().put("items", arr).put("unread", unread)
            notifsFile.writeText(obj.toString())
        }
    }
}
