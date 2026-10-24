package io.github.coderang.laps

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.coderang.laps.calendar.CalendarSyncManager
import io.github.coderang.laps.calendar.PlanGenerator
import io.github.coderang.laps.data.*
import io.github.coderang.laps.notifications.LapsNotificationHelper
import io.github.coderang.laps.ui.ExpressiveTheme
import io.github.coderang.laps.ui.ExpressiveWidgetRenderer
import io.github.coderang.laps.widgets.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_RUN_DETAIL = "EXTRA_OPEN_RUN_DETAIL"
    }

    data class WidgetCatalogItem(
        val styleIndex: Int,
        val title: String,
        val subtitle: String,
        val spanLabel: String,
        val category: String, // "SCULPTURAL" or "CARDS"
        val slot: ExpressiveTileType
    )

    private val catalog = listOf(
        WidgetCatalogItem(0, "Capsule Stack", "Mileage, Load, Z2 & Recovery", "2×2", "CARDS", ExpressiveTileType.WEEKLY_MILEAGE),
        WidgetCatalogItem(1, "EvoLab Ring", "Concentric load & readiness", "2×2", "SCULPTURAL", ExpressiveTileType.TRAINING_LOAD),
        WidgetCatalogItem(2, "Contrast Pods", "Weekly volume + Load pods", "2×2", "SCULPTURAL", ExpressiveTileType.NEXT_WORKOUT),
        WidgetCatalogItem(3, "80/20 Split Bar", "Aerobic vs Threshold HR balance", "2×2", "CARDS", ExpressiveTileType.ZONE_SPECTRUM),
        WidgetCatalogItem(4, "Cookie-12 Seal", "EvoLab Load & Readiness seal", "2×2", "SCULPTURAL", ExpressiveTileType.RACE_COUNTDOWN),
        WidgetCatalogItem(5, "Horizon Split", "Weekly progress + Cookie-9", "2×2", "CARDS", ExpressiveTileType.COMMAND_CENTER),
        WidgetCatalogItem(6, "Nested Activity", "Recent workouts & load stack", "2×2", "CARDS", ExpressiveTileType.NEXT_WORKOUT),
        WidgetCatalogItem(7, "Slanted Pill", "Weekly volume & runs logged", "2×2", "SCULPTURAL", ExpressiveTileType.WEEKLY_MILEAGE),
        WidgetCatalogItem(8, "Tonal Ellipses", "Load, readiness & recovery", "2×2", "SCULPTURAL", ExpressiveTileType.RACE_COUNTDOWN),
        WidgetCatalogItem(9, "Flower Dial", "Cookie-12 readiness + week dots", "2×2", "SCULPTURAL", ExpressiveTileType.TRAINING_LOAD),
        WidgetCatalogItem(10, "Display Split", "Condensed Acute Load display", "2×2", "CARDS", ExpressiveTileType.RACE_COUNTDOWN),
        WidgetCatalogItem(11, "EvoLab Splits", "Pace, HR & Lactate Threshold", "2×2", "CARDS", ExpressiveTileType.COMMAND_CENTER)
    )

    private var oauthRefreshTrigger by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "laps" && uri.host == "oauth") {
                val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("access_token") ?: uri.getQueryParameter("code")
                if (!token.isNullOrBlank()) {
                    LapsRepository.saveCorosToken(this, token)
                    LapsRepository.setSetupCompleted(this, true)
                    oauthRefreshTrigger++
                    Toast.makeText(this, "COROS OAuth 2.0 Connected!", Toast.LENGTH_SHORT).show()
                    LapsWidgetUpdateHelper.refreshAllWidgetsAsync(this)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LapsNotificationHelper.createNotificationChannel(this)

        // Handle COROS OAuth 2.0 / MCP deep link callback
        intent?.data?.let { uri ->
            if (uri.scheme == "laps" && uri.host == "oauth") {
                val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("access_token") ?: uri.getQueryParameter("code")
                if (!token.isNullOrBlank()) {
                    LapsRepository.saveCorosToken(this, token)
                    LapsRepository.setSetupCompleted(this, true)
                    oauthRefreshTrigger++
                    Toast.makeText(this, "COROS OAuth 2.0 Connected!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val shouldOpenRunDetailFromIntent = intent?.getBooleanExtra(EXTRA_OPEN_RUN_DETAIL, false) ?: false

        setContent {
            val context = LocalContext.current
            val colorScheme = ExpressiveTheme.getComposeColorScheme(context)

            val trigger = oauthRefreshTrigger
            var payload by remember(trigger) { mutableStateOf(LapsRepository.getCachedPayload()) }
            var isSyncing by remember { mutableStateOf(false) }
            var selectedSection by remember { mutableIntStateOf(0) } // 0 = Training Hub, 1 = Widgets
            var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Sculptural, 2 = Cards
            var showSettingsDialog by remember { mutableStateOf(false) }
            var showPlanDialog by remember { mutableStateOf(false) }
            val hasCredentials = remember(trigger) { LapsRepository.hasCredentials(context) }
            var showOnboarding by remember(trigger) { mutableStateOf(!hasCredentials && !LapsRepository.isSetupCompleted(context)) }
            var isResolvingMissed by remember { mutableStateOf(false) }
            var selectedRunForDetail by remember {
                mutableStateOf(if (shouldOpenRunDetailFromIntent) payload.lastRun else null)
            }

            // Permissions launcher for Android 13+ Notifications & Android Calendar
            val permissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {}

            LaunchedEffect(Unit) {
                val perms = mutableListOf(
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionsLauncher.launch(perms.toTypedArray())

                isSyncing = true
                io.github.coderang.laps.sync.LapsSyncWorker.schedule(context)
                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) { res ->
                    runOnUiThread {
                        payload = LapsRepository.getCachedPayload()
                        isSyncing = false
                    }
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                MainAppScaffold(
                    payload = payload,
                    isSyncing = isSyncing,
                    selectedSection = selectedSection,
                    onSectionSelected = { selectedSection = it },
                    selectedFilter = selectedFilter,
                    onFilterSelected = { selectedFilter = it },
                    catalog = catalog,
                    onRunClicked = { run -> selectedRunForDetail = run },
                    onResolveMissedWorkout = { action, targetDate ->
                        val missed = payload.missedWorkout ?: return@MainAppScaffold
                        isResolvingMissed = true
                        Thread {
                            val success = if (action == "SHIFT") {
                                val cal = Calendar.getInstance()
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                val targetTime = try {
                                    sdf.parse(targetDate)?.time ?: (System.currentTimeMillis() + 86400000L)
                                } catch (_: Exception) { System.currentTimeMillis() + 86400000L }
                                CalendarSyncManager.shiftWorkout(context, missed.eventId, targetTime)
                            } else {
                                CalendarSyncManager.skipWorkout(context, missed.eventId)
                            }

                            runOnUiThread {
                                isResolvingMissed = false
                                val updated = payload.copy(missedWorkout = null)
                                payload = updated
                                LapsRepository.setCachedPayload(updated)
                                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context)

                                val msg = if (action == "SHIFT") {
                                    "Shifted to ${targetDate.ifBlank { "upcoming day" }} in Google Calendar"
                                } else {
                                    "Workout marked [SKIPPED] in Google Calendar"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    },
                    isResolvingMissed = isResolvingMissed,
                    onSyncClicked = {
                        isSyncing = true
                        LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) { res ->
                            runOnUiThread {
                                val (email, pwd) = LapsRepository.getCorosCredentials(context)
                                if (email.isBlank() || pwd.isBlank()) {
                                    LapsRepository.resetSamplePayload()
                                }
                                payload = LapsRepository.getCachedPayload()
                                isSyncing = false
                                res.onSuccess {
                                    Toast.makeText(context, "Synced directly with COROS & Google Calendar", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Using local cache: ${err.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onPlanClicked = { showPlanDialog = true },
                    onSettingsClicked = { showSettingsDialog = true },
                    onConnectAccountClicked = { showSettingsDialog = true },
                    onPinWidget = { item ->
                        LapsRepository.setStyleForTile(context, item.slot, item.styleIndex)
                        LapsWidgetUpdateHelper.renderAllFromCache(context)
                        pinWidgetToLauncher(item)
                    }
                )

                if (showOnboarding) {
                    LapsOnboardingDialog(
                        onDismiss = { showOnboarding = false },
                        onConnected = {
                            showOnboarding = false
                            isSyncing = true
                            LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) {
                                runOnUiThread {
                                    payload = LapsRepository.getCachedPayload()
                                    isSyncing = false
                                }
                            }
                        }
                    )
                }

                selectedRunForDetail?.let { run ->
                    RunDetailModalBottomSheet(
                        run = run,
                        onDismiss = { selectedRunForDetail = null }
                    )
                }

                if (showSettingsDialog) {
                    LapsSettingsDialog(
                        onDismiss = { showSettingsDialog = false },
                        onSaved = {
                            showSettingsDialog = false
                            isSyncing = true
                            LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) { res ->
                                runOnUiThread {
                                    payload = LapsRepository.getCachedPayload()
                                    isSyncing = false
                                    res.onSuccess {
                                        Toast.makeText(context, "Logged in & Synced directly with COROS!", Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Toast.makeText(context, "Sync note: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    )
                }

                if (showPlanDialog) {
                    PlanGeneratorDialog(
                        onDismiss = { showPlanDialog = false },
                        onOpenSettings = {
                            showPlanDialog = false
                            showSettingsDialog = true
                        },
                        onPlanPublished = {
                            showPlanDialog = false
                            isSyncing = true
                            LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) {
                                runOnUiThread {
                                    payload = LapsRepository.getCachedPayload()
                                    isSyncing = false
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun pinWidgetToLauncher(item: WidgetCatalogItem) {
        val manager = AppWidgetManager.getInstance(this)
        val providerClass = when (item.slot) {
            ExpressiveTileType.WEEKLY_MILEAGE -> WeeklyMileageWidgetProvider::class.java
            ExpressiveTileType.TRAINING_LOAD -> TrainingLoadWidgetProvider::class.java
            ExpressiveTileType.NEXT_WORKOUT -> NextWorkoutWidgetProvider::class.java
            ExpressiveTileType.ZONE_SPECTRUM -> ZoneSpectrumWidgetProvider::class.java
            ExpressiveTileType.RACE_COUNTDOWN -> RaceCountdownWidgetProvider::class.java
            ExpressiveTileType.COMMAND_CENTER -> CommandCenterWidgetProvider::class.java
        }
        val provider = ComponentName(this, providerClass)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        }
        Toast.makeText(
            this,
            "Applied '${item.title}' to ${item.slot.title}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppScaffold(
    payload: LapsPayload,
    isSyncing: Boolean,
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
    selectedFilter: Int,
    onFilterSelected: (Int) -> Unit,
    catalog: List<MainActivity.WidgetCatalogItem>,
    onRunClicked: (LastRunData) -> Unit,
    onResolveMissedWorkout: (String, String) -> Unit = { _, _ -> },
    isResolvingMissed: Boolean = false,
    onSyncClicked: () -> Unit,
    onPlanClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onConnectAccountClicked: () -> Unit = onSettingsClicked,
    onPinWidget: (MainActivity.WidgetCatalogItem) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedSection == 0) "Training Hub" else "Widgets",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                actions = {
                    IconButton(
                        onClick = onPlanClicked,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = rememberPlanVector(),
                            contentDescription = "Training Plan Builder"
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onSyncClicked,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = rememberRefreshVector(),
                            contentDescription = "Sync COROS"
                        )
                    }
                    IconButton(
                        onClick = onSettingsClicked,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = rememberTuneVector(),
                            contentDescription = "Laps Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                NavigationBarItem(
                    selected = selectedSection == 0,
                    onClick = { onSectionSelected(0) },
                    icon = {
                        Icon(
                            imageVector = rememberHubVector(),
                            contentDescription = "Training Hub"
                        )
                    },
                    label = {
                        Text(
                            text = "Training Hub",
                            fontWeight = if (selectedSection == 0) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
                NavigationBarItem(
                    selected = selectedSection == 1,
                    onClick = { onSectionSelected(1) },
                    icon = {
                        Icon(
                            imageVector = rememberWidgetsVector(),
                            contentDescription = "Widgets"
                        )
                    },
                    label = {
                        Text(
                            text = "Widgets",
                            fontWeight = if (selectedSection == 1) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        if (selectedSection == 0) {
            TrainingHubSection(
                payload = payload,
                isSyncing = isSyncing,
                contentPadding = innerPadding,
                onRunClicked = onRunClicked,
                onResolveMissedWorkout = onResolveMissedWorkout,
                isResolvingMissed = isResolvingMissed,
                onConnectAccountClicked = onConnectAccountClicked,
                onPlanClicked = onPlanClicked,
                onBrowseWidgetsClicked = { onSectionSelected(1) }
            )
        } else {
            WidgetsGallerySection(
                payload = payload,
                selectedFilter = selectedFilter,
                onFilterSelected = onFilterSelected,
                catalog = catalog,
                contentPadding = innerPadding,
                onPinWidget = onPinWidget
            )
        }
    }
}

@Composable
private fun TrainingHubSection(
    payload: LapsPayload,
    isSyncing: Boolean,
    contentPadding: PaddingValues,
    onRunClicked: (LastRunData) -> Unit,
    onResolveMissedWorkout: (String, String) -> Unit = { _, _ -> },
    isResolvingMissed: Boolean = false,
    onConnectAccountClicked: () -> Unit = {},
    onPlanClicked: () -> Unit = {},
    onBrowseWidgetsClicked: () -> Unit = {}
) {
    val runs = payload.recentRuns.ifEmpty { listOf(payload.lastRun) }
    val latest = runs.first()
    val context = LocalContext.current
    val isConnected = remember(payload) { LapsRepository.hasCredentials(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connect Account Prompt Card (if not logged into COROS)
        if (!isConnected) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Sample Data Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "You can add and customize home screen widgets right away with demo metrics. Connect your COROS account whenever you're ready to sync live data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onConnectAccountClicked,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Connect Account")
                            }
                            OutlinedButton(
                                onClick = onBrowseWidgetsClicked
                            ) {
                                Text("Browse Widgets")
                            }
                        }
                    }
                }
            }
        }
        // Missed Workout Resolution Card (if a past scheduled session wasn't logged)
        if (payload.missedWorkout != null) {
            item {
                MissedWorkoutCoachCard(
                    missed = payload.missedWorkout,
                    isResolving = isResolvingMissed,
                    onResolve = onResolveMissedWorkout
                )
            }
        }

        // 1. Athlete Overview & Next Workout Card
        item {
            AthleteSummaryHeroCard(payload = payload, isSyncing = isSyncing)
        }

        // 2. Weekly Mileage Bar & 7-Day M-S Schedule Card
        item {
            WeeklyMileageProgressCard(payload = payload, onPlanClicked = onPlanClicked)
        }

        // 3. Tappable Latest Run Analysis Card (opens detailed modal on tap)
        item {
            LatestRunAnalysisCard(
                run = latest,
                onClick = { onRunClicked(latest) }
            )
        }

        // 4. Recent Runs History (each item also tappable for full analysis)
        if (runs.size > 1) {
            item {
                Text(
                    text = "RECENT WORKOUT LOG",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                )
            }
            items(runs.drop(1), key = { it.date + it.name + it.distanceKm + it.durationMinutes }) { run ->
                RecentRunRowCard(
                    run = run,
                    onClick = { onRunClicked(run) }
                )
            }
        }
    }
}

@Composable
private fun WidgetsGallerySection(
    payload: LapsPayload,
    selectedFilter: Int,
    onFilterSelected: (Int) -> Unit,
    catalog: List<MainActivity.WidgetCatalogItem>,
    contentPadding: PaddingValues,
    onPinWidget: (MainActivity.WidgetCatalogItem) -> Unit
) {
    val filteredItems = remember(selectedFilter, catalog) {
        when (selectedFilter) {
            1 -> catalog.filter { it.category == "SCULPTURAL" }
            2 -> catalog.filter { it.category == "CARDS" }
            else -> catalog
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tap any widget to apply & pin",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Colors automatically adapt to your device wallpaper & dark/light theme.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            val options = listOf("All (12)", "Sculptural (6)", "Data Cards (6)")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp)
            ) {
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedFilter == index,
                        onClick = { onFilterSelected(index) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selectedFilter == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        gridItems(filteredItems, key = { it.styleIndex }) { item ->
            StockPixelWidgetCard(
                item = item,
                payload = payload,
                onClick = { onPinWidget(item) }
            )
        }
    }
}

@Composable
private fun WeeklyMileageProgressCard(payload: LapsPayload, onPlanClicked: (() -> Unit)? = null) {
    val wm = payload.weeklyMileage
    val weekProgress = (wm.completionPct / 100f).coerceIn(0f, 1f)
    val expectedProgress = (wm.expectedCompletionPct / 100f).coerceIn(0f, 1f)
    // Only show active/scheduled training days (hide rest days)
    val trainingDays = remember(wm.days) {
        wm.days.filter { it.plannedKm > 0.0 || it.actualKm > 0.0 }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WEEKLY MILEAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", wm.actualKm),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (wm.plannedKm > 0) "/ ${String.format(Locale.US, "%.0f", wm.plannedKm)} km" else "km this week",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (wm.plannedKm > 0) "${wm.completionPct}% Done" else "${wm.runsCompleted} Runs Logged",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = if (wm.plannedKm > 0) "${String.format(Locale.US, "%.1f", wm.remainingKm)} km remaining" else "${wm.avgPace} · ${wm.totalElevationM}m elev",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Weekly Capsule Progress Bar
            val fillFraction = if (wm.plannedKm > 0) weekProgress.coerceAtLeast(0.06f) else (wm.actualKm.toFloat() / 35f).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (trainingDays.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlanClicked?.invoke() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NO RUNS SCHEDULED THIS WEEK",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Build an 80/20 Plan (1-Tap)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        FilledTonalButton(
                            onClick = { onPlanClicked?.invoke() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Create Plan")
                        }
                    }
                }
            } else {
                // Training Days Only (No Rest Days)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trainingDays.forEach { day ->
                    val isCompleted = day.actualKm > 0
                    val dayPct = if (day.plannedKm > 0) {
                        ((day.actualKm / day.plannedKm) * 100.0 + 0.5).toInt()
                    } else if (isCompleted) 100 else 0
                    val barFrac = (dayPct / 100f).coerceIn(0f, 1f)

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (day.isToday) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = day.dayName.uppercase(Locale.US),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isCompleted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = day.workoutType,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Text(
                                    text = if (isCompleted) {
                                        if (day.plannedKm > 0) {
                                            "${String.format(Locale.US, "%.1f", day.actualKm)} / ${String.format(Locale.US, "%.0f", day.plannedKm)}K · $dayPct%"
                                        } else {
                                            "${String.format(Locale.US, "%.1f", day.actualKm)} km"
                                        }
                                    } else {
                                        "Upcoming · ${String.format(Locale.US, "%.0f", day.plannedKm)}K"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            ) {
                                if (isCompleted) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(barFrac.coerceAtLeast(0.05f))
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun LatestRunAnalysisCard(
    run: LastRunData,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "LATEST RUN · ${run.dayLabel.uppercase(Locale.US)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = run.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.2f", run.distanceKm)} km  ·  ${run.avgPace}  ·  ${run.avgHr} bpm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = run.verdictBadge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // AI Coach Verdict & Executive Summary Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = run.aiVerdictHeadline,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = run.aiExecutiveSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                    )
                }
            }

            if (run.aiTips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "TOP COACH TIP",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = run.aiTips.first(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Text(
                    text = "View AI Coach Analysis & Tips",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RecentRunRowCard(
    run: LastRunData,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.dayLabel.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = run.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = run.verdictBadge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = run.aiVerdictHeadline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Tap for AI Coach verdict & tips →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunDetailModalBottomSheet(
    run: LastRunData,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI COACH RUN ANALYZER · ${run.dayLabel.uppercase(Locale.US)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = run.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.2f", run.distanceKm)} km  ·  ${run.avgPace}  ·  ${run.avgHr} bpm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = run.verdictBadge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. AI Coach Executive Verdict Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "COACH VERDICT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = run.aiVerdictHeadline,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = run.aiExecutiveSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Deep AI Analyzer Findings
            Text(
                text = "WHAT THE AI ANALYZER FOUND",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                run.aiInsights.forEachIndexed { idx, insight ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "0${idx + 1}",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = insight,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Actionable Coach Tips & Next Workout Prescription
            Text(
                text = "ACTIONABLE COACH TIPS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                run.aiTips.forEachIndexed { idx, tip ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiary
                            ) {
                                Text(
                                    text = "TIP ${idx + 1}",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = tip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MissedWorkoutCoachCard(
    missed: MissedWorkoutData,
    isResolving: Boolean,
    onResolve: (action: String, targetDate: String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "!",
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }
                }
                Column {
                    Text(
                        text = "MISSED WORKOUT DETECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${missed.title} (${missed.targetDistanceKm.toInt()}K)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = missed.coachAdvice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (missed.options.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    missed.options.forEach { opt ->
                        if (opt.isRecommended) {
                            Button(
                                onClick = { onResolve(opt.action, opt.targetDate) },
                                enabled = !isResolving,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = opt.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                                        ) {
                                            Text(
                                                text = "AI RECOMMENDED",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                    if (opt.sublabel.isNotBlank()) {
                                        Text(
                                            text = opt.sublabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onResolve(opt.action, opt.targetDate) },
                                enabled = !isResolving,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = opt.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (opt.sublabel.isNotBlank()) {
                                        Text(
                                            text = opt.sublabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onResolve("DROP", "") },
                        enabled = !isResolving,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = if (isResolving) "Updating..." else "Drop & Move On",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AthleteSummaryHeroCard(payload: LapsPayload, isSyncing: Boolean) {
    val wm = payload.weeklyMileage
    val tl = payload.trainingLoad
    val rc = payload.raceCountdown
    val nw = payload.nextWorkout

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Top Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSyncing) "Syncing COROS…" else "Updated ${payload.updatedLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    val statusText = if (rc.daysLeft > 0) {
                        "${rc.raceName.take(12)} · ${rc.daysLeft}d"
                    } else {
                        "COROS · ${tl.statusBadge}"
                    }
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3-Column M3 Tonal Metric Pods
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val wkSub = if (wm.plannedKm > 0) {
                    "/ ${wm.plannedKm.toInt()}K (${wm.completionPct}%)"
                } else {
                    "${wm.runsCompleted} runs"
                }
                MetricPod(
                    modifier = Modifier.weight(1f),
                    label = if (wm.plannedKm > 0) "Week ${rc.currentWeek}" else "Weekly Dist",
                    value = String.format(Locale.US, "%.1fK", wm.actualKm),
                    sub = wkSub,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 10.dp, bottomEnd = 10.dp)
                )
                MetricPod(
                    modifier = Modifier.weight(1f),
                    label = "Acute Load",
                    value = "${tl.acuteTl}",
                    sub = tl.statusBadge.lowercase().replaceFirstChar { it.uppercase() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(10.dp)
                )
                MetricPod(
                    modifier = Modifier.weight(1f),
                    label = "Threshold",
                    value = rc.currentLtsp.replace(" /km", ""),
                    sub = "${tl.lthr} bpm",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 22.dp, bottomEnd = 22.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Next Workout Strip / COROS Metrics Live Sync Strip
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                if (nw.targetDistanceKm > 0 || (nw.title.isNotBlank() && nw.title != "No Scheduled Workout")) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NEXT WORKOUT · ${nw.dayBadge}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = nw.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${nw.targetPace}  ·  ${nw.targetHrZone}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = if (nw.targetDistanceKm > 0) "${nw.targetDistanceKm.toInt()} km" else "Planned",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "COROS LIVE SYNC · METRICS ONLY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${wm.runsCompleted} runs logged this week (${String.format(Locale.US, "%.1f", wm.actualKm)} km)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Widgets updating on-device · No training plan required",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Live",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunStatChip(
    modifier: Modifier,
    label: String,
    value: String,
    sub: String = ""
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetricPod(
    modifier: Modifier,
    label: String,
    value: String,
    sub: String,
    containerColor: Color,
    contentColor: Color,
    shape: RoundedCornerShape
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = contentColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StockPixelWidgetCard(
    item: MainActivity.WidgetCatalogItem,
    payload: LapsPayload,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val bitmap = remember(item.styleIndex, payload, isDark) {
        ExpressiveWidgetRenderer.renderTileBitmap(
            context = context,
            tileType = item.slot,
            payload = payload,
            widthPx = 520,
            heightPx = 520,
            styleIndexOverride = item.styleIndex
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Widget Preview Well
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer Metadata Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = item.spanLabel,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LapsOnboardingDialog(
    onDismiss: () -> Unit,
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    var isOAuthMode by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val calendars = remember { CalendarSyncManager.getAvailableCalendars(context) }
    var selectedCalendarId by remember { mutableLongStateOf(-1L) }
    var calendarExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* Modal: require explicit choice */ },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "L",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Welcome to Laps",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Standalone COROS & Google Calendar companion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = rememberShieldVector(),
                                    contentDescription = "Privacy Shield",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Zero Cloud • On-Device Sandboxing",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Tokens and credentials stay 100% inside your device sandbox. Connect password-free via official COROS OAuth / MCP.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Auth Method Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { isOAuthMode = false; errorMessage = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (!isOAuthMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (!isOAuthMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("Direct Login", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { isOAuthMode = true; errorMessage = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isOAuthMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (isOAuthMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("MCP Token", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isOAuthMode) {
                    Text(
                        text = "COROS MCP Bearer Token (Advanced)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Paste your Bearer token from COROS Model Context Protocol (mcp.coros.com).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; errorMessage = null },
                        label = { Text("COROS MCP Bearer Token") },
                        placeholder = { Text("Bearer eyJhbGci...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "Direct COROS Training Hub Login",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Direct on-device authentication with teamapi.coros.com. Credentials stay encrypted strictly in your device sandbox.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; errorMessage = null },
                        label = { Text("COROS Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text("COROS Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "Hide" else "Show", fontSize = 12.sp)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Google Calendar (Optional)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Only needed for scheduled workouts. Choose 'None (Metrics Only)' if you just want widgets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val selectedCal = calendars.find { it.id == selectedCalendarId }
                val selectedCalLabel = when {
                    selectedCalendarId == -1L -> "None (Metrics Only — Widgets & Live Sync)"
                    selectedCal != null -> "${selectedCal.displayName} (${selectedCal.accountName})"
                    else -> "None (Metrics Only — Widgets & Live Sync)"
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        onClick = { calendarExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = selectedCalLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text("▾", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(
                        expanded = calendarExpanded,
                        onDismissRequest = { calendarExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("None (Metrics Only — Widgets & Live Sync)", fontWeight = FontWeight.SemiBold)
                                    Text("No training plan. Connect purely for COROS metrics widgets.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                selectedCalendarId = -1L
                                calendarExpanded = false
                            }
                        )
                        calendars.forEach { cal ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(cal.displayName, fontWeight = FontWeight.Medium)
                                        Text(
                                            cal.accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    selectedCalendarId = cal.id
                                    calendarExpanded = false
                                }
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isOAuthMode) {
                        if (token.isBlank()) {
                            errorMessage = "Please paste your COROS Bearer token or connect in browser."
                            return@Button
                        }
                        isLoggingIn = true
                        errorMessage = null
                        Thread {
                            val client = io.github.coderang.laps.coros.CorosApiClient()
                            val authRes = client.authenticateWithToken(token.trim())
                            (context as? ComponentActivity)?.runOnUiThread {
                                isLoggingIn = false
                                authRes.onSuccess {
                                    LapsRepository.saveCorosToken(context, token.trim())
                                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                    LapsRepository.setSetupCompleted(context, true)
                                    Toast.makeText(context, "Connected via COROS OAuth!", Toast.LENGTH_SHORT).show()
                                    onConnected()
                                }.onFailure { err ->
                                    errorMessage = "OAuth Token Error: ${err.message ?: "Invalid token"}"
                                }
                            }
                        }.start()
                    } else {
                        if (email.isBlank() || password.isBlank()) {
                            errorMessage = "Please enter your COROS email and password."
                            return@Button
                        }
                        isLoggingIn = true
                        errorMessage = null
                        Thread {
                            val client = io.github.coderang.laps.coros.CorosApiClient()
                            val loginRes = client.login(email.trim(), password.trim())
                            (context as? ComponentActivity)?.runOnUiThread {
                                isLoggingIn = false
                                loginRes.onSuccess {
                                    LapsRepository.saveCorosCredentials(context, email.trim(), password.trim())
                                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                    LapsRepository.setSetupCompleted(context, true)
                                    Toast.makeText(context, "Connected to COROS Training Hub!", Toast.LENGTH_SHORT).show()
                                    onConnected()
                                }.onFailure { err ->
                                    errorMessage = "COROS Login failed: ${err.message ?: "Invalid credentials"}"
                                }
                            }
                        }.start()
                    }
                },
                enabled = !isLoggingIn
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Connect & Sync")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    LapsRepository.setSetupCompleted(context, true)
                    onDismiss()
                },
                enabled = !isLoggingIn
            ) {
                Text("Use Widgets (Demo Mode)")
            }
        }
    )
}

@Composable
private fun LapsSettingsDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val (savedEmail, savedPassword) = remember { LapsRepository.getCorosCredentials(context) }
    val savedToken = remember { LapsRepository.getCorosToken(context) }
    val savedCalId = remember { LapsRepository.getSelectedCalendarId(context) }
    val savedGemini = remember { LapsRepository.getGeminiKey(context) }

    var token by remember { mutableStateOf(savedToken) }
    var email by remember { mutableStateOf(savedEmail) }
    var password by remember { mutableStateOf(savedPassword) }
    var geminiKey by remember { mutableStateOf(savedGemini) }
    var selectedCalendarId by remember { mutableLongStateOf(savedCalId) }
    var isOAuthMode by remember { mutableStateOf(savedToken.isNotBlank() || (savedEmail.isBlank() && savedPassword.isBlank())) }

    val calendars = remember { CalendarSyncManager.getAvailableCalendars(context) }
    var calendarExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Laps Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = rememberShieldVector(),
                                    contentDescription = "Privacy Shield",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Zero Cloud • On-Device Sandboxing",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Tokens and credentials reside strictly in private Android app sandboxing. For password-free security, connect via COROS OAuth 2.0 / MCP.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Auth Method Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { isOAuthMode = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (!isOAuthMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (!isOAuthMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("Direct Login", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { isOAuthMode = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isOAuthMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (isOAuthMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("MCP Token", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isOAuthMode) {
                    Text(
                        text = "COROS MCP Bearer Token (Advanced)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Paste your Bearer token from COROS Model Context Protocol (mcp.coros.com).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("COROS MCP Bearer Token") },
                        placeholder = { Text("Bearer eyJhbGci...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "Direct COROS Training Hub Login",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Direct HTTPS authentication with teamapi.coros.com on-device. Stored securely in private app storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("COROS Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("COROS Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Google Calendar (Optional)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Only needed for scheduled workouts. Choose 'None (Metrics Only)' if you just want widgets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val selectedCal = calendars.find { it.id == selectedCalendarId }
                val selectedCalLabel = when {
                    selectedCalendarId == -1L -> "None (Metrics Only — Widgets & Live Sync)"
                    selectedCal != null -> "${selectedCal.displayName} (${selectedCal.accountName})"
                    else -> "None (Metrics Only — Widgets & Live Sync)"
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        onClick = { calendarExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Training Calendar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = selectedCalLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text("▾", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(
                        expanded = calendarExpanded,
                        onDismissRequest = { calendarExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("None (Metrics Only — Widgets & Live Sync)", fontWeight = FontWeight.SemiBold)
                                    Text("No training plan. Connect purely for COROS metrics widgets.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                selectedCalendarId = -1L
                                calendarExpanded = false
                            }
                        )
                        calendars.forEach { cal ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(cal.displayName, fontWeight = FontWeight.Medium)
                                        Text(
                                            cal.accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    selectedCalendarId = cal.id
                                    calendarExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Gemini AI Coach (Optional)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Optional Gemini 1.5 Pro / Flash API key for deep physiological recovery guidance. If empty, local EvoLab heuristics are used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (LapsRepository.hasCredentials(context)) {
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = {
                            LapsRepository.clearAllCredentials(context)
                            onSaved()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign Out & Reset to Sample Data")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isOAuthMode) {
                        LapsRepository.saveCorosToken(context, token)
                    } else {
                        LapsRepository.saveCorosCredentials(context, email, password)
                    }
                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                    LapsRepository.saveGeminiKey(context, geminiKey)
                    LapsRepository.setSetupCompleted(context, true)
                    onSaved()
                }
            ) {
                Text("Save & Sync")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PlanGeneratorDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onPlanPublished: () -> Unit
) {
    val context = LocalContext.current
    var selectedDistance by remember { mutableStateOf(PlanGenerator.RaceDistance.HALF_MARATHON) }
    var planWeeks by remember { mutableIntStateOf(10) }
    var isPublishing by remember { mutableStateOf(false) }

    val selectedCalId = LapsRepository.getSelectedCalendarId(context)
    val hasCalendar = selectedCalId > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Training Plan Generator",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (!hasCalendar) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "No Google Calendar Selected",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "You are currently in Metrics-Only mode. To publish workouts to your device, select a Google Calendar in Settings first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FilledTonalButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Select Calendar in Settings", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                Text(
                    text = "Build a scientifically periodized 80/20 plan tailored to your COROS threshold pace & heart rate zones, published directly to your Google Calendar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Target Distance",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlanGenerator.RaceDistance.entries.forEach { dist ->
                        FilterChip(
                            selected = selectedDistance == dist,
                            onClick = { selectedDistance = dist },
                            label = {
                                Text(
                                    text = dist.label,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    fontWeight = if (selectedDistance == dist) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Plan Duration: $planWeeks Weeks",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(6, 8, 10, 12, 16).forEach { weeks ->
                        FilterChip(
                            selected = planWeeks == weeks,
                            onClick = { planWeeks = weeks },
                            label = {
                                Text(
                                    text = "${weeks}w",
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    fontWeight = if (planWeeks == weeks) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Plan Summary",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• 4 runs per week (Tue Quality, Wed Easy, Fri Easy+Strides, Sun Long Run)\n• 80% Aerobic base / 20% Threshold & Tempo\n• Peak volume aligned to ${selectedDistance.label}\n• Auto-scheduled into Google Calendar with race day finale",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!hasCalendar) {
                        Toast.makeText(context, "Cannot publish: No Google Calendar selected", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isPublishing = true
                    Thread {
                        val cal = Calendar.getInstance().apply {
                            add(Calendar.WEEK_OF_YEAR, planWeeks)
                            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                        }
                        val raceDateMillis = cal.timeInMillis

                        PlanGenerator.generatePlan(
                            context = context,
                            calendarId = selectedCalId,
                            raceDistance = selectedDistance,
                            raceDateMillis = raceDateMillis,
                            planWeeks = planWeeks
                        )
                        (context as? ComponentActivity)?.runOnUiThread {
                            isPublishing = false
                            Toast.makeText(
                                context,
                                "Published $planWeeks-week ${selectedDistance.label} plan to Google Calendar!",
                                Toast.LENGTH_LONG
                            ).show()
                            onPlanPublished()
                        }
                    }.start()
                },
                enabled = !isPublishing && hasCalendar
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (hasCalendar) "Publish to Calendar" else "No Calendar Selected")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isPublishing
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun rememberPlanVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "CalendarMonth",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(19.0f, 4.0f)
            horizontalLineToRelative(-1.0f)
            verticalLineTo(2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(2.0f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(2.0f)
            horizontalLineTo(5.0f)
            curveTo(3.89f, 4.0f, 3.01f, 4.9f, 3.01f, 6.0f)
            lineTo(3.0f, 20.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(6.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 20.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(10.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(10.0f)
            close()
            moveTo(19.0f, 8.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(6.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(2.0f)
            close()
        }
    }.build()
}

@Composable
private fun rememberHubVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Hub",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(3.5f, 18.49f)
            lineToRelative(6.0f, -6.01f)
            lineToRelative(4.0f, 4.0f)
            lineTo(22.0f, 6.92f)
            lineToRelative(-1.41f, -1.41f)
            lineToRelative(-7.09f, 7.97f)
            lineToRelative(-4.0f, -4.0f)
            lineTo(2.0f, 16.99f)
            close()
        }
    }.build()
}

@Composable
private fun rememberWidgetsVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Widgets",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(13.0f, 13.0f)
            verticalLineToRelative(8.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-8.0f)
            horizontalLineToRelative(-8.0f)
            close()
            moveTo(3.0f, 21.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-8.0f)
            horizontalLineTo(3.0f)
            verticalLineToRelative(8.0f)
            close()
            moveTo(3.0f, 3.0f)
            verticalLineToRelative(8.0f)
            horizontalLineToRelative(8.0f)
            verticalLineTo(3.0f)
            horizontalLineTo(3.0f)
            close()
            moveTo(16.66f, 1.69f)
            lineTo(11.0f, 7.34f)
            lineTo(16.66f, 13.0f)
            lineToRelative(5.66f, -5.66f)
            lineToRelative(-5.66f, -5.65f)
            close()
        }
    }.build()
}

@Composable
private fun rememberRefreshVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Refresh",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
            curveTo(7.58f, 4.0f, 4.01f, 7.58f, 4.01f, 12.0f)
            reflectiveCurveTo(7.58f, 20.0f, 12.0f, 20.0f)
            curveTo(15.73f, 20.0f, 18.84f, 17.45f, 19.73f, 14.0f)
            horizontalLineTo(17.65f)
            curveTo(16.83f, 16.33f, 14.61f, 18.0f, 12.0f, 18.0f)
            curveTo(8.69f, 18.0f, 6.0f, 15.31f, 6.0f, 12.0f)
            reflectiveCurveTo(8.69f, 6.0f, 12.0f, 6.0f)
            curveTo(13.66f, 6.0f, 15.14f, 6.69f, 16.22f, 7.78f)
            lineTo(13.0f, 11.0f)
            horizontalLineTo(20.0f)
            verticalLineTo(4.0f)
            lineTo(17.65f, 6.35f)
            close()
        }
    }.build()
}

@Composable
private fun rememberTuneVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Tune",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(3.0f, 17.0f)
            verticalLineTo(19.0f)
            horizontalLineTo(9.0f)
            verticalLineTo(17.0f)
            horizontalLineTo(3.0f)
            close()
            moveTo(3.0f, 5.0f)
            verticalLineTo(7.0f)
            horizontalLineTo(13.0f)
            verticalLineTo(5.0f)
            horizontalLineTo(3.0f)
            close()
            moveTo(13.0f, 21.0f)
            verticalLineTo(19.0f)
            horizontalLineTo(21.0f)
            verticalLineTo(17.0f)
            horizontalLineTo(13.0f)
            verticalLineTo(15.0f)
            horizontalLineTo(11.0f)
            verticalLineTo(21.0f)
            horizontalLineTo(13.0f)
            close()
            moveTo(7.0f, 9.0f)
            verticalLineTo(11.0f)
            horizontalLineTo(3.0f)
            verticalLineTo(13.0f)
            horizontalLineTo(7.0f)
            verticalLineTo(15.0f)
            horizontalLineTo(9.0f)
            verticalLineTo(9.0f)
            horizontalLineTo(7.0f)
            close()
            moveTo(21.0f, 13.0f)
            verticalLineTo(11.0f)
            horizontalLineTo(11.0f)
            verticalLineTo(13.0f)
            horizontalLineTo(21.0f)
            close()
            moveTo(15.0f, 9.0f)
            horizontalLineTo(17.0f)
            verticalLineTo(7.0f)
            horizontalLineTo(21.0f)
            verticalLineTo(5.0f)
            horizontalLineTo(17.0f)
            verticalLineTo(3.0f)
            horizontalLineTo(15.0f)
            verticalLineTo(9.0f)
            close()
        }
    }.build()
}

@Composable
private fun rememberShieldVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Shield",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(12.0f, 1.0f)
            lineTo(3.0f, 5.0f)
            verticalLineTo(11.0f)
            curveTo(3.0f, 16.55f, 6.84f, 21.74f, 12.0f, 23.0f)
            curveTo(17.16f, 21.74f, 21.0f, 16.55f, 21.0f, 11.0f)
            verticalLineTo(5.0f)
            lineTo(12.0f, 1.0f)
            close()
        }
    }.build()
}
