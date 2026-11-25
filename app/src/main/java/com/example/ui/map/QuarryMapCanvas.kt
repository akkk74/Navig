package com.example.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.DrawZoneMode
import com.example.data.model.GeoZone
import com.example.data.model.GeoZoneKind
import com.example.data.model.LatLngPoint
import com.example.data.model.TrackPoint
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType
import com.example.domain.GeoAnalytics
import com.example.ui.FleetUiState
import com.example.ui.MapCameraCommand
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val ESRI_WORLD_IMAGERY = object : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    18,
    256,
    ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$z/$y/$x"
    }
}

private fun configureOsmdroid(context: Context) {
    val cfg = Configuration.getInstance()
    val base = File(context.cacheDir, "osmdroid").apply { mkdirs() }
    val tiles = File(base, "tiles").apply { mkdirs() }
    cfg.osmdroidBasePath = base
    cfg.osmdroidTileCache = tiles
    cfg.userAgentValue = context.packageName
}

private data class RenderZone(
    val zone: GeoZone,
    val baseColor: Int,
    val fillColor: Int,
    val strokeColor: Int,
    val caption: String,
    val centerGeo: GeoPoint,
    val polygonGeo: List<GeoPoint>
)

private data class RenderVehicle(
    val vehicle: Vehicle,
    val isSelected: Boolean,
    val isFavorite: Boolean,
    val markerColor: Int,
    val labelText: String,
    val glyph: String
)

private data class RenderTrackPoint(
    val geo: GeoPoint,
    val isLoaded: Boolean
)

private data class MapRenderSnapshot(
    val zones: List<RenderZone>,
    val vehicles: List<RenderVehicle>,
    val trackPoints: List<RenderTrackPoint>,
    val playbackGeo: GeoPoint?,
    val myLocationGeo: GeoPoint?,
    val isDrawingZone: Boolean,
    val drawZoneMode: DrawZoneMode,
    val drawCircleCenter: GeoPoint?,
    val drawCircleRadius: Int,
    val drawPolygonGeo: List<GeoPoint>,
    val showLabels: Boolean,
    val simpleIcons: Boolean,
    val darkLabels: Boolean,
    val uiScale: Float,
    val smoothMotion: Boolean,
    val frameDelayMs: Long,
    val isMapVisible: Boolean
)

private class CameraCommandHolder {
    var isLaidOut: Boolean = false
    var pending: MapCameraCommand? = null
    var lastAppliedNonce: Long = 0L
}

private fun executeCameraCommand(mapView: MapView, cmd: MapCameraCommand) {
    when (cmd) {
        is MapCameraCommand.FocusPoint -> {
            val currentZoom = mapView.zoomLevelDouble
            val targetZoom = if (cmd.preserveZoom && currentZoom >= 13.5) {
                currentZoom
            } else {
                cmd.zoom
            }
            val targetPt = GeoPoint(cmd.lat, cmd.lng)
            if (cmd.animate) {
                mapView.controller.animateTo(targetPt, targetZoom, 380L)
            } else {
                mapView.controller.setZoom(targetZoom)
                mapView.controller.setCenter(targetPt)
            }
        }
        is MapCameraCommand.FitBounds -> {
            val pts = cmd.points
            if (pts.size == 1) {
                mapView.controller.animateTo(GeoPoint(pts[0].lat, pts[0].lng), 15.5, 420L)
            } else if (pts.size > 1) {
                val north = pts.maxOf { it.lat }
                val south = pts.minOf { it.lat }
                val east = pts.maxOf { it.lng }
                val west = pts.minOf { it.lng }
                val padLat = ((north - south) * 0.15).coerceAtLeast(0.004)
                val padLng = ((east - west) * 0.15).coerceAtLeast(0.004)
                val box = BoundingBox(north + padLat, east + padLng, south - padLat, west - padLng)
                runCatching { mapView.zoomToBoundingBox(box, true, 96) }
            }
        }
    }
}

