package com.dearminder.fossume

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// Material3's baseline palette - used when dynamic color is unavailable (API < 31).
private val AppLightColorScheme = lightColorScheme(
    primary = Color(0xFF2E6B4F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB1F1CE), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D9), onSecondaryContainer = Color(0xFF0A1F16),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF5), onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF5), onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDBE5DD), onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973)
)

private val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFF95D5B2), onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF115238), onPrimaryContainer = Color(0xFFB1F1CE),
    secondary = Color(0xFFB3CCBE), onSecondary = Color(0xFF1F352A),
    secondaryContainer = Color(0xFF354B40), onSecondaryContainer = Color(0xFFCFE9D9),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DE),
    surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DE),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF8A938C)
)

private enum class Tab { HOME, SETTINGS }

class MainActivity : ComponentActivity() {

    // Bumped on every resume (and permission result), so permission state is re-checked after
    // the user comes back from a system settings screen or dialog.
    private val resumeTick = mutableIntStateOf(0)

    private var permissionAskedAt = 0L
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            resumeTick.intValue++
            // Denied for good, the system answers at once without showing a dialog - its settings
            // screen is then the only way left. A real "deny" from the user is left alone.
            val instant = SystemClock.elapsedRealtime() - permissionAskedAt < INSTANT_DENIAL_MS
            if (!granted && instant && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openAppNotificationSettings(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(resumeTick = resumeTick.intValue, requestNotifications = ::requestNotifications)
        }
    }

    override fun onResume() {
        super.onResume()
        rebindNotificationListenerIfNeeded(this)
        resumeTick.intValue++
    }

    private fun requestNotifications() {
        if (!isPostNotificationsPermissionGranted(this)) {
            permissionAskedAt = SystemClock.elapsedRealtime()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below Android 13 there's no permission - notifications were switched off in Settings.
            openAppNotificationSettings(this)
        }
    }
}

private const val INSTANT_DENIAL_MS = 500L

@Composable
private fun App(resumeTick: Int, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    // Sliders call this on every frame of a drag; unchanged values aren't worth a disk write.
    val update: (AppSettings) -> Unit = { if (it != settings) { settings = it; it.save(context) } }

    val dark = isSystemInDarkTheme()
    val colorScheme = remember(dark) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> AppDarkColorScheme
            else -> AppLightColorScheme
        }
    }

    // Status/navigation bar icons follow the theme.
    LaunchedEffect(dark) {
        val window = (context as ComponentActivity).window
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // Once per app start; Updates itself keeps it to once a day and only with the setting on.
    LaunchedEffect(Unit) { Updates.checkIfDue(context, settings) }

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    BackHandler(enabled = tab == Tab.SETTINGS) { tab = Tab.HOME }

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.HOME,
                        onClick = { tab = Tab.HOME },
                        icon = { Icon(painterResource(R.drawable.ic_home), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_home)) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) }
                    )
                }
            }
        ) { padding ->
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(SCREEN_ANIMATION_MS)) togetherWith fadeOut(tween(SCREEN_ANIMATION_MS / 2)) },
                label = "tab",
                modifier = Modifier.padding(padding)
            ) { target ->
                when (target) {
                    Tab.HOME -> HomeScreen(settings, resumeTick, onOpenSettings = { tab = Tab.SETTINGS })
                    Tab.SETTINGS -> SettingsScreen(settings, update, resumeTick, requestNotifications)
                }
            }
        }
    }
}

private const val SCREEN_ANIMATION_MS = 250

// ---------- Home ----------

