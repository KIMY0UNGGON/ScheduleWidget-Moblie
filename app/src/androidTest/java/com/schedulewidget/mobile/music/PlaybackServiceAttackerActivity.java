package com.schedulewidget.mobile.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import dalvik.system.DexClassLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs with the androidTest APK UID to exercise exported components as an ordinary external app. */
public final class PlaybackServiceAttackerActivity extends Activity {
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_SHORTCUT_BLOCKED = "shortcut_blocked";
    public static final String EXTRA_SESSION_REJECTED = "session_rejected";
    public static final String EXTRA_SESSION_SETUP_ERROR = "session_setup_error";
    public static final String EXTRA_SET_MEDIA_ITEM = "set_media_item";
    public static final String EXTRA_CHANGE_MEDIA_ITEMS = "change_media_items";

    private static final String TARGET_PACKAGE = "com.schedulewidget.mobile";
    private static final String TARGET_SERVICE = "com.schedulewidget.mobile.music.PlaybackService";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ResultReceiver receiver = Build.VERSION.SDK_INT >= 33
                ? getIntent().getParcelableExtra(EXTRA_RESULT, ResultReceiver.class)
                : getIntent().getParcelableExtra(EXTRA_RESULT);
        if (receiver == null) {
            finish();
            return;
        }

        boolean shortcutBlocked = probeShortcut();
        probeMediaSession(receiver, shortcutBlocked);
    }

    private boolean probeShortcut() {
        ComponentName shortcut = new ComponentName(
                TARGET_PACKAGE, "com.schedulewidget.mobile.pet.IdShortcutActivity");
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(shortcut, 0);
            if (!info.exported) {
                try {
                    startActivity(new Intent().setComponent(shortcut));
                } catch (SecurityException expected) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Report false so the instrumentation fails if it cannot inspect the target component.
        }
        return false;
    }

    private void probeMediaSession(ResultReceiver receiver, boolean shortcutBlocked) {
        final DexClassLoader loader;
        final Object future;
        try {
            ApplicationInfo target = getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0);
            ServiceInfo targetService = getPackageManager().getServiceInfo(
                    new ComponentName(TARGET_PACKAGE, TARGET_SERVICE), 0);
            if (!targetService.exported) throw new IllegalStateException("PlaybackService is not exported");
            if (targetService.applicationInfo.uid == android.os.Process.myUid()) {
                throw new IllegalStateException("Attacker Activity is running with the target UID");
            }
            if (targetService.permission != null &&
                    checkSelfPermission(targetService.permission) != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("PlaybackService requires " + targetService.permission);
            }
            // Media3 is packaged in the target APK, not this test APK; load the installed target code read-only.
            loader = new DexClassLoader(
                    target.sourceDir, getCodeCacheDir().getAbsolutePath(), null, getClassLoader());
            Class<?> tokenClass = loader.loadClass("androidx.media3.session.SessionToken");
            Object token = tokenClass.getConstructor(Context.class, ComponentName.class)
                    .newInstance(this, new ComponentName(TARGET_PACKAGE, TARGET_SERVICE));
            Class<?> builderClass = loader.loadClass("androidx.media3.session.MediaController$Builder");
            Object builder = builderClass.getConstructor(Context.class, tokenClass).newInstance(this, token);
            future = builderClass.getMethod("buildAsync").invoke(builder);
        } catch (Throwable error) {
            sendResult(receiver, shortcutBlocked, false, false, null, null, null,
                    error.getClass().getSimpleName() + ": " + error.getMessage());
            return;
        }
        new Thread(() -> awaitMediaSession(future, loader, receiver, shortcutBlocked),
                "external-media-controller").start();
    }

    private void awaitMediaSession(Object futureObject, DexClassLoader loader,
            ResultReceiver receiver, boolean shortcutBlocked) {
        boolean connected = false;
        boolean rejected = false;
        Object controller = null;
        String sessionError = null;
        String setupError = null;
        try {
            controller = ((Future<?>) futureObject).get(10, TimeUnit.SECONDS);
            connected = true;
        } catch (ExecutionException denied) {
            rejected = true;
            Throwable cause = denied.getCause();
            sessionError = cause == null ? denied.getClass().getSimpleName()
                    : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (TimeoutException timeout) {
            setupError = "MediaController connection timed out";
        } catch (Throwable error) {
            setupError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        final Object connectedController = controller;
        final boolean didConnect = connected;
        final boolean wasRejected = rejected;
        final String error = sessionError;
        final String setup = setupError;
        runOnUiThread(() -> sendResult(receiver, shortcutBlocked, didConnect, wasRejected,
                connectedController, loader, error, setup));
    }

    private void sendResult(ResultReceiver receiver, boolean shortcutBlocked, boolean connected,
            boolean rejected, Object controller, DexClassLoader loader,
            String sessionError, String setupError) {
        boolean canSetMediaItem = false;
        boolean canChangeMediaItems = false;
        String error = setupError;
        if (controller != null) {
            try {
                Class<?> playerClass = loader.loadClass("androidx.media3.common.Player");
                int setCommand = playerClass.getField("COMMAND_SET_MEDIA_ITEM").getInt(null);
                int changeCommand = playerClass.getField("COMMAND_CHANGE_MEDIA_ITEMS").getInt(null);
                Method isCommandAvailable = controller.getClass().getMethod("isCommandAvailable", int.class);
                canSetMediaItem = (Boolean) isCommandAvailable.invoke(controller, setCommand);
                canChangeMediaItems = (Boolean) isCommandAvailable.invoke(controller, changeCommand);
            } catch (Throwable failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            } finally {
                try {
                    controller.getClass().getMethod("release").invoke(controller);
                } catch (Throwable failure) {
                    if (error == null) error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                }
            }
        }
        Bundle result = new Bundle();
        result.putBoolean(EXTRA_CONNECTED, connected);
        result.putBoolean(EXTRA_SESSION_REJECTED, rejected);
        result.putBoolean(EXTRA_SET_MEDIA_ITEM, canSetMediaItem);
        result.putBoolean(EXTRA_CHANGE_MEDIA_ITEMS, canChangeMediaItems);
        result.putBoolean(EXTRA_SHORTCUT_BLOCKED, shortcutBlocked);
        result.putString(EXTRA_SESSION_SETUP_ERROR, error);
        result.putString("session_error", sessionError);
        receiver.send(RESULT_OK, result);
        finish();
    }
}
