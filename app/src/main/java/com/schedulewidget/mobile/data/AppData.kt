package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Mirrors the desktop ScheduleWidget schedules.json (PascalCase keys), so a desktop file can be imported as-is.
 * Unknown desktop keys (window positions, Kakao/Telegram settings, …) are ignored on read.
 * Fields prefixed "Mobile" do not exist on the desktop.
 */
@Serializable
data class AppData(
    @SerialName("Schedules") val schedules: List<ScheduleItem> = emptyList(),
    @SerialName("Music") val music: MusicSettings = MusicSettings(),
    @SerialName("MiniDayCount") val miniDayCount: Int = 7,
    @SerialName("MiniPlayerVisible") val miniPlayerVisible: Boolean = true,
    @SerialName("MiniCharacterVisible") val miniCharacterVisible: Boolean = true,
    @SerialName("MiniCharacterScale") val miniCharacterScale: Int = 100,
    @SerialName("CharacterManifest") val characterManifest: String = "builtin:mochi-white",
    @SerialName("CharacterAnimation") val characterAnimation: String = "idle",
    @SerialName("MiniExtraCharacters") val miniExtraCharacters: List<MiniCharacterSlot> = emptyList(),
    @SerialName("MiniFirstPetHidden") val miniFirstPetHidden: Boolean = false,
    @SerialName("MiniFirstPetFlipped") val miniFirstPetFlipped: Boolean = false,
    @SerialName("MiniFlipEffect") val miniFlipEffect: Int = 1,
    @SerialName("MobilePetPositions") val petPositions: Map<String, PetPosition> = emptyMap(),
    // mobile only: mini calendar theme id (see ui/MiniThemes.kt)
    @SerialName("MobileMiniTheme") val miniTheme: String = "paper",
    // mobile only: external music apps the user chose to show/control, in display order (package names)
    @SerialName("MobileMusicApps") val musicApps: List<String> = emptyList(),
    // mobile only: current playback source. "widget" = in-app playlist, otherwise a package name from musicApps.
    @SerialName("MobileMusicSource") val musicSource: String = SOURCE_WIDGET,
    // mobile only: device calendar (Google Calendar / system calendar app) integration
    @SerialName("MobileCalendar") val calendar: CalendarSync = CalendarSync(),
    // mobile only: where the user dragged the in-app pet (pet center as a fraction of the screen). null = default spot.
    @SerialName("MobilePetX") val petX: Float? = null,
    @SerialName("MobilePetY") val petY: Float? = null,
    // mobile only: double-tapping the in-app pet hides/shows the calendar board.
    @SerialName("MobileCalendarHidden") val calendarHidden: Boolean = false,
    // mobile only: the pet floats over other apps (PetOverlayService), even after the app is closed.
    @SerialName("MobileFloatingPet") val floatingPet: Boolean = false,
    // mobile only: apps pinned to the pet's speech bubble (package names, up to 3; pet/PetApps.kt).
    @SerialName("MobilePetApps") val petApps: List<String> = emptyList(),
    // mobile only: loudness boost above 100% (100–300) for sound this app plays itself (widget playlist, recordings).
    @SerialName("MobileVolumeBoost") val volumeBoost: Int = 100,
    // Deadline reminders — the PC's "Reminders" block (same keys and defaults), so both apps share one setting.
    @SerialName("Reminders") val reminders: ReminderSettings = ReminderSettings(),
    // PC key: show D-day (D-3 / D-day / D+2) on the calendar blocks.
    @SerialName("MiniBlockDDayVisible") val miniBlockDDayVisible: Boolean = true,
    // mobile only: calendar text size in percent (80–150).
    @SerialName("MobileCalendarTextScale") val calendarTextScale: Int = 100,
    // mobile only: lecture recording / on-device transcription settings (stt/*, record/*).
    @SerialName("MobileStt") val stt: SttSettings = SttSettings(),
    // mobile only: the 노트 (PDF / PPT / Word handwriting notes) feature.
    @SerialName("MobileNotes") val notes: NotesSettings = NotesSettings(),
    // mobile only: floating pet window position in screen px (top-left).
    @SerialName("MobileFloatingX") val floatingX: Int = 60,
    @SerialName("MobileFloatingY") val floatingY: Int = 600,
    // mobile only: who plays YouTube tracks of widget playlists. "embed" = in-app player; otherwise the package of the
    // official YouTube Music / YouTube app, which plays with the user's own account (Premium: no ads, background play).
    @SerialName("MobileYouTubePlayer") val youTubePlayer: String = YOUTUBE_MUSIC_APP,
    // mobile only: in-app calendar layout. "month" grid, "week" (Mon–Sun, one day per row going down),
    // or "custom" (MiniDayCount days from the chosen date, side by side).
    @SerialName("MobileCalendarView") val calendarView: String = VIEW_WEEK,
    // mobile only: size the user dragged the floating pet's calendar panel to (dp). 0 width = nearly screen wide.
    @SerialName("MobilePetCalendarWidth") val petCalendarWidth: Int = 0,
    @SerialName("MobilePetCalendarHeight") val petCalendarHeight: Int = 260,
    // mobile only: imported characters kept in Google Drive together with the PC app (pet/DrivePets.kt).
    @SerialName("MobilePetDrive") val petDrive: PetDriveSync = PetDriveSync(),
    // mobile only: two-way sync of the schedules with the Google account's primary calendar (like the PC's 구글 캘린더 연동).
    @SerialName("MobileGoogleCalendar") val googleCalendar: GoogleCalendarLink = GoogleCalendarLink(),
) {
    companion object {
        const val SOURCE_WIDGET = "widget"
        const val YOUTUBE_EMBED = "embed"
        const val YOUTUBE_MUSIC_APP = "com.google.android.apps.youtube.music"
        const val YOUTUBE_APP = "com.google.android.youtube"
        const val SPOTIFY_APP = "com.spotify.music"
        const val VIEW_MONTH = "month"
        const val VIEW_WEEK = "week"
        const val VIEW_CUSTOM = "custom"
        const val MAX_CUSTOM_DAYS = 14
    }
}