private fun resolveVehicleColor(v: Vehicle): Int {
    if (!v.online) return 0xFF64748B.toInt()
    if (v.engineOff == true) return 0xFF475569.toInt()
    if (v.isLoadedHaul) return 0xFFEF4444.toInt() // Красный гружёный ход
    return when (v.type) {
        VehicleType.BELAZ -> if (v.isMoving) 0xFF10B981.toInt() else 0xFF3B82F6.toInt()
        VehicleType.EXCAVATOR -> 0xFFF97316.toInt()
        VehicleType.BULLDOZER -> 0xFFEAB308.toInt()
        VehicleType.GRADER -> 0xFF14B8A6.toInt()
        VehicleType.FUEL -> 0xFF06B6D4.toInt()
        VehicleType.OTHER -> if (v.isMoving) 0xFF10B981.toInt() else 0xFF6366F1.toInt()
    }
}

private fun decimateTrack(points: List<TrackPoint>, maxPoints: Int = 750): List<RenderTrackPoint> {
    if (points.isEmpty()) return emptyList()
    val step = (points.size / maxPoints).coerceAtLeast(1)
    val result = ArrayList<RenderTrackPoint>(points.size.coerceAtMost(maxPoints + 16))
    var prevLoaded = false
    for (i in points.indices) {
        val p = points[i]
        val loaded = (p.weight ?: 0.0) >= GeoAnalytics.LOADED_WEIGHT_THRESHOLD_TONS
        val mustKeep = i == 0 || i == points.lastIndex || (i % step == 0) || (loaded != prevLoaded)
        if (mustKeep) {
            result.add(RenderTrackPoint(GeoPoint(p.lat, p.lng), loaded))
            prevLoaded = loaded
        }
    }
    return result
}

