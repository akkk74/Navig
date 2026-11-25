package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.FleetTab
import com.example.ui.ActiveModal
import com.example.ui.FleetUiState
import com.example.ui.FleetViewModel
import com.example.ui.ToastTone
import com.example.ui.components.FleetModalHost
import com.example.ui.map.MapScreen
import com.example.ui.objects.ObjectsScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.DarkBgBottom
import com.example.ui.theme.DarkBgMid
import com.example.ui.theme.DarkBgTop
import com.example.ui.theme.ExcavatorAmber
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KuzbassFleetTheme
import com.example.ui.theme.LightBgBottom
import com.example.ui.theme.LightBgMid
import com.example.ui.theme.LightBgTop
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OnlineEmerald
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val fleetViewModel: FleetViewModel = viewModel()
            val state by fleetViewModel.uiState.collectAsStateWithLifecycle()

            KuzbassFleetTheme(
                darkTheme = state.settings.darkMode,
                uiScale = state.settings.uiScale
            ) {
                KuzbassFleetRoot(state = state, viewModel = fleetViewModel)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun KuzbassFleetRoot(
    state: FleetUiState,
    viewModel: FleetViewModel
) {
    val context = LocalContext.current

    fun fetchCurrentGps() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    viewModel.updateMyLocation(loc.latitude, loc.longitude)
                } else {
                    client.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            viewModel.updateMyLocation(last.latitude, last.longitude)
                        } else {
                            viewModel.showToast("Не удалось получить координаты GPS", ToastTone.WARNING)
                        }
                    }
                }
            }
            .addOnFailureListener {
                viewModel.showToast("Ошибка определения геолокации", ToastTone.ERROR)
            }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchCurrentGps()
        } else {
            viewModel.showToast("Нет доступа к геолокации. Разрешите в настройках Android.", ToastTone.WARNING)
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* handled gracefully; in-app toasts & history always work */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun requestMyLocation() {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            fetchCurrentGps()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // Predictive & Clean Back Handling for secondary tabs, modals, and draw mode
    BackHandler(enabled = state.activeModal != ActiveModal.None || state.isDrawingZone || state.activeTab != FleetTab.MAP) {
        when {
            state.activeModal != ActiveModal.None -> viewModel.closeModal()
            state.isDrawingZone -> viewModel.cancelDrawZoneMode()
            state.activeTab != FleetTab.MAP -> viewModel.switchTab(FleetTab.MAP)
        }
    }

    val ambientBrush = if (state.settings.darkMode) {
        Brush.linearGradient(listOf(DarkBgTop, DarkBgMid, DarkBgBottom))
    } else {
        Brush.linearGradient(listOf(LightBgTop, LightBgMid, LightBgBottom))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ambientBrush)
    ) {
        val useNavigationRail = maxWidth >= 720.dp
        val dockClearance = if (useNavigationRail) 16.dp else 94.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                NavigationRail(
                    containerColor = LocalFleetColors.current.glassDock,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    val items = listOf(
                        Triple(FleetTab.MAP, "Карта", Icons.Filled.Map to Icons.Outlined.Map),
                        Triple(FleetTab.OBJECTS, "Объекты", Icons.Filled.LocalShipping to Icons.Outlined.LocalShipping),
                        Triple(FleetTab.SETTINGS, "Настройки", Icons.Filled.Settings to Icons.Outlined.Settings)
                    )
                    items.forEach { (tab, label, icons) ->
                        val selected = state.activeTab == tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.switchTab(tab) },
                            icon = { Icon(if (selected) icons.first else icons.second, contentDescription = label) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Keep MapScreen alive so OSMDroid tiles, camera center & zoom never reset on tab switches
                MapScreen(
                    state = state,
                    viewModel = viewModel,
                    isMapVisible = state.activeTab == FleetTab.MAP,
                    onRequestMyLocation = ::requestMyLocation,
                    bottomDockClearance = dockClearance
                )

                when (state.activeTab) {
                    FleetTab.MAP -> Unit
                    FleetTab.OBJECTS -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ambientBrush)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                    ) {
                        ObjectsScreen(
                            state = state,
                            viewModel = viewModel,
                            bottomDockClearance = dockClearance
                        )
                    }
                    FleetTab.SETTINGS -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ambientBrush)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                    ) {
                        SettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            bottomDockClearance = dockClearance
                        )
                    }
                }

                // Floating Liquid Glass Bottom Dock for Handheld / Compact Screens
                if (!useNavigationRail) {
                    FloatingLiquidGlassDock(
                        activeTab = state.activeTab,
                        onSelectTab = viewModel::switchTab,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }

                // Top Animated Toast Banner
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.toast != null,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 20.dp, end = 20.dp)
                ) {
                    state.toast?.let { toast ->
                        val bg = when (toast.tone) {
                            ToastTone.SUCCESS -> OnlineEmerald
                            ToastTone.WARNING -> ExcavatorAmber
                            ToastTone.ERROR -> LoadedHaulRed
                            ToastTone.INFO -> KruBlue
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = bg,
                            shadowElevation = 10.dp,
                            modifier = Modifier.widthIn(max = 420.dp)
                        ) {
                            Text(
                                text = toast.text,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Modal Sheets Host
        FleetModalHost(state = state, viewModel = viewModel)
    }
}

@Composable
private fun FloatingLiquidGlassDock(
    activeTab: FleetTab,
    onSelectTab: (FleetTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalFleetColors.current
    val items = listOf(
        Triple(FleetTab.MAP, "Карта", Icons.Filled.Map to Icons.Outlined.Map),
        Triple(FleetTab.OBJECTS, "Объекты", Icons.Filled.LocalShipping to Icons.Outlined.LocalShipping),
        Triple(FleetTab.SETTINGS, "Настройки", Icons.Filled.Settings to Icons.Outlined.Settings)
    )

    Surface(
        shape = CircleShape,
        color = ext.glassDock,
        border = BorderStroke(1.dp, ext.glassBorder),
        shadowElevation = 14.dp,
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .height(64.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, label, icons) ->
                val selected = activeTab == tab
                val pillBg = if (selected) {
                    if (ext.isDark) KruBlue.copy(alpha = 0.28f) else Color.White
                } else {
                    Color.Transparent
                }
                val contentColor = if (selected) MaterialTheme.colorScheme.primary else ext.mutedText

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(pillBg)
                        .testTag("nav_tab_${tab.name.lowercase()}")
                        .clickable { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (selected) icons.first else icons.second,
                        contentDescription = label,
                        tint = contentColor,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
