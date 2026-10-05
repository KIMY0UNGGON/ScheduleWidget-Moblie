package com.schedulewidget.mobile.pet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.ui.Route

/** Pet controls layered over the notes library and editor without intercepting input outside each pet. */
@Composable
fun AppPetLayer(
    navigate: (Route) -> Unit,
    onNotes: () -> Unit = { navigate(Route.Notes) },
) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsState()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val floating = remember(resumed, data.floatingPet) { FloatingPet.isActive(context) }
    val visiblePets = remember(data) {
        if (data.miniCharacterVisible) data.petSlots().filterNot { it.hidden }.take(3) else emptyList()
    }
    var activePetIndex by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var musicOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(visiblePets) {
        if (visiblePets.none { it.index == activePetIndex }) {
            activePetIndex = visiblePets.firstOrNull()?.index ?: 0
            menuOpen = false
            musicOpen = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        visiblePets.forEach { pet -> key(pet.index) {
            DraggablePet(
                slotIndex = pet.index,
                heightDp = 96,
                defaultTop = NOTES_PET_DEFAULT_TOP,
                alpha = if (floating) 1f else 0.45f,
                onTap = { activePetIndex = pet.index; musicOpen = false; menuOpen = !menuOpen },
                onDoubleTap = {
                    activePetIndex = pet.index
                    if (data.stt.enabled && Recorder.isRecording) Recorder.stop(context)
                },
                onTripleTap = {
                    activePetIndex = pet.index
                    if (data.stt.enabled) {
                        if (!Recorder.isRecording) { menuOpen = false; musicOpen = false }
                        Recorder.start(context)
                    } else {
                        menuOpen = false
                        musicOpen = !musicOpen
                    }
                },
                onLongPress = { FloatingPet.enable(context) },
                panel = if (pet.index != activePetIndex) null else when {
                    musicOpen -> ({
                        PetMusicPanel(
                            onClose = { musicOpen = false },
                            onOpenPlaylists = { musicOpen = false; navigate(Route.Playlists) },
                            onOpenMusicApps = { musicOpen = false; navigate(Route.MusicApps) },
                        )
                    })
                    menuOpen -> ({
                        PetMenu(
                            onApp = { pkg -> menuOpen = false; PetApps.launch(context, pkg) },
                            onNotes = if (data.notes.enabled) ({ menuOpen = false; onNotes() }) else null,
                            onMusic = { menuOpen = false; musicOpen = true },
                            onCalendar = { menuOpen = false; navigate(Route.Mini) },
                            onSettings = { menuOpen = false; navigate(Route.Pets) },
                            onOpenRecordings = { menuOpen = false; navigate(Route.Recordings) },
                            onOpenTranscript = { id ->
                                menuOpen = false
                                Recorder.openTranscript.value = id
                                navigate(Route.Recordings)
                            },
                        )
                    })
                    else -> null
                },
                onPanelDismiss = { menuOpen = false; musicOpen = false },
            )
        } }
    }
}

private val NOTES_PET_DEFAULT_TOP = 152.dp
