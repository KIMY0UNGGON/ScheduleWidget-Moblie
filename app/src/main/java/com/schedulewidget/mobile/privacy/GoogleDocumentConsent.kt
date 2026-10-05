package com.schedulewidget.mobile.privacy

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Document-upload consent is separate from a Drive grant used for pet synchronization. */
object GoogleDocumentConsent {
    private const val PREFS = "privacy_choices"
    private const val DOCUMENT_UPLOAD = "document_upload_consent_version"
    private const val VERSION = 1
    private val mutex = Mutex()

    class Request internal constructor(val title: String) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending = _pending.asStateFlow()
    private val _choicesChanged = MutableStateFlow(0)
    val choicesChanged = _choicesChanged.asStateFlow()

    fun isAllowed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(DOCUMENT_UPLOAD, 0) == VERSION

    fun revoke(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(DOCUMENT_UPLOAD).apply()
        _choicesChanged.value++
    }

    suspend fun awaitPermission(context: Context, title: String): Boolean = mutex.withLock {
        if (isAllowed(context)) return@withLock true
        val request = Request(title)
        _pending.value = request
        try {
            request.answer.await()
        } finally {
            _pending.compareAndSet(request, null)
        }
    }

    fun respond(context: Context, request: Request, allow: Boolean) {
        if (_pending.value !== request || request.answer.isCompleted) return
        if (allow) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(DOCUMENT_UPLOAD, VERSION).apply()
            _choicesChanged.value++
        }
        request.answer.complete(allow)
    }

    suspend fun showCleanupWarning(context: Context) = withContext(Dispatchers.Main) {
        Toast.makeText(
            context.applicationContext,
            "변환은 끝났지만 Drive 임시 사본의 삭제를 확인하지 못했어요. 이후 구글 변환 때 정리를 다시 시도합니다. Drive에서도 확인할 수 있어요.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
