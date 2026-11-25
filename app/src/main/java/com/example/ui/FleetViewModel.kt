package com.example.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.model.AppSettings
import com.example.data.model.AutoZoneObservation
import com.example.data.model.AutoZonesDb
import com.example.data.model.ConnectionState
import com.example.data.model.CustomZoneData
import com.example.data.model.DrawZoneMode
import com.example.data.model.FleetNotification
import com.example.data.model.FleetTab
import com.example.data.model.GeoZone
import com.example.data.model.GeoZoneKind
import com.example.data.model.LatLngPoint
import com.example.data.model.ListActFilter
import com.example.data.model.ListConnFilter
import com.example.data.model.ListTypeFilter
import com.example.data.model.PerfMetrics
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType
import com.example.data.network.NavisolSoapClient
import com.example.data.storage.FleetStorage
import com.example.domain.GeoAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class FleetViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = FleetStorage(application)
    private val soapClient = NavisolSoapClient()

    private val _uiState = MutableStateFlow(FleetUiState())
    val uiState: StateFlow<FleetUiState> = _uiState.asStateFlow()

    private var refreshLoopJob: Job? = null
    private var shiftFetchJob: Job? = null
    private var playbackJob: Job? = null
    private var toastJob: Job? = null
    private var hasInitialFit = false
    private var refreshFailCount = 0
    private val isPollInFlight = AtomicBoolean(false)
    private val analyticsMutex = Mutex()

    // Runtime memory for red loaded haul ("красный груженый ход"), live shift odometer & fuel/zone events
    private var currentShiftKey: String = GeoAnalytics.currentShiftWindow().key
    private val liveOdometerKmByVeh = HashMap<String, Double>()
    private val shiftFetchedAtMsByVeh = HashMap<String, Long>()
    private val lastLoadPlaceByVeh = HashMap<String, Triple<String, Double, Double>>()
    private val loadedStateByVeh = HashMap<String, Boolean>()
    private val prevCoordsByVeh = HashMap<String, Pair<Double, Double>>()
    private val prevFuelByVeh = HashMap<String, Double>()
    private val zoneEnterState = HashMap<String, MutableMap<String, Long>>() // zoneKey -> (vehId -> enterMs)
    private val favZoneEventState = HashMap<String, String>() // vehId -> zoneKey

    companion object {
        private const val NOTIF_CHANNEL_ID = "kru_fleet_alerts"
        private val PLAYBACK_SPEEDS = listOf(1, 2, 5, 10)
        private const val SHIFT_REFRESH_INTERVAL_MS = 90_000L
    }

    init {
        ensureNotificationChannel()
        viewModelScope.launch {
            val loadedSettings = storage.loadSettings()
            val loadedAzDb = storage.loadAutoZonesDb()
            val (loadedNotifs, unread) = storage.loadNotifications()

            _uiState.update {
                it.copy(
                    settings = loadedSettings,
                    autoZonesDb = loadedAzDb,
                    notifications = loadedNotifs,
                    unreadNotifications = unread
                )
            }

            if (loadedSettings.autoConnect && loadedSettings.server.isNotBlank() && loadedSettings.login.isNotBlank()) {
                connect(loadedSettings.server, loadedSettings.login, loadedSettings.password)
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Уведомления техники",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "События избранной техники: пересменка, заправка, погрузка"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun showToast(text: String, tone: ToastTone = ToastTone.INFO) {
        toastJob?.cancel()
        _uiState.update { it.copy(toast = ToastMessage(text = text, tone = tone)) }
        toastJob = viewModelScope.launch {
            delay(2800L)
            _uiState.update { it.copy(toast = null) }
        }
    }

    fun switchTab(tab: FleetTab) {
        _uiState.update { it.copy(activeTab = tab, quickExcPopoverOpen = false) }
    }

    fun openModal(modal: ActiveModal) {
        _uiState.update { it.copy(activeModal = modal, quickExcPopoverOpen = false) }
        if (modal is ActiveModal.VehicleInfo) {
            loadSensorsForVehicle(modal.vehicleId)
        } else if (modal is ActiveModal.Notifications) {
            markAllNotificationsRead()
        }
    }

    fun closeModal() {
        _uiState.update { it.copy(activeModal = ActiveModal.None) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val prevSettings = _uiState.value.settings
        val updated = transform(prevSettings)
        val zonesChanged = prevSettings.showDynamicZones != updated.showDynamicZones ||
            prevSettings.dynZoneRadiusMeters != updated.dynZoneRadiusMeters ||
            prevSettings.customNames != updated.customNames
        val loopChanged = prevSettings.autoRefresh != updated.autoRefresh ||
            prevSettings.refreshIntervalSec != updated.refreshIntervalSec ||
            prevSettings.selectedIntervalSec != updated.selectedIntervalSec ||
            prevSettings.ecoMode != updated.ecoMode

        _uiState.update { it.copy(settings = updated) }
        viewModelScope.launch { storage.saveSettings(updated) }

        if (zonesChanged) {
            viewModelScope.launch {
                val snapshot = _uiState.value
                val (dynZones, queues) = withContext(Dispatchers.Default) {
                    val dz = if (updated.showDynamicZones) {
                        GeoAnalytics.buildDynamicZones(snapshot.vehicles, updated.customNames, updated.dynZoneRadiusMeters, snapshot.autoZonesDb)
                    } else emptyList()
                    val q = GeoAnalytics.computeExcavatorQueues(snapshot.vehicles, dz, snapshot.zonePresence)
                    dz to q
                }
                _uiState.update { it.copy(dynamicZones = dynZones, excavatorQueues = queues) }
            }
        }
        if (updated.showZones && _uiState.value.serverZones.isEmpty() && _uiState.value.connectionState == ConnectionState.ONLINE) {
            loadServerZones()
        }
        if (loopChanged) {
            restartAutoRefreshLoop()
        }
    }

    fun toggleFollowSelected() {
        val nextFollow = !_uiState.value.settings.followSelected
        updateSettings { it.copy(followSelected = nextFollow) }
        if (nextFollow) {
            val sel = _uiState.value.selectedVehicle
            if (sel != null) {
                _uiState.update {
                    it.copy(cameraCommand = MapCameraCommand.FocusPoint(sel.lat, sel.lng, zoom = 16.5, preserveZoom = true))
                }
            }
            showToast("Слежение за объектом включено")
        } else {
            showToast("Слежение за объектом отключено")
        }
    }

    fun connect(server: String, login: String, password: String) {
        if (_uiState.value.connectionState == ConnectionState.CONNECTING) return
        updateSettings { it.copy(server = server.trim(), login = login.trim(), password = password) }

        _uiState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                isManualRefreshing = true
            )
        }

        viewModelScope.launch {
            val result = soapClient.performConnect(server.trim(), login.trim(), password)
            if (result.ok) {
                refreshFailCount = 0
                hasInitialFit = false
                applyIncomingVehicles(result.vehicles, PerfMetrics(), switchToMap = true)
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ONLINE,
                        isManualRefreshing = false,
                        connectionLogs = result.logs
                    )
                }
                showToast("Подключено · объектов: ${result.vehicles.size}", ToastTone.SUCCESS)
                if (_uiState.value.settings.showZones) {
                    loadServerZones()
                }
                restartAutoRefreshLoop()
            } else {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        isManualRefreshing = false,
                        connectionLogs = result.logs
                    )
                }
                showToast(result.error ?: "Ошибка подключения", ToastTone.ERROR)
            }
        }
    }

    fun manualRefresh() {
        val state = _uiState.value
        if (state.isManualRefreshing) return
        if (soapClient.cachedSession == null) {
            connect(state.settings.server, state.settings.login, state.settings.password)
            return
        }
        viewModelScope.launch {
            performRefreshInternal(showFeedback = true)
        }
    }

    private suspend fun performRefreshInternal(showFeedback: Boolean) {
        if (!isPollInFlight.compareAndSet(false, true)) return
        if (showFeedback) {
            _uiState.update { it.copy(isManualRefreshing = true) }
        }
        try {
            val res = soapClient.performFastRefresh()
            if (res.ok) {
                refreshFailCount = 0
                applyIncomingVehicles(
                    res.vehicles,
                    PerfMetrics(netMs = res.netMs, parseMs = res.parseMs, bytes = res.bytes),
                    switchToMap = false
                )
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ONLINE,
                        isManualRefreshing = false
                    )
                }
                if (showFeedback) {
                    showToast("Обновлено · ${res.vehicles.size} объ.", ToastTone.SUCCESS)
                }
            } else {
                refreshFailCount++
                // Silently re-authenticate if ASP.NET session cookie expired after 3 consecutive failures
                val settings = _uiState.value.settings
                if (refreshFailCount == 3 && settings.server.isNotBlank() && settings.login.isNotBlank()) {
                    val recon = soapClient.performConnect(settings.server, settings.login, settings.password)
                    if (recon.ok) {
                        refreshFailCount = 0
                        applyIncomingVehicles(recon.vehicles, PerfMetrics(), switchToMap = false)
                        _uiState.update {
                            it.copy(
                                connectionState = ConnectionState.ONLINE,
                                isManualRefreshing = false
                            )
                        }
                        if (showFeedback) {
                            showToast("Сессия восстановлена · ${recon.vehicles.size} объ.", ToastTone.SUCCESS)
                        }
                        return
                    }
                }
                _uiState.update {
                    it.copy(
                        isManualRefreshing = false,
                        connectionState = if (refreshFailCount >= 3) ConnectionState.ERROR else it.connectionState
                    )
                }
                if (showFeedback) {
                    showToast(res.error ?: "Не удалось обновить", ToastTone.WARNING)
                }
            }
        } finally {
            isPollInFlight.set(false)
            if (showFeedback) {
                _uiState.update { it.copy(isManualRefreshing = false) }
            }
        }
    }

    private fun restartAutoRefreshLoop() {
        refreshLoopJob?.cancel()
        val settings = _uiState.value.settings
        if (!settings.autoRefresh || _uiState.value.connectionState != ConnectionState.ONLINE) return

        refreshLoopJob = viewModelScope.launch {
            while (isActive) {
                val cur = _uiState.value
                val sec = if (cur.selectedVehicleId != null) {
                    cur.settings.selectedIntervalSec.coerceAtLeast(1.0)
                } else {
                    cur.settings.refreshIntervalSec.toDouble().coerceAtLeast(2.0)
                }
                val effectiveMs = if (cur.settings.ecoMode) (sec * 1000.0).toLong().coerceAtLeast(3000L) else (sec * 1000.0).toLong()
                delay(effectiveMs)
                if (_uiState.value.settings.autoRefresh && soapClient.cachedSession != null) {
                    performRefreshInternal(showFeedback = false)
                }
            }
        }
    }

    private data class PendingAlert(
        val vehId: String,
        val vehName: String,
        val kind: String,
        val title: String,
        val body: String,
        val isFav: Boolean,
        val tsMs: Long
    )

    private suspend fun applyIncomingVehicles(
        incoming: List<Vehicle>,
        perf: PerfMetrics,
        switchToMap: Boolean
    ) {
        val state = _uiState.value
        val settings = state.settings
        val nowMs = System.currentTimeMillis()
        val shiftWin = GeoAnalytics.currentShiftWindow(nowMs)

        val computed = withContext(Dispatchers.Default) {
            analyticsMutex.withLock {
                val shiftChanged = shiftWin.key != currentShiftKey
                if (shiftChanged) {
                    currentShiftKey = shiftWin.key
                    liveOdometerKmByVeh.clear()
                    shiftFetchedAtMsByVeh.clear()
                }

                val updatedShiftCache = if (shiftChanged) {
                    mutableMapOf()
                } else {
                    state.shiftStatsCache.toMutableMap()
                }

                // Step 1: Compute bearing & accumulate live shift odometer from GPS deltas
                val withCourse = incoming.map { v ->
                    val prev = prevCoordsByVeh[v.id]
                    var computedCourse = v.course
                    if (prev != null) {
                        val segMeters = GeoAnalytics.distanceMeters(prev.first, prev.second, v.lat, v.lng)
                        if (computedCourse == null && segMeters > 3.0) {
                            computedCourse = GeoAnalytics.bearingDegrees(prev.first, prev.second, v.lat, v.lng)
                        }
                        if (v.online && segMeters in 4.0..2500.0) {
                            val deltaKm = segMeters / 1000.0
                            val nextLive = (liveOdometerKmByVeh[v.id] ?: 0.0) + deltaKm
                            liveOdometerKmByVeh[v.id] = nextLive
                            updatedShiftCache[v.id]?.let { existing ->
                                updatedShiftCache[v.id] = existing.copy(
                                    totalDistanceKm = max(existing.totalDistanceKm + deltaKm, nextLive)
                                )
                            }
                        }
                    }
                    prevCoordsByVeh[v.id] = v.lat to v.lng
                    v.copy(course = computedCourse)
                }

                // Step 2: Build live dynamic zones (excavators + learned dump/fuel/shift)
                val dynZones = if (settings.showDynamicZones) {
                    GeoAnalytics.buildDynamicZones(withCourse, settings.customNames, settings.dynZoneRadiusMeters, state.autoZonesDb)
                } else emptyList()

                val excZones = dynZones.filter { it.kind == GeoZoneKind.EXCAVATOR }
                val dumpZones = dynZones.filter { it.kind == GeoZoneKind.DUMP }

                // Step 3: Enrich BelAZ loaded haul state ("красный груженый ход") & last loading zone
                val enriched = withCourse.map { v ->
                    if (v.type == VehicleType.BELAZ) {
                        val inExc = excZones.firstOrNull { GeoAnalytics.isVehicleInZone(v, it) }
                        val inDump = dumpZones.firstOrNull { GeoAnalytics.isVehicleInZone(v, it) }

                        if (inExc != null) {
                            lastLoadPlaceByVeh[v.id] = Triple(inExc.shortName, inExc.centerLat, inExc.centerLng)
                            if (v.speed <= 5.0 || (v.weight ?: 0.0) >= GeoAnalytics.LOADED_WEIGHT_THRESHOLD_TONS) {
                                loadedStateByVeh[v.id] = true
                            }
                        }
                        if (v.weight != null) {
                            if (v.weight >= GeoAnalytics.LOADED_WEIGHT_THRESHOLD_TONS) {
                                loadedStateByVeh[v.id] = true
                            } else if (v.weight < 8.0) {
                                loadedStateByVeh[v.id] = false
                            }
                        } else if (inDump != null && v.speed <= 4.0) {
                            loadedStateByVeh[v.id] = false
                        }

                        val isLoaded = v.isLoaded || (loadedStateByVeh[v.id] == true)
                        val lastLoad = lastLoadPlaceByVeh[v.id]
                        v.copy(
                            isLoaded = isLoaded,
                            lastLoadZone = lastLoad?.first,
                            lastLoadLat = lastLoad?.second,
                            lastLoadLng = lastLoad?.third
                        )
                    } else {
                        v
                    }
                }

                // Step 4: Track zone presence & fuel/shift notifications
                val allZones = buildList {
                    addAll(dynZones)
                    addAll(settings.customZones.map { cz ->
                        GeoZone(
                            id = cz.id,
                            key = "cz_${cz.id}",
                            name = cz.name,
                            shortName = cz.name,
                            colorHex = cz.colorHex,
                            kind = GeoZoneKind.CUSTOM,
                            isCircle = cz.isCircle,
                            centerLat = cz.centerLat,
                            centerLng = cz.centerLng,
                            radiusMeters = cz.radiusMeters,
                            polygon = cz.polygon
                        )
                    })
                    addAll(state.serverZones)
                }

                val pendingAlerts = mutableListOf<PendingAlert>()
                updateZonePresenceAndEvents(enriched, allZones, settings, nowMs, pendingAlerts)

                // Step 5: Accumulate live auto-zone observations
                val (updatedAzDb, azChanged) = accumulateAutoZoneObservations(enriched, state.autoZonesDb, settings, nowMs, pendingAlerts)

                val presenceSnapshot = zoneEnterState.mapValues { it.value.toMap() }
                val queues = GeoAnalytics.computeExcavatorQueues(enriched, dynZones, presenceSnapshot)
                val liveKmSnapshot = liveOdometerKmByVeh.toMap()

                Triple(
                    Triple(enriched, dynZones, presenceSnapshot),
                    Triple(queues, updatedAzDb, azChanged),
                    Triple(updatedShiftCache.toMap(), liveKmSnapshot, pendingAlerts.toList())
                )
            }
        }

        val (enriched, dynZones, presenceSnapshot) = computed.first
        val (queues, updatedAzDb, azChanged) = computed.second
        val (updatedShiftCache, liveKmSnapshot, pendingAlerts) = computed.third

        if (azChanged) {
            viewModelScope.launch { storage.saveAutoZonesDb(updatedAzDb) }
        }
        pendingAlerts.forEach { a ->
            pushNotification(a.vehId, a.vehName, a.kind, a.title, a.body, a.isFav, a.tsMs)
        }

        // Step 6: Camera focus command (initial fit or smooth follow selected preserving user zoom)
        val latestState = _uiState.value
        var camCmd: MapCameraCommand? = latestState.cameraCommand
        val selId = latestState.selectedVehicleId
        val selVeh = selId?.let { id -> enriched.firstOrNull { it.id == id } }

        if (!hasInitialFit && enriched.isNotEmpty()) {
            hasInitialFit = true
            camCmd = MapCameraCommand.FitBounds(enriched.map { LatLngPoint(it.lat, it.lng) })
        } else if (selVeh != null && latestState.settings.followSelected && latestState.activeTrack == null && !latestState.isDrawingZone) {
            camCmd = MapCameraCommand.FocusPoint(
                lat = selVeh.lat,
                lng = selVeh.lng,
                zoom = 16.5,
                preserveZoom = true,
                animate = true
            )
        }

        _uiState.update {
            it.copy(
                activeTab = if (switchToMap) FleetTab.MAP else it.activeTab,
                vehicles = enriched,
                dynamicZones = dynZones,
                zonePresence = presenceSnapshot,
                excavatorQueues = queues,
                autoZonesDb = updatedAzDb,
                shiftStatsCache = updatedShiftCache,
                liveShiftDistanceKm = liveKmSnapshot,
                currentShiftLabel = shiftWin.shortLabel,
                perfMetrics = if (perf.bytes > 0) perf else it.perfMetrics,
                cameraCommand = camCmd
            )
        }

        // Periodically refresh server shift mileage for the currently selected vehicle
        if (selVeh != null) {
            val lastFetch = shiftFetchedAtMsByVeh[selVeh.id] ?: 0L
            if (nowMs - lastFetch >= SHIFT_REFRESH_INTERVAL_MS && _uiState.value.loadingShiftForId != selVeh.id) {
                loadShiftSummaryForSelected(selVeh, force = true)
            }
        }
    }

    private fun updateZonePresenceAndEvents(
        vehicles: List<Vehicle>,
        zones: List<GeoZone>,
        settings: AppSettings,
        nowMs: Long,
        outAlerts: MutableList<PendingAlert>
    ) {
        val activeKeys = HashSet<String>()
        for (z in zones) {
            activeKeys.add(z.key)
            val prevMap = zoneEnterState[z.key] ?: mutableMapOf()
            val nextMap = mutableMapOf<String, Long>()
            for (v in vehicles) {
                if (!v.online) continue
                if (z.kind == GeoZoneKind.EXCAVATOR && v.type != VehicleType.BELAZ) continue
                if (GeoAnalytics.isVehicleInZone(v, z)) {
                    nextMap[v.id] = prevMap[v.id] ?: nowMs
                }
            }
            zoneEnterState[z.key] = nextMap
        }
        zoneEnterState.keys.retainAll(activeKeys)

        val shiftOrFuelZones = zones.filter {
            val k = GeoAnalytics.classifyAnyZoneKind(it)
            k == GeoZoneKind.FUEL || k == GeoZoneKind.SHIFT_CHANGE
        }

        for (v in vehicles) {
            val isFav = v.id in settings.favorites
            val currentZone = shiftOrFuelZones.firstOrNull { GeoAnalytics.isVehicleInZone(v, it) }
            val prevZoneKey = favZoneEventState[v.id]

            if (currentZone != null && prevZoneKey != currentZone.key) {
                val zKind = GeoAnalytics.classifyAnyZoneKind(currentZone)
                if (isFav || zKind == GeoZoneKind.FUEL) {
                    val vName = settings.customNames[v.id]?.takeIf { it.isNotBlank() } ?: v.name
                    val title = if (zKind == GeoZoneKind.FUEL) "Заправка · $vName" else "Пересменка · $vName"
                    val body = "Въезд в зону «${currentZone.shortName}»"
                    outAlerts.add(PendingAlert(v.id, vName, if (zKind == GeoZoneKind.FUEL) "fuel" else "shift_change", title, body, isFav, nowMs))
                }
                favZoneEventState[v.id] = currentZone.key
            } else if (currentZone == null && prevZoneKey != null) {
                favZoneEventState.remove(v.id)
            }
        }
    }

    private fun accumulateAutoZoneObservations(
        vehicles: List<Vehicle>,
        db: AutoZonesDb,
        settings: AppSettings,
        nowMs: Long,
        outAlerts: MutableList<PendingAlert>
    ): Pair<AutoZonesDb, Boolean> {
        val newFuelObs = db.fuelObs.toMutableList()
        var changed = false

        for (v in vehicles) {
            val liters = GeoAnalytics.parseFuelLiters(v.fuel) ?: continue
            val prev = prevFuelByVeh[v.id]
            prevFuelByVeh[v.id] = liters
            if (prev != null && liters - prev >= 60.0 && v.speed <= 5.0) {
                newFuelObs.add(AutoZoneObservation(v.lat, v.lng, nowMs, v.name))
                changed = true
                val vName = settings.customNames[v.id]?.takeIf { it.isNotBlank() } ?: v.name
                val delta = (liters - prev).toInt()
                outAlerts.add(
                    PendingAlert(
                        vehId = v.id,
                        vehName = vName,
                        kind = "refuel",
                        title = "Заправка +$delta л · $vName",
                        body = "Обнаружен рост ДУТ: ${prev.toInt()} -> ${liters.toInt()} л",
                        isFav = v.id in settings.favorites,
                        tsMs = nowMs
                    )
                )
            }
        }

        if (!changed) return db to false
        val pruned = db.copy(
            lastAnalyzeTs = nowMs,
            fuelObs = newFuelObs.filter { nowMs - it.tsMs <= GeoAnalytics.AZ_RETENTION_MS }.takeLast(400)
        )
        return pruned to true
    }

    private fun pushNotification(
        vehId: String,
        vehName: String,
        kind: String,
        title: String,
        body: String,
        isFav: Boolean,
        tsMs: Long
    ) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale("ru", "RU")).format(Date(tsMs))
        val entry = FleetNotification(
            id = "n_${tsMs}_${(1000..9999).random()}",
            vehId = vehId,
            vehName = vehName,
            kind = kind,
            isFav = isFav,
            title = title,
            body = body,
            timeStr = timeStr,
            tsMs = tsMs
        )
        val isModalOpen = _uiState.value.activeModal is ActiveModal.Notifications
        val updatedList = (listOf(entry) + _uiState.value.notifications).take(100)
        val newUnread = if (isModalOpen) 0 else _uiState.value.unreadNotifications + 1

        _uiState.update {
            it.copy(notifications = updatedList, unreadNotifications = newUnread)
        }
        viewModelScope.launch { storage.saveNotifications(updatedList, newUnread) }

        showToast("$title — $body", if (kind == "refuel") ToastTone.SUCCESS else ToastTone.INFO)

        if (isFav) {
            postSystemNotification(title, body, tsMs.toInt())
        }
    }

    private fun postSystemNotification(title: String, body: String, id: Int) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(app, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    fun markAllNotificationsRead() {
        _uiState.update { it.copy(unreadNotifications = 0) }
        viewModelScope.launch { storage.saveNotifications(_uiState.value.notifications, 0) }
    }

    fun clearNotifications() {
        _uiState.update { it.copy(notifications = emptyList(), unreadNotifications = 0) }
        viewModelScope.launch { storage.saveNotifications(emptyList(), 0) }
        showToast("Уведомления очищены")
    }

    // --- Selection, Search & Map Controls ---

    fun selectVehicle(vehicleId: String, switchToMap: Boolean = false, zoom: Double = 16.5) {
        val v = _uiState.value.vehicles.firstOrNull { it.id == vehicleId } ?: return
        _uiState.update {
            it.copy(
                selectedVehicleId = v.id,
                activeTab = if (switchToMap) FleetTab.MAP else it.activeTab,
                mapSearchQuery = "",
                quickExcPopoverOpen = false,
                cameraCommand = MapCameraCommand.FocusPoint(v.lat, v.lng, zoom = zoom, preserveZoom = false)
            )
        }
        loadSensorsForVehicle(v.id)
        loadShiftSummaryForSelected(v)
    }

    fun clearSelection() {
        shiftFetchJob?.cancel()
        _uiState.update { it.copy(selectedVehicleId = null, loadingShiftForId = null) }
    }

    fun toggleFollowDetailsExpanded() {
        _uiState.update { it.copy(followDetailsExpanded = !it.followDetailsExpanded) }
    }

    fun panToSelectedNow() {
        val v = _uiState.value.selectedVehicle ?: return
        _uiState.update { it.copy(cameraCommand = MapCameraCommand.FocusPoint(v.lat, v.lng, zoom = 16.8, preserveZoom = true)) }
    }

    fun focusLastLoadPlaceForSelected() {
        val v = _uiState.value.selectedVehicle ?: return
        val lat = v.lastLoadLat ?: return
        val lng = v.lastLoadLng ?: return
        _uiState.update { it.copy(cameraCommand = MapCameraCommand.FocusPoint(lat, lng, 16.5)) }
    }

    fun toggleSatelliteLayer() {
        _uiState.update { it.copy(isSatellite = !it.isSatellite) }
    }

    fun fitAllVehicles() {
        val visible = _uiState.value.visibleMapVehicles
        if (visible.isEmpty()) {
            showToast("Нет объектов для отображения", ToastTone.WARNING)
            return
        }
        updateSettings { it.copy(followSelected = false) }
        _uiState.update {
            it.copy(cameraCommand = MapCameraCommand.FitBounds(visible.map { v -> LatLngPoint(v.lat, v.lng) }))
        }
    }

    fun updateMyLocation(lat: Double, lng: Double) {
        _uiState.update {
            it.copy(
                myLocation = LatLngPoint(lat, lng),
                cameraCommand = MapCameraCommand.FocusPoint(lat, lng, 16.0)
            )
        }
    }

    fun onMapSearchQueryChange(q: String) {
        _uiState.update { it.copy(mapSearchQuery = q) }
    }

    fun onListSearchQueryChange(q: String) {
        _uiState.update { it.copy(listSearchQuery = q) }
    }

    fun setListTypeFilter(f: ListTypeFilter) {
        _uiState.update { it.copy(listTypeFilter = f) }
    }

    fun setListConnFilter(f: ListConnFilter) {
        _uiState.update { it.copy(listConnFilter = f) }
    }

    fun setListActFilter(f: ListActFilter) {
        _uiState.update { it.copy(listActFilter = f) }
    }

    fun toggleQuickExcRow() {
        _uiState.update { it.copy(quickExcRowExpanded = !it.quickExcRowExpanded) }
    }

    fun toggleQuickExcPopover() {
        _uiState.update { it.copy(quickExcPopoverOpen = !it.quickExcPopoverOpen) }
    }

    fun closeQuickExcPopover() {
        _uiState.update { it.copy(quickExcPopoverOpen = false) }
    }

    // --- Favorites, Renaming & Quick Excavators ---

    fun toggleFavorite(vehicleId: String) {
        updateSettings { s ->
            val list = s.favorites.toMutableList()
            if (vehicleId in list) list.remove(vehicleId) else list.add(vehicleId)
            s.copy(favorites = list)
        }
    }

    fun renameVehicle(vehicleId: String, newName: String) {
        val trimmed = newName.trim()
        updateSettings { s ->
            val map = s.customNames.toMutableMap()
            if (trimmed.isEmpty()) map.remove(vehicleId) else map[vehicleId] = trimmed
            s.copy(customNames = map)
        }
        closeModal()
        showToast(if (trimmed.isEmpty()) "Имя сброшено" else "Сохранено: $trimmed", ToastTone.SUCCESS)
    }

    fun resetRenamedExcavators() {
        val excIds = _uiState.value.vehicles.filter { it.type == VehicleType.EXCAVATOR }.map { it.id }.toSet()
        updateSettings { s ->
            s.copy(customNames = s.customNames.filterKeys { it !in excIds })
        }
        showToast("Имена экскаваторов сброшены")
    }

    fun toggleQuickExcavator(excId: String) {
        updateSettings { s ->
            val list = s.quickExcavatorIds.toMutableList()
            if (excId in list) list.remove(excId) else list.add(excId)
            s.copy(quickExcavatorIds = list)
        }
    }

    fun selectAllQuickExcavators() {
        val allIds = _uiState.value.vehicles.filter { it.type == VehicleType.EXCAVATOR }.map { it.id }
        updateSettings { it.copy(quickExcavatorIds = allIds) }
    }

    fun clearQuickExcavators() {
        updateSettings { it.copy(quickExcavatorIds = emptyList()) }
    }

    fun setAllMapTypes(enabled: Boolean) {
        updateSettings { it.copy(mapTypeFilter = VehicleType.entries.associateWith { enabled }) }
    }

    fun toggleMapType(type: VehicleType) {
        updateSettings { s ->
            val next = s.mapTypeFilter.toMutableMap()
            next[type] = !(next[type] ?: true)
            s.copy(mapTypeFilter = next)
        }
    }

    // --- Sensors, Server Zones & AutoZones ---

    fun loadSensorsForVehicle(vehicleId: String) {
        if (soapClient.cachedSession == null) return
        _uiState.update { it.copy(loadingSensorsForId = vehicleId) }
        viewModelScope.launch {
            val sensors = soapClient.performGetSensors(vehicleId)
            _uiState.update { state ->
                val map = state.sensorsCache.toMutableMap()
                map[vehicleId] = sensors
                state.copy(
                    sensorsCache = map,
                    loadingSensorsForId = if (state.loadingSensorsForId == vehicleId) null else state.loadingSensorsForId
                )
            }
        }
    }

    private fun extractCandidateAltIds(v: Vehicle): List<String> {
        val keys = listOf("ObjectID", "ObjId", "UnitId", "DeviceId", "Code", "OID", "PID", "IMEI", "ID", "Id")
        val rawLower = v.rawFields.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        return keys.mapNotNull { k -> v.rawFields[k] ?: rawLower[k.lowercase(Locale.ROOT)] }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != v.id }
            .distinct()
    }

    private fun loadShiftSummaryForSelected(v: Vehicle, force: Boolean = false) {
        if (soapClient.cachedSession == null) return
        val nowMs = System.currentTimeMillis()
        val shiftWin = GeoAnalytics.currentShiftWindow(nowMs)
        val lastFetch = shiftFetchedAtMsByVeh[v.id] ?: 0L
        if (!force && _uiState.value.shiftStatsCache.containsKey(v.id) && (nowMs - lastFetch < SHIFT_REFRESH_INTERVAL_MS)) {
            return
        }

        shiftFetchJob?.cancel()
        _uiState.update { it.copy(loadingShiftForId = v.id, currentShiftLabel = shiftWin.shortLabel) }

        shiftFetchJob = viewModelScope.launch {
            val altIds = extractCandidateAltIds(v)
            val res = soapClient.performGetHistory(v.id, shiftWin.startMs, shiftWin.endMs, altIds)
            shiftFetchedAtMsByVeh[v.id] = System.currentTimeMillis()

            if (res.ok && res.points.isNotEmpty()) {
                val summary = withContext(Dispatchers.Default) {
                    val base = GeoAnalytics.computeTrackSummary(
                        objectId = v.id,
                        vehicleName = _uiState.value.displayName(v),
                        points = res.points,
                        method = res.method,
                        format = res.format
                    )
                    val liveKm = liveOdometerKmByVeh[v.id] ?: 0.0
                    base.copy(totalDistanceKm = max(base.totalDistanceKm, liveKm))
                }
                _uiState.update { state ->
                    state.copy(
                        shiftStatsCache = state.shiftStatsCache + (v.id to summary),
                        loadingShiftForId = if (state.loadingShiftForId == v.id) null else state.loadingShiftForId
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        loadingShiftForId = if (state.loadingShiftForId == v.id) null else state.loadingShiftForId
                    )
                }
            }
        }
    }

    fun loadServerZones() {
        if (soapClient.cachedSession == null) return
        viewModelScope.launch {
            val res = soapClient.performGetZones()
            if (res.ok) {
                _uiState.update { it.copy(serverZones = res.zones) }
                showToast("Загружено геозон: ${res.zones.size}", ToastTone.SUCCESS)
            } else if (!res.error.isNullOrBlank()) {
                showToast(res.error, ToastTone.WARNING)
            }
        }
    }

    fun scanAutoZonesFromHistory() {
        if (_uiState.value.isScanningAutoZones) return
        if (soapClient.cachedSession == null) {
            showToast("Сначала подключитесь к серверу", ToastTone.WARNING)
            return
        }
        val candidates = _uiState.value.vehicles
            .filter { it.online && (it.type == VehicleType.BELAZ || it.type == VehicleType.FUEL) }
            .take(25)
        if (candidates.isEmpty()) {
            showToast("Нет активных самосвалов для анализа", ToastTone.WARNING)
            return
        }

        _uiState.update { it.copy(isScanningAutoZones = true) }
        showToast("Анализ истории за 24ч (${candidates.size} авто)...")

        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val fromMs = nowMs - 24L * 3600L * 1000L
            val newDumps = _uiState.value.autoZonesDb.dumpObs.toMutableList()
            val newFuels = _uiState.value.autoZonesDb.fuelObs.toMutableList()
            val newShifts = _uiState.value.autoZonesDb.shiftObs.toMutableList()

            for (v in candidates) {
                val res = soapClient.performGetHistory(v.id, fromMs, nowMs)
                if (!res.ok || res.points.size < 4) continue
                var prevWeight: Double? = null
                var prevFuel: Double? = null
                var stopStreak = 0
                for (pt in res.points) {
                    val w = pt.weight
                    if (w != null && prevWeight != null && prevWeight >= 18.0 && w < 8.0) {
                        newDumps.add(AutoZoneObservation(pt.lat, pt.lng, nowMs, v.name))
                    }
                    if (w != null) prevWeight = w

                    val f = pt.fuel
                    if (f != null && prevFuel != null && f - prevFuel >= 60.0 && pt.speed <= 5.0) {
                        newFuels.add(AutoZoneObservation(pt.lat, pt.lng, nowMs, v.name))
                    }
                    if (f != null) prevFuel = f

                    if (pt.speed <= 1.0) {
                        stopStreak++
                        if (stopStreak == 15) {
                            newShifts.add(AutoZoneObservation(pt.lat, pt.lng, nowMs, v.name))
                        }
                    } else {
                        stopStreak = 0
                    }
                }
            }

            val updatedDb = _uiState.value.autoZonesDb.copy(
                lastAnalyzeTs = nowMs,
                dumpObs = newDumps.takeLast(400),
                fuelObs = newFuels.takeLast(400),
                shiftObs = newShifts.takeLast(400)
            )
            storage.saveAutoZonesDb(updatedDb)
            val dynZones = if (_uiState.value.settings.showDynamicZones) {
                GeoAnalytics.buildDynamicZones(_uiState.value.vehicles, _uiState.value.settings.customNames, _uiState.value.settings.dynZoneRadiusMeters, updatedDb)
            } else emptyList()

            _uiState.update {
                it.copy(
                    isScanningAutoZones = false,
                    autoZonesDb = updatedDb,
                    dynamicZones = dynZones
                )
            }
            showToast("Автозоны обновлены: ${dynZones.size} зон", ToastTone.SUCCESS)
        }
    }

    fun forceRecalculateAutoZones() {
        val state = _uiState.value
        val nowMs = System.currentTimeMillis()
        val pruned = state.autoZonesDb.copy(
            lastAnalyzeTs = nowMs,
            excObs = state.autoZonesDb.excObs.filter { nowMs - it.tsMs <= GeoAnalytics.AZ_RETENTION_MS },
            dumpObs = state.autoZonesDb.dumpObs.filter { nowMs - it.tsMs <= GeoAnalytics.AZ_RETENTION_MS },
            fuelObs = state.autoZonesDb.fuelObs.filter { nowMs - it.tsMs <= GeoAnalytics.AZ_RETENTION_MS },
            shiftObs = state.autoZonesDb.shiftObs.filter { nowMs - it.tsMs <= GeoAnalytics.AZ_RETENTION_MS }
        )
        val dynZones = if (state.settings.showDynamicZones) {
            GeoAnalytics.buildDynamicZones(state.vehicles, state.settings.customNames, state.settings.dynZoneRadiusMeters, pruned)
        } else emptyList()
        _uiState.update { it.copy(autoZonesDb = pruned, dynamicZones = dynZones) }
        viewModelScope.launch { storage.saveAutoZonesDb(pruned) }
        showToast("Пересчитано автозон: ${dynZones.size}", ToastTone.SUCCESS)
    }

    fun resetAutoZonesDb() {
        val empty = AutoZonesDb()
        val state = _uiState.value
        val dynZones = if (state.settings.showDynamicZones) {
            GeoAnalytics.buildDynamicZones(state.vehicles, state.settings.customNames, state.settings.dynZoneRadiusMeters, empty)
        } else emptyList()
        _uiState.update { it.copy(autoZonesDb = empty, dynamicZones = dynZones) }
        viewModelScope.launch { storage.saveAutoZonesDb(empty) }
        showToast("База автозон очищена")
    }

    // --- Custom Zone Drawing & Editing ---

    fun toggleDrawZoneMode() {
        val currently = _uiState.value.isDrawingZone
        if (currently) {
            cancelDrawZoneMode()
        } else {
            _uiState.update {
                it.copy(
                    isDrawingZone = true,
                    drawZoneMode = DrawZoneMode.CIRCLE,
                    drawCircleCenter = null,
                    drawCircleRadius = 80,
                    drawPolygonPoints = emptyList(),
                    editingZoneId = null,
                    selectedVehicleId = null
                )
            }
            showToast("Нажмите на карту, чтобы указать центр зоны")
        }
    }

    fun cancelDrawZoneMode() {
        _uiState.update {
            it.copy(
                isDrawingZone = false,
                drawCircleCenter = null,
                drawPolygonPoints = emptyList(),
                editingZoneId = null
            )
        }
    }

    fun setDrawZoneMode(mode: DrawZoneMode) {
        _uiState.update { it.copy(drawZoneMode = mode) }
    }

    fun setDrawCircleRadius(radius: Int) {
        _uiState.update { it.copy(drawCircleRadius = radius.coerceIn(20, 500)) }
    }

    fun onMapTapWhileDrawing(lat: Double, lng: Double) {
        val pt = LatLngPoint(lat, lng)
        _uiState.update { state ->
            if (state.drawZoneMode == DrawZoneMode.CIRCLE) {
                state.copy(drawCircleCenter = pt)
            } else {
                state.copy(drawPolygonPoints = state.drawPolygonPoints + pt)
            }
        }
    }

    fun undoDrawPolygonPoint() {
        _uiState.update { state ->
            if (state.drawPolygonPoints.isNotEmpty()) {
                state.copy(drawPolygonPoints = state.drawPolygonPoints.dropLast(1))
            } else state
        }
    }

    fun openSaveCustomZoneModal() {
        val state = _uiState.value
        val valid = if (state.drawZoneMode == DrawZoneMode.CIRCLE) {
            state.drawCircleCenter != null
        } else {
            state.drawPolygonPoints.size >= 3
        }
        if (!valid) {
            showToast(
                if (state.drawZoneMode == DrawZoneMode.CIRCLE) "Сначала укажите центр круга на карте"
                else "Поставьте минимум 3 точки контура на карте",
                ToastTone.WARNING
            )
            return
        }
        openModal(ActiveModal.SaveCustomZone(state.editingZoneId))
    }

    fun startEditCustomZoneMeta(zoneId: String) {
        val existing = _uiState.value.settings.customZones.firstOrNull { it.id == zoneId } ?: return
        _uiState.update {
            it.copy(
                editingZoneId = existing.id,
                drawZoneMode = if (existing.isCircle) DrawZoneMode.CIRCLE else DrawZoneMode.POLYGON,
                drawCircleCenter = if (existing.isCircle) LatLngPoint(existing.centerLat, existing.centerLng) else null,
                drawCircleRadius = existing.radiusMeters.toInt(),
                drawPolygonPoints = existing.polygon,
                activeModal = ActiveModal.SaveCustomZone(existing.id)
            )
        }
    }

    fun startReshapeCustomZone(zoneId: String) {
        val existing = _uiState.value.settings.customZones.firstOrNull { it.id == zoneId } ?: return
        _uiState.update {
            it.copy(
                activeModal = ActiveModal.None,
                isDrawingZone = true,
                editingZoneId = existing.id,
                drawZoneMode = if (existing.isCircle) DrawZoneMode.CIRCLE else DrawZoneMode.POLYGON,
                drawCircleCenter = if (existing.isCircle) LatLngPoint(existing.centerLat, existing.centerLng) else null,
                drawCircleRadius = existing.radiusMeters.toInt(),
                drawPolygonPoints = existing.polygon
            )
        }
        showToast("Измените контур на карте и нажмите «Сохранить»")
    }

    fun saveCustomZone(name: String, colorHex: String) {
        val state = _uiState.value
        val cleanName = name.trim().ifEmpty { "Своя зона #${state.settings.customZones.size + 1}" }
        val isCircle = state.drawZoneMode == DrawZoneMode.CIRCLE
        val center = state.drawCircleCenter ?: LatLngPoint(
            state.drawPolygonPoints.map { it.lat }.average().takeIf { !it.isNaN() } ?: 0.0,
            state.drawPolygonPoints.map { it.lng }.average().takeIf { !it.isNaN() } ?: 0.0
        )

        val zoneId = state.editingZoneId ?: "cz_${System.currentTimeMillis()}"
        val newZone = CustomZoneData(
            id = zoneId,
            name = cleanName,
            colorHex = colorHex,
            isCircle = isCircle,
            centerLat = center.lat,
            centerLng = center.lng,
            radiusMeters = state.drawCircleRadius.toDouble(),
            polygon = if (isCircle) emptyList() else state.drawPolygonPoints
        )

        updateSettings { s ->
            val filtered = s.customZones.filterNot { it.id == zoneId }
            s.copy(customZones = filtered + newZone)
        }

        _uiState.update {
            it.copy(
                activeModal = ActiveModal.None,
                isDrawingZone = false,
                drawCircleCenter = null,
                drawPolygonPoints = emptyList(),
                editingZoneId = null
            )
        }
        showToast("Зона «$cleanName» сохранена", ToastTone.SUCCESS)
    }

    fun deleteCustomZone(zoneId: String) {
        updateSettings { s ->
            s.copy(customZones = s.customZones.filterNot { it.id == zoneId })
        }
        closeModal()
        showToast("Зона удалена")
    }

    // --- Route History & Playback ---

    fun requestRouteHistory(vehicleId: String, fromMs: Long, toMs: Long) {
        val vehicle = _uiState.value.vehicles.firstOrNull { it.id == vehicleId } ?: return
        if (toMs <= fromMs) {
            showToast("Дата «До» должна быть позже даты «От»", ToastTone.WARNING)
            return
        }

        _uiState.update { it.copy(isLoadingRoute = true, routeDebugText = "") }
        viewModelScope.launch {
            val altIds = extractCandidateAltIds(vehicle)

            val res = soapClient.performGetHistory(vehicle.id, fromMs, toMs, altIds)
            if (res.ok && res.points.isNotEmpty()) {
                val summary = GeoAnalytics.computeTrackSummary(
                    objectId = vehicle.id,
                    vehicleName = _uiState.value.displayName(vehicle),
                    points = res.points,
                    method = res.method,
                    format = res.format
                )
                stopPlayback()
                _uiState.update {
                    it.copy(
                        isLoadingRoute = false,
                        activeModal = ActiveModal.None,
                        activeTab = FleetTab.MAP,
                        activeTrack = summary,
                        playbackIndex = 0,
                        isPlayingRoute = false,
                        cameraCommand = MapCameraCommand.FitBounds(res.points.map { p -> LatLngPoint(p.lat, p.lng) })
                    )
                }
                showToast("Маршрут построен: ${res.points.size} точек", ToastTone.SUCCESS)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingRoute = false,
                        routeDebugText = res.debug
                    )
                }
                showToast(res.error ?: "Точек маршрута не найдено", ToastTone.WARNING)
            }
        }
    }

    fun clearActiveTrack() {
        stopPlayback()
        _uiState.update { it.copy(activeTrack = null, playbackIndex = 0) }
    }

    fun togglePlayback() {
        val track = _uiState.value.activeTrack ?: return
        if (track.points.size < 2) return
        if (_uiState.value.isPlayingRoute) {
            stopPlayback()
        } else {
            if (_uiState.value.playbackIndex >= track.points.lastIndex) {
                _uiState.update { it.copy(playbackIndex = 0) }
            }
            _uiState.update { it.copy(isPlayingRoute = true) }
            playbackJob?.cancel()
            playbackJob = viewModelScope.launch {
                while (isActive) {
                    val cur = _uiState.value
                    val pts = cur.activeTrack?.points ?: break
                    if (!cur.isPlayingRoute || pts.isEmpty()) break
                    val delayMs = (420L / cur.playbackSpeed.coerceAtLeast(1)).coerceAtLeast(45L)
                    delay(delayMs)
                    val nextIdx = cur.playbackIndex + 1
                    if (nextIdx >= pts.size) {
                        _uiState.update { it.copy(isPlayingRoute = false) }
                        break
                    }
                    val pt = pts[nextIdx]
                    _uiState.update {
                        it.copy(
                            playbackIndex = nextIdx,
                            cameraCommand = if (it.playbackFollow) MapCameraCommand.FocusPoint(pt.lat, pt.lng, zoom = 16.0, preserveZoom = true) else it.cameraCommand
                        )
                    }
                }
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(isPlayingRoute = false) }
    }

    fun cyclePlaybackSpeed() {
        val cur = _uiState.value.playbackSpeed
        val next = PLAYBACK_SPEEDS[(PLAYBACK_SPEEDS.indexOf(cur) + 1) % PLAYBACK_SPEEDS.size]
        _uiState.update { it.copy(playbackSpeed = next) }
    }

    fun togglePlaybackFollow() {
        _uiState.update { it.copy(playbackFollow = !it.playbackFollow) }
    }

    fun seekPlayback(index: Int) {
        val track = _uiState.value.activeTrack ?: return
        val clamped = index.coerceIn(0, track.points.lastIndex.coerceAtLeast(0))
        val pt = track.points.getOrNull(clamped)
        _uiState.update {
            it.copy(
                playbackIndex = clamped,
                cameraCommand = if (pt != null && it.playbackFollow) MapCameraCommand.FocusPoint(pt.lat, pt.lng, zoom = 16.0, preserveZoom = true) else it.cameraCommand
            )
        }
    }

    override fun onCleared() {
        refreshLoopJob?.cancel()
        shiftFetchJob?.cancel()
        playbackJob?.cancel()
        toastJob?.cancel()
        super.onCleared()
    }
}