@Composable
private fun HomeScreen(settings: AppSettings, resumeTick: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val connected by VolumeKeyService.connected.collectAsState()
    val accessibility = remember(resumeTick, connected) { isAccessibilityEnabled(context) }
    val notificationAccess = remember(resumeTick) { isNotificationAccessGranted(context) }
    val detected by Players.detected.collectAsState()
    val playing by Players.playing.collectAsState()
    val update by Updates.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)
            )
        }
        item {
            StatusCard(
                accessibility = accessibility,
                // Enabled in Settings but never bound means the system gave up on it.
                serviceStuck = accessibility && !connected,
                notificationAccess = notificationAccess,
                activation = settings.activation,
                onFix = onOpenSettings
            )
        }
        (update as? UpdateState.Available)?.let { available ->
            item {
                HintCard(
                    title = stringResource(R.string.update_available_title),
                    text = stringResource(R.string.update_available, available.release.version),
                    action = stringResource(R.string.update_download),
                    onAction = { Updates.download(context, available.release) }
                )
            }
        }
        item {
            SectionHeader(stringResource(R.string.home_players), painterResource(R.drawable.ic_music_note))
            Text(
                stringResource(R.string.home_players_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (detected.isEmpty()) {
            item {
                Text(
                    stringResource(if (notificationAccess) R.string.home_players_empty else R.string.home_players_no_access),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(detected, key = { it }) { pkg ->
            PlayerRow(pkg, isPlaying = pkg in playing, isDisabled = pkg in settings.disabledPlayers)
        }
    }
}

private val StatusGreenLight = Color(0xFF2E7D32)
private val StatusGreenDark = Color(0xFF81C784)
private val StatusRedLight = Color(0xFFC62828)
private val StatusRedDark = Color(0xFFEF9A9A)

@Composable
private fun statusColor(ok: Boolean): Color {
    val dark = isSystemInDarkTheme()
    return when {
        ok && dark -> StatusGreenDark
        ok -> StatusGreenLight
        dark -> StatusRedDark
        else -> StatusRedLight
    }
}

@Composable
private fun StatusCard(
    accessibility: Boolean,
    serviceStuck: Boolean,
    notificationAccess: Boolean,
    activation: Activation,
    onFix: () -> Unit
) {
    val working = accessibility && !serviceStuck && notificationAccess
    val color = statusColor(working)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.home_status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                Text(
                    stringResource(if (working) R.string.status_working else R.string.status_not_working),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
            if (working) {
                Text(
                    stringResource(if (activation == Activation.LOCKED) R.string.status_active_locked else R.string.status_active_always),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            } else {
                val missing = buildList {
                    if (!accessibility) add(stringResource(R.string.status_missing_accessibility))
                    if (serviceStuck) add(stringResource(R.string.status_service_stuck))
                    if (!notificationAccess) add(stringResource(R.string.status_missing_notification_access))
                }
                missing.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                Button(onClick = onFix) { Text(stringResource(R.string.status_fix)) }
            }
        }
    }
}

@Composable
private fun PlayerRow(pkg: String, isPlaying: Boolean, isDisabled: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).alpha(if (isDisabled) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val info = rememberAppInfo(pkg)
            AppIcon(info.icon, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(info.label, style = MaterialTheme.typography.titleMedium)
                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                isDisabled -> StatusBadge(stringResource(R.string.player_disabled), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                isPlaying -> StatusBadge(stringResource(R.string.player_playing), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private class AppInfo(val label: String, val icon: ImageBitmap?)

private const val APP_ICON_PX = 96

/** Rendered once per process: both tabs show the same players, and switching tabs recreates them. */
private val appInfoCache = ConcurrentHashMap<String, AppInfo>()

/** Label and icon of an installed app (an uninstalled one just shows its package name), loaded off the main thread. */
@Composable
private fun rememberAppInfo(pkg: String): AppInfo {
    val context = LocalContext.current
    val info by produceState(appInfoCache[pkg] ?: AppInfo(pkg, null), pkg) {
        if (!appInfoCache.containsKey(pkg)) value = withContext(Dispatchers.IO) { appInfoCache.getOrPut(pkg) { loadAppInfo(context, pkg) } }
    }
    return info
}

private fun loadAppInfo(context: Context, pkg: String): AppInfo = try {
    val pm = context.packageManager
    val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
    }
    AppInfo(pm.getApplicationLabel(app).toString(), pm.getApplicationIcon(app).toBitmap(APP_ICON_PX, APP_ICON_PX).asImageBitmap())
} catch (e: Exception) {
    AppInfo(pkg, null)
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: Dp) {
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, modifier = Modifier.size(size).padding(6.dp))
    }
}

// ---------- Settings ----------

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    resumeTick: Int,
    requestNotifications: () -> Unit
) {
    val context = LocalContext.current
    val connected by VolumeKeyService.connected.collectAsState()
    val accessibility = remember(resumeTick, connected) { isAccessibilityEnabled(context) }
    val notificationAccess = remember(resumeTick) { isNotificationAccessGranted(context) }
    val batteryIgnored = remember(resumeTick) { isBatteryOptimizationIgnored(context) }
    val notificationsAllowed = remember(resumeTick) { areNotificationsAllowed(context) }
    val seen by Players.detected.collectAsState()
    var saved by remember { mutableStateOf(SavedPlayers.load(context)) }
    // Players seen this session only become saved ones here.
    LaunchedEffect(seen) {
        val merged = SavedPlayers.merge(saved, seen)
        if (merged != saved) {
            saved = merged
            SavedPlayers.save(context, merged)
        }
    }
    var addingCombo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp)
        )

        // ---------- Permissions ----------
        SectionHeader(stringResource(R.string.section_permissions), painterResource(R.drawable.ic_shield))
        PermissionSummary(granted = listOf(accessibility, notificationAccess).count { it }, total = 2)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermissionCard(
                icon = painterResource(R.drawable.ic_accessibility),
                title = stringResource(R.string.perm_accessibility),
                description = stringResource(R.string.perm_accessibility_helper),
                granted = accessibility,
                required = true,
                buttonLabel = stringResource(R.string.perm_accessibility_button),
                onClick = { openAccessibilitySettings(context) }
            )
            PermissionCard(
                icon = painterResource(R.drawable.ic_notifications),
                title = stringResource(R.string.perm_notification_access),
                description = stringResource(R.string.perm_notification_access_helper),
                granted = notificationAccess,
                required = true,
                buttonLabel = stringResource(R.string.perm_notification_access_button),
                onClick = { openNotificationAccessSettings(context) }
            )
            if ((!accessibility || !notificationAccess) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                HintCard(
                    title = stringResource(R.string.perm_restricted_title),
                    text = stringResource(R.string.perm_restricted_hint),
                    action = stringResource(R.string.perm_restricted_open),
                    onAction = { openAppDetails(context) }
                )
            }
            PermissionCard(
                icon = painterResource(R.drawable.ic_battery),
                title = stringResource(R.string.perm_battery),
                description = stringResource(R.string.perm_battery_helper),
                granted = batteryIgnored,
                required = false,
                buttonLabel = stringResource(R.string.perm_battery_button),
                onClick = { openBatteryOptimizationSettings(context) }
            )
        }

        // ---------- Notifications ----------
        SectionHeader(stringResource(R.string.section_notifications), painterResource(R.drawable.ic_notifications))
        SwitchRow(
            label = stringResource(R.string.status_notification_label),
            helper = stringResource(R.string.status_notification_helper),
            checked = settings.statusNotification,
            onCheckedChange = {
                onChange(settings.copy(statusNotification = it))
                if (it && !notificationsAllowed) requestNotifications()
            }
        )
        if (settings.statusNotification) {
            if (!notificationsAllowed) {
                HintCard(
                    title = null,
                    text = stringResource(R.string.status_notification_no_permission),
                    action = stringResource(R.string.status_notification_allow),
                    onAction = requestNotifications
                )
            }
            OutlinedButton(
                onClick = { openStatusNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = BIG_BUTTON_HEIGHT)
            ) {
                Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_notification_hide))
            }
        }

        // ---------- Activation ----------
        SectionHeader(stringResource(R.string.section_activation), painterResource(R.drawable.ic_smartphone))
        RadioRow(stringResource(R.string.activation_locked), settings.activation == Activation.LOCKED) {
            onChange(settings.copy(activation = Activation.LOCKED))
        }
        RadioRow(stringResource(R.string.activation_always), settings.activation == Activation.ALWAYS) {
            onChange(settings.copy(activation = Activation.ALWAYS))
        }
        SwitchRow(
            label = stringResource(R.string.screen_off_keys_label),
            helper = stringResource(R.string.screen_off_keys_helper),
            checked = settings.screenOffKeys,
            onCheckedChange = { onChange(settings.copy(screenOffKeys = it)) }
        )

        // ---------- Vibration ----------
        SectionHeader(stringResource(R.string.section_vibration), painterResource(R.drawable.ic_vibration))
        TickSlider(
            value = settings.vibration,
            range = AppSettings.MIN_VIBRATION..AppSettings.MAX_VIBRATION,
            sliderRange = 0f..100f,
            steps = 9,
            labels = listOf(
                stringResource(R.string.vibration_off),
                stringResource(R.string.vibration_normal),
                stringResource(R.string.vibration_strong)
            ),
            onValue = { onChange(settings.copy(vibration = it)) },
            // A sample buzz once the thumb is let go, so the strength can be felt.
            onFinished = { vibrate(context, it) }
        )
        NumberField(
            label = stringResource(R.string.vibration_custom),
            value = settings.vibration,
            range = AppSettings.MIN_VIBRATION..AppSettings.MAX_VIBRATION,
            onValue = { onChange(settings.copy(vibration = it)) }
        )

        // ---------- Long press time ----------
        SectionHeader(stringResource(R.string.section_long_press), painterResource(R.drawable.ic_timer))
        TickSlider(
            value = settings.longPressMs,
            range = AppSettings.MIN_LONG_PRESS_MS..AppSettings.MAX_LONG_PRESS_MS,
            // Ticks every 100 ms; the lowest one stands for the 10 ms minimum.
            sliderRange = 0f..2000f,
            steps = 19,
            labels = listOf("${AppSettings.MIN_LONG_PRESS_MS}", "1000", "${AppSettings.MAX_LONG_PRESS_MS}"),
            onValue = { onChange(settings.copy(longPressMs = it)) }
        )
        NumberField(
            label = stringResource(R.string.ms_custom),
            value = settings.longPressMs,
            range = AppSettings.MIN_LONG_PRESS_MS..AppSettings.MAX_LONG_PRESS_MS,
            onValue = { onChange(settings.copy(longPressMs = it)) }
        )

        // ---------- Gap between clicks ----------
        SectionHeader(stringResource(R.string.section_click_gap), painterResource(R.drawable.ic_timer))
        Text(
            stringResource(R.string.click_gap_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TickSlider(
            value = settings.clickGapMs,
            range = AppSettings.MIN_CLICK_GAP_MS..AppSettings.MAX_CLICK_GAP_MS,
            sliderRange = 100f..1000f,
            steps = 17,
            labels = listOf("${AppSettings.MIN_CLICK_GAP_MS}", "550", "${AppSettings.MAX_CLICK_GAP_MS}"),
            onValue = { onChange(settings.copy(clickGapMs = it)) }
        )
        NumberField(
            label = stringResource(R.string.ms_custom),
            value = settings.clickGapMs,
            range = AppSettings.MIN_CLICK_GAP_MS..AppSettings.MAX_CLICK_GAP_MS,
            onValue = { onChange(settings.copy(clickGapMs = it)) }
        )

        // ---------- Players ----------
        SectionHeader(stringResource(R.string.actions_players), painterResource(R.drawable.ic_music_note))
        Text(
            stringResource(if (saved.isEmpty()) R.string.players_saved_empty else R.string.players_saved_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        saved.forEach { pkg -> key(pkg) {
            val info = rememberAppInfo(pkg)
            val enabled = pkg !in settings.disabledPlayers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onChange(settings.copy(disabledPlayers = if (enabled) settings.disabledPlayers + pkg else settings.disabledPlayers - pkg))
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIcon(info.icon, 32.dp)
                Text(info.label, modifier = Modifier.weight(1f))
                // A player seen this session would only come straight back.
                if (pkg !in seen) {
                    IconButton(onClick = {
                        saved = saved - pkg
                        SavedPlayers.save(context, saved)
                        if (pkg in settings.disabledPlayers) onChange(settings.copy(disabledPlayers = settings.disabledPlayers - pkg))
                    }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.cd_forget_player))
                    }
                }
                Checkbox(checked = enabled, onCheckedChange = null)
            }
        } }

        // ---------- Actions ----------
        SectionHeader(stringResource(R.string.section_actions), painterResource(R.drawable.ic_tune))
        SubHeader(stringResource(R.string.actions_long), topPadding = 0.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LongTrigger.entries.forEach { trigger ->
                BindingCard(
                    title = {
                        Icon(painterResource(triggerIcon(trigger)), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(triggerLabel(trigger)), style = MaterialTheme.typography.titleSmall)
                    },
                    binding = settings.longAction(trigger),
                    onChange = { onChange(settings.copy(longActions = settings.longActions + (trigger to it))) }
                )
            }
        }
        if (settings.longAction(LongTrigger.BOTH).enabled) {
            // Android sees the keys before any accessibility service, so this can't be blocked here.
            Text(
                stringResource(R.string.both_keys_shortcut_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = { openAccessibilityList(context) }) {
                Text(stringResource(R.string.both_keys_shortcut_open))
            }
        }

        SubHeader(stringResource(R.string.actions_short))
        Text(
            stringResource(R.string.actions_short_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.shortCombos.forEach { combo ->
                key(combo.id) {
                    BindingCard(
                        title = { ComboKeys(combo.keys) },
                        binding = combo.binding,
                        onChange = { b ->
                            onChange(settings.copy(shortCombos = settings.shortCombos.map { if (it.id == combo.id) it.copy(binding = b) else it }))
                        },
                        onDelete = { onChange(settings.copy(shortCombos = settings.shortCombos.filter { it.id != combo.id })) }
                    )
                }
            }
        }
        FilledTonalButton(onClick = { addingCombo = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.combo_add))
        }

        // ---------- Updates ----------
        SectionHeader(stringResource(R.string.section_updates), painterResource(R.drawable.ic_update))
        SwitchRow(
            label = stringResource(R.string.update_auto_label),
            helper = stringResource(R.string.update_auto_helper),
            checked = settings.autoUpdateCheck,
            onCheckedChange = { onChange(settings.copy(autoUpdateCheck = it)) }
        )
        UpdateCard()

        Spacer(Modifier.height(32.dp))
        AboutLine()
        Spacer(Modifier.height(24.dp))
    }

    if (addingCombo) {
        AddComboDialog(
            existing = settings.shortCombos.map { it.id }.toSet(),
            onDismiss = { addingCombo = false },
            onAdd = {
                addingCombo = false
                onChange(settings.copy(shortCombos = settings.shortCombos + it))
            }
        )
    }
}

