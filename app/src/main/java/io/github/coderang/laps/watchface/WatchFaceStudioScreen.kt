package io.github.coderang.laps.watchface

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.coderang.laps.coros.CorosMobileAuth
import io.github.coderang.laps.data.LapsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watch Face Studio Screen: Interactive live render preview, Material 3 theming,
 * COROS compilation engine, and direct BLE watch discovery.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatchFaceStudioScreen(
    modifier: Modifier = Modifier,
    bleManager: CorosBleManager = rememberCorosBleManager()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(WatchFaceConfig()) }
    var cycleStep by remember { mutableStateOf(0) }
    var activeStudioTab by remember { mutableStateOf(0) }
    var compilationResult by remember { mutableStateOf<CorosWatchFaceCompiler.CompilationResult?>(null) }
    var showCompilationDialog by remember { mutableStateOf(false) }
    var showBleSheet by remember { mutableStateOf(false) }
    var showDirectSyncDialog by remember { mutableStateOf(false) }
    var selectedSlot by remember { mutableStateOf(10) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var authEmail by remember { mutableStateOf(LapsRepository.getCorosCredentials(context).first) }
    var authPassword by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }

    val bleState by bleManager.connectionState.collectAsState()
    val isWatchAuthenticated by bleManager.isAuthenticated.collectAsState()
    val discoveredWatches by bleManager.discoveredWatches.collectAsState()
    val watchInfo by bleManager.watchInfo.collectAsState()
    val watchSlots by bleManager.watchSlots.collectAsState()
    val syncProg by bleManager.syncProgress.collectAsState()

    // Automatically align target model when a COROS watch connects over BLE
    LaunchedEffect(watchInfo.modelName) {
        val m = watchInfo.modelName.uppercase()
        if (m.isNotBlank()) {
            val detected = when {
                m.contains("PACE PRO") -> WatchModelPreset.PACE_PRO
                m.contains("PACE 3") -> WatchModelPreset.PACE_3
                m.contains("APEX 2") || m.contains("VERTIX") -> WatchModelPreset.APEX_2_PRO
                m.contains("PACE 2") || m.contains("APEX") -> WatchModelPreset.PACE_2
                else -> null
            }
            if (detected != null && config.model != detected) {
                config = config.copy(model = detected)
            }
        }
    }

    // Reset "Done Syncing" badge back to "Sync Watch" as soon as user tweaks any watch face option
    LaunchedEffect(config.style, config.font, config.palette, config.complicationLayout, config.model) {
        if (syncProg is CorosBleManager.BleSyncProgress.Success) {
            bleManager.resetSyncProgress()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val canScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }
        if (canScan) {
            if (!showDirectSyncDialog) {
                showBleSheet = true
            }
            bleManager.startScan()
        } else {
            Toast.makeText(context, "Bluetooth permission required to connect watch", Toast.LENGTH_SHORT).show()
        }
    }

    val triggerDirectSync = {
        if (syncProg is CorosBleManager.BleSyncProgress.Success || syncProg is CorosBleManager.BleSyncProgress.Failed) {
            bleManager.resetSyncProgress()
        }
        showDirectSyncDialog = true
        if (bleState != CorosBleManager.BleConnectionState.CONNECTED) {
            val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else true
            val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true

            if (!hasScan || !hasConnect) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                }
            } else {
                bleManager.startScan()
            }
        }
    }

    val currentCycleSample = CorosWatchFaceCompiler.BACK_BUTTON_CYCLE_SAMPLES[
        cycleStep.mod(CorosWatchFaceCompiler.BACK_BUTTON_CYCLE_SAMPLES.size)
    ]

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // =========================================================================
        // 1. PINNED LIVE PREVIEW DOCK (Never scrolls out of view!)
        // =========================================================================
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Row: Hardware Target Badge + BLE Status + Sync Action Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (bleState == CorosBleManager.BleConnectionState.CONNECTED) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                        Column {
                            Text(
                                text = if (bleState == CorosBleManager.BleConnectionState.CONNECTED) {
                                    watchInfo.modelName.ifEmpty { config.model.modelName } +
                                        (if (watchInfo.batteryLevel >= 0) " • ${watchInfo.batteryLevel}%" else "")
                                } else {
                                    "${config.model.modelName.removePrefix("COROS ")} • ${config.model.resolutionPx}×${config.model.resolutionPx} ${config.model.screenType}"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "1:1 Hardware Binary Preview",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val isDoneSyncing = syncProg is CorosBleManager.BleSyncProgress.Success
                    Button(
                        onClick = triggerDirectSync,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDoneSyncing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = when {
                                isDoneSyncing -> "✅ Done Syncing"
                                bleState == CorosBleManager.BleConnectionState.CONNECTED -> "⚡ Sync Watch"
                                else -> "⚡ Connect & Sync"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Center Row: Fixed-Position Live Watch Canvas + Fixed-Width Quick Controls Column
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    WatchFacePreviewCanvas(
                        config = config,
                        sizeDp = 186.dp,
                        cycleIndex = cycleStep,
                        onCycleComplication = { cycleStep++ }
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.width(136.dp)
                    ) {
                        // Active Mode Chip
                        FilterChip(
                            selected = !config.isAodMode,
                            onClick = { config = config.copy(isAodMode = false) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    "Active ☀️",
                                    fontSize = 11.sp,
                                    fontWeight = if (!config.isAodMode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )

                        // Always-On Display (AOD) Mode Chip
                        FilterChip(
                            selected = config.isAodMode,
                            onClick = { config = config.copy(isAodMode = true) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    "AOD Dim 🌙",
                                    fontSize = 11.sp,
                                    fontWeight = if (config.isAodMode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )

                        // Fixed-size slot so WatchFacePreviewCanvas NEVER moves when BACK button appears/disappears
                        val showBackSimulator = config.complicationLayout == ComplicationLayout.BACK_BUTTON_CYCLE && !config.isAodMode
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showBackSimulator) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .then(if (showBackSimulator) Modifier.clickable { cycleStep++ } else Modifier)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (showBackSimulator) {
                                    Text(
                                        text = "⟳ Test BACK Button",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "${currentCycleSample.label}: ${currentCycleSample.digits}${if (currentCycleSample.showPercent) "%" else ""}",
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                    )
                                } else {
                                    Text(
                                        text = config.font.title,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = config.palette.displayName,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // =========================================================================
        // 2. CATEGORY TABS (Switch controls without scrolling away from preview)
        // =========================================================================
        val studioTabs = listOf("Complications", "Colors & Font", "Dial Style", "Export & BLE")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            studioTabs.forEachIndexed { index, title ->
                val selected = activeStudioTab == index
                FilterChip(
                    selected = selected,
                    onClick = { activeStudioTab = index },
                    label = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // =========================================================================
        // 3. INDEPENDENTLY SCROLLABLE CUSTOMIZATION PANE (Below pinned preview)
        // =========================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (activeStudioTab) {
                0 -> {
                    // TAB 0: COMPLICATION LAYOUTS (Vetted 1:1 for COROS PACE Pro)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bottom Data Complications",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (config.complicationLayout == ComplicationLayout.BACK_BUTTON_CYCLE) {
                            Text(
                                text = "Tap watch or button to cycle ⟳",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    ComplicationLayout.values().forEach { compOption ->
                        val isSelected = config.complicationLayout == compOption
                        val containerColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            label = "compBg"
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (config.complicationLayout == compOption && compOption.isInteractiveCycle) {
                                        cycleStep++
                                    } else {
                                        config = config.copy(complicationLayout = compOption)
                                    }
                                },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = containerColor),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(modifier = Modifier.padding(11.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (compOption.isInteractiveCycle) {
                                            Text("⟳ ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text(
                                            text = compOption.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (isSelected) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = if (compOption.isInteractiveCycle) "TAP TO CYCLE ⟳" else "ACTIVE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = compOption.subtitle,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = compOption.description,
                                    fontSize = 10.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 1: COLOR PALETTE & CLOCK TYPOGRAPHY (Instant visible changes!)
                    Text(
                        text = "Color Palette",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WatchFacePalette.values().forEach { palette ->
                            val isSelected = config.palette == palette
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { config = config.copy(palette = palette) }
                                    .padding(vertical = 4.dp, horizontal = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(palette.primaryColor)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(palette.secondaryColor)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    Text(
                                        text = palette.displayName,
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Clock Typography",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2
                    ) {
                        WatchFaceFont.values().forEach { fontOption ->
                            val isSelected = config.font == fontOption
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { config = config.copy(font = fontOption) }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        text = fontOption.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = fontOption.subtitle,
                                        fontSize = 10.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: WATCH FACE DIAL STYLE (All 5 styles compile 1:1 to .bin!)
                    Text(
                        text = "Watch Face Dial Layout",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    WatchFaceStyle.values().forEach { style ->
                        val isSelected = config.style == style
                        val containerColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            label = "cardBg"
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { config = config.copy(style = style) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = containerColor),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = style.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSelected) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "ACTIVE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = style.description,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                else -> {
                    // TAB 3: EXPORT & BLE SESSION MANAGEMENT
                    Text(
                        text = "Target COROS Watch Hardware",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WatchModelPreset.values().forEach { modelPreset ->
                            val isSelected = config.model == modelPreset
                            FilterChip(
                                selected = isSelected,
                                onClick = { config = config.copy(model = modelPreset) },
                                label = {
                                    Text(
                                        text = "${modelPreset.modelName.removePrefix("COROS ")} (${modelPreset.resolutionPx}px)",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Direct Watch Sync & Package Export",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val isDoneSyncing = syncProg is CorosBleManager.BleSyncProgress.Success
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = triggerDirectSync,
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDoneSyncing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = when {
                                    isDoneSyncing -> "✅ Done Syncing • Sync Again"
                                    bleState == CorosBleManager.BleConnectionState.CONNECTED -> "⚡ Sync to COROS Watch"
                                    else -> "Connect & Sync Watch"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = { showAuthDialog = true },
                            modifier = Modifier.weight(0.9f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (LapsRepository.hasWatchAuth(context)) "Watch Account" else "Sign In Watch",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val result = CorosWatchFaceCompiler.compile(context, config)
                                    compilationResult = result
                                    showCompilationDialog = true
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Compilation failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Export Archive Package (.zip)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val binBytes = CorosWatchFaceCompiler.compileToBin(context, config)
                                    val safeStyle = config.style.title.replace(" ", "_")
                                    val safeFont = config.font.title.replace(" ", "_")
                                    val binFile = java.io.File(context.cacheDir, "${safeStyle}_${safeFont}_${config.model.name}.bin")
                                    binFile.writeBytes(binBytes)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", binFile)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Compiled COROS BIN"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Share Compiled Hardware Binary (.bin)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val bitmap = CorosWatchFaceCompiler.renderFullPreviewBitmap(context, config, 800)
                                    val filename = "Laps_${config.style.name}_${System.currentTimeMillis()}.png"
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                                    }
                                    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    if (uri != null) {
                                        context.contentResolver.openOutputStream(uri)?.use { out ->
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                        Toast.makeText(context, "Saved to Gallery! Select it in COROS App > Custom Watch Face", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Saved: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Preview Image to Photos (.png)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Compilation & Sync Dialog
    if (showCompilationDialog && compilationResult != null) {
        val res = compilationResult!!
        AlertDialog(
            onDismissRequest = { showCompilationDialog = false },
            title = { Text("Ready to Sync Watch Face") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${config.style.title} package built with both Active Display & Always-On (AOD) standby layers for ${config.model.modelName}.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("• Target: ${res.resolution} × ${res.resolution} px (${config.model.modelName})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("• Package: ${res.zipFile.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("• Standby: AOD hollow wireframe included", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Text(
                        text = "How to install:\n1. Tap 'Send to COROS App' below.\n2. In COROS App, go to Profile > Watch > Watch Faces > Custom to flash it directly.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                res.zipFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Send Watch Face to COROS App"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Ready at: ${res.zipFile.absolutePath}", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("Send to COROS App")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val corosIntent = context.packageManager.getLaunchIntentForPackage("com.yf.smart.coros.dist")
                    if (corosIntent != null) {
                        OutlinedButton(onClick = { context.startActivity(corosIntent) }) {
                            Text("Open COROS")
                        }
                    }
                    TextButton(onClick = { showCompilationDialog = false }) {
                        Text("Done")
                    }
                }
            }
        )
    }

    // Direct Bluetooth LE Watch Face Transfer Dialog
    if (showDirectSyncDialog) {
        val activeWatchName = watchInfo.modelName.ifEmpty { config.model.modelName }

        LaunchedEffect(showDirectSyncDialog, bleState) {
            if (showDirectSyncDialog && bleState == CorosBleManager.BleConnectionState.CONNECTED && syncProg is CorosBleManager.BleSyncProgress.Idle && watchSlots == null) {
                bleManager.readWatchfaceInventory()
            }
        }

        LaunchedEffect(discoveredWatches, bleState, showDirectSyncDialog) {
            if (showDirectSyncDialog && (bleState == CorosBleManager.BleConnectionState.SCANNING || bleState == CorosBleManager.BleConnectionState.DISCONNECTED)) {
                val watch = discoveredWatches.firstOrNull {
                    it.name.contains("COROS", ignoreCase = true) ||
                    it.name.contains("PACE", ignoreCase = true) ||
                    it.name.contains("APEX", ignoreCase = true) ||
                    it.name.contains("VERTIX", ignoreCase = true)
                } ?: bleManager.getBondedWatches().firstOrNull()
                if (watch != null && bleState != CorosBleManager.BleConnectionState.CONNECTED && bleState != CorosBleManager.BleConnectionState.CONNECTING) {
                    bleManager.connectWatch(watch)
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (syncProg !is CorosBleManager.BleSyncProgress.Transferring) {
                    showDirectSyncDialog = false
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Direct COROS Watch Sync")
                    if (bleState == CorosBleManager.BleConnectionState.CONNECTING ||
                        syncProg is CorosBleManager.BleSyncProgress.QueryingSlots ||
                        syncProg is CorosBleManager.BleSyncProgress.Transferring ||
                        syncProg is CorosBleManager.BleSyncProgress.Verifying
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (bleState == CorosBleManager.BleConnectionState.SCANNING) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Searching for nearby COROS watch...")
                            Text("Ensure Bluetooth is on and watch is awake.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (bleState == CorosBleManager.BleConnectionState.CONNECTING) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Establishing secure BLE link with $activeWatchName...")
                            Text("Press the watch crown to wake the screen if it is in standby.", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (bleState != CorosBleManager.BleConnectionState.CONNECTED) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Watch is not connected.", color = MaterialTheme.colorScheme.error)
                            Button(onClick = { bleManager.startScan() }) {
                                Text("Scan & Connect Watch")
                            }
                        }
                    } else {
                        when (val s = syncProg) {
                            is CorosBleManager.BleSyncProgress.Idle,
                            is CorosBleManager.BleSyncProgress.Connecting,
                            is CorosBleManager.BleSyncProgress.QueryingSlots -> {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // Watch status chip
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = activeWatchName,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = "Connected over BLE • ${config.model.resolutionPx}×${config.model.resolutionPx}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (watchInfo.batteryLevel >= 0) {
                                                Text(
                                                    text = "🔋 ${watchInfo.batteryLevel}%",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }

                                    // Slot Picker Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Select Watch Face Slot to Replace:",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                        TextButton(onClick = {
                                            scope.launch { bleManager.readWatchfaceInventory() }
                                        }) {
                                            Text("↻ Refresh", fontSize = 11.sp)
                                        }
                                    }

                                    Text(
                                        text = if (watchSlots != null) {
                                            val occ = watchSlots?.count { it != 0xFFFFFFFFL } ?: 10
                                            "$occ of 10 slots occupied • Slot 10 is custom slot"
                                        } else {
                                            "10 of 10 slots on watch • Choose a slot to replace (Slot 10 recommended)"
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // 10 Slot Grid (5 columns x 2 rows)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        maxItemsInEachRow = 5
                                    ) {
                                        for (slotNum in 1..10) {
                                            val isSelected = selectedSlot == slotNum
                                            val slotId = watchSlots?.getOrNull(slotNum - 1)
                                            val isCustom = slotNum == 10

                                            val subtitleText = when {
                                                slotId != null && (slotId == 0xFFFFFFFFL || slotId == 0L) -> if (isCustom) "Custom" else "Free"
                                                slotId != null -> if (slotId == 0x4C415053L) "Laps" else "#$slotId"
                                                isCustom -> "Custom"
                                                else -> "Slot $slotNum"
                                            }

                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { selectedSlot = slotNum },
                                                label = {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "S$slotNum",
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                        )
                                                        Text(
                                                            text = subtitleText,
                                                            fontSize = 8.sp,
                                                            fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Normal,
                                                            color = when {
                                                                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                                                isCustom -> MaterialTheme.colorScheme.primary
                                                                subtitleText == "Free" -> MaterialTheme.colorScheme.tertiary
                                                                else -> MaterialTheme.colorScheme.secondary
                                                            }
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.weight(1f, fill = true),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            )
                                        }
                                    }

                                    // Selected Slot Target Card
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "Target: Slot $selectedSlot",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (selectedSlot == 10) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer
                                                    ) {
                                                        Text(
                                                            text = "RECOMMENDED",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = if (selectedSlot == 10) {
                                                    "Slot 10 is the dedicated custom watch face slot. Flashing will replace the current custom face on your $activeWatchName."
                                                } else {
                                                    "⚠️ Flashing will replace the existing watch face currently installed in Slot $selectedSlot."
                                                },
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    if (!LapsRepository.hasWatchAuth(context)) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Watch Account Sign-In Required",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "Sign in once to fetch your watch's RSA encryption key for direct BLE transfers.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Button(
                                                    onClick = { showAuthDialog = true },
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text("Sign In")
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }

                                    // Direct Flash Button
                                    Button(
                                        onClick = {
                                            if (!LapsRepository.hasWatchAuth(context)) {
                                                showAuthDialog = true
                                            } else {
                                                scope.launch {
                                                    val binBytes = try {
                                                        CorosWatchFaceCompiler.compileToBin(context, config)
                                                    } catch (e: Exception) {
                                                        try {
                                                            context.assets.open("watchfaces/pixel_bubbly_pace_pro.bin").readBytes()
                                                        } catch (_: Exception) { null }
                                                    }
                                                    if (binBytes != null) {
                                                        bleManager.directSyncWatchface(binBytes, targetSlot = selectedSlot - 1)
                                                    } else {
                                                        Toast.makeText(context, "Watch face binary could not be generated", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "⚡ Replace & Flash to Slot $selectedSlot",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Secondary Option: Share BIN File
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val binBytes = try {
                                                        CorosWatchFaceCompiler.compileToBin(context, config)
                                                    } catch (e: Exception) {
                                                        context.assets.open("watchfaces/pixel_bubbly_pace_pro.bin").readBytes()
                                                    }
                                                    val safeStyle = config.style.title.replace(" ", "_")
                                                    val binFile = java.io.File(context.cacheDir, "${safeStyle}_${config.model.name}.bin")
                                                    binFile.writeBytes(binBytes)
                                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", binFile)
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "application/octet-stream"
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share COROS Watch BIN"))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("📤 Share .BIN File Directly", fontSize = 12.sp)
                                    }
                                }
                            }
                            is CorosBleManager.BleSyncProgress.Transferring -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Flashing to Slot $selectedSlot: ${s.percent}%",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    LinearProgressIndicator(
                                        progress = { s.percent / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${s.sentBytes / 1024} KB / ${s.totalBytes / 1024} KB",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Keep watch awake",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            is CorosBleManager.BleSyncProgress.Verifying -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Text("Verifying installation on $activeWatchName...")
                                }
                            }
                            is CorosBleManager.BleSyncProgress.Success -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "✅ Done Syncing!",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${config.style.title} (${config.font.title}) is now active on your $activeWatchName in Slot ${s.slot}!",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            is CorosBleManager.BleSyncProgress.Failed -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "❌ Sync Error",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = s.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Tip: Press the crown to wake your watch display and keep it near your phone.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (syncProg is CorosBleManager.BleSyncProgress.Success) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            bleManager.resetSyncProgress()
                        }) {
                            Text("🔄 Sync Again")
                        }
                        Button(onClick = { showDirectSyncDialog = false }) {
                            Text("Done")
                        }
                    }
                } else if (syncProg is CorosBleManager.BleSyncProgress.Failed) {
                    Button(onClick = {
                        scope.launch {
                            val binBytes = try {
                                CorosWatchFaceCompiler.compileToBin(context, config)
                            } catch (e: Exception) {
                                try {
                                    context.assets.open("watchfaces/pixel_bubbly_pace_pro.bin").readBytes()
                                } catch (_: Exception) { null }
                            }
                            if (binBytes != null) {
                                bleManager.directSyncWatchface(binBytes, targetSlot = selectedSlot - 1)
                            }
                        }
                    }) {
                        Text("Retry")
                    }
                } else {
                    OutlinedButton(
                        onClick = { showDirectSyncDialog = false },
                        enabled = syncProg !is CorosBleManager.BleSyncProgress.Transferring
                    ) {
                        Text("Close")
                    }
                }
            }
        )
    }

    // COROS Watch Authentication Dialog
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAuthenticating) showAuthDialog = false },
            title = {
                Text(
                    text = "Authenticate COROS Watch",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "ℹ️ One-Time Watch Key Setup",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Signing in fetches your account's permanent RSA watch key from COROS Cloud. Note: fetching this key may momentarily sign out the COROS app, after which you can sign back into COROS and use both apps together.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = authEmail,
                        onValueChange = { authEmail = it },
                        label = { Text("COROS Account Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { authPassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    authErrorMessage?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (authEmail.isBlank() || authPassword.isBlank()) {
                            authErrorMessage = "Please enter both email and password"
                            return@Button
                        }
                        isAuthenticating = true
                        authErrorMessage = null
                        scope.launch {
                            val region = LapsRepository.getCorosRegion(context)
                            val res = CorosMobileAuth.login(authEmail.trim(), authPassword.trim(), region)
                            withContext(Dispatchers.Main) {
                                isAuthenticating = false
                                res.onSuccess { creds ->
                                    LapsRepository.saveWatchAuth(context, creds)
                                    showAuthDialog = false
                                    Toast.makeText(context, "Watch Authenticated Successfully!", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        bleManager.authenticateWatchSession()
                                        bleManager.readWatchfaceInventory()
                                    }
                                }.onFailure { err ->
                                    authErrorMessage = err.message ?: "Authentication failed"
                                }
                            }
                        }
                    },
                    enabled = !isAuthenticating
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Authenticate")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAuthDialog = false },
                    enabled = !isAuthenticating
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Direct BLE Connection Sheet / Dialog
    if (showBleSheet) {
        AlertDialog(
            onDismissRequest = {
                bleManager.stopScan()
                showBleSheet = false
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("COROS BLE Link")
                    if (bleState == CorosBleManager.BleConnectionState.SCANNING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (bleState == CorosBleManager.BleConnectionState.CONNECTED) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Connected to ${watchInfo.modelName.ifEmpty { "COROS PACE Pro" }}",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (watchInfo.batteryLevel >= 0) {
                                    Text(
                                        text = "Battery: ${watchInfo.batteryLevel}%",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = if (discoveredWatches.isEmpty()) "Scanning for nearby COROS watches..." else "Nearby / Paired Watches:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    discoveredWatches.forEach { watch ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    bleManager.connectWatch(watch)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(watch.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(watch.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${watch.rssi} dBm", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        bleManager.stopScan()
                        showBleSheet = false
                    }
                ) {
                    Text(if (bleState == CorosBleManager.BleConnectionState.CONNECTED) "Done & Continue to Sync" else "Close")
                }
            }
        )
    }
}

@Composable
private fun rememberCorosBleManager(): CorosBleManager {
    val context = LocalContext.current
    return remember { CorosBleManager(context) }
}
