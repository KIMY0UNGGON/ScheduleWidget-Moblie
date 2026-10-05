package com.schedulewidget.mobile.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal object GoogleCalendarAuthorization {
    private const val EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.events"
    // calendars.get is used only to identify which account owns the primary calendar. `calendar.events` does not grant
    // that metadata endpoint, so request its narrower read-only scope explicitly instead of guessing the account.
    private const val CALENDARS_READONLY_SCOPE = "https://www.googleapis.com/auth/calendar.calendars.readonly"
    suspend fun authorize(context: Context): GoogleCalendarSync.Auth = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(EVENTS_SCOPE), Scope(CALENDARS_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { r ->
                if (!cont.isActive) return@addOnSuccessListener
                val pending = r.pendingIntent
                cont.resume(
                    when {
                        r.hasResolution() && pending != null -> GoogleCalendarSync.Auth.NeedsConsent(IntentSenderRequest.Builder(pending.intentSender).build())
                        r.accessToken != null -> GoogleCalendarSync.Auth.Token(r.accessToken!!)
                        else -> GoogleCalendarSync.Auth.Failed("구글 캘린더 권한을 받지 못했습니다.")
                    }
                )
            }
            .addOnFailureListener { e ->
                if (!cont.isActive) return@addOnFailureListener
                cont.resume(GoogleCalendarSync.Auth.Failed(when ((e as? ApiException)?.statusCode) {
                    CommonStatusCodes.DEVELOPER_ERROR -> "이 앱의 구글 로그인이 아직 등록되지 않았습니다. (README 참고)"
                    CommonStatusCodes.NETWORK_ERROR -> "네트워크에 연결할 수 없습니다."
                    else -> "구글 로그인 실패: ${e.message ?: e.javaClass.simpleName}"
                }))
            }
            .addOnCanceledListener { if (cont.isActive) cont.resume(GoogleCalendarSync.Auth.Failed("로그인을 취소했습니다.")) }
    }

    fun tokenFrom(activity: Activity, data: Intent?): GoogleCalendarSync.Auth = runCatching {
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
            ?.let { GoogleCalendarSync.Auth.Token(it) } ?: GoogleCalendarSync.Auth.Failed("구글 캘린더 권한을 받지 못했습니다.")
    }.getOrElse { GoogleCalendarSync.Auth.Failed(it.message ?: "구글 캘린더 권한을 받지 못했습니다.") }
}