private fun triggerLabel(trigger: LongTrigger) = when (trigger) {
    LongTrigger.UP -> R.string.trigger_up
    LongTrigger.DOWN -> R.string.trigger_down
    LongTrigger.BOTH -> R.string.trigger_both
}

private fun triggerIcon(trigger: LongTrigger) = when (trigger) {
    LongTrigger.UP -> R.drawable.ic_volume_up
    LongTrigger.DOWN -> R.drawable.ic_volume_down
    LongTrigger.BOTH -> R.drawable.ic_unfold
}

@Composable
private fun actionLabel(action: MediaAction, seconds: Int? = null): String = when (action) {
    MediaAction.NEXT -> stringResource(R.string.action_next)
    MediaAction.PREVIOUS -> stringResource(R.string.action_previous)
    MediaAction.FORWARD -> stringResource(R.string.action_forward, seconds ?: Prefs.DEFAULT_SEEK_SECONDS)
    MediaAction.REWIND -> stringResource(R.string.action_rewind, seconds ?: Prefs.DEFAULT_SEEK_SECONDS)
    MediaAction.PLAY_PAUSE -> stringResource(R.string.action_play_pause)
    MediaAction.LIKE -> stringResource(R.string.action_like)
    MediaAction.UNLIKE -> stringResource(R.string.action_unlike)
    MediaAction.HIDE -> stringResource(R.string.action_hide)
}

