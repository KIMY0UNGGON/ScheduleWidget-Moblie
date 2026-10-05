package com.schedulewidget.mobile

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.schedulewidget.mobile.pet.TypingReaction
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.ui.ScheduleActions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.schedulewidget.mobile.apps.MusicAppsScreen
import com.schedulewidget.mobile.calendar.CalendarSyncScreen
import com.schedulewidget.mobile.music.PlaylistScreen
import com.schedulewidget.mobile.music.YouTubeHost
import com.schedulewidget.mobile.pet.DrivePets
import com.schedulewidget.mobile.pet.FloatingPet
import com.schedulewidget.mobile.data.Repository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.schedulewidget.mobile.pet.PetSettingsScreen
import com.schedulewidget.mobile.pet.AppPetLayer
import com.schedulewidget.mobile.ui.AppTheme
import com.schedulewidget.mobile.ui.HomeTabs
import com.schedulewidget.mobile.ui.Route
import com.schedulewidget.mobile.ui.ScheduleListScreen
import com.schedulewidget.mobile.ui.SettingsScreen
// notes
import com.schedulewidget.mobile.notes.NoteEditorScreen
import com.schedulewidget.mobile.notes.library.NoteOpenWith
import com.schedulewidget.mobile.privacy.GoogleDocumentConsentDialog
import com.schedulewidget.mobile.privacy.PrivacyScreen

class MainActivity : ComponentActivity() {
    private val route = mutableStateOf(Route.Mini)
    // notes: the notebook shown by Route.NoteEditor.
    private val noteId = mutableStateOf<String?>(null)
    private var editorExit: ((() -> Unit) -> Unit)? = null
    private var editorStylusKeyHandler: ((KeyEvent) -> Boolean)? = null

    private fun afterEditorSaved(action: () -> Unit) {
        val exit = editorExit
        if (route.value == Route.NoteEditor && exit != null) exit(action) else action()
    }

