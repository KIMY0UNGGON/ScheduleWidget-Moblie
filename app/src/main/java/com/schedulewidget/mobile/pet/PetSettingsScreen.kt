package com.schedulewidget.mobile.pet

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsState()
    val slots = data.petSlots()
    var selectedSlotIndex by remember { mutableIntStateOf(0) }
    var addingPet by remember { mutableStateOf(false) }
    var catalogVersion by remember { mutableIntStateOf(0) }
    val characters = remember(catalogVersion) { Characters.list(context) }
    val scope = rememberCoroutineScope()
    var importMessage by remember { mutableStateOf<String?>(null) }
    var pendingJson by remember { mutableStateOf<PetImport.Result.NeedsImage?>(null) }
    var deleteTarget by remember { mutableStateOf<CharacterInfo?>(null) }

    var driveStatus by remember { mutableStateOf<String?>(null) }
    var driveBusy by remember { mutableStateOf(false) }
    val activity = LocalActivity.current

    LaunchedEffect(slots.size) { selectedSlotIndex = selectedSlotIndex.coerceIn(0, slots.lastIndex) }
    val active = slots.getOrElse(selectedSlotIndex) { slots.first() }

    fun runDriveSync(token: String) {
        scope.launch {
            driveBusy = true
            driveStatus = "구글 드라이브와 맞추는 중…"
            runCatching { DrivePets.sync(context, token) }
                .onSuccess { o ->
                    catalogVersion++
                    driveStatus = buildString {
                        append("동기화 완료")
                        if (o.downloaded > 0) append(" · ${o.downloaded}개 받음")
                        if (o.uploaded > 0) append(" · ${o.uploaded}개 올림")
                        if (o.deleted > 0) append(" · 드라이브에서 ${o.deleted}개 삭제")
                        if (o.failed > 0) append(" · ${o.failed}개 실패(다음에 다시 시도)")
                    }
                }
                .onFailure { driveStatus = it.message ?: "동기화하지 못했습니다." }
            driveBusy = false
        }
    }
    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val act = activity ?: return@rememberLauncherForActivityResult
        if (r.resultCode != android.app.Activity.RESULT_OK) {
            driveStatus = "로그인이 끝나지 않았습니다. 직접 취소했다면 필요할 때 다시 연결할 수 있습니다."
            repo.update { it.copy(petDrive = it.petDrive.copy(enabled = false, lastError = driveStatus)) }
            return@rememberLauncherForActivityResult
        }
        when (val auth = DrivePets.tokenFrom(act, r.data)) {
            is DrivePets.Auth.Token -> runDriveSync(auth.value)
            is DrivePets.Auth.Failed -> {
                driveStatus = "구글 드라이브 인증에 실패했습니다. 캐릭터 설정에서 다시 연결해 주세요. ${auth.message}"
                repo.update { it.copy(petDrive = it.petDrive.copy(enabled = false, lastError = driveStatus)) }
            }
            is DrivePets.Auth.NeedsConsent -> Unit
        }
    }
    fun startDrive() {
        scope.launch {
            if (repo.data.value.petDrive.lastError != null) {
                repo.update { it.copy(petDrive = it.petDrive.copy(lastError = null)) }
            }
            driveStatus = "구글 계정 확인 중…"
            when (val auth = DrivePets.authorize(activity ?: context)) {
                is DrivePets.Auth.Token -> runDriveSync(auth.value)
                is DrivePets.Auth.NeedsConsent -> driveConsent.launch(auth.request)
                is DrivePets.Auth.Failed -> {
                    driveStatus = "구글 드라이브 인증에 실패했습니다. 캐릭터 설정에서 다시 연결해 주세요. ${auth.message}"
                    repo.update { it.copy(petDrive = it.petDrive.copy(enabled = false, lastError = driveStatus)) }
                }
            }
        }
    }
    // Opening this screen (and every import/delete) brings the Drive characters in step, like the PC does.
    LaunchedEffect(Unit) { if (data.petDrive.enabled) startDrive() }

    fun handle(result: PetImport.Result) {
        when (result) {
            is PetImport.Result.Imported -> {
                catalogVersion++
                if (data.petDrive.enabled) startDrive()
                if (addingPet && slots.size < 3) {
                    selectedSlotIndex = slots.size
                    repo.update { it.addPet(result.manifest).copy(miniCharacterVisible = true) }
                } else {
                    repo.update { it.changePet(selectedSlotIndex, result.manifest).copy(miniCharacterVisible = true) }
                }
                addingPet = false
                importMessage = "‘${result.name}’ 캐릭터를 가져왔습니다."
            }
            is PetImport.Result.NeedsImage -> {
                pendingJson = result
                importMessage = "pet.json이 가리키는 이미지 ‘${result.fileName}’을(를) 선택해 주세요."
            }
            is PetImport.Result.Failed -> importMessage = result.message
        }
    }
    fun importWith(block: () -> PetImport.Result) {
        scope.launch { handle(withContext(Dispatchers.IO) { block() }) }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importWith { PetImport.fromFiles(context, uris) }
    }
    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingJson
        pendingJson = null
        if (uri != null && pending != null) importWith { PetImport.withImage(context, pending.petJson, uri) }
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) importWith { PetImport.fromFolder(context, tree) }
    }
    LaunchedEffect(pendingJson) { pendingJson?.let { importImage.launch(arrayOf("image/*")) } }
    val current = if (active.manifest.startsWith(Characters.URI_PREFIX)) null
        else Characters.find(context, active.manifest) ?: Characters.find(context, Characters.DEFAULT)
    val isUser = active.manifest.startsWith(Characters.URI_PREFIX) || current?.rows == 0

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val manifest = Characters.URI_PREFIX + uri
            if (addingPet && slots.size < 3) {
                selectedSlotIndex = slots.size
                repo.update { it.addPet(manifest).copy(miniCharacterVisible = true) }
            } else {
                repo.update { it.changePet(selectedSlotIndex, manifest).copy(miniCharacterVisible = true) }
            }
            addingPet = false
        }
    }

    var scale by remember { mutableFloatStateOf(active.scale.coerceIn(50, 300).toFloat()) }
    LaunchedEffect(active.index, active.scale) { scale = active.scale.coerceIn(50, 300).toFloat() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캐릭터 설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { repo.update { it.copy(miniCharacterVisible = !it.miniCharacterVisible) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("캐릭터 표시", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = data.miniCharacterVisible, onCheckedChange = { on -> repo.update { it.copy(miniCharacterVisible = on) } })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("펫", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleSmall)
                slots.forEach { slot ->
                    val name = Characters.find(context, slot.manifest)?.name ?: "펫 ${slot.index + 1}"
                    FilterChip(
                        selected = selectedSlotIndex == slot.index,
                        onClick = { selectedSlotIndex = slot.index; addingPet = false },
                        label = { Text(name, maxLines = 1) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                OutlinedButton(onClick = { addingPet = true }, enabled = slots.size < 3) { Text("추가") }
            }
            if (addingPet) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("아래 목록에서 추가할 캐릭터를 고르세요.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { addingPet = false }) { Text("취소") }
            }
            Row(Modifier.fillMaxWidth().clickable {
                repo.update { currentData ->
                    currentData.petSlots().getOrNull(active.index)?.let { currentData.withPet(it.copy(hidden = !it.hidden)) } ?: currentData
                }
            }, verticalAlignment = Alignment.CenterVertically) {
                Text("선택한 펫 표시", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = !active.hidden,
                    onCheckedChange = { visible ->
                        repo.update { currentData ->
                            currentData.petSlots().getOrNull(active.index)?.let { currentData.withPet(it.copy(hidden = !visible)) } ?: currentData
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("좌우 반전", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = active.flipped,
                    onCheckedChange = { flipped ->
                        repo.update { currentData ->
                            currentData.petSlots().getOrNull(active.index)?.let { currentData.withPet(it.copy(flipped = flipped)) } ?: currentData
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    repo.update { currentData ->
                        currentData.petSlots().getOrNull(active.index)?.let { pet ->
                            currentData.withPet(pet.copy(
                                animation = "idle", scale = 100, hidden = false, flipped = false,
                                x = null, y = null,
                                floatingX = if (pet.index == 0) 60 else null,
                                floatingY = if (pet.index == 0) 600 else null,
                            ))
                        } ?: currentData
                    }
                }) { Text("이 펫 초기화") }
                if (slots.size > 1) OutlinedButton(onClick = {
                    repo.update { it.removePet(active.index) }
                    selectedSlotIndex = (active.index - 1).coerceAtLeast(0)
                    addingPet = false
                }) { Text("이 펫 삭제") }
            }
            FloatingPetToggle(Modifier.padding(vertical = 4.dp))
            PetAppsSetting(Modifier.padding(vertical = 4.dp))
            // Only matters once 순천향톡 is pinned: its mobile ID can then open directly.
            if (IdShortcut.ID_APP in data.petApps) IdShortcutCard(Modifier.padding(vertical = 4.dp))

            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.BottomCenter) {
                CharacterSprite(
                    active.manifest, active.animation, (120 * scale / 100f).coerceAtMost(170f).dp,
                    flipped = active.flipped,
                )
            }
            Text(
                "캐릭터를 누르면 반응합니다.",
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("크기 ${scale.roundToInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = scale,
                onValueChange = { scale = (it / 10f).roundToInt() * 10f },
                valueRange = 50f..300f,
                steps = 24,
                onValueChangeFinished = {
                    repo.update { currentData ->
                        currentData.petSlots().getOrNull(active.index)?.let { currentData.withPet(it.copy(scale = scale.roundToInt())) } ?: currentData
                    }
                },
            )

            HorizontalDivider()
            Text("동작", style = MaterialTheme.typography.titleMedium)
            if (isUser) {
                Text(
                    "사용자 이미지는 멈춘 그림으로 표시되며, 누르면 살짝 뛰어오릅니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Characters.options(current).forEach { (key, label) ->
                        FilterChip(
                            selected = active.animation == key,
                            onClick = {
                                repo.update { currentData ->
                                    currentData.petSlots().getOrNull(active.index)?.let { currentData.withPet(it.copy(animation = key)) } ?: currentData
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                if (active.animation == "typing") Text(
                    "앱에서 입력할 때 반응합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            PetCharacterPicker(
                characters = characters,
                active = active,
                onSelect = { manifest ->
                    if (addingPet && slots.size < 3) {
                        selectedSlotIndex = slots.size
                        repo.update { it.addPet(manifest).copy(miniCharacterVisible = true) }
                    } else {
                        repo.update { it.changePet(active.index, manifest).copy(miniCharacterVisible = true) }
                    }
                    addingPet = false
                },
                onPickImage = { picker.launch(arrayOf("image/*")) },
                onDelete = { deleteTarget = it },
            )
            if (isUser) {
                TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("다른 이미지 선택") }
            }
            PetDriveSettings(
                enabled = data.petDrive.enabled,
                busy = driveBusy,
                status = data.petDrive.lastError ?: driveStatus,
                onManageAccount = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://myaccount.google.com/connections")))
                    }.onFailure { driveStatus = "Google 계정 권한 관리 페이지를 열지 못했습니다." }
                },
                onEnabledChange = { enabled ->
                    repo.update { it.copy(petDrive = it.petDrive.copy(enabled = enabled, lastError = null)) }
                    if (enabled) startDrive() else driveStatus = null
                },
                onSync = { startDrive() },
            )
            PetImportSettings(
                message = importMessage,
                onImportFiles = { importFiles.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "image/*", "text/plain")) },
                onImportFolder = { importFolder.launch(null) },
            )
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("캐릭터 삭제") },
            text = { Text("가져온 캐릭터 ‘${target.name}’을(를) 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    PetImport.delete(context, target.manifest)
                    repo.update { currentData ->
                        currentData.petSlots().indices.fold(currentData) { updated, index ->
                            if (updated.petSlots().getOrNull(index)?.manifest == target.manifest) updated.changePet(index, Characters.DEFAULT) else updated
                        }
                    }
                    catalogVersion++
                    if (data.petDrive.enabled) startDrive()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}