/** One trigger (a long press or a combination) with its on/off switch and the action it runs. */
@Composable
private fun BindingCard(
    title: @Composable RowScope.() -> Unit,
    binding: ActionBinding,
    onChange: (ActionBinding) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = title
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.cd_delete))
                    }
                }
                Switch(checked = binding.enabled, onCheckedChange = { onChange(binding.copy(enabled = it)) })
            }
            Column(Modifier.alpha(if (binding.enabled) 1f else 0.5f)) {
                ActionEditor(binding, onChange)
            }
        }
    }
}

@Composable
private fun ActionEditor(binding: ActionBinding, onChange: (ActionBinding) -> Unit) {
    // Labels are resolved up front: DropdownSetting's optionLabel isn't composable.
    val labels = MediaAction.entries.associateWith { actionLabel(it, binding.seconds) }
    DropdownSetting(
        label = stringResource(R.string.action_label),
        options = MediaAction.entries,
        selected = binding.action,
        optionLabel = { labels.getValue(it) },
        onSelect = { onChange(binding.copy(action = it)) }
    )
    if (binding.action.usesSeconds) {
        Spacer(Modifier.height(8.dp))
        NumberField(
            label = stringResource(R.string.seek_seconds),
            value = binding.seconds,
            range = ActionBinding.MIN_SECONDS..ActionBinding.MAX_SECONDS,
            onValue = { onChange(binding.copy(seconds = it)) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComboKeys(keys: List<VolumeKey>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEach { key ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painterResource(if (key == VolumeKey.UP) R.drawable.ic_volume_up else R.drawable.ic_volume_down),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(if (key == VolumeKey.UP) R.string.key_up else R.string.key_down),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun AddComboDialog(existing: Set<String>, onDismiss: () -> Unit, onAdd: (ShortCombo) -> Unit) {
    var keys by remember { mutableStateOf(listOf<VolumeKey>()) }
    var binding by remember { mutableStateOf(ActionBinding(true, MediaAction.NEXT)) }
    val combo = ShortCombo(keys, binding)
    val duplicate = combo.id in existing
    val full = keys.size >= ShortCombo.MAX_KEYS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.combo_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.combo_new_helper), style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (keys.isEmpty()) {
                                Text(stringResource(R.string.combo_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                ComboKeys(keys)
                            }
                        }
                        IconButton(onClick = { keys = keys.dropLast(1) }, enabled = keys.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_backspace), stringResource(R.string.cd_remove_last))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { keys = keys + VolumeKey.UP }, enabled = !full, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.key_up))
                    }
                    FilledTonalButton(onClick = { keys = keys + VolumeKey.DOWN }, enabled = !full, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_volume_down), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.key_down))
                    }
                }
                when {
                    duplicate -> Text(stringResource(R.string.combo_duplicate), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    keys.size == 1 -> Text(stringResource(R.string.combo_single_warning), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                ActionEditor(binding) { binding = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(combo) }, enabled = keys.isNotEmpty() && !duplicate) { Text(stringResource(R.string.combo_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The result of the last check, and the button that starts one - or downloads what it found. */
@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    val installed = remember { installedVersion(context).orEmpty() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (state is UpdateState.Available) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val message = when (val s = state) {
                UpdateState.Idle, UpdateState.Checking -> stringResource(R.string.update_installed, installed)
                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                is UpdateState.Available -> stringResource(R.string.update_available, s.release.version)
                UpdateState.Failed -> stringResource(R.string.update_failed)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                is UpdateState.Available -> Button(
                    onClick = { Updates.download(context, s.release) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON_HEIGHT)
                ) {
                    Text(stringResource(R.string.update_download), style = MaterialTheme.typography.titleSmall)
                }
                else -> OutlinedButton(
                    onClick = { Updates.check(context) },
                    enabled = s != UpdateState.Checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON_HEIGHT)
                ) {
                    if (s == UpdateState.Checking) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_checking))
                    } else {
                        Icon(painterResource(R.drawable.ic_update), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_check))
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutLine() {
    val context = LocalContext.current
    val version = remember { installedVersion(context) }
    Text(
        stringResource(R.string.about_version, stringResource(R.string.app_name), version.orEmpty()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------- Building blocks ----------

@Composable
private fun SectionHeader(title: String, icon: Painter, topPadding: Dp = 24.dp) {
    Spacer(Modifier.height(topPadding))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SubHeader(title: String, topPadding: Dp = 16.dp) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = topPadding, bottom = 4.dp))
}

private val BIG_BUTTON_HEIGHT = 52.dp

/** How many of the required permissions are on, as a sentence and a bar. */
@Composable
private fun PermissionSummary(granted: Int, total: Int) {
    val done = granted == total
    // Missing permissions aren't an error yet - the bar shows progress, not a warning.
    val color = if (done) statusColor(true) else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Text(
                    if (done) stringResource(R.string.perm_summary_done) else stringResource(R.string.perm_summary_missing, total - granted, total),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (done) color else MaterialTheme.colorScheme.onSurface
                )
            }
            LinearProgressIndicator(
                progress = { granted.toFloat() / total },
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
        }
    }
}

/**
 * One permission: what it's for, whether it's on, and a big button to the system screen that
 * turns it on. Once granted the button shrinks to a small "Change" link.
 */
@Composable
private fun PermissionCard(
    icon: Painter,
    title: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    buttonLabel: String,
    onClick: () -> Unit
) {
    val ok = statusColor(true)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (granted) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (granted) ok.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (granted) ok else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (required) R.string.perm_required else R.string.perm_recommended),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.labelLarge, color = ok, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClick) { Text(stringResource(R.string.perm_change)) }
                }
            } else {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON_HEIGHT)) {
                    Text(buttonLabel, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, helper: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 8.dp))
        }
        if (helper != null) {
            Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(label)
    }
}