@Composable
fun QuarryMapCanvas(
    state: FleetUiState,
    isMapVisible: Boolean,
    onVehicleTap: (Vehicle) -> Unit,
    onZoneTap: (GeoZone) -> Unit,
    onDrawTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { configureOsmdroid(context) }

    // Precompute all heavy sorting, color parsing, zone captions, and track decimation ONLY when map-visual inputs change
    val renderSnapshot = remember(
        state.vehicles,
        state.selectedVehicleId,
        state.isSatellite,
        state.dynamicZones,
        state.serverZones,
        state.excavatorQueues,
        state.activeTrack,
        state.playbackIndex,
        state.myLocation,
        state.isDrawingZone,
        state.drawZoneMode,
        state.drawCircleCenter,
        state.drawCircleRadius,
        state.drawPolygonPoints,
        state.settings.showLabels,
        state.settings.showOffline,
        state.settings.showZones,
        state.settings.showDynamicZones,
        state.settings.simpleIcons,
        state.settings.darkMode,
        state.settings.uiScale,
        state.settings.favorites,
        state.settings.customNames,
        state.settings.customZones,
        state.settings.mapTypeFilter,
        state.settings.smoothMotion,
        state.settings.ecoMode,
        state.settings.fps,
        isMapVisible
    ) {
        val queuesByZone = state.excavatorQueues.associateBy { it.zone.key }
        val rawZones = buildList {
            if (state.settings.showZones) addAll(state.serverZones)
            if (state.settings.showDynamicZones) addAll(state.dynamicZones)
            addAll(state.settings.customZones.map { cz ->
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
        }

        val renderZones = rawZones.map { z ->
            val baseColor = runCatching { Color.parseColor(z.colorHex) }.getOrDefault(0xFF2563EB.toInt())
            val qCount = queuesByZone[z.key]?.belazList?.size
            val caption = if (z.kind == GeoZoneKind.EXCAVATOR && qCount != null && qCount > 0) {
                "${z.shortName} · $qCount БелАЗ"
            } else {
                z.shortName
            }
            RenderZone(
                zone = z,
                baseColor = baseColor,
                fillColor = (baseColor and 0x00FFFFFF) or 0x26000000,
                strokeColor = (baseColor and 0x00FFFFFF) or 0xCC000000.toInt(),
                caption = caption,
                centerGeo = GeoPoint(z.centerLat, z.centerLng),
                polygonGeo = z.polygon.map { GeoPoint(it.lat, it.lng) }
            )
        }

        val favSet = state.settings.favorites.toHashSet()
        val renderVehicles = state.visibleMapVehicles
            .sortedWith(
                compareBy<Vehicle> { it.id == state.selectedVehicleId }
                    .thenBy { it.id in favSet }
                    .thenBy { it.isLoadedHaul }
            )
            .map { v ->
                val dName = state.displayName(v)
                val suffix = when {
                    v.isLoadedHaul && v.speed > 2.0 -> " · ${v.speed.roundToInt()} км/ч · ГРУЖ"
                    v.speed > 2.0 -> " · ${v.speed.roundToInt()} км/ч"
                    v.isLoadedHaul -> " · ГРУЖ"
                    else -> ""
                }
                val glyph = when (v.type) {
                    VehicleType.BELAZ -> if (v.isLoaded) "Г" else "Б"
                    VehicleType.EXCAVATOR -> "Э"
                    VehicleType.BULLDOZER -> "Д"
                    VehicleType.GRADER -> "Р"
                    VehicleType.FUEL -> "Т"
                    VehicleType.OTHER -> "•"
                }
                RenderVehicle(
                    vehicle = v,
                    isSelected = v.id == state.selectedVehicleId,
                    isFavorite = v.id in favSet,
                    markerColor = resolveVehicleColor(v),
                    labelText = dName + suffix,
                    glyph = glyph
                )
            }

        val trackPts = state.activeTrack?.points.orEmpty()
        val decimatedTrack = decimateTrack(trackPts)
        val playPt = trackPts.getOrNull(state.playbackIndex.coerceIn(0, trackPts.lastIndex.coerceAtLeast(0)))
        val targetFps = if (state.settings.ecoMode) 20 else state.settings.fps.coerceIn(15, 60)

        MapRenderSnapshot(
            zones = renderZones,
            vehicles = renderVehicles,
            trackPoints = decimatedTrack,
            playbackGeo = playPt?.let { GeoPoint(it.lat, it.lng) },
            myLocationGeo = state.myLocation?.let { GeoPoint(it.lat, it.lng) },
            isDrawingZone = state.isDrawingZone,
            drawZoneMode = state.drawZoneMode,
            drawCircleCenter = state.drawCircleCenter?.let { GeoPoint(it.lat, it.lng) },
            drawCircleRadius = state.drawCircleRadius,
            drawPolygonGeo = state.drawPolygonPoints.map { GeoPoint(it.lat, it.lng) },
            showLabels = state.settings.showLabels,
            simpleIcons = state.settings.simpleIcons,
            darkLabels = state.settings.darkMode || state.isSatellite,
            uiScale = state.settings.uiScale.coerceIn(1f, 1.5f),
            smoothMotion = state.settings.smoothMotion,
            frameDelayMs = (1000L / targetFps).coerceIn(16L, 66L),
            isMapVisible = isMapVisible
        )
    }

    val overlay = remember {
        FleetMapOverlay(
            onVehicleTap = onVehicleTap,
            onZoneTap = onZoneTap,
            onDrawTap = onDrawTap
        )
    }
    val camHolder = remember { CameraCommandHolder() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            // Default focus on Kuzbass coal basin (Kemerovo region)
            controller.setZoom(11.5)
            controller.setCenter(GeoPoint(54.35, 86.18))
            overlays.add(overlay)
            addOnFirstLayoutListener { _, _, _, _, _ ->
                camHolder.isLaidOut = true
                camHolder.pending?.let { pendingCmd ->
                    camHolder.pending = null
                    camHolder.lastAppliedNonce = pendingCmd.nonce
                    post { executeCameraCommand(this, pendingCmd) }
                }
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Update tile source & dark tile filter only when they actually change
    LaunchedEffect(state.isSatellite, state.settings.darkMode) {
        val targetSource = if (state.isSatellite) ESRI_WORLD_IMAGERY else TileSourceFactory.MAPNIK
        if (mapView.tileProvider.tileSource.name() != targetSource.name()) {
            mapView.setTileSource(targetSource)
        }
        if (!state.isSatellite && state.settings.darkMode) {
            val invertMatrix = ColorMatrix(
                floatArrayOf(
                    -0.88f, 0f, 0f, 0f, 230f,
                    0f, -0.88f, 0f, 0f, 232f,
                    0f, 0f, -0.82f, 0f, 238f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(invertMatrix))
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
        mapView.invalidate()
    }

    // Hide native MapView when another tab is active (keeps tiles & layout intact, avoids GPU work & touch bleed)
    LaunchedEffect(isMapVisible) {
        mapView.visibility = if (isMapVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (isMapVisible) {
            mapView.invalidate()
        }
    }

    // Execute camera commands reliably even if fired before first layout or when returning to Map tab
    LaunchedEffect(state.cameraCommand?.nonce, isMapVisible) {
        val cmd = state.cameraCommand ?: return@LaunchedEffect
        if (cmd.nonce == camHolder.lastAppliedNonce) return@LaunchedEffect
        if (!isMapVisible) {
            camHolder.pending = cmd
            return@LaunchedEffect
        }
        if (camHolder.isLaidOut && mapView.width > 0 && mapView.height > 0) {
            camHolder.pending = null
            camHolder.lastAppliedNonce = cmd.nonce
            executeCameraCommand(mapView, cmd)
        } else {
            camHolder.pending = cmd
            mapView.post {
                if (mapView.width > 0 && mapView.height > 0 && camHolder.pending?.nonce == cmd.nonce) {
                    camHolder.isLaidOut = true
                    camHolder.pending = null
                    camHolder.lastAppliedNonce = cmd.nonce
                    executeCameraCommand(mapView, cmd)
                }
            }
        }
    }

    // Push snapshot to overlay & invalidate ONLY when the precomputed visual snapshot changes
    LaunchedEffect(renderSnapshot) {
        overlay.updateSnapshot(renderSnapshot)
        if (isMapVisible) {
            mapView.invalidate()
        }
    }

    AndroidView(
        factory = { mapView },
        update = { /* Visual invalidation handled exclusively by renderSnapshot LaunchedEffect */ },
        modifier = modifier
    )
}

private data class VehAnimState(
    var startLat: Double,
    var startLng: Double,
    var targetLat: Double,
    var targetLng: Double,
    var startMs: Long,
    var durationMs: Long
) {
    fun currentLat(nowMs: Long): Double {
        if (durationMs <= 0L || nowMs >= startMs + durationMs) return targetLat
        val t = ((nowMs - startMs).toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val eased = 1.0 - (1.0 - t) * (1.0 - t)
        return startLat + (targetLat - startLat) * eased
    }

    fun currentLng(nowMs: Long): Double {
        if (durationMs <= 0L || nowMs >= startMs + durationMs) return targetLng
        val t = ((nowMs - startMs).toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val eased = 1.0 - (1.0 - t) * (1.0 - t)
        return startLng + (targetLng - startLng) * eased
    }

    fun isActive(nowMs: Long): Boolean = durationMs > 0L && nowMs < startMs + durationMs
}

private class FleetMapOverlay(
    private val onVehicleTap: (Vehicle) -> Unit,
    private val onZoneTap: (GeoZone) -> Unit,
    private val onDrawTap: (Double, Double) -> Unit
) : Overlay() {

    private var snapshot: MapRenderSnapshot? = null
    private val animByVehId = HashMap<String, VehAnimState>()

    // Reusable scratch geometry (zero allocations in draw)
    private val screenPt = Point()
    private val screenPt2 = Point()
    private val scratchGeo = GeoPoint(0.0, 0.0)
    private val reusablePath = Path()
    private val reusableRect = RectF()

    // Cached Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun updateSnapshot(newSnapshot: MapRenderSnapshot) {
        val nowMs = SystemClock.uptimeMillis()
        val smooth = newSnapshot.smoothMotion
        val activeIds = HashSet<String>(newSnapshot.vehicles.size)

        for (rv in newSnapshot.vehicles) {
            val v = rv.vehicle
            activeIds.add(v.id)
            val prev = animByVehId[v.id]
            if (prev == null) {
                animByVehId[v.id] = VehAnimState(v.lat, v.lng, v.lat, v.lng, nowMs, 0L)
            } else if (abs(prev.targetLat - v.lat) > 1e-6 || abs(prev.targetLng - v.lng) > 1e-6) {
                val curLat = prev.currentLat(nowMs)
                val curLng = prev.currentLng(nowMs)
                val jumpDeg = abs(v.lat - curLat) + abs(v.lng - curLng)
                if (smooth && jumpDeg < 0.03) {
                    prev.startLat = curLat
                    prev.startLng = curLng
                    prev.targetLat = v.lat
                    prev.targetLng = v.lng
                    prev.startMs = nowMs
                    prev.durationMs = 700L
                } else {
                    prev.startLat = v.lat
                    prev.startLng = v.lng
                    prev.targetLat = v.lat
                    prev.targetLng = v.lng
                    prev.startMs = nowMs
                    prev.durationMs = 0L
                }
            }
        }
        animByVehId.keys.retainAll(activeIds)
        snapshot = newSnapshot
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val snap = snapshot ?: return
        val proj = mapView.projection
        val density = mapView.resources.displayMetrics.density
        val zoom = mapView.zoomLevelDouble
        val scale = snap.uiScale
        val darkLabels = snap.darkLabels
        val viewW = mapView.width
        val viewH = mapView.height
        val nowMs = SystemClock.uptimeMillis()

        // 1. Draw Zones (Server + Dynamic Auto-Zones + Custom Zones)
        for (rz in snap.zones) {
            val z = rz.zone
            fillPaint.color = rz.fillColor
            strokePaint.color = rz.strokeColor
            strokePaint.strokeWidth = 2.2f * density

            if (z.isCircle) {
                proj.toPixels(rz.centerGeo, screenPt)
                val pxRadius = proj.metersToPixels(z.radiusMeters.toFloat()).coerceAtLeast(6f * density)
                val cx = screenPt.x.toFloat()
                val cy = screenPt.y.toFloat()
                if (cx + pxRadius < -40 || cy + pxRadius < -40 || cx - pxRadius > viewW + 40 || cy - pxRadius > viewH + 40) continue

                canvas.drawCircle(cx, cy, pxRadius, fillPaint)
                if (z.kind == GeoZoneKind.EXCAVATOR) {
                    dashedStrokePaint.color = rz.strokeColor
                    dashedStrokePaint.strokeWidth = 2.2f * density
                    canvas.drawCircle(cx, cy, pxRadius, dashedStrokePaint)
                } else {
                    canvas.drawCircle(cx, cy, pxRadius, strokePaint)
                }

                if (zoom >= 13.0) {
                    drawZonePill(canvas, cx, cy - pxRadius - 8f * density, rz.caption, rz.baseColor, density, scale)
                }
            } else if (rz.polygonGeo.size >= 3) {
                reusablePath.reset()
                for (idx in rz.polygonGeo.indices) {
                    proj.toPixels(rz.polygonGeo[idx], screenPt)
                    if (idx == 0) reusablePath.moveTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                    else reusablePath.lineTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                }
                reusablePath.close()
                canvas.drawPath(reusablePath, fillPaint)
                canvas.drawPath(reusablePath, strokePaint)
                if (zoom >= 12.5) {
                    proj.toPixels(rz.centerGeo, screenPt)
                    drawZonePill(canvas, screenPt.x.toFloat(), screenPt.y.toFloat(), rz.caption, rz.baseColor, density, scale)
                }
            }
        }

        // 2. Draw Custom Zone Creation Preview
        if (snap.isDrawingZone) {
            val previewColor = 0xFF4F46E5.toInt()
            fillPaint.color = 0x334F46E5
            strokePaint.color = previewColor
            strokePaint.strokeWidth = 3f * density

            if (snap.drawZoneMode == DrawZoneMode.CIRCLE) {
                snap.drawCircleCenter?.let { c ->
                    proj.toPixels(c, screenPt)
                    val pxR = proj.metersToPixels(snap.drawCircleRadius.toFloat()).coerceAtLeast(10f * density)
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxR, fillPaint)
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxR, strokePaint)
                    fillPaint.color = previewColor
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
                }
            } else if (snap.drawPolygonGeo.isNotEmpty()) {
                reusablePath.reset()
                for (idx in snap.drawPolygonGeo.indices) {
                    proj.toPixels(snap.drawPolygonGeo[idx], screenPt)
                    if (idx == 0) reusablePath.moveTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                    else reusablePath.lineTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                }
                if (snap.drawPolygonGeo.size >= 3) {
                    reusablePath.close()
                    canvas.drawPath(reusablePath, fillPaint)
                }
                canvas.drawPath(reusablePath, strokePaint)
                for (pt in snap.drawPolygonGeo) {
                    proj.toPixels(pt, screenPt)
                    fillPaint.color = Color.WHITE
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
                    strokePaint.strokeWidth = 2.5f * density
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, strokePaint)
                }
            }
        }

        // 3. Draw Active Track (Red for Loaded Haul, Blue for Empty)
        val pts = snap.trackPoints
        if (pts.size >= 2) {
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeJoin = Paint.Join.ROUND
            proj.toPixels(pts[0].geo, screenPt)
            for (i in 1 until pts.size) {
                val p1 = pts[i]
                proj.toPixels(p1.geo, screenPt2)
                val loadedSeg = p1.isLoaded || pts[i - 1].isLoaded
                strokePaint.color = if (loadedSeg) 0xFFEF4444.toInt() else 0xFF2563EB.toInt()
                strokePaint.strokeWidth = (if (loadedSeg) 5f else 3.8f) * density
                canvas.drawLine(
                    screenPt.x.toFloat(), screenPt.y.toFloat(),
                    screenPt2.x.toFloat(), screenPt2.y.toFloat(),
                    strokePaint
                )
                screenPt.x = screenPt2.x
                screenPt.y = screenPt2.y
            }
            // Start & End dots
            proj.toPixels(pts.first().geo, screenPt)
            fillPaint.color = 0xFF10B981.toInt()
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 7f * density, fillPaint)

            proj.toPixels(pts.last().geo, screenPt)
            fillPaint.color = 0xFFEF4444.toInt()
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 7f * density, fillPaint)

            // Playback cursor
            snap.playbackGeo?.let { playGeo ->
                proj.toPixels(playGeo, screenPt)
                fillPaint.color = 0x442563EB
                canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 16f * density, fillPaint)
                fillPaint.color = Color.WHITE
                canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 9f * density, fillPaint)
                fillPaint.color = 0xFF2563EB.toInt()
                canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
            }
        }

        // 4. Draw User GPS Location
        snap.myLocationGeo?.let { locGeo ->
            proj.toPixels(locGeo, screenPt)
            fillPaint.color = 0x333B82F6
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 18f * density, fillPaint)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 8.5f * density, fillPaint)
            fillPaint.color = 0xFF2563EB.toInt()
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
        }

        // 5. Draw Vehicles with Smooth Interpolation (zero per-frame allocations)
        var anyAnimatingVisible = false
        for (rv in snap.vehicles) {
            val v = rv.vehicle
            val anim = animByVehId[v.id]
            val drawLat = anim?.currentLat(nowMs) ?: v.lat
            val drawLng = anim?.currentLng(nowMs) ?: v.lng

            scratchGeo.setCoords(drawLat, drawLng)
            proj.toPixels(scratchGeo, screenPt)
            val cx = screenPt.x.toFloat()
            val cy = screenPt.y.toFloat()
            if (cx < -90 || cy < -90 || cx > viewW + 90 || cy > viewH + 90) continue

            if (anim != null && anim.isActive(nowMs)) {
                anyAnimatingVisible = true
            }

            val isSelected = rv.isSelected
            val markerColor = rv.markerColor
            val baseRadius = (if (snap.simpleIcons) 9.5f else 13.5f) * density * (if (isSelected) 1.2f else 1f)

            // Halo for selected or loaded haul
            if (isSelected) {
                fillPaint.color = 0x442563EB
                canvas.drawCircle(cx, cy, baseRadius + 9f * density, fillPaint)
            } else if (v.isLoadedHaul) {
                fillPaint.color = 0x38EF4444
                canvas.drawCircle(cx, cy, baseRadius + 6f * density, fillPaint)
            }

            // Directional course pointer
            val course = v.course
            if (course != null && v.online && (v.speed > 1.0 || v.type == VehicleType.BELAZ)) {
                val rad = Math.toRadians(course - 90.0)
                val tipDist = baseRadius + 7.5f * density
                val sideDist = baseRadius * 0.72f
                val tipX = cx + (cos(rad) * tipDist).toFloat()
                val tipY = cy + (sin(rad) * tipDist).toFloat()
                val leftX = cx + (cos(rad + 2.35) * sideDist).toFloat()
                val leftY = cy + (sin(rad + 2.35) * sideDist).toFloat()
                val rightX = cx + (cos(rad - 2.35) * sideDist).toFloat()
                val rightY = cy + (sin(rad - 2.35) * sideDist).toFloat()
                reusablePath.reset()
                reusablePath.moveTo(tipX, tipY)
                reusablePath.lineTo(leftX, leftY)
                reusablePath.lineTo(rightX, rightY)
                reusablePath.close()
                fillPaint.color = markerColor
                canvas.drawPath(reusablePath, fillPaint)
            }

            // Main circular badge
            fillPaint.color = markerColor
            canvas.drawCircle(cx, cy, baseRadius, fillPaint)
            strokePaint.color = if (isSelected) 0xFF009BE0.toInt() else Color.WHITE
            strokePaint.strokeWidth = (if (isSelected) 3f else 2f) * density
            canvas.drawCircle(cx, cy, baseRadius, strokePaint)

            if (!snap.simpleIcons) {
                textPaint.color = Color.WHITE
                textPaint.textSize = 9.5f * density
                canvas.drawText(rv.glyph, cx, cy + 3.4f * density, textPaint)
            }

            if (rv.isFavorite) {
                fillPaint.color = 0xFFF59E0B.toInt()
                canvas.drawCircle(cx + baseRadius * 0.78f, cy - baseRadius * 0.78f, 4.2f * density, fillPaint)
            }

            // Vehicle pill label (shown when zoom >= 12.2 or priority selected/fav)
            val showLabel = snap.showLabels && (zoom >= 12.2 || isSelected || rv.isFavorite)
            if (showLabel) {
                drawVehiclePill(
                    canvas = canvas,
                    cx = cx,
                    topY = cy + baseRadius + 4f * density,
                    label = rv.labelText,
                    dark = darkLabels,
                    highlightRed = v.isLoadedHaul,
                    density = density,
                    scale = scale
                )
            }
        }

        // Only continue scheduling animation frames while visible vehicles are actively gliding
        if (anyAnimatingVisible && snap.isMapVisible) {
            mapView.postInvalidateDelayed(snap.frameDelayMs)
        }
    }

    private fun drawVehiclePill(
        canvas: Canvas,
        cx: Float,
        topY: Float,
        label: String,
        dark: Boolean,
        highlightRed: Boolean,
        density: Float,
        scale: Float
    ) {
        textPaint.textSize = 10f * density * scale
        val textWidth = textPaint.measureText(label)
        val padH = 7f * density
        val pillH = 17f * density * scale
        reusableRect.set(cx - textWidth / 2f - padH, topY, cx + textWidth / 2f + padH, topY + pillH)

        labelBgPaint.color = when {
            highlightRed -> 0xE6DC2626.toInt()
            dark -> 0xE00F172A.toInt()
            else -> 0xECFFFFFF.toInt()
        }
        labelBorderPaint.color = when {
            highlightRed -> 0x99FCA5A5.toInt()
            dark -> 0x38FFFFFF
            else -> 0x240F172A
        }
        labelBorderPaint.strokeWidth = 1f * density

        canvas.drawRoundRect(reusableRect, pillH / 2f, pillH / 2f, labelBgPaint)
        canvas.drawRoundRect(reusableRect, pillH / 2f, pillH / 2f, labelBorderPaint)

        textPaint.color = if (dark || highlightRed) Color.WHITE else 0xFF0F172A.toInt()
        val baseline = topY + pillH / 2f + (textPaint.textSize * 0.35f)
        canvas.drawText(label, cx, baseline, textPaint)
    }

    private fun drawZonePill(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        label: String,
        accentColor: Int,
        density: Float,
        scale: Float
    ) {
        textPaint.textSize = 9.5f * density * scale
        val tw = textPaint.measureText(label)
        val padH = 7f * density
        val h = 16f * density * scale
        reusableRect.set(cx - tw / 2f - padH, cy - h / 2f, cx + tw / 2f + padH, cy + h / 2f)
        labelBgPaint.color = 0xD90F172A.toInt()
        labelBorderPaint.color = (accentColor and 0x00FFFFFF) or 0xCC000000.toInt()
        labelBorderPaint.strokeWidth = 1.4f * density
        canvas.drawRoundRect(reusableRect, h / 2f, h / 2f, labelBgPaint)
        canvas.drawRoundRect(reusableRect, h / 2f, h / 2f, labelBorderPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.34f, textPaint)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val snap = snapshot ?: return false
        val proj = mapView.projection
        val density = mapView.resources.displayMetrics.density
        val nowMs = SystemClock.uptimeMillis()

        if (snap.isDrawingZone) {
            val geo = proj.fromPixels(e.x.roundToInt(), e.y.roundToInt())
            onDrawTap(geo.latitude, geo.longitude)
            return true
        }

        // 1. Hit-test vehicles first (generous 28dp touch radius using interpolated coords)
        val hitRadiusPx = 28f * density
        var nearestVeh: Vehicle? = null
        var bestDist = hitRadiusPx
        for (rv in snap.vehicles) {
            val v = rv.vehicle
            val anim = animByVehId[v.id]
            scratchGeo.setCoords(anim?.currentLat(nowMs) ?: v.lat, anim?.currentLng(nowMs) ?: v.lng)
            proj.toPixels(scratchGeo, screenPt)
            val d = hypot((screenPt.x - e.x).toDouble(), (screenPt.y - e.y).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                nearestVeh = v
            }
        }
        if (nearestVeh != null) {
            onVehicleTap(nearestVeh)
            return true
        }

        // 2. Hit-test zones
        val tapGeo = proj.fromPixels(e.x.roundToInt(), e.y.roundToInt())
        val dummyProbe = Vehicle(
            id = "", name = "", address = "", lat = tapGeo.latitude, lng = tapGeo.longitude,
            speed = 0.0, course = null, online = true, active = false, engineOff = null,
            lastTime = "", duration = "", fuel = "", weight = null
        )
        val tappedZone = snap.zones.firstOrNull { GeoAnalytics.isVehicleInZone(dummyProbe, it.zone) }?.zone
        if (tappedZone != null) {
            onZoneTap(tappedZone)
            return true
        }

        return false
    }
}
