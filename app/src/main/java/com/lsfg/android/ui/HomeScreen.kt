package com.lsfg.android.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.SHOW_IMAGE_QUALITY
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.session.CrashReporter
import com.lsfg.android.session.LsfgForegroundService
import com.lsfg.android.session.PermissionsHelper
import com.lsfg.android.session.ShizukuCaptureEngine
import com.lsfg.android.ui.theme.LsfgPrimary
import com.lsfg.android.ui.theme.LsfgStatusGood
import com.lsfg.android.ui.theme.LsfgStatusWarn
import rikka.shizuku.Shizuku

private data class PremiumTile(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val gradient: Pair<Color, Color>,
    val route: String? = null,
    val badge: String? = null,
    val badgeColor: Color = LsfgPrimary,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var lastError by remember { mutableStateOf<String?>(null) }
    var pendingTargetPkg by remember { mutableStateOf<String?>(null) }
    var pendingCaptureSource by remember { mutableStateOf(CaptureSource.MEDIA_PROJECTION) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var crashPreview by remember { mutableStateOf("") }
    var showCrashDetail by remember { mutableStateOf(false) }
    var shizukuPermissionRetry by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        if (CrashReporter.hasPendingCrash(ctx)) {
            crashPreview = CrashReporter.readCrashSummary(ctx)
            CrashReporter.markPendingCrashSeen(ctx)
            showCrashDialog = true
        }
    }

    if (showCrashDialog) {
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            icon = { Icon(Icons.Filled.BugReport, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text(stringResource(R.string.crash_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.crash_dialog_body))
                    if (showCrashDetail) {
                        Spacer(Modifier.height(8.dp))
                        Text(crashPreview.take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CrashReporter.buildShareIntent(ctx)?.let {
                        ctx.startActivity(Intent.createChooser(it, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    CrashReporter.clearPendingCrash(ctx)
                    showCrashDialog = false
                }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showCrashDetail = !showCrashDetail }) { Text("View") }
                    TextButton(onClick = {
                        CrashReporter.clearPendingCrash(ctx)
                        showCrashDialog = false
                    }) { Text("Dismiss") }
                }
            },
        )
    }

    val mpm = remember(ctx) { ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val target = pendingTargetPkg
        val source = pendingCaptureSource
        pendingTargetPkg = null
        pendingCaptureSource = CaptureSource.MEDIA_PROJECTION
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            lastError = ctx.getString(R.string.perm_capture_denied)
            return@rememberLauncherForActivityResult
        }
        val intent = LsfgForegroundService.buildStartIntent(
            ctx, result.resultCode, data, target,
            prefs.load().fpsCounterEnabled, source
        )
        ContextCompat.startForegroundService(ctx, intent)
    }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST && grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                shizukuPermissionRetry?.invoke()
                shizukuPermissionRetry = null
            } else if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                lastError = "Shizuku permission denied."
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    val canStart = state.shadersReady && state.targetPackage != null
    val a11yEnabled = PermissionsHelper.isAccessibilityServiceEnabled(ctx)

    val tiles = listOf(
        PremiumTile(
            id = "session", title = "START SESSION", subtitle = if (canStart) "Ready to launch" else "Complete setup",
            icon = Icons.Filled.Speed, gradient = LsfgPrimary to Color(0xFF00BCD4),
            badge = if (canStart) "READY" else "SETUP", badgeColor = if (canStart) LsfgStatusGood else LsfgStatusWarn,
            onClick = {
                lastError = null
                if (!Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    lastError = ctx.getString(R.string.perm_overlay_missing)
                    return@PremiumTile
                }
                val targetPkg = state.targetPackage
                pendingTargetPkg = targetPkg
                targetPkg?.let { ctx.packageManager.getLaunchIntentForPackage(it)?.let { launch -> ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                when (state.captureSource) {
                    CaptureSource.SHIZUKU -> {
                        val start = { ContextCompat.startForegroundService(ctx, LsfgForegroundService.buildShizukuStartIntent(ctx, targetPkg, prefs.load().fpsCounterEnabled)) }
                        if (runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) start()
                        else { shizukuPermissionRetry = start; runCatching { ShizukuCaptureEngine.requestPermission(SHIZUKU_PERMISSION_REQUEST) } }
                    }
                    CaptureSource.ROOT -> ContextCompat.startForegroundService(ctx, LsfgForegroundService.buildRootStartIntent(ctx, targetPkg, prefs.load().fpsCounterEnabled))
                    CaptureSource.MEDIA_PROJECTION -> {
                        pendingCaptureSource = CaptureSource.MEDIA_PROJECTION
                        projectionLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()) else mpm.createScreenCaptureIntent())
                    }
                }
            }
        ),
        PremiumTile("dll", "Lossless.dll", state.dllDisplayName ?: "No file selected", Icons.Filled.Android, Color(0xFF4CAF50) to Color(0xFF2E7D32), if (state.legalAccepted) Routes.DLL else Routes.LEGAL, if (state.shadersReady) "READY" else if (state.dllDisplayName != null) "PENDING" else "MISSING", if (state.shadersReady) LsfgStatusGood else LsfgStatusWarn),
        PremiumTile("target", "Target App", state.targetPackage ?: "No app selected", Icons.Filled.GridView, Color(0xFF2196F3) to Color(0xFF0D47A1), Routes.APP_PICKER, if (state.targetPackage != null) "SET" else "REQUIRED", if (state.targetPackage != null) LsfgStatusGood else LsfgStatusWarn),
        PremiumTile("framegen", "Frame Gen", if (state.lsfgEnabled) "${state.multiplier}x · ${"%.2f".format(state.flowScale)}" else "OFF", Icons.Filled.Timeline, Color(0xFF9C27B0) to Color(0xFF4A148C), Routes.PARAMS_FRAMEGEN_PACING, if (state.lsfgEnabled) "ON" else "OFF", if (state.lsfgEnabled) LsfgStatusGood else Color(0xFF757575)),
        PremiumTile("image", "Image Quality", buildString { if (state.gpuPostProcessingEnabled || state.npuPostProcessingEnabled || state.cpuPostProcessingEnabled) append("GPU/NPU/CPU") else append("OFF") }, Icons.Filled.AutoFixHigh, Color(0xFFFF9800) to Color(0xFFE65100), if (SHOW_IMAGE_QUALITY) Routes.PARAMS_IMAGE_QUALITY else null, if (SHOW_IMAGE_QUALITY && (state.gpuPostProcessingEnabled || state.npuPostProcessingEnabled || state.cpuPostProcessingEnabled)) "ON" else "OFF", if (state.gpuPostProcessingEnabled || state.npuPostProcessingEnabled || state.cpuPostProcessingEnabled) LsfgStatusGood else Color(0xFF757575)),
        PremiumTile("overlay", "Overlay", "${if (state.captureSource == CaptureSource.SHIZUKU) "Shizuku" else if (state.captureSource == CaptureSource.ROOT) "Root" else "MP"} · ${state.drawerEdge.name.lowercase()}", Icons.Filled.TouchApp, Color(0xFF00BCD4) to Color(0xFF006064), Routes.OVERLAY_DISPLAY, when (state.captureSource) { CaptureSource.SHIZUKU -> "SHIZUKU"; CaptureSource.ROOT -> "ROOT"; else -> "MP" }, LsfgPrimary),
        PremiumTile("auto", "Auto Overlay", "${state.autoEnabledApps.size} app(s)", Icons.Filled.Dashboard, Color(0xFF3F51B5) to Color(0xFF1A237E), Routes.AUTOMATIC_OVERLAY, if (state.autoEnabledApps.isNotEmpty()) "ON" else "OFF", if (state.autoEnabledApps.isNotEmpty()) LsfgStatusGood else Color(0xFF757575)),
        PremiumTile("tutorial", "Tutorial", "Step-by-step guide", Icons.Filled.School, Color(0xFF009688) to Color(0xFF004D40), Routes.TUTORIAL, "GUIDE", LsfgPrimary),
        PremiumTile("benchmark", "Benchmark", "Performance test", Icons.Filled.Speed, Color(0xFFE91E63) to Color(0xFF880E4F), Routes.BENCHMARK, "TEST", LsfgPrimary),
        PremiumTile("legal", "Legal", "License & notices", Icons.Filled.Gavel, Color(0xFF607D8B) to Color(0xFF263238), Routes.LEGAL, "INFO", LsfgPrimary),
        PremiumTile("export", "Export Log", "Share crash report", Icons.Filled.BugReport, Color(0xFF795548) to Color(0xFF3E2723), null, "DEBUG", LsfgPrimary, onClick = { CrashReporter.buildShareIntent(ctx)?.let { ctx.startActivity(Intent.createChooser(it, "Export log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: Toast.makeText(ctx, R.string.crash_export_none, Toast.LENGTH_SHORT).show() }),
    ).filter { it.route != null || it.onClick != null }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Animated gradient background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(LsfgPrimary.copy(alpha = 0.08f), Color.Transparent, Color.Transparent),
                        radius = 1200f,
                        center = Offset(200f, 200f)
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize().padding(if (isLandscape) 32.dp else 24.dp)) {
            // Premium Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Glowing logo
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.horizontalGradient(listOf(LsfgPrimary, LsfgPrimary.copy(alpha = 0.6f)))
                            )
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("LF", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = LsfgPrimary)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = (-0.5).sp,
                        )
                        Text(
                            "Frame Generation via Vulkan",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Premium status pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(if (a11yEnabled) LsfgStatusGood.copy(alpha = 0.15f) else LsfgStatusWarn.copy(alpha = 0.15f))
                            .clickable { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Accessibility, null, Modifier.size(20.dp), tint = if (a11yEnabled) LsfgStatusGood else LsfgStatusWarn)
                            Text(if (a11yEnabled) "Accessibility OK" else "A11Y Required", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (a11yEnabled) LsfgStatusGood else LsfgStatusWarn)
                        }
                    }
                }
            }

            if (lastError != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f))
                        .padding(16.dp)
                ) {
                    Text(lastError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(Modifier.height(16.dp))
            }

            // Premium Game Grid
            val columns = if (isLandscape) 4 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(tiles, key = { it.id }) { tile ->
                    PremiumTileCard(tile = tile, nav = nav)
                }
            }
        }
    }
}

