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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FleetViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = FleetStorage(application)
    private val soapClient = NavisolSoapClient()

    private val _uiState = MutableStateFlow(FleetUiState())
    val uiState: StateFlow<FleetUiState> = _uiState.asStateFlow()

    private var refreshLoopJob: Job? = null
    private var playbackJob: Job? = null
    private var toastJob: Job? = null
    private var hasInitialFit = false
    private var refreshFailCount = 0

    // Runtime memory for red loaded haul ("красный груженый ход") & fuel/zone events
    private val lastLoadPlaceByVeh = HashMap<String, Triple<String, Double, Double>>()
    private val loadedStateByVeh = HashMap<String, Boolean>()
    private val prevCoordsByVeh = HashMap<String, Pair<Double, Double>>()
    private val prevFuelByVeh = HashMap<String, Double>()
    private val zoneEnterState = HashMap<String, MutableMap<String, Long>>() // zoneKey -> (vehId -> enterMs)
    private val favZoneEventState = HashMap<String, String>() // vehId -> zoneKey

    companion object {
        private const val NOTIF_CHANNEL_ID = "kru_fleet_alerts"
        private val PLAYBACK_SPEEDS = listOf(1, 2, 5, 10)
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
        val updated = transform(_uiState.value.settings)
        _uiState.update { state ->
            val dynZones = if (updated.showDynamicZones) {
                GeoAnalytics.buildDynamicZones(state.vehicles, updated.customNames, updated.dynZoneRadiusMeters, state.autoZonesDb)
            } else emptyList()
            val queues = GeoAnalytics.computeExcavatorQueues(state.vehicles, dynZones, state.zonePresence)
            state.copy(
                settings = updated,
                dynamicZones = dynZones,
                excavatorQueues = queues
            )
        }
        viewModelScope.launch { storage.saveSettings(updated) }
        if (updated.showZones && _uiState.value.serverZones.isEmpty() && _uiState.value.connectionState == ConnectionState.ONLINE) {
            loadServerZones()
        }
        restartAutoRefreshLoop()
    }

    fun connect(server: String, login: String, password: String) {
        if (_uiState.value.connectionState == ConnectionState.CONNECTING) return
        updateSettings { it.copy(server = server.trim(), login = login.trim(), password = password) }

        _uiState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                isRefreshing = true
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
                        isRefreshing = false,
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
                        isRefreshing = false,
                        connectionLogs = result.logs
                    )
                }
                showToast(result.error ?: "Ошибка подключения", ToastTone.ERROR)
            }
        }
    }

    fun manualRefresh() {
        val state = _uiState.value
        if (state.isRefreshing) return
        if (soapClient.cachedSession == null) {
            connect(state.settings.server, state.settings.login, state.settings.password)
            return
        }
        viewModelScope.launch {
            performRefreshInternal(showFeedback = true)
        }
    }

    private suspend fun performRefreshInternal(showFeedback: Boolean) {
        _uiState.update { it.copy(isRefreshing = true) }
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
                    isRefreshing = false
                )
            }
            if (showFeedback) {
                showToast("Обновлено · ${res.vehicles.size} объ.", ToastTone.SUCCESS)
            }
        } else {
            refreshFailCount++
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    connectionState = if (refreshFailCount >= 3) ConnectionState.ERROR else it.connectionState
                )
            }
            if (showFeedback) {
                showToast(res.error ?: "Не удалось обновить", ToastTone.WARNING)
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
                    cur.settings.selectedIntervalSec.coerceAtLeast(0.5)
                } else {
                    cur.settings.refreshIntervalSec.toDouble().coerceAtLeast(1.0)
                }
                val effectiveMs = if (cur.settings.ecoMode) (sec * 1000.0).toLong().coerceAtLeast(2500L) else (sec * 1000.0).toLong()
                delay(effectiveMs)
                if (_uiState.value.settings.autoRefresh && !_uiState.value.isRefreshing && soapClient.cachedSession != null) {
                    performRefreshInternal(showFeedback = false)
                }
            }
        }
    }

    private fun applyIncomingVehicles(
        incoming: List<Vehicle>,
        perf: PerfMetrics,
        switchToMap: Boolean
    ) {
        val state = _uiState.value
        val settings = state.settings
        val nowMs = System.currentTimeMillis()

        // Step 1: Compute bearing from previous coordinates if server course is missing
        val withCourse = incoming.map { v ->
            val prev = prevCoordsByVeh[v.id]
            val computedCourse = if (v.course != null) {
                v.course
            } else if (prev != null && GeoAnalytics.distanceMeters(prev.first, prev.second, v.lat, v.lng) > 3.0) {
                GeoAnalytics.bearingDegrees(prev.first, prev.second, v.lat, v.lng)
            } else {
                null
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
                    // BelAZ stopped/loading at excavator becomes loaded
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

        updateZonePresenceAndEvents(enriched, allZones, nowMs)

        // Step 5: Accumulate live auto-zone observations (fuel refills by DUT jump >= 60L, dumps when weight drops)
        val updatedAzDb = accumulateAutoZoneObservations(enriched, state.autoZonesDb, nowMs)

        val presenceSnapshot = zoneEnterState.mapValues { it.value.toMap() }
        val queues = GeoAnalytics.computeExcavatorQueues(enriched, dynZones, presenceSnapshot)

        // Step 6: Camera focus command (initial fit or follow selected)
        var camCmd: MapCameraCommand? = state.cameraCommand
        val selId = state.selectedVehicleId
        if (!hasInitialFit && enriched.isNotEmpty()) {
            hasInitialFit = true
            camCmd = MapCameraCommand.FitBounds(enriched.map { LatLngPoint(it.lat, it.lng) })
        } else if (selId != null && settings.followSelected && state.activeTrack == null) {
            val selVeh = enriched.firstOrNull { it.id == selId }
            if (selVeh != null) {
                camCmd = MapCameraCommand.FocusPoint(selVeh.lat, selVeh.lng, zoom = 16.5)
            }
        }

        _uiState.update {
            it.copy(
                activeTab = if (switchToMap) FleetTab.MAP else it.activeTab,
                vehicles = enriched,
                dynamicZones = dynZones,
                zonePresence = presenceSnapshot,
                excavatorQueues = queues,
                autoZonesDb = updatedAzDb,
                perfMetrics = if (perf.bytes > 0) perf else it.perfMetrics,
                cameraCommand = camCmd
            )
        }
    }

    private fun updateZonePresenceAndEvents(vehicles: List<Vehicle>, zones: List<GeoZone>, nowMs: Long) {
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

        val settings = _uiState.value.settings
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
                    val vName = _uiState.value.displayName(v)
                    val title = if (zKind == GeoZoneKind.FUEL) "Заправка · $vName" else "Пересменка · $vName"
                    val body = "Въезд в зону «${currentZone.shortName}»"
                    pushNotification(v.id, vName, if (zKind == GeoZoneKind.FUEL) "fuel" else "shift_change", title, body, isFav, nowMs)
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
        nowMs: Long
    ): AutoZonesDb {
        val newFuelObs = db.fuelObs.toMutableList()
        var changed = false

        for (v in vehicles) {
            val liters = GeoAnalytics.parseFuelLiters(v.fuel) ?: continue
            val prev = prevFuelByVeh[v.id]
            prevFuelByVeh[v.id] = liters
            if (prev != null && liters - prev >= 60.0 && v.speed <= 5.0) {
                newFuelObs.add(AutoZoneObservation(v.lat, v.lng, nowMs, v.name))
                changed = true
                val vName = _uiState.value.displayName(v)
                val delta = (liters - prev).toInt()
                pushNotification(
                    vehId = v.id,
                    vehName = vName,
                    kind = "refuel",
                    title = "Заправка +$delta л · $vName",
                    body = "Обнаружен рост ДУТ: ${prev.toInt()} -> ${liters.toInt()} л",
                    isFav = v.id in _uiState.value.settings.favorites,
                    tsMs = nowMs
                )
            }
        }

        if (!changed) return db
        val pruned = db.copy(
            lastAnalyzeTs = nowMs,
            fuelObs = newFuelObs.filter { nowMs - it.tsMs <= GeoAnalytics.AZ_RETENTION_MS }.takeLast(400)
        )
        viewModelScope.launch { storage.saveAutoZonesDb(pruned) }
        return pruned
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
                cameraCommand = MapCameraCommand.FocusPoint(v.lat, v.lng, zoom)
            )
        }
        loadSensorsForVehicle(v.id)
        loadShiftSummaryForSelected(v)
        restartAutoRefreshLoop()
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedVehicleId = null) }
        restartAutoRefreshLoop()
    }

    fun toggleFollowDetailsExpanded() {
        _uiState.update { it.copy(followDetailsExpanded = !it.followDetailsExpanded) }
    }

    fun panToSelectedNow() {
        val v = _uiState.value.selectedVehicle ?: return
        _uiState.update { it.copy(cameraCommand = MapCameraCommand.FocusPoint(v.lat, v.lng, 16.8)) }
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

    private fun loadShiftSummaryForSelected(v: Vehicle) {
        if (soapClient.cachedSession == null || v.type != VehicleType.BELAZ) return
        if (_uiState.value.shiftStatsCache.containsKey(v.id)) return
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val shiftStartMs = nowMs - 12L * 3600L * 1000L
            val res = soapClient.performGetHistory(v.id, shiftStartMs, nowMs)
            if (res.ok && res.points.isNotEmpty()) {
                val summary = GeoAnalytics.computeTrackSummary(v.id, _uiState.value.displayName(v), res.points, res.method, res.format)
                _uiState.update { it.copy(shiftStatsCache = it.shiftStatsCache + (v.id to summary)) }
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
            val altIds = listOfNotNull(
                vehicle.rawFields["ObjectID"],
                vehicle.rawFields["ObjId"],
                vehicle.rawFields["UnitId"],
                vehicle.rawFields["IMEI"]
            ).filter { it.isNotBlank() && it != vehicle.id }

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
                            cameraCommand = if (it.playbackFollow) MapCameraCommand.FocusPoint(pt.lat, pt.lng, 16.0) else it.cameraCommand
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
                cameraCommand = if (pt != null && it.playbackFollow) MapCameraCommand.FocusPoint(pt.lat, pt.lng, 16.0) else it.cameraCommand
            )
        }
    }

    override fun onCleared() {
        refreshLoopJob?.cancel()
        playbackJob?.cancel()
        toastJob?.cancel()
        super.onCleared()
    }
}
