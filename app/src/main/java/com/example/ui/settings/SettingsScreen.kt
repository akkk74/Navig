package com.example.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.BuildConfig
import com.example.data.model.ConnectionState
import com.example.ui.FleetUiState
import com.example.ui.FleetViewModel
import com.example.ui.ToastTone
import com.example.ui.components.GlassSurface
import com.example.ui.components.KruEmblemCanvas
import com.example.ui.components.SegmentedPillRow
import com.example.ui.theme.ExcavatorOrange
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KruBlue
import com.example.ui.theme.KruIndigo
import com.example.ui.theme.LoadedHaulRed
import com.example.ui.theme.LocalFleetColors
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.OnlineEmeraldDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: FleetUiState,
    viewModel: FleetViewModel,
    bottomDockClearance: Dp,
    modifier: Modifier = Modifier
) {
    val ext = LocalFleetColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings = state.settings

    var serverInput by remember { mutableStateOf(settings.server) }
    var loginInput by remember { mutableStateOf(settings.login) }
    var passwordInput by remember { mutableStateOf(settings.password) }
    var showPassword by remember { mutableStateOf(false) }
    var isExportingApk by remember { mutableStateOf(false) }

    val apkSizeMb = remember {
        val f = File(context.applicationInfo.sourceDir)
        if (f.exists()) String.format(Locale.US, "%.1f МБ", f.length() / (1024.0 * 1024.0)) else "APK"
    }

    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        isExportingApk = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sourceApk = File(context.applicationInfo.sourceDir)
                    require(sourceApk.exists() && sourceApk.canRead()) { "Файл сборки недоступен" }
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        sourceApk.inputStream().use { input -> input.copyTo(out, bufferSize = 64 * 1024) }
                    } ?: error("Не удалось открыть файл для записи")
                }
            }
            isExportingApk = false
            result.onSuccess {
                viewModel.showToast("APK текущей сборки сохранён", ToastTone.SUCCESS)
            }.onFailure { err ->
                viewModel.showToast("Ошибка сохранения APK: ${err.message}", ToastTone.ERROR)
            }
        }
    }

    LaunchedEffect(settings.server, settings.login, settings.password) {
        if (serverInput != settings.server) serverInput = settings.server
        if (loginInput != settings.login) loginInput = settings.login
        if (passwordInput != settings.password) passwordInput = settings.password
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = bottomDockClearance + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand Header with KRU Mountain-Chevron Emblem
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (ext.isDark) Color(0xFF141F36) else Color.White,
                    shadowElevation = 10.dp,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                        KruEmblemCanvas(modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "КУЗБАССРАЗРЕЗУГОЛЬ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Прямое подключение SOAP / WSDL · Красный гружёный ход",
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.mutedText
                )
            }

            // Connection Card
            GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        label = { Text("Сервер (.asmx)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_server_input")
                    )
                    OutlinedTextField(
                        value = loginInput,
                        onValueChange = { loginInput = it },
                        label = { Text("Логин") },
                        placeholder = { Text("Введите логин") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_login_input")
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Пароль") },
                        placeholder = { Text("Введите пароль") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Показать пароль"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_password_input")
                    )

                    val isConnecting = state.connectionState == ConnectionState.CONNECTING
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(listOf(KruBlue, KruIndigo)))
                            .testTag("settings_connect_button")
                            .clickable(enabled = !isConnecting) {
                                viewModel.connect(serverInput, loginInput, passwordInput)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isConnecting) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Text("Подключение…", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                        } else {
                            Text("Подключить", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }
            }

            // Export Current Build APK Card (Zero-Permission Native System Save Dialog)
            GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Установщик текущей сборки", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Сохранить APK v${BuildConfig.VERSION_NAME} ($apkSizeMb) через системное окно",
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.mutedText
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(listOf(OnlineEmerald, OnlineEmeraldDark)))
                            .testTag("settings_download_apk_button")
                            .clickable(enabled = !isExportingApk) {
                                saveApkLauncher.launch("Kuzbassrazrezugol_v${BuildConfig.VERSION_NAME}.apk")
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isExportingApk) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Скачать APK",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Скачать APK",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Monitoring & Map Preferences Card
            GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "НАСТРОЙКИ КАРТЫ И ОБНОВЛЕНИЯ",
                        style = MaterialTheme.typography.labelMedium,
                        color = ext.mutedText
                    )

                    SettingToggleRow(
                        title = "Режим без нагрева (Эко)",
                        subtitle = "Щадящий опрос сети и экономия батареи",
                        checked = settings.ecoMode,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(ecoMode = v) } }
                    )
                    SettingToggleRow(
                        title = "Простые — лёгкие значки техники",
                        subtitle = "Компактные точки без текстовых букв внутри",
                        checked = settings.simpleIcons,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(simpleIcons = v) } }
                    )
                    SettingToggleRow(
                        title = "Автообновление",
                        subtitle = "Обновлять позиции техники в фоне",
                        checked = settings.autoRefresh,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoRefresh = v) } }
                    )

                    Text("Выбранный объект — обновлять каждые", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
                    SegmentedPillRow(
                        items = listOf(0.5 to "0.5с", 1.0 to "1с", 2.0 to "2с", 3.0 to "3с", 5.0 to "5с"),
                        selected = settings.selectedIntervalSec,
                        onSelect = { v -> viewModel.updateSettings { it.copy(selectedIntervalSec = v) } }
                    )

                    Text("Остальные объекты — обновлять каждые", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
                    SegmentedPillRow(
                        items = listOf(1 to "1с", 3 to "3с", 5 to "5с", 10 to "10с", 30 to "30с"),
                        selected = settings.refreshIntervalSec,
                        onSelect = { v -> viewModel.updateSettings { it.copy(refreshIntervalSec = v) } }
                    )

                    HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

                    SettingToggleRow(
                        title = "Плавное движение маркеров",
                        subtitle = "Сглаживание переходов между точками GPS",
                        checked = settings.smoothMotion,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(smoothMotion = v) } }
                    )
                    SettingToggleRow(
                        title = "Прогноз движения",
                        subtitle = "Упреждение по скорости и курсу между пакетами",
                        checked = settings.predictMotion,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(predictMotion = v) } }
                    )

                    Text("Частота кадров анимации", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
                    SegmentedPillRow(
                        items = listOf(30 to "30 к/с", 60 to "60 к/с", 120 to "120 к/с"),
                        selected = settings.fps,
                        onSelect = { v -> viewModel.updateSettings { it.copy(fps = v) } }
                    )

                    HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

                    SettingToggleRow(
                        title = "Вести выбранный объект",
                        subtitle = "Удерживать выбранную машину в центре карты",
                        checked = settings.followSelected,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(followSelected = v) } }
                    )
                    SettingToggleRow(
                        title = "Подписи и скорость на карте",
                        subtitle = "Скрываются при отдалении (кроме избранных)",
                        checked = settings.showLabels,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(showLabels = v) } }
                    )
                    SettingToggleRow(
                        title = "Геозоны (GetZonesMobile)",
                        subtitle = "Отображать участки и базы с сервера",
                        checked = settings.showZones,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(showZones = v) } }
                    )
                    SettingToggleRow(
                        title = "Автозоны (погрузка, заправка, пересменка)",
                        subtitle = "Радиусы экскаваторов, отвалы, АЗС и пересменки",
                        checked = settings.showDynamicZones,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(showDynamicZones = v) } }
                    )

                    Text("Базовый радиус зон экскаваторов", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
                    SegmentedPillRow(
                        items = listOf(40 to "40 м", 60 to "60 м", 100 to "100 м", 150 to "150 м"),
                        selected = settings.dynZoneRadiusMeters,
                        onSelect = { v -> viewModel.updateSettings { it.copy(dynZoneRadiusMeters = v) } }
                    )

                    // 7-Day AutoZones Database Panel
                    val totalObs = state.autoZonesDb.dumpObs.size + state.autoZonesDb.fuelObs.size + state.autoZonesDb.shiftObs.size
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ext.subtleSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("База автозон (анализ 7 дней)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "$totalObs точек",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Отвалы: ${state.autoZonesDb.dumpObs.size} · АЗС (ДУТ): ${state.autoZonesDb.fuelObs.size} · Пересменки: ${state.autoZonesDb.shiftObs.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.mutedText
                            )
                            Button(
                                onClick = viewModel::scanAutoZonesFromHistory,
                                enabled = !state.isScanningAutoZones,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ExcavatorOrange),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_scan_autozones_button")
                            ) {
                                if (state.isScanningAutoZones) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Сканирование истории…")
                                } else {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Собрать зоны из истории (24ч)", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = viewModel::forceRecalculateAutoZones,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KruBlue),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Пересчитать", style = MaterialTheme.typography.labelSmall)
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = LoadedHaulRed.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clickable { viewModel.resetAutoZonesDb() }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = LoadedHaulRed, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Сбросить базу", style = MaterialTheme.typography.labelSmall, color = LoadedHaulRed)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

                    SettingToggleRow(
                        title = "Показывать «Нет связи»",
                        subtitle = "Отображать неактивные маркеры на карте",
                        checked = settings.showOffline,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(showOffline = v) } }
                    )
                    SettingToggleRow(
                        title = "Тёмная тема (ночной режим)",
                        subtitle = "Удобно для работы в кабине ночью",
                        checked = settings.darkMode,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(darkMode = v) } }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Размер интерфейса", style = MaterialTheme.typography.titleSmall)
                            Text("Масштаб текста и элементов", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                        }
                        Text(
                            text = "${(settings.uiScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    SegmentedPillRow(
                        items = listOf(1.0f to "1", 1.12f to "2", 1.25f to "3", 1.38f to "4", 1.5f to "5"),
                        selected = settings.uiScale,
                        onSelect = { v -> viewModel.updateSettings { it.copy(uiScale = v) } }
                    )

                    HorizontalDivider(color = ext.glassBorder.copy(alpha = 0.4f))

                    SettingToggleRow(
                        title = "Автовход при запуске",
                        subtitle = "Сразу подключаться и открывать карту",
                        checked = settings.autoConnect,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoConnect = v) } }
                    )
                }
            }

            // Connection Log Terminal Card
            GlassSurface(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Журнал подключения", style = MaterialTheme.typography.titleSmall)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    clipboard.setText(AnnotatedString(state.connectionLogs.joinToString("\n")))
                                    viewModel.showToast("Журнал скопирован", ToastTone.SUCCESS)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Копировать журнал", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text("Копировать", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp, max = 200.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F172A))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = state.connectionLogs.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            ),
                            color = OnlineEmerald
                        )
                    }

                    if (state.perfMetrics.bytes > 0) {
                        Text(
                            text = "Сеть: ${state.perfMetrics.netMs} мс · Разбор: ${state.perfMetrics.parseMs} мс · ${(state.perfMetrics.bytes / 1024)} КБ",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Normal),
                            color = ext.mutedText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val ext = LocalFleetColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = OnlineEmerald
            )
        )
    }
}
