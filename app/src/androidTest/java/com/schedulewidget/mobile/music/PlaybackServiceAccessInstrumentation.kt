package com.schedulewidget.mobile.music

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val ATTACKER_ACTIVITY = "com.schedulewidget.mobile.music.PlaybackServiceAttackerActivity"
private const val EXTRA_RESULT = "result"
private const val EXTRA_CONNECTED = "connected"
private const val EXTRA_SESSION_REJECTED = "session_rejected"
private const val EXTRA_SESSION_ERROR = "session_error"
private const val EXTRA_SESSION_SETUP_ERROR = "session_setup_error"
private const val EXTRA_SET_MEDIA_ITEM = "set_media_item"
private const val EXTRA_CHANGE_MEDIA_ITEMS = "change_media_items"
private const val EXTRA_SHORTCUT_BLOCKED = "shortcut_blocked"
private const val ARG_EXPECT_SESSION_BLOCKED = "expectedSessionBlocked"
private const val ARG_EXPECT_SHORTCUT_BLOCKED = "expectedShortcutBlocked"

/** Runs the attacker in the separate androidTest APK UID and verifies the observed boundary. */
class PlaybackServiceAccessInstrumentation : Instrumentation() {
    private var expectedSessionBlocked = true
    private var expectedShortcutBlocked = true

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedSessionBlocked = arguments?.getString(ARG_EXPECT_SESSION_BLOCKED)?.toBooleanStrictOrNull() ?: true
        expectedShortcutBlocked = arguments?.getString(ARG_EXPECT_SHORTCUT_BLOCKED)?.toBooleanStrictOrNull() ?: true
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        var connected = false
        var rejected = false
        var canSetMediaItem = false
        var canChangeMediaItems = false
        var shortcutBlocked = false
        var setupError: String? = null
        var sessionError: String? = null
        try {
            val latch = CountDownLatch(1)
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    connected = resultData?.getBoolean(EXTRA_CONNECTED, false) ?: false
                    rejected = resultData?.getBoolean(EXTRA_SESSION_REJECTED, false) ?: false
                    canSetMediaItem = resultData?.getBoolean(EXTRA_SET_MEDIA_ITEM, false) ?: false
                    canChangeMediaItems = resultData?.getBoolean(EXTRA_CHANGE_MEDIA_ITEMS, false) ?: false
                    shortcutBlocked = resultData?.getBoolean(EXTRA_SHORTCUT_BLOCKED, false) ?: false
                    setupError = resultData?.getString(EXTRA_SESSION_SETUP_ERROR)
                    sessionError = resultData?.getString(EXTRA_SESSION_ERROR)
                    latch.countDown()
                }
            }
            val testContext = context
            runOnMainSync {
                testContext.startActivity(
                    Intent().setClassName(testContext, ATTACKER_ACTIVITY)
                        .putExtra(EXTRA_RESULT, receiver)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            check(latch.await(20, TimeUnit.SECONDS)) { "The external component probes timed out" }
            check(setupError == null) { "External Media3 controller setup failed: $setupError" }
            check(connected == !expectedSessionBlocked) {
                "Unexpected external media-session connection result: connected=$connected"
            }
            check(rejected == expectedSessionBlocked) {
                "The external controller did not report the expected connection outcome"
            }
            check(canSetMediaItem == !expectedSessionBlocked && canChangeMediaItems == !expectedSessionBlocked) {
                "Unexpected external media-item command availability"
            }
            check(shortcutBlocked == expectedShortcutBlocked) {
                "Unexpected external mobile-ID shortcut result: blocked=$shortcutBlocked"
            }
        } catch (e: Throwable) {
            failure = e
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", failure?.stackTraceToString() ?:
                    "PASS: external controller connected=$connected rejected=$rejected error=$sessionError, " +
                        "setMediaItem=$canSetMediaItem changeMediaItems=$canChangeMediaItems; shortcutBlocked=$shortcutBlocked\n")
            },
        )
    }
}

/** Focused in-process checks for the policy functions used by PlaybackService. */
class PlaybackServicePolicyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            fun controller(uid: Int, trusted: Boolean) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                "test.controller", Process.myPid(), uid, 1, 1, trusted, Bundle.EMPTY,
            )

            val own = controller(Process.myUid(), trusted = false)
            val untrusted = controller(Process.myUid() + 10_000, trusted = false)
            val trustedExternal = controller(Process.myUid() + 10_001, trusted = true)
            check(PlaybackService.allowsController(own)) { "The app's own media controller was denied" }
            check(!PlaybackService.allowsController(untrusted)) { "An untrusted controller was accepted" }
            check(PlaybackService.allowsController(trustedExternal)) { "A trusted system controller was denied" }

            val ownCommands = PlaybackService.playerCommandsFor(own)
            check(ownCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
            check(ownCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
            val externalCommands = PlaybackService.playerCommandsFor(trustedExternal)
            check(!externalCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
            check(!externalCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
            check(externalCommands.contains(Player.COMMAND_PLAY_PAUSE))

            val sharedFile = MediaItem.fromUri(Uri.parse("content://com.example.provider/audio/1"))
            val remoteFile = MediaItem.fromUri(Uri.parse("https://192.168.1.1/audio"))
            check(PlaybackService.acceptsMediaItems(own, listOf(sharedFile))) { "A SAF content URI was rejected" }
            check(!PlaybackService.acceptsMediaItems(own, listOf(sharedFile, remoteFile))) {
                "A network URI was accepted by the local player"
            }
            check(!PlaybackService.acceptsMediaItems(trustedExternal, listOf(sharedFile))) {
                "A trusted external controller could replace the local playlist"
            }
        } catch (e: Throwable) {
            failure = e
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString() ?: "PASS: playback policy is restricted\n") },
        )
    }
}