@Composable
private fun HintCard(title: String?, text: String, action: String, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON_HEIGHT)) { Text(action) }
        }
    }
}

/**
 * A stepped slider with labels under its start, middle and end. Values the slider can't land on
 * exactly (typed into the field next to it) still work - the thumb just sits near them.
 */
@Composable
private fun TickSlider(
    value: Int,
    range: IntRange,
    sliderRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    labels: List<String>,
    onValue: (Int) -> Unit,
    onFinished: ((Int) -> Unit)? = null
) {
    val current by rememberUpdatedState(value)
    Slider(
        value = value.toFloat().coerceIn(sliderRange),
        onValueChange = { v ->
            val n = v.roundToInt().coerceIn(range)
            if (n != current) onValue(n)
        },
        onValueChangeFinished = { onFinished?.invoke(current) },
        valueRange = sliderRange,
        steps = steps
    )
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        labels.getOrNull(0)?.let { Text(it, style = style, color = color, modifier = Modifier.align(Alignment.CenterStart)) }
        labels.getOrNull(1)?.let { Text(it, style = style, color = color, modifier = Modifier.align(Alignment.Center)) }
        labels.getOrNull(2)?.let { Text(it, style = style, color = color, modifier = Modifier.align(Alignment.CenterEnd)) }
    }
    Spacer(Modifier.height(8.dp))
}

/** Digits only; a value outside [range] is shown as an error and not saved until it's fixed. */
@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            val digits = new.filter(Char::isDigit).take(MAX_NUMBER_DIGITS)
            text = digits
            digits.toIntOrNull()?.takeIf { it in range && it != value }?.let(onValue)
        },
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.range_helper, range.first, range.last)) },
        isError = parsed == null || parsed !in range,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

private const val MAX_NUMBER_DIGITS = 5

/** A read-only field that opens a dropdown list of [options] when tapped. */
@Composable
private fun <T> DropdownSetting(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { fieldWidth = it.width }) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.rotate(if (expanded) 180f else 0f))
            },
            modifier = Modifier.fillMaxWidth()
        )
        // A text field consumes taps for focus handling, so a transparent layer on top opens the menu.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { fieldWidth.toDp() })
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel(option),
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        expanded = false
                        if (option != selected) onSelect(option)
                    }
                )
            }
        }
    }
}
