package com.schedulewidget.mobile.apps

import android.service.notification.NotificationListenerService

/** Exists so the user can grant notification access (needed for MediaSessionManager.getActiveSessions). */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MusicHub.refresh(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Access revoked (or unbound): let MusicHub drop its now-unusable controllers.
        MusicHub.refresh(applicationContext)
    }
}
