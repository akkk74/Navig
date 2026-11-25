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

@Composable
fun QuarryMapCanvas(
    state: FleetUiState,
    onVehicleTap: (Vehicle) -> Unit,
    onZoneTap: (GeoZone) -> Unit,
    onDrawTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { configureOsmdroid(context) }

    val overlay = remember {
        FleetMapOverlay(
            onVehicleTap = onVehicleTap,
            onZoneTap = onZoneTap,
            onDrawTap = onDrawTap
        )
    }

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
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Update tile source & dark tile filter
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

    // Execute camera commands
    LaunchedEffect(state.cameraCommand?.nonce) {
        when (val cmd = state.cameraCommand) {
            is MapCameraCommand.FocusPoint -> {
                mapView.controller.animateTo(GeoPoint(cmd.lat, cmd.lng), cmd.zoom, 420L)
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
                    mapView.post {
                        runCatching { mapView.zoomToBoundingBox(box, true, 96) }
                    }
                }
            }
            null -> Unit
        }
    }

    AndroidView(
        factory = { mapView },
        update = { mv ->
            overlay.updateState(state)
            mv.invalidate()
        },
        modifier = modifier
    )
}

private class FleetMapOverlay(
    private val onVehicleTap: (Vehicle) -> Unit,
    private val onZoneTap: (GeoZone) -> Unit,
    private val onDrawTap: (Double, Double) -> Unit
) : Overlay() {

    private var state: FleetUiState = FleetUiState()
    private val screenPt = Point()
    private val screenPt2 = Point()

    // Cached Paints (zero allocations in draw)
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
    private val reusablePath = Path()
    private val reusableRect = RectF()

    fun updateState(newState: FleetUiState) {
        state = newState
    }

    private fun allDrawableZones(): List<GeoZone> {
        val list = mutableListOf<GeoZone>()
        if (state.settings.showZones) list.addAll(state.serverZones)
        if (state.settings.showDynamicZones) list.addAll(state.dynamicZones)
        list.addAll(state.settings.customZones.map { cz ->
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
        return list
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val density = mapView.resources.displayMetrics.density
        val zoom = mapView.zoomLevelDouble
        val scale = state.settings.uiScale.coerceIn(1f, 1.5f)
        val darkLabels = state.settings.darkMode || state.isSatellite

        // 1. Draw Zones (Server + Dynamic Auto-Zones + Custom Zones)
        val zones = allDrawableZones()
        val queuesByZone = state.excavatorQueues.associateBy { it.zone.key }
        for (z in zones) {
            val baseColor = runCatching { Color.parseColor(z.colorHex) }.getOrDefault(0xFF2563EB.toInt())
            fillPaint.color = (baseColor and 0x00FFFFFF) or 0x26000000
            strokePaint.color = (baseColor and 0x00FFFFFF) or 0xCC000000.toInt()
            strokePaint.strokeWidth = 2.2f * density

            if (z.isCircle) {
                proj.toPixels(GeoPoint(z.centerLat, z.centerLng), screenPt)
                val pxRadius = proj.metersToPixels(z.radiusMeters.toFloat()).coerceAtLeast(6f * density)
                canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxRadius, fillPaint)
                if (z.kind == GeoZoneKind.EXCAVATOR) {
                    dashedStrokePaint.color = strokePaint.color
                    dashedStrokePaint.strokeWidth = 2.2f * density
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxRadius, dashedStrokePaint)
                } else {
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxRadius, strokePaint)
                }

                if (zoom >= 13.0) {
                    val qCount = queuesByZone[z.key]?.belazList?.size
                    val caption = if (z.kind == GeoZoneKind.EXCAVATOR && qCount != null && qCount > 0) {
                        "${z.shortName} · $qCount БелАЗ"
                    } else z.shortName
                    drawZonePill(canvas, screenPt.x.toFloat(), screenPt.y.toFloat() - pxRadius - 8f * density, caption, baseColor, density, scale)
                }
            } else if (z.polygon.size >= 3) {
                reusablePath.reset()
                z.polygon.forEachIndexed { idx, pt ->
                    proj.toPixels(GeoPoint(pt.lat, pt.lng), screenPt)
                    if (idx == 0) reusablePath.moveTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                    else reusablePath.lineTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                }
                reusablePath.close()
                canvas.drawPath(reusablePath, fillPaint)
                canvas.drawPath(reusablePath, strokePaint)
                if (zoom >= 12.5) {
                    proj.toPixels(GeoPoint(z.centerLat, z.centerLng), screenPt)
                    drawZonePill(canvas, screenPt.x.toFloat(), screenPt.y.toFloat(), z.shortName, baseColor, density, scale)
                }
            }
        }

        // 2. Draw Custom Zone Creation Preview
        if (state.isDrawingZone) {
            val previewColor = 0xFF4F46E5.toInt()
            fillPaint.color = 0x334F46E5
            strokePaint.color = previewColor
            strokePaint.strokeWidth = 3f * density

            if (state.drawZoneMode == DrawZoneMode.CIRCLE) {
                state.drawCircleCenter?.let { c ->
                    proj.toPixels(GeoPoint(c.lat, c.lng), screenPt)
                    val pxR = proj.metersToPixels(state.drawCircleRadius.toFloat()).coerceAtLeast(10f * density)
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxR, fillPaint)
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), pxR, strokePaint)
                    fillPaint.color = previewColor
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
                }
            } else if (state.drawPolygonPoints.isNotEmpty()) {
                reusablePath.reset()
                state.drawPolygonPoints.forEachIndexed { idx, pt ->
                    proj.toPixels(GeoPoint(pt.lat, pt.lng), screenPt)
                    if (idx == 0) reusablePath.moveTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                    else reusablePath.lineTo(screenPt.x.toFloat(), screenPt.y.toFloat())
                }
                if (state.drawPolygonPoints.size >= 3) {
                    reusablePath.close()
                    canvas.drawPath(reusablePath, fillPaint)
                }
                canvas.drawPath(reusablePath, strokePaint)
                state.drawPolygonPoints.forEach { pt ->
                    proj.toPixels(GeoPoint(pt.lat, pt.lng), screenPt)
                    fillPaint.color = Color.WHITE
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
                    strokePaint.strokeWidth = 2.5f * density
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, strokePaint)
                }
            }
        }

        // 3. Draw Active Track (Red for Loaded Haul, Blue/Emerald for Empty)
        state.activeTrack?.let { track ->
            val pts = track.points
            if (pts.size >= 2) {
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeJoin = Paint.Join.ROUND
                for (i in 1 until pts.size) {
                    val p0 = pts[i - 1]
                    val p1 = pts[i]
                    proj.toPixels(GeoPoint(p0.lat, p0.lng), screenPt)
                    proj.toPixels(GeoPoint(p1.lat, p1.lng), screenPt2)
                    val loadedSeg = (p1.weight ?: p0.weight ?: 0.0) >= GeoAnalytics.LOADED_WEIGHT_THRESHOLD_TONS
                    strokePaint.color = if (loadedSeg) 0xFFEF4444.toInt() else 0xFF2563EB.toInt()
                    strokePaint.strokeWidth = (if (loadedSeg) 5f else 3.8f) * density
                    canvas.drawLine(
                        screenPt.x.toFloat(), screenPt.y.toFloat(),
                        screenPt2.x.toFloat(), screenPt2.y.toFloat(),
                        strokePaint
                    )
                }
                // Start & End dots
                proj.toPixels(GeoPoint(pts.first().lat, pts.first().lng), screenPt)
                fillPaint.color = 0xFF10B981.toInt()
                canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 7f * density, fillPaint)

                proj.toPixels(GeoPoint(pts.last().lat, pts.last().lng), screenPt)
                fillPaint.color = 0xFFEF4444.toInt()
                canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 7f * density, fillPaint)

                // Playback cursor
                val playPt = pts.getOrNull(state.playbackIndex.coerceIn(0, pts.lastIndex))
                if (playPt != null) {
                    proj.toPixels(GeoPoint(playPt.lat, playPt.lng), screenPt)
                    fillPaint.color = 0x442563EB
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 16f * density, fillPaint)
                    fillPaint.color = Color.WHITE
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 9f * density, fillPaint)
                    fillPaint.color = 0xFF2563EB.toInt()
                    canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
                }
            }
        }

        // 4. Draw User GPS Location
        state.myLocation?.let { loc ->
            proj.toPixels(GeoPoint(loc.lat, loc.lng), screenPt)
            fillPaint.color = 0x333B82F6
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 18f * density, fillPaint)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 8.5f * density, fillPaint)
            fillPaint.color = 0xFF2563EB.toInt()
            canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), 6f * density, fillPaint)
        }

        // 5. Draw Vehicles (selected and favorites drawn on top)
        val sortedVehicles = state.visibleMapVehicles.sortedWith(
            compareBy<Vehicle> { it.id == state.selectedVehicleId }
                .thenBy { state.isFavorite(it.id) }
                .thenBy { it.isLoadedHaul }
        )

        for (v in sortedVehicles) {
            proj.toPixels(GeoPoint(v.lat, v.lng), screenPt)
            val cx = screenPt.x.toFloat()
            val cy = screenPt.y.toFloat()
            if (cx < -80 || cy < -80 || cx > mapView.width + 80 || cy > mapView.height + 80) continue

            val isSelected = v.id == state.selectedVehicleId
            val isFav = state.isFavorite(v.id)
            val markerColor = resolveVehicleColor(v)
            val baseRadius = (if (state.settings.simpleIcons) 9.5f else 13.5f) * density * (if (isSelected) 1.2f else 1f)

            // Halo for selected or loaded haul
            if (isSelected) {
                fillPaint.color = 0x442563EB
                canvas.drawCircle(cx, cy, baseRadius + 9f * density, fillPaint)
            } else if (v.isLoadedHaul) {
                fillPaint.color = 0x38EF4444
                canvas.drawCircle(cx, cy, baseRadius + 6f * density, fillPaint)
            }

            // Directional course pointer if moving or has course
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

            // Main circular badge with crisp white border
            fillPaint.color = markerColor
            canvas.drawCircle(cx, cy, baseRadius, fillPaint)
            strokePaint.color = if (isSelected) 0xFF009BE0.toInt() else Color.WHITE
            strokePaint.strokeWidth = (if (isSelected) 3f else 2f) * density
            canvas.drawCircle(cx, cy, baseRadius, strokePaint)

            // Inner symbol glyph (compact high-contrast letter/badge)
            if (!state.settings.simpleIcons) {
                textPaint.color = Color.WHITE
                textPaint.textSize = 9.5f * density
                val glyph = when (v.type) {
                    VehicleType.BELAZ -> if (v.isLoaded) "Г" else "Б"
                    VehicleType.EXCAVATOR -> "Э"
                    VehicleType.BULLDOZER -> "Д"
                    VehicleType.GRADER -> "Р"
                    VehicleType.FUEL -> "Т"
                    VehicleType.OTHER -> "•"
                }
                canvas.drawText(glyph, cx, cy + 3.4f * density, textPaint)
            }

            // Favorite gold dot
            if (isFav) {
                fillPaint.color = 0xFFF59E0B.toInt()
                canvas.drawCircle(cx + baseRadius * 0.78f, cy - baseRadius * 0.78f, 4.2f * density, fillPaint)
            }

            // Vehicle pill label (shown when zoom >= 12.2 or priority selected/fav)
            val showLabel = state.settings.showLabels && (zoom >= 12.2 || isSelected || isFav)
            if (showLabel) {
                val dName = state.displayName(v)
                val suffix = when {
                    v.isLoadedHaul && v.speed > 2.0 -> " · ${v.speed.roundToInt()} км/ч · ГРУЖ"
                    v.speed > 2.0 -> " · ${v.speed.roundToInt()} км/ч"
                    v.isLoadedHaul -> " · ГРУЖ"
                    else -> ""
                }
                drawVehiclePill(
                    canvas = canvas,
                    cx = cx,
                    topY = cy + baseRadius + 4f * density,
                    label = dName + suffix,
                    dark = darkLabels,
                    highlightRed = v.isLoadedHaul,
                    density = density,
                    scale = scale
                )
            }
        }
    }

    private fun resolveVehicleColor(v: Vehicle): Int {
        if (!v.online) return 0xFF64748B.toInt()
        if (v.engineOff == true) return 0xFF475569.toInt()
        if (v.isLoadedHaul) return 0xFFEF4444.toInt() // Красный гружёный ход!
        return when (v.type) {
            VehicleType.BELAZ -> if (v.isMoving) 0xFF10B981.toInt() else 0xFF3B82F6.toInt()
            VehicleType.EXCAVATOR -> 0xFFF97316.toInt()
            VehicleType.BULLDOZER -> 0xFFEAB308.toInt()
            VehicleType.GRADER -> 0xFF14B8A6.toInt()
            VehicleType.FUEL -> 0xFF06B6D4.toInt()
            VehicleType.OTHER -> if (v.isMoving) 0xFF10B981.toInt() else 0xFF6366F1.toInt()
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
        val proj = mapView.projection
        val density = mapView.resources.displayMetrics.density

        if (state.isDrawingZone) {
            val geo = proj.fromPixels(e.x.roundToInt(), e.y.roundToInt())
            onDrawTap(geo.latitude, geo.longitude)
            return true
        }

        // 1. Hit-test vehicles first (generous 28dp touch radius)
        val hitRadiusPx = 28f * density
        var nearestVeh: Vehicle? = null
        var bestDist = hitRadiusPx
        for (v in state.visibleMapVehicles) {
            proj.toPixels(GeoPoint(v.lat, v.lng), screenPt)
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
        val tappedZone = allDrawableZones().firstOrNull { GeoAnalytics.isVehicleInZone(dummyProbe, it) }
        if (tappedZone != null) {
            onZoneTap(tappedZone)
            return true
        }

        return false
    }
}
