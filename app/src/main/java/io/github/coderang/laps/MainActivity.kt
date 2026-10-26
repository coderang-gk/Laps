package io.github.coderang.laps

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen {
    MAIN,
    SETTINGS,
    PLAN_RACE_HUB,
    ONBOARDING
}

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
            var appTheme by remember { mutableStateOf(LapsRepository.getAppTheme(context)) }
            val colorScheme = ExpressiveTheme.getComposeColorScheme(context, appTheme)

            val trigger = oauthRefreshTrigger
            var payload by remember(trigger) { mutableStateOf(LapsRepository.getCachedPayload(context)) }
            var isSyncing by remember { mutableStateOf(false) }
            var selectedSection by remember { mutableIntStateOf(0) } // 0 = Training Hub, 1 = Widgets
            var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Sculptural, 2 = Cards
            val hasCredentials = remember(trigger) { LapsRepository.hasCredentials(context) }
            var currentScreen by remember(trigger) {
                mutableStateOf(
                    if (!hasCredentials && !LapsRepository.isSetupCompleted(context)) AppScreen.ONBOARDING
                    else AppScreen.MAIN
                )
            }
            var initialPlanHubTab by remember { mutableIntStateOf(0) } // 0 = Schedule, 1 = Strategy, 2 = Customizer
            var isResolvingMissed by remember { mutableStateOf(false) }
            var selectedRunForDetail by remember {
                mutableStateOf(if (shouldOpenRunDetailFromIntent) payload.lastRun else null)
            }
            val coroutineScope = rememberCoroutineScope()

            BackHandler(enabled = currentScreen != AppScreen.MAIN) {
                currentScreen = AppScreen.MAIN
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
                        payload = LapsRepository.getCachedPayload(context)
                        isSyncing = false
                    }
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                when (currentScreen) {
                    AppScreen.MAIN -> {
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
                                coroutineScope.launch(Dispatchers.IO) {
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

                                    withContext(Dispatchers.Main) {
                                        isResolvingMissed = false
                                        val updated = payload.copy(missedWorkout = null)
                                        payload = updated
                                        LapsRepository.setCachedPayload(updated)
                                        LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context)

                                        val msg = if (action == "SHIFT") {
                                            "Shifted to ${targetDate.ifBlank { "upcoming day" }} in Device Calendar"
                                        } else {
                                            "Workout marked [SKIPPED] in Device Calendar"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            isResolvingMissed = isResolvingMissed,
                            onSyncClicked = {
                                isSyncing = true
                                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) { res ->
                                    runOnUiThread {
                                        val (email, pwd) = LapsRepository.getCorosCredentials(context)
                                        val token = LapsRepository.getCorosToken(context)
                                        if (token.isBlank() && (email.isBlank() || pwd.isBlank())) {
                                            LapsRepository.resetSamplePayload(context)
                                        }
                                        payload = LapsRepository.getCachedPayload(context)
                                        isSyncing = false
                                        res.onSuccess {
                                            Toast.makeText(context, "Synced directly with COROS & Device Calendar", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, "Using local cache: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onPlanClicked = {
                                initialPlanHubTab = 0
                                currentScreen = AppScreen.PLAN_RACE_HUB
                            },
                            onWorkoutClicked = {
                                initialPlanHubTab = 0
                                currentScreen = AppScreen.PLAN_RACE_HUB
                            },
                            onRaceClicked = {
                                initialPlanHubTab = 1
                                currentScreen = AppScreen.PLAN_RACE_HUB
                            },
                            onSettingsClicked = { currentScreen = AppScreen.SETTINGS },
                            onConnectAccountClicked = { currentScreen = AppScreen.ONBOARDING },
                            onPinWidget = { item ->
                                LapsRepository.setStyleForTile(context, item.slot, item.styleIndex)
                                LapsWidgetUpdateHelper.renderAllFromCache(context)
                                pinWidgetToLauncher(item)
                            }
                        )

                        selectedRunForDetail?.let { run ->
                            RunDetailModalBottomSheet(
                                run = run,
                                onDismiss = { selectedRunForDetail = null }
                            )
                        }
                    }
                    AppScreen.ONBOARDING -> {
                        LapsOnboardingScreen(
                            onConnected = {
                                currentScreen = AppScreen.MAIN
                                isSyncing = true
                                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) {
                                    runOnUiThread {
                                        payload = LapsRepository.getCachedPayload(context)
                                        isSyncing = false
                                    }
                                }
                            },
                            onExploreDemo = {
                                LapsRepository.setSetupCompleted(context, true)
                                currentScreen = AppScreen.MAIN
                            }
                        )
                    }
                    AppScreen.SETTINGS -> {
                        LapsSettingsScreen(
                            payload = payload,
                            currentTheme = appTheme,
                            onThemeChange = { newTheme ->
                                appTheme = newTheme
                                LapsRepository.saveAppTheme(context, newTheme)
                                LapsWidgetUpdateHelper.renderAllFromCache(context)
                            },
                            onNavigateBack = { currentScreen = AppScreen.MAIN },
                            onSaved = {
                                if (!LapsRepository.hasCredentials(context)) {
                                    currentScreen = AppScreen.ONBOARDING
                                } else {
                                    currentScreen = AppScreen.MAIN
                                }
                                isSyncing = true
                                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) { res ->
                                    runOnUiThread {
                                        payload = LapsRepository.getCachedPayload(context)
                                        isSyncing = false
                                        res.onSuccess {
                                            Toast.makeText(context, "Logged in & Synced directly with COROS!", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, "Sync note: ${err.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onOpenPlanHub = {
                                initialPlanHubTab = 0
                                currentScreen = AppScreen.PLAN_RACE_HUB
                            }
                        )
                    }
                    AppScreen.PLAN_RACE_HUB -> {
                        PlanRaceHubScreen(
                            payload = payload,
                            initialTab = initialPlanHubTab,
                            onNavigateBack = { currentScreen = AppScreen.MAIN },
                            onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                            onPlanPublished = {
                                isSyncing = true
                                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context) {
                                    runOnUiThread {
                                        payload = LapsRepository.getCachedPayload(context)
                                        isSyncing = false
                                    }
                                }
                            }
                        )
                    }
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
    onWorkoutClicked: () -> Unit = onPlanClicked,
    onRaceClicked: () -> Unit = onPlanClicked,
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
                onWorkoutClicked = onWorkoutClicked,
                onRaceClicked = onRaceClicked,
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
private fun TrainingHubLoadingSkeleton(contentPadding: PaddingValues) {
    val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    val highlightColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim - 400f, translateAnim - 400f),
        end = Offset(translateAnim, translateAnim)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Skeleton Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Top status pills skeleton
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Middle: Ring & Load Stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Two bottom pods (Countdown & Next Run)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(88.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(88.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(shimmerBrush)
                    )
                }
            }
        }

        // 2. Weekly Mileage Progress Skeleton Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .width(110.dp)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(150.dp)
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(shimmerBrush)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 7 Day Capsules
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val heights = listOf(60.dp, 84.dp, 72.dp, 40.dp, 68.dp, 40.dp, 92.dp)
                    for (h in heights) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(h)
                                .clip(RoundedCornerShape(14.dp))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }

        // 3. Latest Workout Skeleton Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .height(22.dp)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(14.dp))
                // 4 metrics grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
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
    onWorkoutClicked: () -> Unit = onPlanClicked,
    onRaceClicked: () -> Unit = onPlanClicked,
    onBrowseWidgetsClicked: () -> Unit = {}
) {
    val runs = payload.recentRuns.ifEmpty { listOf(payload.lastRun) }
    val latest = runs.first()
    val context = LocalContext.current
    val isConnected = remember(payload) { LapsRepository.hasCredentials(context) }

    // When account is connected but real data has not been synced yet, display skeleton placeholder
    if (isConnected && !payload.isRealData) {
        TrainingHubLoadingSkeleton(contentPadding = contentPadding)
        return
    }

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
            AthleteSummaryHeroCard(
                payload = payload,
                isSyncing = isSyncing,
                onWorkoutClicked = onWorkoutClicked,
                onRaceClicked = onRaceClicked
            )
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
    val activePlan = payload.activePlan
    val rc = payload.raceCountdown
    val nowMillis = System.currentTimeMillis()

    // Determine plan state for current week
    val isUpcomingPlan = activePlan != null && activePlan.startDateMillis > nowMillis
    val isPlanActiveThisWeek = activePlan != null && !isUpcomingPlan && wm.plannedKm > 0.0

    val weekProgress = if (wm.plannedKm > 0) (wm.completionPct / 100f).coerceIn(0f, 1f) else 0f

    // Training days: if plan is active, include planned and actual. If pre-plan/metrics, only show actual logged runs.
    val trainingDays = remember(wm.days, isPlanActiveThisWeek) {
        if (isPlanActiveThisWeek) {
            wm.days.filter { it.plannedKm > 0.0 || it.actualKm > 0.0 }
        } else {
            wm.days.filter { it.actualKm > 0.0 }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "WEEKLY MILEAGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (isUpcomingPlan) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "PRE-PLAN",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", wm.actualKm),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPlanActiveThisWeek && wm.plannedKm > 0) "/ ${String.format(Locale.US, "%.0f", wm.plannedKm)} km" else "km logged",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = if (isPlanActiveThisWeek && wm.plannedKm > 0) {
                                "${wm.completionPct}% Done"
                            } else {
                                "${wm.runsCompleted} Runs Logged"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (isPlanActiveThisWeek && wm.plannedKm > 0) {
                            "${String.format(Locale.US, "%.1f", wm.remainingKm)} km remaining"
                        } else if (isUpcomingPlan) {
                            val startDay = SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(activePlan.startDateMillis))
                            "Plan starts $startDay"
                        } else {
                            "${wm.avgPace} · ${wm.totalElevationM}m elev"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Weekly Capsule Progress Bar (or Base Volume indicator if pre-plan/metrics)
            val fillFraction = if (isPlanActiveThisWeek && wm.plannedKm > 0) {
                weekProgress.coerceAtLeast(0.06f)
            } else {
                (wm.actualKm.toFloat() / 35f).coerceIn(0.08f, 1f)
            }
            val animatedFillFraction by animateFloatAsState(
                targetValue = fillFraction,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "weekly_capsule_progress_spring"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFillFraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            if (isPlanActiveThisWeek) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Expressive 7-Day Mini Bar Chart
            var selectedDayIndex by remember { mutableStateOf<Int?>(null) }
            val maxDayKm = remember(wm.days) {
                (wm.days.maxOfOrNull { maxOf(it.actualKm, it.plannedKm) } ?: 10.0).coerceAtLeast(6.0)
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DAILY BREAKDOWN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        val activeDay = wm.days.find { it.index == selectedDayIndex }
                        if (activeDay != null) {
                            Text(
                                text = "${activeDay.dayName}: ${if (activeDay.actualKm > 0) String.format(Locale.US, "%.1f km", activeDay.actualKm) else if (activeDay.plannedKm > 0) String.format(Locale.US, "%.0f km planned", activeDay.plannedKm) else "Rest"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "Tap day for details",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        wm.days.forEach { day ->
                            val isSelected = selectedDayIndex == day.index
                            val barTargetFraction = if (day.actualKm > 0) {
                                (day.actualKm / maxDayKm).toFloat().coerceIn(0.12f, 1f)
                            } else if (isPlanActiveThisWeek && day.plannedKm > 0) {
                                (day.plannedKm / maxDayKm).toFloat().coerceIn(0.12f, 1f)
                            } else {
                                0f
                            }
                            val animatedBarFrac by animateFloatAsState(
                                targetValue = barTargetFraction,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "day_bar_spring_${day.index}"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selectedDayIndex = if (selectedDayIndex == day.index) null else day.index
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                                        ),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (day.actualKm > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(animatedBarFrac)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (day.isToday) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                                )
                                        )
                                    } else if (isPlanActiveThisWeek && day.plannedKm > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(animatedBarFrac)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .padding(bottom = 6.dp)
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                if (day.isToday) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = day.dayLetter,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = day.dayLetter,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    if (selectedDayIndex != null) {
                        val sel = wm.days.find { it.index == selectedDayIndex }
                        if (sel != null && (sel.actualKm > 0 || sel.plannedKm > 0 || sel.workoutType.isNotBlank())) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${sel.dayName} · ${sel.workoutType}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = if (sel.actualKm > 0) "${String.format(Locale.US, "%.1f", sel.actualKm)} km" else "${String.format(Locale.US, "%.0f", sel.plannedKm)} km planned",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Training Days Matrix
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
                                text = if (isUpcomingPlan) "UPCOMING TRAINING PLAN" else "NO RUNS LOGGED THIS WEEK",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isUpcomingPlan) {
                                    if (activePlan.customRaceName.isNotBlank()) {
                                        "${activePlan.customRaceName} (${activePlan.planWeeks}w · ${activePlan.distanceLabel}) starts next week"
                                    } else {
                                        "${activePlan.planWeeks}-Week ${activePlan.distanceLabel} starts next week"
                                    }
                                } else {
                                    "Build an 80/20 Plan (1-Tap)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        FilledTonalButton(
                            onClick = { onPlanClicked?.invoke() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (isUpcomingPlan) "View Plan" else "Create Plan")
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trainingDays.forEach { day ->
                        val isCompleted = day.actualKm > 0
                        val dayPct = if (isPlanActiveThisWeek && day.plannedKm > 0) {
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
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
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

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isCompleted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = if (isCompleted) {
                                                if (isPlanActiveThisWeek && day.plannedKm > 0) {
                                                    "${String.format(Locale.US, "%.1f", day.actualKm)} / ${String.format(Locale.US, "%.0f", day.plannedKm)}K"
                                                } else {
                                                    "${String.format(Locale.US, "%.1f", day.actualKm)} km"
                                                }
                                            } else {
                                                "${String.format(Locale.US, "%.0f", day.plannedKm)} km"
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }

                                if (isPlanActiveThisWeek) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
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

            // SINGLE FOOTER / BANNER AT THE BOTTOM (rendered exactly once, outside trainingDays loop)
            Spacer(modifier = Modifier.height(14.dp))
            if (isUpcomingPlan) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlanClicked?.invoke() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val bannerTitle = if (activePlan.customRaceName.isNotBlank()) {
                                "🚀 ${activePlan.customRaceName.uppercase()} (${activePlan.planWeeks}W ${activePlan.distanceLabel.uppercase()})"
                            } else {
                                "🚀 ${activePlan.planWeeks}-WEEK ${activePlan.distanceLabel.uppercase()} PLAN"
                            }
                            Text(
                                text = bannerTitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val startFmt = SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(activePlan.startDateMillis))
                            val daysUntil = ((activePlan.startDateMillis - nowMillis) / 86400000L + 1).toInt().coerceAtLeast(1)
                            Text(
                                text = "Kicks off $startFmt · Week 1 in $daysUntil days",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Switch Plan",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (isPlanActiveThisWeek) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Week ${rc.currentWeek} of ${activePlan.planWeeks} · ${activePlan.distanceLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Switch Plan",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onPlanClicked?.invoke() }
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Free Running Mode · COROS Sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "+ Create 80/20 Plan",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onPlanClicked?.invoke() }
                    )
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
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = run.dayLabel.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = run.name,
                        style = MaterialTheme.typography.titleMedium,
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
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = run.aiVerdictHeadline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Analysis →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
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
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
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

            // Comprehensive Run Metrics 6-Pack Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RunStatChip(
                    modifier = Modifier.weight(1f),
                    label = "Avg Pace",
                    value = run.avgPace,
                    sub = if (run.bestKmPace.isNotBlank() && run.bestKmPace != "--:--") "Best ${run.bestKmPace}" else ""
                )
                RunStatChip(
                    modifier = Modifier.weight(1f),
                    label = "Heart Rate",
                    value = "${run.avgHr} bpm",
                    sub = if (run.maxHr > 0) "Max ${run.maxHr} bpm" else ""
                )
                RunStatChip(
                    modifier = Modifier.weight(1f),
                    label = "Cadence",
                    value = if (run.cadence > 0) "${run.cadence} spm" else "--",
                    sub = if (run.maxCadence > 0) "Max ${run.maxCadence}" else ""
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RunStatChip(
                    modifier = Modifier.weight(1f),
                    label = "Training Load",
                    value = if (run.trainingLoad > 0) "${run.trainingLoad}" else "--",
                    sub = if (run.aerobicTe > 0.0) "${String.format(Locale.US, "%.1f", run.aerobicTe)} Aero TE" else ""
                )
                RunStatChip(
                    modifier = Modifier.weight(1f),
                    label = "Elevation",
                    value = "${run.elevGainM} m",
                    sub = if (run.caloriesKcal > 0) "${run.caloriesKcal} kcal" else ""
                )
                RunStatChip(
                    modifier = Modifier.weight(1f),
                    label = "Duration",
                    value = "${run.durationMinutes} min",
                    sub = if (run.strideLengthCm > 0) "${run.strideLengthCm}cm stride" else ""
                )
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
private fun AthleteSummaryHeroCard(
    payload: LapsPayload,
    isSyncing: Boolean,
    onWorkoutClicked: (() -> Unit)? = null,
    onRaceClicked: (() -> Unit)? = null
) {
    val wm = payload.weeklyMileage
    val tl = payload.trainingLoad
    val rc = payload.raceCountdown
    val nw = payload.nextWorkout

    val infiniteTransition = rememberInfiniteTransition(label = "hero_pulse")
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 7f,
        targetValue = if (isSyncing) 11f else 9.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSyncing) 500 else 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_size"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = if (isSyncing) 0.35f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSyncing) 500 else 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

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
                            modifier = Modifier.size(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(pulseSize.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
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
                    val isUpcomingPlan = payload.activePlan != null && payload.activePlan.startDateMillis > System.currentTimeMillis()
                    val statusText = if (rc.daysLeft > 0) {
                        if (isUpcomingPlan || rc.currentWeek == 0) {
                            "🏁 Starts Mon · ${rc.daysLeft}d"
                        } else {
                            "🏁 ${rc.daysLeft}d to Race"
                        }
                    } else {
                        "COROS · ${tl.statusBadge}"
                    }
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3-Column M3 Tonal Metric Pods
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isUpcomingPlan = payload.activePlan != null && payload.activePlan.startDateMillis > System.currentTimeMillis()
                val wkSub = if (!isUpcomingPlan && wm.plannedKm > 0) {
                    "/ ${wm.plannedKm.toInt()}K (${wm.completionPct}%)"
                } else {
                    "${wm.runsCompleted} runs"
                }
                MetricPod(
                    modifier = Modifier.weight(1f),
                    label = if (!isUpcomingPlan && rc.currentWeek > 0 && wm.plannedKm > 0) "Week ${rc.currentWeek}" else "Weekly Dist",
                    value = String.format(Locale.US, "%.1fK", wm.actualKm),
                    sub = wkSub,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 12.dp, bottomEnd = 12.dp)
                )
                val statusDot = when (tl.statusBadge.uppercase(Locale.US)) {
                    "PRODUCTIVE", "OPTIMAL" -> Color(0xFF10B981) // emerald
                    "OVERREACHING", "STRAINED" -> Color(0xFFF59E0B) // amber
                    "FATIGUED", "EXHAUSTED" -> Color(0xFFEF4444) // coral
                    else -> null
                }
                MetricPod(
                    modifier = Modifier.weight(1f),
                    label = "Acute Load",
                    value = "${tl.acuteTl}",
                    sub = tl.statusBadge.lowercase().replaceFirstChar { it.uppercase() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    statusDotColor = statusDot
                )
                MetricPod(
                    modifier = Modifier.weight(1f),
                    label = "Threshold",
                    value = rc.currentLtsp.replace(" /km", ""),
                    sub = "${tl.lthr} bpm",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 24.dp, bottomEnd = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Next Workout Strip / COROS Metrics Live Sync Strip
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = onWorkoutClicked != null) { onWorkoutClicked?.invoke() },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                if (nw.targetDistanceKm > 0 || (nw.title.isNotBlank() && nw.title != "No Scheduled Workout")) {
                    val accentColor = when {
                        nw.title.contains("Interval", ignoreCase = true) || nw.title.contains("Tempo", ignoreCase = true) -> Color(0xFFF59E0B)
                        nw.title.contains("Long Run", ignoreCase = true) -> Color(0xFF8B5CF6)
                        nw.title.contains("Easy", ignoreCase = true) || nw.title.contains("Recovery", ignoreCase = true) -> Color(0xFF10B981)
                        nw.title.contains("Race", ignoreCase = true) -> Color(0xFFEF4444)
                        else -> MaterialTheme.colorScheme.primary
                    }

                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "NEXT WORKOUT · ${nw.dayBadge.uppercase(Locale.US)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = nw.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${nw.targetPace}  ·  ${nw.targetHrZone}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
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
                            Icon(
                                imageVector = rememberChevronRightVector(),
                                contentDescription = "Open Plan Hub",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
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

            if (payload.activePlan != null || rc.daysLeft > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = onRaceClicked != null) { onRaceClicked?.invoke() },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TARGET RACE · ${rc.daysLeft} DAYS AWAY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = rc.raceName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val isUpcomingPlan = payload.activePlan != null && payload.activePlan.startDateMillis > System.currentTimeMillis()
                            val subtitleText = if (isUpcomingPlan || rc.currentWeek == 0) {
                                "${rc.goalPace} target  ·  ${rc.phase}"
                            } else {
                                "Week ${rc.currentWeek} of ${rc.totalWeeks}  ·  ${rc.phase}"
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${rc.daysLeft}d",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                imageVector = rememberChevronRightVector(),
                                contentDescription = "Open Plan Hub",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
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
    shape: RoundedCornerShape,
    statusDotColor: Color? = null
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (statusDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = contentColor,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.88f),
                fontWeight = FontWeight.Medium,
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
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val currentTheme = if (isDark) "DARK" else "LIGHT"
    val bitmap = remember(item.styleIndex, payload, currentTheme) {
        ExpressiveWidgetRenderer.renderTileBitmap(
            context = context,
            tileType = item.slot,
            payload = payload,
            widthPx = 520,
            heightPx = 520,
            styleIndexOverride = item.styleIndex,
            forcedTheme = currentTheme
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

@Composable
private fun LapsAppLogo(
    modifier: Modifier = Modifier,
    isDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
) {
    val logoRes = if (isDark) R.drawable.ic_laps_logo else R.drawable.ic_laps_logo_light
    Image(
        painter = painterResource(id = logoRes),
        contentDescription = "Laps Logo",
        modifier = modifier.clip(RoundedCornerShape(16.dp))
    )
}

@Composable
private fun AuthMethodSegmentedTabs(
    isOAuthMode: Boolean,
    onSelectOAuth: () -> Unit,
    onSelectDirect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // OAuth Tab (50% equal width, centered)
            Surface(
                onClick = onSelectOAuth,
                shape = RoundedCornerShape(10.dp),
                color = if (isOAuthMode) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (isOAuthMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OAuth",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isOAuthMode) FontWeight.Bold else FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isOAuthMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = if (isOAuthMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "RECOMMENDED",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Direct Login Tab (50% equal width, centered)
            Surface(
                onClick = onSelectDirect,
                shape = RoundedCornerShape(10.dp),
                color = if (!isOAuthMode) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (!isOAuthMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Direct Login",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (!isOAuthMode) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LapsOnboardingScreen(
    onConnected: () -> Unit,
    onExploreDemo: () -> Unit
) {
    val context = LocalContext.current
    var isOAuthMode by remember { mutableStateOf(true) }
    var selectedRegion by remember { mutableStateOf(LapsRepository.getCorosRegion(context)) }
    var token by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var calendars by remember { mutableStateOf(CalendarSyncManager.getAvailableCalendars(context)) }
    var selectedCalendarId by remember { mutableLongStateOf(-1L) }
    var calendarExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    TextButton(
                        onClick = onExploreDemo,
                        enabled = !isLoggingIn
                    ) {
                        Text("Demo Mode", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            LapsAppLogo(modifier = Modifier.size(72.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Welcome to Laps",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Athletic Command Center & Device Calendar Companion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Privacy Shield Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zero Cloud • On-Device Sandboxing",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Tokens and credentials stay 100% inside your device sandbox. Connect password-free via official COROS OAuth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // COROS Account Card
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "COROS ACCOUNT INTEGRATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    AuthMethodSegmentedTabs(
                        isOAuthMode = isOAuthMode,
                        onSelectOAuth = { isOAuthMode = true; errorMessage = null },
                        onSelectDirect = { isOAuthMode = false; errorMessage = null }
                    )

                    // Region Selector (Direct Login only)
                    if (!isOAuthMode) {
                        Column {
                            Text(
                                text = "COROS Server Region",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "teamapi.coros.com" to "Global / US",
                                    "teameuapi.coros.com" to "Europe",
                                    "teamsgapi.coros.com" to "Singapore / Asia",
                                    "teamcnapi.coros.com" to "China"
                                ).forEach { (reg, label) ->
                                    FilterChip(
                                        selected = selectedRegion == reg,
                                        onClick = { selectedRegion = reg },
                                        label = {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (selectedRegion == reg) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isOAuthMode) {
                        Text(
                            text = "Official Web Sign-In",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Connect password-free via official COROS OAuth. Your browser opens to COROS's official sign-in page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                isLoggingIn = true
                                errorMessage = null
                                coroutineScope.launch {
                                    val res = io.github.coderang.laps.coros.CorosOAuthManager.authenticate(context, selectedRegion)
                                    if (res.isFailure) {
                                        withContext(Dispatchers.Main) {
                                            isLoggingIn = false
                                            val err = res.exceptionOrNull()
                                            val msg = err?.message ?: err?.javaClass?.simpleName ?: "OAuth flow failed"
                                            android.util.Log.e("LapsAuth", "OAuth failed: $msg", err)
                                            errorMessage = "OAuth failed: $msg"
                                            Toast.makeText(context, "OAuth failed: $msg", Toast.LENGTH_LONG).show()
                                        }
                                        return@launch
                                    }

                                    val tokenStr = res.getOrThrow()
                                    android.util.Log.i("LapsAuth", "Verifying OAuth token on background IO dispatcher...")
                                    val client = io.github.coderang.laps.coros.CorosApiClient(region = selectedRegion)
                                    val authRes = withContext(Dispatchers.IO) {
                                        client.authenticateWithToken(tokenStr)
                                    }

                                    withContext(Dispatchers.Main) {
                                        isLoggingIn = false
                                        authRes.onSuccess {
                                            token = tokenStr
                                            android.util.Log.i("LapsAuth", "COROS OAuth verification successful!")
                                            LapsRepository.saveCorosToken(context, tokenStr, region = selectedRegion)
                                            LapsRepository.saveCorosRegion(context, selectedRegion)
                                            LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                            LapsRepository.setSetupCompleted(context, true)
                                            Toast.makeText(context, "Connected via OAuth!", Toast.LENGTH_SHORT).show()
                                            onConnected()
                                        }.onFailure { err ->
                                            val msg = err.message ?: err.javaClass.simpleName
                                            android.util.Log.e("LapsAuth", "Token verification error: $msg", err)
                                            errorMessage = "Token verification error: $msg"
                                            Toast.makeText(context, "Verification error: $msg", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isLoggingIn
                        ) {
                            if (isLoggingIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Sign In with Browser", fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; errorMessage = null },
                            label = { Text("Or paste Access Token manually") },
                            placeholder = { Text("Bearer eyJhbGci...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            label = { Text("COROS Email") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            label = { Text("COROS Password") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Target Calendar Card
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TARGET CALENDAR (OPTIONAL)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                CalendarSyncManager.requestSync(context)
                                calendars = CalendarSyncManager.getAvailableCalendars(context)
                                Toast.makeText(context, "Refreshed calendars from device", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = rememberRefreshVector(),
                                contentDescription = "Refresh Calendars",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        text = "Workouts generated by Laps publish directly to your chosen device calendar for seamless watch and phone sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val selectedCal = calendars.find { it.id == selectedCalendarId }
                    val selectedCalLabel = when {
                        selectedCalendarId == -1L -> "None (Metrics Only — Widgets & Live Sync)"
                        selectedCal != null -> "${selectedCal.displayName} (${selectedCal.accountName})"
                        else -> "None (Metrics Only — Widgets & Live Sync)"
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = {
                                calendars = CalendarSyncManager.getAvailableCalendars(context)
                                calendarExpanded = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
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
                                Icon(
                                    imageVector = rememberArrowDownVector(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = calendarExpanded,
                            onDismissRequest = { calendarExpanded = false },
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .widthIn(max = 320.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = "None (Metrics Only — Widgets & Live Sync)",
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "No training plan. Connect purely for COROS metrics widgets.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = cal.displayName,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = cal.accountName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedCalendarId = cal.id
                                        calendarExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = rememberRefreshVector(),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Refresh Calendars from Device",
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Pulls newly created device or synced calendars",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    CalendarSyncManager.requestSync(context)
                                    calendars = CalendarSyncManager.getAvailableCalendars(context)
                                    Toast.makeText(context, "Refreshed calendars from device", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            // Confirm / Direct Sign-in Button
            Button(
                onClick = {
                    if (isOAuthMode) {
                        if (token.isBlank()) {
                            errorMessage = "Please paste your COROS Bearer token or sign in via browser above."
                            return@Button
                        }
                        isLoggingIn = true
                        errorMessage = null
                        coroutineScope.launch(Dispatchers.IO) {
                            val client = io.github.coderang.laps.coros.CorosApiClient(region = selectedRegion)
                            val authRes = client.authenticateWithToken(token.trim())
                            withContext(Dispatchers.Main) {
                                isLoggingIn = false
                                authRes.onSuccess {
                                    LapsRepository.saveCorosToken(context, token.trim(), region = selectedRegion)
                                    LapsRepository.saveCorosRegion(context, selectedRegion)
                                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                    LapsRepository.setSetupCompleted(context, true)
                                    Toast.makeText(context, "Connected via COROS OAuth!", Toast.LENGTH_SHORT).show()
                                    onConnected()
                                }.onFailure { err ->
                                    errorMessage = "OAuth Token Error: ${err.message ?: "Invalid token"}"
                                }
                            }
                        }
                    } else {
                        if (email.isBlank() || password.isBlank()) {
                            errorMessage = "Please enter your COROS email and password."
                            return@Button
                        }
                        isLoggingIn = true
                        errorMessage = null
                        coroutineScope.launch(Dispatchers.IO) {
                            val client = io.github.coderang.laps.coros.CorosApiClient(region = selectedRegion)
                            val loginRes = client.login(email.trim(), password.trim())
                            withContext(Dispatchers.Main) {
                                isLoggingIn = false
                                loginRes.onSuccess {
                                    LapsRepository.saveCorosCredentials(context, email.trim(), password.trim(), region = selectedRegion)
                                    LapsRepository.saveCorosRegion(context, selectedRegion)
                                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                    LapsRepository.setSetupCompleted(context, true)
                                    Toast.makeText(context, "Connected to COROS Training Hub!", Toast.LENGTH_SHORT).show()
                                    onConnected()
                                }.onFailure { err ->
                                    errorMessage = "COROS Login failed: ${err.message ?: "Invalid credentials"}"
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoggingIn
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Connect & Sync", fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = onExploreDemo,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoggingIn
            ) {
                Text("Explore in Demo Mode (Try Widgets)", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LapsSettingsScreen(
    payload: LapsPayload,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenPlanHub: () -> Unit
) {
    val context = LocalContext.current
    val (savedEmail, savedPassword) = remember { LapsRepository.getCorosCredentials(context) }
    val savedToken = remember { LapsRepository.getCorosToken(context) }
    val savedRegion = remember { LapsRepository.getCorosRegion(context) }
    val savedCalId = remember { LapsRepository.getSelectedCalendarId(context) }
    val savedGemini = remember { LapsRepository.getGeminiKey(context) }
    val activePlan = payload.activePlan ?: remember { LapsRepository.getActivePlan(context) }

    var token by remember { mutableStateOf(savedToken) }
    var selectedRegion by remember { mutableStateOf(savedRegion) }
    var email by remember { mutableStateOf(savedEmail) }
    var password by remember { mutableStateOf(savedPassword) }
    var showPassword by remember { mutableStateOf(false) }
    var geminiKey by remember { mutableStateOf(savedGemini) }
    var selectedCalendarId by remember { mutableLongStateOf(savedCalId) }
    var isOAuthMode by remember { mutableStateOf(savedToken.isNotBlank() || savedEmail.isBlank()) }
    var isSaving by remember { mutableStateOf(false) }

    var calendars by remember { mutableStateOf(CalendarSyncManager.getAvailableCalendars(context)) }
    var calendarExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = rememberArrowBackVector(),
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (isSaving) return@ExtendedFloatingActionButton
                    if (isOAuthMode && token.isNotBlank()) {
                        isSaving = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val client = io.github.coderang.laps.coros.CorosApiClient(region = selectedRegion)
                            val authRes = client.authenticateWithToken(token.trim())
                            withContext(Dispatchers.Main) {
                                isSaving = false
                                authRes.onSuccess {
                                    LapsRepository.saveCorosToken(context, token.trim(), region = selectedRegion)
                                    LapsRepository.saveCorosRegion(context, selectedRegion)
                                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                    LapsRepository.saveGeminiKey(context, geminiKey)
                                    LapsRepository.setSetupCompleted(context, true)
                                    onSaved()
                                }.onFailure { err ->
                                    Toast.makeText(context, "OAuth Token Error: ${err.message ?: "Invalid token"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else if (!isOAuthMode && email.isNotBlank() && password.isNotBlank()) {
                        isSaving = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val client = io.github.coderang.laps.coros.CorosApiClient(region = selectedRegion)
                            val loginRes = client.login(email.trim(), password.trim())
                            withContext(Dispatchers.Main) {
                                isSaving = false
                                loginRes.onSuccess {
                                    LapsRepository.saveCorosCredentials(context, email.trim(), password.trim(), region = selectedRegion)
                                    LapsRepository.saveCorosRegion(context, selectedRegion)
                                    LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                    LapsRepository.saveGeminiKey(context, geminiKey)
                                    LapsRepository.setSetupCompleted(context, true)
                                    onSaved()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Login failed: ${err.message ?: "Invalid credentials"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        if (isOAuthMode) {
                            LapsRepository.saveCorosToken(context, token, region = selectedRegion)
                        } else {
                            LapsRepository.saveCorosCredentials(context, email, password, region = selectedRegion)
                        }
                        LapsRepository.saveCorosRegion(context, selectedRegion)
                        LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                        LapsRepository.saveGeminiKey(context, geminiKey)
                        LapsRepository.setSetupCompleted(context, true)
                        onSaved()
                    }
                },
                icon = {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            imageVector = rememberCheckVector(),
                            contentDescription = "Save & Sync",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                text = {
                    Text(
                        text = if (isSaving) "Saving..." else "Save & Sync",
                        fontWeight = FontWeight.Bold
                    )
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Privacy Shield
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = rememberShieldVector(),
                                contentDescription = "Privacy Shield",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zero Cloud • On-Device Sandboxing",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Your COROS login, workouts, and biometrics stay strictly on this device. No intermediate servers or analytics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // COROS Account Card
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "COROS ACCOUNT INTEGRATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Connect directly with COROS Training Hub for physiological metrics, acute load, and threshold pace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    AuthMethodSegmentedTabs(
                        isOAuthMode = isOAuthMode,
                        onSelectOAuth = { isOAuthMode = true },
                        onSelectDirect = { isOAuthMode = false }
                    )

                    // Region Selector (Direct Login only)
                    if (!isOAuthMode) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "COROS Server Region",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "teamapi.coros.com" to "Global / US",
                                "teameuapi.coros.com" to "Europe",
                                "teamsgapi.coros.com" to "Singapore / Asia",
                                "teamcnapi.coros.com" to "China"
                            ).forEach { (reg, label) ->
                                FilterChip(
                                    selected = selectedRegion == reg,
                                    onClick = { selectedRegion = reg },
                                    label = {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selectedRegion == reg) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isOAuthMode) {
                        Button(
                            onClick = {
                                if (isSaving) return@Button
                                isSaving = true
                                coroutineScope.launch {
                                    val res = io.github.coderang.laps.coros.CorosOAuthManager.authenticate(context, selectedRegion)
                                    if (res.isFailure) {
                                        withContext(Dispatchers.Main) {
                                            isSaving = false
                                            val err = res.exceptionOrNull()
                                            val msg = err?.message ?: err?.javaClass?.simpleName ?: "OAuth flow failed"
                                            android.util.Log.e("LapsAuth", "OAuth failed: $msg", err)
                                            Toast.makeText(context, "OAuth failed: $msg", Toast.LENGTH_LONG).show()
                                        }
                                        return@launch
                                    }

                                    val tokenStr = res.getOrThrow()
                                    android.util.Log.i("LapsAuth", "Verifying OAuth token on background IO dispatcher...")
                                    val client = io.github.coderang.laps.coros.CorosApiClient(region = selectedRegion)
                                    val authRes = withContext(Dispatchers.IO) {
                                        client.authenticateWithToken(tokenStr)
                                    }

                                    withContext(Dispatchers.Main) {
                                        isSaving = false
                                        authRes.onSuccess {
                                            token = tokenStr
                                            android.util.Log.i("LapsAuth", "COROS OAuth verification successful!")
                                            LapsRepository.saveCorosToken(context, tokenStr, region = selectedRegion)
                                            LapsRepository.saveCorosRegion(context, selectedRegion)
                                            LapsRepository.saveSelectedCalendarId(context, selectedCalendarId)
                                            LapsRepository.saveGeminiKey(context, geminiKey)
                                            LapsRepository.setSetupCompleted(context, true)
                                            Toast.makeText(context, "Connected via OAuth!", Toast.LENGTH_SHORT).show()
                                            onSaved()
                                        }.onFailure { err ->
                                            val msg = err.message ?: err.javaClass.simpleName
                                            android.util.Log.e("LapsAuth", "Verification error: $msg", err)
                                            Toast.makeText(context, "Verification error: $msg", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSaving
                        ) {
                            Text("Sign In with Browser", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Or paste Access Token manually") },
                            placeholder = { Text("Bearer eyJhbGci...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("COROS Email") },
                            placeholder = { Text("athlete@example.com") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("COROS Password") },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Device Calendar Card
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DEVICE CALENDAR SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                CalendarSyncManager.requestSync(context)
                                calendars = CalendarSyncManager.getAvailableCalendars(context)
                                Toast.makeText(context, "Refreshed calendars from device", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = rememberRefreshVector(),
                                contentDescription = "Refresh Calendars",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = "Workouts generated by Laps publish directly to your chosen device calendar for seamless watch and phone sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val selectedCal = calendars.find { it.id == selectedCalendarId }
                    val selectedCalLabel = when {
                        selectedCalendarId == -1L -> "None (Metrics Only — Widgets & Live Sync)"
                        selectedCal != null -> "${selectedCal.displayName} (${selectedCal.accountName})"
                        else -> "None (Metrics Only — Widgets & Live Sync)"
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = {
                                calendars = CalendarSyncManager.getAvailableCalendars(context)
                                calendarExpanded = true
                            },
                            shape = RoundedCornerShape(12.dp),
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
                                        text = "Target Calendar",
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
                                Icon(
                                    imageVector = rememberArrowDownVector(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = calendarExpanded,
                            onDismissRequest = { calendarExpanded = false },
                            modifier = Modifier.heightIn(max = 280.dp).widthIn(max = 320.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("None (Metrics Only)", fontWeight = FontWeight.SemiBold)
                                        Text("No calendar sync. Widget metrics only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            Text(cal.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            }

            // Active Training Plan Card
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ACTIVE TRAINING PLAN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (activePlan != null) {
                        val planName = if (activePlan.customRaceName.isNotBlank()) activePlan.customRaceName else activePlan.distanceLabel
                        Text(
                            text = "$planName (${activePlan.planWeeks}w · ${activePlan.distanceLabel})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onOpenPlanHub,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Manage in Hub")
                            }
                            FilledTonalButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        if (selectedCalendarId > 0L) {
                                            CalendarSyncManager.clearPlanEvents(context, selectedCalendarId)
                                        }
                                        LapsRepository.clearActivePlan(context)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Plan cleared (Metrics-Only mode)", Toast.LENGTH_SHORT).show()
                                            onSaved()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Clear Plan")
                            }
                        }
                    } else {
                        Text(
                            text = "No active training plan. App is operating in Metrics-Only mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = onOpenPlanHub,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Build Training Plan")
                        }
                    }
                }
            }

            // Appearance & Theme Card
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "APPEARANCE & THEME",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Switch between system auto-adaptation, dedicated light mode, or stealth dark mode, and customize your home screen launcher icon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "App Theme",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 3-way Segmented Theme Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "SYSTEM" to "System",
                            "LIGHT" to "Light",
                            "DARK" to "Dark"
                        ).forEach { (themeKey, label) ->
                            val isSelected = currentTheme == themeKey
                            Surface(
                                onClick = { onThemeChange(themeKey) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Gemini AI Coach Card (Optional)
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GEMINI AI COACH (OPTIONAL)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Optional Gemini 1.5 Pro / Flash API key for deep physiological recovery guidance. If empty, local EvoLab heuristics are used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Sign Out & Reset
            if (LapsRepository.hasCredentials(context)) {
                OutlinedButton(
                    onClick = {
                        LapsRepository.clearAllCredentials(context)
                        onSaved()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out & Reset to Sample Data")
                }
            }

            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanRaceHubScreen(
    payload: LapsPayload,
    initialTab: Int = 0,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlanPublished: () -> Unit
) {
    val context = LocalContext.current
    val activePlan = payload.activePlan
    val rc = payload.raceCountdown
    val nw = payload.nextWorkout

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    // Customizer / Plan Generator State
    var selectedDistance by remember {
        mutableStateOf(
            PlanGenerator.RaceDistance.entries.find { it.label.equals(activePlan?.distanceLabel, ignoreCase = true) }
                ?: PlanGenerator.RaceDistance.HALF_MARATHON
        )
    }
    var planWeeks by remember { mutableIntStateOf(activePlan?.planWeeks ?: 10) }
    var customRaceName by remember { mutableStateOf(activePlan?.customRaceName ?: "") }
    var clearPreviousWorkouts by remember { mutableStateOf(true) }
    var isPublishing by remember { mutableStateOf(false) }

    val selectedCalId = LapsRepository.getSelectedCalendarId(context)
    val hasCalendar = selectedCalId > 0L
    val coroutineScope = rememberCoroutineScope()

    // Multi-week workouts for display (from real device calendar events first, then generator fallback)
    val workoutsByWeek = remember(activePlan, selectedCalId) {
        if (activePlan == null) {
            emptyMap<Int, List<PlanGenerator.PlanWorkoutItem>>()
        } else if (activePlan.calendarId > 0L) {
            val calEvents = CalendarSyncManager.getPlannedWorkouts(
                context = context,
                calendarId = activePlan.calendarId,
                startMillis = activePlan.startDateMillis - (7 * 86400000L),
                endMillis = activePlan.raceDateMillis + 86400000L
            )
            if (calEvents.isNotEmpty()) {
                val list = calEvents.map { ev ->
                    val weekNum = Regex("W(\\d+)").find(ev.title)?.groupValues?.get(1)?.toIntOrNull()
                        ?: activePlan.planWeeks
                    val dayOffset = ((ev.startMillis - activePlan.startDateMillis) / 86400000L).toInt().coerceAtLeast(0)
                    Pair(
                        weekNum,
                        PlanGenerator.PlanWorkoutItem(
                            dayOffset = dayOffset,
                            title = ev.title,
                            description = ev.description,
                            targetDistanceKm = ev.targetDistanceKm,
                            workoutType = ev.workoutType,
                            dateMillis = ev.startMillis
                        )
                    )
                }
                list.groupBy({ it.first }, { it.second })
            } else {
                val distEnum = PlanGenerator.RaceDistance.entries.find { it.label.equals(activePlan.distanceLabel, ignoreCase = true) }
                    ?: PlanGenerator.RaceDistance.HALF_MARATHON
                val all = PlanGenerator.generatePlan(
                    calendarId = -1L,
                    raceDistance = distEnum,
                    startDateMillis = activePlan.startDateMillis,
                    planWeeks = activePlan.planWeeks,
                    customRaceName = activePlan.customRaceName
                )
                all.groupBy { (it.dayOffset / 7) + 1 }
            }
        } else {
            val distEnum = PlanGenerator.RaceDistance.entries.find { it.label.equals(activePlan.distanceLabel, ignoreCase = true) }
                ?: PlanGenerator.RaceDistance.HALF_MARATHON
            val all = PlanGenerator.generatePlan(
                calendarId = -1L,
                raceDistance = distEnum,
                startDateMillis = activePlan.startDateMillis,
                planWeeks = activePlan.planWeeks,
                customRaceName = activePlan.customRaceName
            )
            all.groupBy { (it.dayOffset / 7) + 1 }
        }
    }

    val defaultExpanded = if (rc.currentWeek > 0) rc.currentWeek else 1
    var expandedWeeks by remember(activePlan) { mutableStateOf(setOf(defaultExpanded)) }

    val pageTitle = if (activePlan != null && activePlan.customRaceName.isNotBlank()) {
        activePlan.customRaceName
    } else if (activePlan != null) {
        "${activePlan.distanceLabel} Plan"
    } else {
        "Training Plan & Race Hub"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = rememberArrowBackVector(),
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedTab != 2,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = rememberEditVector(),
                            contentDescription = if (activePlan != null) "Edit Plan" else "Build Plan"
                        )
                    },
                    text = {
                        Text(
                            text = if (activePlan != null) "Edit Plan" else "Build Plan",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tabs
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Schedule", maxLines = 1, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Race Strategy", maxLines = 1, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Customizer", maxLines = 1, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                )
            }

            when (selectedTab) {
                0 -> {
                    // Schedule Tab (Multi-Week Collapsible Accordion + Next Workout)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (activePlan == null) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "No Active Training Plan",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "You are currently in Metrics-Only mode. Tap 'Build 80/20 Plan' to create a periodized plan tailored to your COROS EvoLab profile.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = { selectedTab = 2 },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Build 80/20 Plan")
                                        }
                                    }
                                }
                            }
                        } else {
                            // Hero: Next Scheduled Workout
                            item {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "NEXT SCHEDULED WORKOUT · ${nw.dayBadge.uppercase(Locale.US)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = if (nw.targetDistanceKm > 0) "${nw.targetDistanceKm.toInt()} km" else "Planned",
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = nw.title,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Target: ${nw.targetPace}  ·  HR Zone: ${nw.targetHrZone}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            // Section header
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${activePlan.planWeeks}-WEEK CALENDAR SCHEDULE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Tap week to expand",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Multi-week Collapsible Weeks
                            items(activePlan.planWeeks) { index ->
                                val week = index + 1
                                val isExpanded = expandedWeeks.contains(week)
                                val weekWorkouts = workoutsByWeek[week] ?: emptyList()
                                val weekTotalKm = weekWorkouts.sumOf { it.targetDistanceKm }
                                val isCurrentWeek = week == rc.currentWeek
                                val isRaceWeek = week == activePlan.planWeeks
                                val isPeakWeek = week == (activePlan.planWeeks * 0.7).toInt()
                                val isTaperWeek = week == activePlan.planWeeks - 1

                                val weekTag = when {
                                    isRaceWeek -> "🏁 RACE WEEK"
                                    isTaperWeek -> "TAPER"
                                    isPeakWeek -> "PEAK VOLUME"
                                    isCurrentWeek -> "CURRENT"
                                    else -> "BUILD"
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isCurrentWeek) {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable {
                                            expandedWeeks = if (isExpanded) {
                                                expandedWeeks - week
                                            } else {
                                                expandedWeeks + week
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = if (isCurrentWeek || isRaceWeek) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                                                ) {
                                                    Text(
                                                        text = "W$week",
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isCurrentWeek || isRaceWeek) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Text(
                                                        text = "Week $week of ${activePlan.planWeeks}",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${String.format(Locale.US, "%.1f", weekTotalKm)} km planned · 4 runs",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = when {
                                                        isRaceWeek -> MaterialTheme.colorScheme.primary
                                                        isCurrentWeek -> MaterialTheme.colorScheme.secondaryContainer
                                                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                                    }
                                                ) {
                                                    Text(
                                                        text = weekTag,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = when {
                                                            isRaceWeek -> MaterialTheme.colorScheme.onPrimary
                                                            isCurrentWeek -> MaterialTheme.colorScheme.onSecondaryContainer
                                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        }
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = if (isExpanded) rememberArrowUpVector() else rememberArrowDownVector(),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // Expanded Workout Rows
                                        if (isExpanded) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                for (workout in weekWorkouts) {
                                                    val isRaceEvent = workout.workoutType.equals("Race", ignoreCase = true)
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (isRaceEvent) {
                                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                        } else {
                                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                                        },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(12.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = workout.title,
                                                                    style = MaterialTheme.typography.titleSmall,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )
                                                                if (workout.description.isNotBlank()) {
                                                                    Text(
                                                                        text = workout.description,
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 2,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                            }
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = if (isRaceEvent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
                                                            ) {
                                                                Text(
                                                                    text = "${workout.targetDistanceKm.toInt()} km",
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = if (isRaceEvent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
                    }
                }
                1 -> {
                    // Race Strategy & Readiness Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Countdown Hero
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = "TARGET RACE ROADMAP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = rc.raceName,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Target Date: ${rc.raceDate}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text(
                                                text = "DAYS LEFT",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "${rc.daysLeft}",
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (rc.currentWeek > 0) "Week ${rc.currentWeek} of ${rc.totalWeeks}" else "Starts next week",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text(
                                                text = "READINESS",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            Text(
                                                text = rc.readinessTag,
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp
                                            )
                                            Text(
                                                text = "COROS EvoLab Status",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Pace & Fitness Matrix
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "TARGET PACE VS CURRENT FITNESS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Goal Finish Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(rc.goalTime, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Goal Target Pace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(rc.goalPace, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Current Threshold Pace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(rc.currentLtsp, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Lactate Threshold HR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${rc.currentLthr} bpm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        // Periodization Macrocycle Roadmap
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "PERIODIZATION MACROCYCLE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Current: ${rc.phase}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                listOf(
                                    "Phase 1 · Aerobic Base Foundation" to "Builds mitochondrial density, capillary network, and joint durability at Zone 2.",
                                    "Phase 2 · Threshold & Tempo Overload" to "Raises lactate clearing ceiling and locks in race-specific cruising speed.",
                                    "Phase 3 · Peak Volume" to "Longest long runs (up to 18-32 km) with target-pace progression blocks.",
                                    "Phase 4 · Taper & Race Readiness" to "Volume reduction by 40-55% while maintaining cadence and sharpening legs."
                                ).forEach { (pTitle, pDesc) ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(text = pTitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            Text(text = pDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        // Race Day Strategy & Fueling
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "RACE DAY FUELING & EXECUTION STRATEGY",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "• Fueling Cadence: Ingest an isotonic energy gel every 40-45 minutes with 150ml water.\n• Controlled First 5K: Run 5-8 seconds per km slower than goal pace to preserve glycogen.\n• Cadence Target: Keep cadence steady between 175-182 spm across hills.\n• Negative Split: Surge steadily after 75% distance to pass fatigued runners.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // Customizer / Plan Generator Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (!hasCalendar) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "No Device Calendar Selected",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "You are currently in Metrics-Only mode. To publish workouts to your device, select a Device Calendar in Settings.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    FilledTonalButton(
                                        onClick = onOpenSettings,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Select Calendar in Settings")
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Build a scientifically periodized 80/20 plan tailored to your COROS threshold pace & heart rate zones, published directly to your Device Calendar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Race / Event Name
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "RACE / EVENT NAME (OPTIONAL)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customRaceName,
                                    onValueChange = { customRaceName = it },
                                    placeholder = { Text("e.g. Hyderabad HM, Mumbai Marathon") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Target Distance
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "TARGET DISTANCE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                                    fontWeight = if (selectedDistance == dist) FontWeight.SemiBold else FontWeight.Normal
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Plan Duration
                        val startMonday = remember { PlanGenerator.getUpcomingMonday() }
                        val raceSunday = remember(planWeeks) {
                            (startMonday.clone() as Calendar).apply {
                                add(Calendar.DAY_OF_YEAR, (planWeeks - 1) * 7 + 6)
                            }
                        }
                        val daysToRace = remember(raceSunday) {
                            val nowCal = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            val rCal = (raceSunday.clone() as Calendar).apply {
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            ((rCal.timeInMillis - nowCal.timeInMillis) / 86400000L).toInt().coerceAtLeast(0)
                        }
                        val sdfDate = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.US) }

                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "PLAN DURATION: $planWeeks WEEKS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                                    fontWeight = if (planWeeks == weeks) FontWeight.SemiBold else FontWeight.Normal
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Target Race Day Card
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val rTag = if (customRaceName.isNotBlank()) "🏁 ${customRaceName.trim().uppercase()}" else "🏁 TARGET RACE DAY"
                                    Text(
                                        text = rTag,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = sdfDate.format(raceSunday.time),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "$daysToRace days away",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }

                        // Clean workouts checkbox
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { clearPreviousWorkouts = !clearPreviousWorkouts }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = clearPreviousWorkouts,
                                onCheckedChange = { clearPreviousWorkouts = it }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text("Clean up previous Laps plan workouts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Removes old plan events from calendar to avoid duplicates", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // Action button
                        Button(
                            onClick = {
                                if (!hasCalendar) {
                                    Toast.makeText(context, "Cannot publish: No Device Calendar selected", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isPublishing = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (clearPreviousWorkouts) {
                                        CalendarSyncManager.clearPlanEvents(context, selectedCalId)
                                    }
                                    val startCal = PlanGenerator.getUpcomingMonday()
                                    val rSunday = (startCal.clone() as Calendar).apply {
                                        add(Calendar.DAY_OF_YEAR, (planWeeks - 1) * 7 + 6)
                                    }
                                    val trimmedName = customRaceName.trim()
                                    PlanGenerator.generatePlan(
                                        context = context,
                                        calendarId = selectedCalId,
                                        raceDistance = selectedDistance,
                                        startDateMillis = startCal.timeInMillis,
                                        planWeeks = planWeeks,
                                        skipPastEvents = true,
                                        customRaceName = trimmedName
                                    )
                                    LapsRepository.saveActivePlan(
                                        context,
                                        ActivePlanInfo(
                                            distanceLabel = selectedDistance.label,
                                            planWeeks = planWeeks,
                                            startDateMillis = startCal.timeInMillis,
                                            raceDateMillis = rSunday.timeInMillis,
                                            calendarId = selectedCalId,
                                            customRaceName = trimmedName
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        isPublishing = false
                                        val displayName = if (trimmedName.isNotBlank()) trimmedName else selectedDistance.label
                                        Toast.makeText(
                                            context,
                                            "Published $planWeeks-week $displayName plan starting ${SimpleDateFormat("MMM d", Locale.US).format(startCal.time)}!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        selectedTab = 0
                                        onPlanPublished()
                                    }
                                }
                            },
                            enabled = !isPublishing && hasCalendar,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (isPublishing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Publishing to Calendar…")
                            } else {
                                Text(if (activePlan != null) "Update & Sync Plan" else "Publish Plan to Calendar", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (activePlan != null) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        if (selectedCalId > 0L) {
                                            CalendarSyncManager.clearPlanEvents(context, selectedCalId)
                                        }
                                        LapsRepository.clearActivePlan(context)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Plan cleared (Metrics-Only mode)", Toast.LENGTH_SHORT).show()
                                            onPlanPublished()
                                            onNavigateBack()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Clear Plan (Switch to Metrics-Only)")
                            }
                        }

                        Spacer(modifier = Modifier.height(88.dp))
                    }
                }
            }
        }
    }
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
private fun rememberEditVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Edit",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(3f, 17.25f)
            verticalLineTo(21f)
            horizontalLineTo(6.75f)
            lineTo(17.81f, 9.94f)
            lineTo(14.06f, 6.19f)
            lineTo(3f, 17.25f)
            close()
            moveTo(20.71f, 7.04f)
            curveTo(21.1f, 6.65f, 21.1f, 6.02f, 20.71f, 5.63f)
            lineTo(18.37f, 3.29f)
            curveTo(17.98f, 2.9f, 17.35f, 2.9f, 16.96f, 3.29f)
            lineTo(15.13f, 5.12f)
            lineTo(18.88f, 8.87f)
            lineTo(20.71f, 7.04f)
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

@Composable
private fun rememberArrowDownVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "ArrowDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(7.41f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineTo(16.59f, 8.59f)
            lineTo(18.0f, 10.0f)
            lineTo(12.0f, 16.0f)
            lineTo(6.0f, 10.0f)
            close()
        }
    }.build()
}

@Composable
private fun rememberArrowUpVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "ArrowUp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(7.41f, 15.41f)
            lineTo(12.0f, 10.83f)
            lineTo(16.59f, 15.41f)
            lineTo(18.0f, 14.0f)
            lineTo(12.0f, 8.0f)
            lineTo(6.0f, 14.0f)
            close()
        }
    }.build()
}

@Composable
private fun rememberArrowBackVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(20.0f, 11.0f)
            lineTo(7.83f, 11.0f)
            lineTo(13.42f, 5.41f)
            lineTo(12.0f, 4.0f)
            lineTo(4.0f, 12.0f)
            lineTo(12.0f, 20.0f)
            lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13.0f)
            lineTo(20.0f, 13.0f)
            close()
        }
    }.build()
}

@Composable
private fun rememberCheckVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "Check",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(9.0f, 16.17f)
            lineTo(4.83f, 12.0f)
            lineTo(3.41f, 13.41f)
            lineTo(9.0f, 19.0f)
            lineTo(21.0f, 7.0f)
            lineTo(19.59f, 5.59f)
            close()
        }
    }.build()
}

@Composable
private fun rememberChevronRightVector(): ImageVector = remember {
    ImageVector.Builder(
        name = "ChevronRight",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(10.0f, 6.0f)
            lineTo(8.59f, 7.41f)
            lineTo(13.17f, 12.0f)
            lineTo(8.59f, 16.59f)
            lineTo(10.0f, 18.0f)
            lineTo(16.0f, 12.0f)
            close()
        }
    }.build()
}
