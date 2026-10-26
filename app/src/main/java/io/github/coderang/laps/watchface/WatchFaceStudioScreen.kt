package io.github.coderang.laps.watchface

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch

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
    var compilationResult by remember { mutableStateOf<CorosWatchFaceCompiler.CompilationResult?>(null) }
    var showCompilationDialog by remember { mutableStateOf(false) }
    var showBleSheet by remember { mutableStateOf(false) }

    val bleState by bleManager.connectionState.collectAsState()
    val discoveredWatches by bleManager.discoveredWatches.collectAsState()
    val watchInfo by bleManager.watchInfo.collectAsState()

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
            showBleSheet = true
            bleManager.startScan()
        } else {
            Toast.makeText(context, "Bluetooth permission required to connect watch", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Model Preset Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WatchModelPreset.values().forEach { preset ->
                FilterChip(
                    selected = config.model == preset,
                    onClick = { config = config.copy(model = preset) },
                    label = { Text(preset.modelName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // Live BLE Connection Status Banner
        AnimatedVisibility(visible = bleState == CorosBleManager.BleConnectionState.CONNECTED) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Column {
                            Text(
                                text = "Connected: ${watchInfo.modelName.ifEmpty { "COROS PACE Pro" }}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (watchInfo.batteryLevel >= 0) {
                                Text(
                                    text = "Watch Battery: ${watchInfo.batteryLevel}%",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    TextButton(onClick = { bleManager.disconnect() }) {
                        Text("Disconnect", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. HERO: Photorealistic Watch Canvas Preview
        WatchFacePreviewCanvas(
            config = config,
            sizeDp = 270.dp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Target Resolution Pill
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                text = "${config.model.resolutionPx} × ${config.model.resolutionPx} px • ${config.model.screenType}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Display Mode: Active vs Always-On Display (AOD / Dim Mode)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !config.isAodMode,
                onClick = { config = config.copy(isAodMode = false) },
                label = { Text("Active ☀️", fontSize = 12.sp, fontWeight = if (!config.isAodMode) FontWeight.Bold else FontWeight.Normal) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilterChip(
                selected = config.isAodMode,
                onClick = { config = config.copy(isAodMode = true) },
                label = { Text("Always-On / Dim 🌙", fontSize = 12.sp, fontWeight = if (config.isAodMode) FontWeight.Bold else FontWeight.Normal) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 3. Style Selector Carousel
        Text(
            text = "Watch Face Style",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = containerColor),
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = style.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = style.description,
                            fontSize = 12.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Material 3 Color Palette Selector
        Text(
            text = "Color Palette",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WatchFacePalette.values().forEach { palette ->
                val isSelected = config.palette == palette
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { config = config.copy(palette = palette) }
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
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
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(palette.secondaryColor)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = palette.displayName,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 5. Actions: Compile & Direct BLE Sync
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
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
                modifier = Modifier.weight(1.2f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (bleState == CorosBleManager.BleConnectionState.CONNECTED) "Sync to PACE Pro" else "Export & Sync Package",
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    } else {
                        showBleSheet = true
                        bleManager.startScan()
                    }
                },
                modifier = Modifier.weight(0.9f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (bleState == CorosBleManager.BleConnectionState.CONNECTED) "Watch Linked ✓" else "Link Watch",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Direct Gallery Wallpaper Export for COROS App Custom Watch Face picker
        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val bitmap = CorosWatchFaceCompiler.renderFullPreviewBitmap(context, config, 800)
                        val filename = "PixelBubbly_${System.currentTimeMillis()}.png"
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
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Save Image to Photos (for COROS Custom Face)", fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(48.dp))
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
                        text = "Pixel Bubbly package built with both Active Display & Always-On (AOD) standby layers for your AMOLED screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("• Target: ${res.resolution} × ${res.resolution} px (PACE Pro AMOLED)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("• Package: ${res.zipFile.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("• Standby: AOD hollow wireframe included", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Text(
                        text = "How to install:\n1. Tap 'Send to COROS App' below.\n2. In COROS App, go to Profile > PACE Pro > Watch Faces > Custom to flash it directly to your watch.",
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
