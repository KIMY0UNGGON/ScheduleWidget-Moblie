package com.schedulewidget.mobile.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Edit which music apps appear as sources (add/remove/reorder/open) and pick the current source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicAppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Repository.get(ctx) }
    val data by repo.data.collectAsStateWithLifecycle()
    val sessions by MusicHub.sessions.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(MusicHub.hasNotificationAccess(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        access = MusicHub.hasNotificationAccess(ctx)
        resumes++ // re-check installed apps (one may have been uninstalled while we were away)
        MusicHub.refresh(ctx)
        onPauseOrDispose { }
    }

    // PackageManager lookups only when the list changes or on resume, not on every session update.
    val installed = remember(data.musicApps, resumes) { data.musicApps.distinct().filter { isInstalled(ctx, it) } }
    val source = if (data.musicSource in installed) data.musicSource else AppData.SOURCE_WIDGET

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("음악 앱 편집") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = { TextButton(onClick = { picking = true }) { Icon(Icons.Filled.Add, null); Text("앱 추가") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!access) item { AccessBanner(ctx) }
            item {
                Text(
                    "재생 소스",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { MusicHub.setSource(ctx, AppData.SOURCE_WIDGET) },
                    leadingContent = { RadioButton(selected = source == AppData.SOURCE_WIDGET, onClick = { MusicHub.setSource(ctx, AppData.SOURCE_WIDGET) }) },
                    headlineContent = { Text(MusicHub.WIDGET_LABEL) },
                    supportingContent = { Text("앱 안의 플레이리스트(파일/YouTube)를 재생합니다") },
                    trailingContent = { Icon(Icons.Filled.MusicNote, null) },
                )
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
            if (installed.isEmpty()) {
                item {
                    Text(
                        "추가된 음악 앱이 없습니다. 오른쪽 위 \"앱 추가\"로 Spotify, YouTube Music 같은 앱을 추가하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(installed, key = { _, p -> p }) { i, pkg ->
                val np = sessions[pkg]
                ListItem(
                    modifier = Modifier.clickable { MusicHub.setSource(ctx, pkg) },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = source == pkg, onClick = { MusicHub.setSource(ctx, pkg) })
                            AppIcon(pkg)
                        }
                    },
                    headlineContent = { Text(MusicHub.label(pkg, ctx), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val status = when {
                            np == null -> if (access) "대기 중" else "알림 접근 권한 필요"
                            np.isPlaying -> "재생 중 · ${np.title}"
                            np.title.isNotBlank() -> "일시정지 · ${np.title}"
                            else -> "연결됨"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (np?.isPlaying == true) {
                                Icon(Icons.Filled.GraphicEq, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { MusicHub.launchApp(ctx, pkg) }) { Text("열기") }
                            Column {
                                IconButton(onClick = { move(repo, pkg, installed.getOrNull(i - 1)) }, enabled = i > 0, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "위로")
                                }
                                IconButton(onClick = { move(repo, pkg, installed.getOrNull(i + 1)) }, enabled = i < installed.lastIndex, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.KeyboardArrowDown, "아래로")
                                }
                            }
                            IconButton(onClick = { remove(ctx, repo, pkg) }) { Icon(Icons.Filled.Close, "삭제") }
                        }
                    },
                )
            }
            item {
                Text(
                    "음악 막대의 소스 목록에 이 순서대로 표시됩니다. 다른 앱을 소스로 고르면 위젯 플레이리스트는 일시정지되고, 반대로 위젯으로 돌아오면 그 앱이 일시정지됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            existing = data.musicApps.toSet(),
            activePackages = sessions.keys,
            onPick = { pkg -> repo.update { d -> if (pkg in d.musicApps) d else d.copy(musicApps = d.musicApps + pkg) } },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun AccessBanner(ctx: Context) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsActive, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("알림 접근 권한이 필요합니다", style = MaterialTheme.typography.titleSmall)
                Text(
                    "다른 음악 앱의 곡 정보를 보고 재생/일시정지/넘기기를 하려면 이 앱에 알림 접근을 허용하세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { runCatching { ctx.startActivity(MusicHub.notificationAccessIntent()) } }) { Text("허용") }
        }
    }
}

/**
 * Swaps [pkg] with the visible neighbour [other]. musicApps may also hold uninstalled (hidden) packages, so moving
 * by ±1 in the raw list could swap with an invisible entry and look like nothing happened.
 */
private fun move(repo: Repository, pkg: String, other: String?) = repo.update { d ->
    val list = d.musicApps.toMutableList()
    val i = list.indexOf(pkg)
    val j = if (other == null) -1 else list.indexOf(other)
    if (i < 0 || j < 0 || i == j) d else d.copy(musicApps = list.apply { this[i] = other!!; this[j] = pkg })
}

private fun remove(ctx: Context, repo: Repository, pkg: String) {
    if (repo.data.value.musicSource == pkg) MusicHub.setSource(ctx, AppData.SOURCE_WIDGET)
    repo.update { d ->
        d.copy(
            musicApps = d.musicApps.filter { it != pkg }, // `- pkg` would drop only the first duplicate
            musicSource = if (d.musicSource == pkg) AppData.SOURCE_WIDGET else d.musicSource,
        )
    }
}

private fun isInstalled(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

@Composable
private fun AppIcon(pkg: String, sizeDp: Int = 36) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(iconCache[pkg], pkg) {
        if (value == null) value = withContext(Dispatchers.IO) { loadIcon(ctx, pkg) }
    }
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(sizeDp.dp)) }
            ?: Icon(Icons.Filled.MusicNote, null)
    }
}

