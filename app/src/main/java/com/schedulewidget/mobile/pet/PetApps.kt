package com.schedulewidget.mobile.pet

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Apps the user pinned to the pet's speech bubble (up to [MAX]), shown before 음악 · 달력 · 설정 and opened with one
 * tap. 순천향톡 goes through [IdShortcut] so the mobile ID opens directly when that helper is on.
 */
object PetApps {
    const val MAX = 3

    /** Icons per row in the bubble; more wrap to a second row. */
    const val PER_ROW = 5

    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val labels = ConcurrentHashMap<String, String>()

    /** The pinned apps that are still installed, in the user's order. */
    fun installed(context: Context, pkgs: List<String>): List<String> =
        pkgs.distinct().filter { context.packageManager.getLaunchIntentForPackage(it) != null }.take(MAX)

    fun launch(context: Context, pkg: String) {
        if (pkg == IdShortcut.ID_APP) return IdShortcut.open(context)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(context, "앱을 찾을 수 없습니다. 펫 설정에서 다시 추가해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun label(context: Context, pkg: String): String = labels[pkg] ?: runCatching {
        context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
    }.getOrDefault(pkg).also { labels[pkg] = it }

    fun icon(context: Context, pkg: String): ImageBitmap? = icons[pkg] ?: runCatching {
        context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
    }.getOrNull()?.also { icons[pkg] = it }

    /** Apps with a launcher icon (except this one), sorted by name. Slow: call off the main thread. */
    fun launchable(context: Context): List<String> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName }
            .sortedBy { label(context, it).lowercase() }
    }
}

/** Bubble width for [icons] icons: one 68dp slot each up to [PetApps.PER_ROW], plus the bubble's padding. */
fun petMenuWidthDp(icons: Int): Int = icons.coerceIn(3, PetApps.PER_ROW) * 68 + 32

/** Pet settings: the apps pinned to the speech bubble (add up to three, remove). */
@Composable
fun PetAppsSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsState()
    val apps = remember(data.petApps) { PetApps.installed(context, data.petApps) }
    var picking by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("말풍선 바로가기 앱", style = MaterialTheme.typography.bodyLarge)
        Text(
            "펫을 한 번 누르면 나오는 말풍선에 앱을 최대 ${PetApps.MAX}개까지 넣어 바로 열 수 있어요. 음악 · 달력 · 설정은 항상 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        apps.forEach { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 36)
                Spacer(Modifier.width(12.dp))
                Text(PetApps.label(context, pkg), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { repo.update { d -> d.copy(petApps = d.petApps - pkg) } }) {
                    Icon(Icons.Filled.Close, contentDescription = "빼기")
                }
            }
        }
        OutlinedButton(onClick = { picking = true }, enabled = apps.size < PetApps.MAX) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (apps.size < PetApps.MAX) "앱 추가 (${apps.size}/${PetApps.MAX})" else "최대 ${PetApps.MAX}개까지 추가했어요")
        }
    }
    if (picking) AppPickerDialog(
        exclude = apps.toSet(),
        onDismiss = { picking = false },
        onPick = { pkg ->
            picking = false
            repo.update { d -> d.copy(petApps = (PetApps.installed(context, d.petApps) + pkg).distinct().take(PetApps.MAX)) }
        },
    )
}

@Composable
internal fun AppIcon(pkg: String, sizeDp: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember(pkg) { PetApps.icon(context, pkg) }
    if (icon != null) Image(icon, contentDescription = null, modifier = modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 4).dp)))
    else Box(modifier.size(sizeDp.dp))
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val all by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { PetApps.launchable(context) } }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("말풍선에 넣을 앱") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("앱 이름 검색") }, modifier = Modifier.fillMaxWidth())
                val list = all
                if (list == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    val shown = list.filter { it !in exclude && (query.isBlank() || PetApps.label(context, it).contains(query.trim(), ignoreCase = true)) }
                    items(shown, key = { it }) { pkg ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(pkg) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(pkg, 36)
                            Spacer(Modifier.width(12.dp))
                            Text(PetApps.label(context, pkg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
