package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Vehicle
import com.example.data.model.VehicleType
import com.example.ui.theme.ExcavatorAmber
import com.example.ui.theme.ExcavatorOrange
import com.example.ui.theme.FuelCyan
import com.example.ui.theme.GraderTeal
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KruCyan
import com.example.ui.theme.KruIndigo
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OfflineSlate
import com.example.ui.theme.OnlineEmerald

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    shadowElevation: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    val ext = LocalFleetColors.current
    Surface(
        modifier = modifier,
        shape = shape,
        color = ext.glassSurface,
        border = BorderStroke(1.dp, ext.glassBorder),
        shadowElevation = shadowElevation,
        content = content
    )
}

@Composable
fun TelemetryPill(
    label: String,
    value: String,
    accent: Color,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val ext = LocalFleetColors.current
    val bg = accent.copy(alpha = if (ext.isDark) 0.18f else 0.12f)
    val border = accent.copy(alpha = if (ext.isDark) 0.38f else 0.30f)
    val valueColor = if (ext.isDark) accent.copy(alpha = 0.95f) else accent

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = valueColor,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = valueColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PulsingStatusDot(
    color: Color,
    pulse: Boolean,
    size: Dp = 10.dp
) {
    if (!pulse) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
        return
    }
    val infinite = rememberInfiniteTransition(label = "pulse")
    val animAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val animScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulseScale"
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(animScale)
            .alpha(animAlpha)
            .clip(CircleShape)
            .background(color)
    )
}

fun vehicleStatusColor(v: Vehicle): Color {
    if (!v.online) return OfflineSlate
    if (v.engineOff == true) return Color(0xFF475569)
    if (v.isLoadedHaul) return LoadedHaulRed
    return when (v.type) {
        VehicleType.BELAZ -> if (v.isMoving) OnlineEmerald else KruBlue
        VehicleType.EXCAVATOR -> ExcavatorOrange
        VehicleType.BULLDOZER -> ExcavatorAmber
        VehicleType.GRADER -> GraderTeal
        VehicleType.FUEL -> FuelCyan
        VehicleType.OTHER -> if (v.isMoving) OnlineEmerald else KruIndigo
    }
}

fun vehicleTypeIcon(type: VehicleType): ImageVector = when (type) {
    VehicleType.BELAZ -> Icons.Default.LocalShipping
    VehicleType.EXCAVATOR -> Icons.Default.PrecisionManufacturing
    VehicleType.BULLDOZER -> Icons.Default.Construction
    VehicleType.GRADER -> Icons.Default.Build
    VehicleType.FUEL -> Icons.Default.LocalGasStation
    VehicleType.OTHER -> Icons.Default.DirectionsCar
}

@Composable
fun KruEmblemCanvas(modifier: Modifier = Modifier) {
    val isDark = LocalFleetColors.current.isDark
    val peakColor = if (isDark) Color.White else Color(0xFF0F172A)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val mainPeak = Path().apply {
            moveTo(w * 0.10f, h * 0.91f)
            lineTo(w * 0.46f, h * 0.13f)
            lineTo(w * 0.58f, h * 0.39f)
            lineTo(w * 0.44f, h * 0.70f)
            lineTo(w * 0.72f, h * 0.70f)
            lineTo(w * 0.82f, h * 0.91f)
            close()
        }
        val cyanPeak = Path().apply {
            moveTo(w * 0.52f, h * 0.63f)
            lineTo(w * 0.69f, h * 0.26f)
            lineTo(w * 0.86f, h * 0.63f)
            close()
        }
        drawPath(mainPeak, color = peakColor)
        drawPath(cyanPeak, color = KruCyan)
    }
}

@Composable
fun <T> SegmentedPillRow(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalFleetColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ext.subtleSurface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) Color.White else ext.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