@Composable
private fun PremiumTileCard(tile: PremiumTile, nav: NavHostController) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.96f else 1f, animationSpec = tween(100), label = "scale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tile.id == "session") 160.dp else 140.dp)
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        tile.gradient.first.copy(alpha = 0.25f),
                        tile.gradient.second.copy(alpha = 0.15f)
                    )
                )
            )
            .clickable(
                onClick = {
                    tile.onClick?.invoke()
                    tile.route?.let { nav.navigate(it) }
                },
                onPress = { isPressed = true; tryAwaitRelease(); isPressed = false }
            )
            .padding(20.dp),
    ) {
        // Glow border effect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(tile.gradient.first.copy(alpha = 0.3f), Color.Transparent, tile.gradient.second.copy(alpha = 0.3f))
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon with glowing background
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tile.gradient.first.copy(alpha = 0.2f))
                        .blur(4.dp)
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tile.gradient.first.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tile.icon, null, Modifier.size(26.dp), tint = tile.gradient.first)
                }

                if (tile.badge != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(tile.badgeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            tile.badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = tile.badgeColor,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
            }

            Column {
                Text(
                    tile.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tile.subtitle,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Decorative shine
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(80.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.1f), Color.Transparent),
                        radius = 100f
                    )
                )
        )
    }
}

private const val SHIZUKU_PERMISSION_REQUEST = 6104