private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

private fun loadIcon(ctx: Context, pkg: String): ImageBitmap? = iconCache[pkg] ?: runCatching {
    ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
}.getOrNull()?.also { iconCache[pkg] = it }

private data class Candidate(val pkg: String, val label: String, val likelyMusic: Boolean)

internal val KNOWN_MUSIC_APPS = setOf(
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.google.android.youtube",
    "app.revanced.android.youtube",
    "app.revanced.android.apps.youtube.music",
    "com.iloen.melon",
    "com.ktmusic.geniemusic",
    "skplanet.musicmate",
    "com.neowiz.android.bugs",
    "com.naver.vibe",
    "com.apple.android.music",
    "com.soundcloud.android",
    "com.sec.android.app.music",
    "com.maxmpz.audioplayer",
    "com.amazon.mp3",
    "deezer.android.app",
    "com.aspiro.tidal",
    "com.pandora.android",
    "com.kakao.music",
    "com.google.android.apps.podcasts",
    "au.com.shiftyjelly.pocketcasts",
    "com.audible.application",
    "com.miui.player",
    "com.oneplus.music",
    "in.krosbits.musicolet",
    "com.shaiban.audioplayer.mplayer",
    "org.videolan.vlc",
)

private fun findCandidates(ctx: Context, activePackages: Set<String>): List<Candidate> {
    val pm = ctx.packageManager
    val music = mutableSetOf<String>()
    music += KNOWN_MUSIC_APPS
    music += activePackages
    runCatching {
        pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).forEach { music += it.serviceInfo.packageName }
    }
    runCatching {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/audio/media/1"), "audio/*")
        pm.queryIntentActivities(view, PackageManager.MATCH_ALL).forEach { music += it.activityInfo.packageName }
    }
    val launchable = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())
    return (launchable + music.filter { isInstalled(ctx, it) })
        .filter { it != ctx.packageName }
        .map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Candidate(pkg, label, pkg in music && pkg in launchable || pkg in activePackages)
        }
        .sortedWith(compareByDescending<Candidate> { it.likelyMusic }.thenBy { it.label.lowercase() })
}

@Composable
private fun AppPickerDialog(existing: Set<String>, activePackages: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    val candidates by produceState<List<Candidate>?>(null) {
        value = withContext(Dispatchers.IO) { findCandidates(ctx, activePackages) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("음악 앱 추가") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("모든 앱 보기", Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
                val list = candidates
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val shown = list.filter { (showAll || it.likelyMusic) && it.pkg !in existing }
                    if (shown.isEmpty()) {
                        Text(
                            if (showAll) "추가할 앱이 없습니다." else "음악 앱을 찾지 못했습니다. \"모든 앱 보기\"를 켜 보세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.pkg }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(c.pkg) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(c.pkg, 32)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (c.pkg in activePackages) {
                                        Text("재생 세션 있음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Icon(Icons.Filled.Add, "추가")
                            }
                        }
                    }
                }
            }
        },
    )
}