    private fun navigateTo(destination: Route) {
        if (destination == route.value) return
        afterEditorSaved { route.value = destination }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val restored = savedInstanceState?.getString(EXTRA_ROUTE)?.let(::parseRoute)
        route.value = restored ?: routeOf(intent) ?: Route.Mini
        // notes: restore the open notebook; a PDF/PPT/Word file opened "with" this app is imported into 노트.
        noteId.value = savedInstanceState?.getString(EXTRA_NOTE_ID)
        if (savedInstanceState == null && NoteOpenWith.handle(this, intent)) route.value = Route.Notes
        setContent {
            AppTheme {
                val homeState = rememberSaveableStateHolder()
                val registerEditorExit = remember {
                    { handler: ((() -> Unit) -> Unit)? -> editorExit = handler }
                }
                val registerStylusKeyHandler = remember {
                    { handler: ((KeyEvent) -> Boolean)? -> editorStylusKeyHandler = handler }
                }
                val navigate: (Route) -> Unit = ::navigateTo
                val back = { navigate(if (route.value == Route.NoteEditor) Route.Notes else Route.Mini) }
                BackHandler(enabled = route.value != Route.Mini) { back() }
                Box(Modifier.fillMaxSize()) {
                    // After the first playback request, the player survives screen changes.
                    YouTubeHost()
                    when (route.value) {
                        // The board, and with 수업 녹음 on the "달력 | 녹음" tabs (Recordings is the 녹음 tab).
                        Route.Mini, Route.Recordings, Route.Notes -> homeState.SaveableStateProvider(route.value.name) {
                            HomeTabs(
                                route.value, navigate = navigate,
                                openNote = { noteId.value = it; navigate(Route.NoteEditor) },
                            )
                        }
                        // notes: a notebook full-screen; back returns to the 노트 tab.
                        Route.NoteEditor -> {
                            val id = noteId.value
                            if (id != null) NoteEditorScreen(
                                id, onBack = { route.value = Route.Notes }, registerExitHandler = registerEditorExit,
                                registerStylusKeyHandler = registerStylusKeyHandler,
                            )
                            else LaunchedEffect(Unit) { route.value = Route.Notes }
                        }
                        Route.Schedules -> ScheduleListScreen(onBack = back)
                        Route.Playlists -> PlaylistScreen(onBack = back)
                        Route.MusicApps -> MusicAppsScreen(onBack = back)
                        Route.CalendarSync -> CalendarSyncScreen(onBack = back)
                        Route.Pets -> PetSettingsScreen(onBack = back)
                        Route.Settings -> SettingsScreen(onBack = back, navigate = navigate)
                        Route.Privacy -> PrivacyScreen(onBack = { navigate(Route.Settings) })
                    }
                    if (route.value == Route.Notes || route.value == Route.NoteEditor)
                        AppPetLayer(navigate = navigate, onNotes = { navigate(Route.Notes) })
                }
                GoogleDocumentConsentDialog()
                // 삭제 되돌리기: after any schedule delete in the app, for a few seconds (the deleted items live in memory only).
                val context = LocalContext.current
                val deleted by ScheduleActions.lastDeleted.collectAsStateWithLifecycle()
                val undoHost = remember { SnackbarHostState() }
                LaunchedEffect(deleted) {
                    val items = deleted ?: return@LaunchedEffect
                    undoHost.currentSnackbarData?.dismiss()
                    val result = undoHost.showSnackbar(
                        if (items.size == 1) "삭제했어요" else "일정 ${items.size}개를 삭제했어요",
                        actionLabel = "되돌리기", duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) ScheduleActions.undoDelete(context)
                    else if (ScheduleActions.lastDeleted.value === items) ScheduleActions.forgetDeleted()
                }
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    SnackbarHost(undoHost, Modifier.padding(bottom = 72.dp))
                }
                // Problems the repository reports (e.g. the data file could not be read or saved), on every screen.
                val repo = Repository.get(this)
                val warning by repo.warning.collectAsStateWithLifecycle()
                warning?.let { message ->
                    AlertDialog(
                        onDismissRequest = { repo.clearWarning() },
                        title = { Text("알림") },
                        text = { Text(message) },
                        confirmButton = { TextButton(onClick = { repo.clearWarning() }) { Text("확인") } },
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (route.value == Route.NoteEditor && editorStylusKeyHandler?.invoke(event) == true) return true
        TypingReaction.notifyInput()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (route.value == Route.NoteEditor && editorStylusKeyHandler?.invoke(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        FloatingPet.screenVisible("main", true)
    }

    override fun onStop() {
        // A rotation/theme change stops and restarts the activity; don't make the floating pet pop out and back in.
        if (!isChangingConfigurations) FloatingPet.screenVisible("main", false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        (application as ScheduleApp).ensureCalendarObserver()
        // Starts the floating pet once the user comes back from granting "draw over other apps".
        FloatingPet.sync(this)
        // lastSync only moves on success, so also throttle attempts: a failing/consent-needing sync would otherwise run
        // on every resume (each permission dialog, share sheet, quick-add dialog...).
        val now = System.currentTimeMillis()
        val gcal = Repository.get(this).data.value.googleCalendar
        if (gcal.enabled && now - gcal.lastSync > 60_000 && now - lastGoogleAttempt > 60_000) {
            lastGoogleAttempt = now
            lifecycleScope.launch { com.schedulewidget.mobile.calendar.GoogleCalendarSync.syncIfEnabled(applicationContext) }
        }
        val drive = Repository.get(this).data.value.petDrive
        if (drive.enabled && now - drive.lastSync > 5 * 60_000 && now - lastDriveAttempt > 5 * 60_000) {
            lastDriveAttempt = now
            lifecycleScope.launch { DrivePets.syncIfEnabled(applicationContext) }
        }
    }

    // Widgets reuse the running activity (singleTop); switch to the screen they asked for.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // notes: a file opened "with" this app while it is running.
        afterEditorSaved {
            if (NoteOpenWith.handle(this, intent)) route.value = Route.Notes
            else routeOf(intent)?.let { route.value = it }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_ROUTE, route.value.name)
        noteId.value?.let { outState.putString(EXTRA_NOTE_ID, it) } // notes
    }

    private fun routeOf(intent: Intent?): Route? = intent?.getStringExtra(EXTRA_ROUTE)?.let(::parseRoute)

    private fun parseRoute(name: String): Route? = runCatching { Route.valueOf(name) }.getOrNull()

    companion object {
        /** Intent extra (Route name) used by widgets to open a specific screen. */
        const val EXTRA_ROUTE = "route"
        private const val EXTRA_NOTE_ID = "noteId" // notes

        // Process-wide, so recreating the activity doesn't reset the throttle.
        @Volatile private var lastGoogleAttempt = 0L
        @Volatile private var lastDriveAttempt = 0L
    }
}
