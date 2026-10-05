package com.schedulewidget.mobile.privacy

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun GoogleDocumentConsentDialog() {
    val context = LocalContext.current
    val request by GoogleDocumentConsent.pending.collectAsStateWithLifecycle()
    request?.let { current ->
        AlertDialog(
            onDismissRequest = { GoogleDocumentConsent.respond(context, current, false) },
            title = { Text("구글 Drive로 문서를 변환할까요?") },
            text = {
                Text(
                    "‘${current.title}’의 내용과 파일 이름을 선택한 구글 계정의 Drive에 보내 PDF로 변환합니다. " +
                        "임시 사본은 변환 뒤 삭제를 시도하며, 오류가 나면 Drive에 남을 수 있어요. " +
                        "허용하면 이후 선택한 PPT·Word 파일에도 적용됩니다. 설정 > 개인정보에서 허용을 끌 수 있어요. " +
                        "폰에서 변환하면 문서를 구글로 보내지 않습니다. 예전 .ppt/.doc 형식은 구글 변환이 필요해요.",
                )
            },
            confirmButton = {
                TextButton(onClick = { GoogleDocumentConsent.respond(context, current, true) }) { Text("구글로 변환") }
            },
            dismissButton = {
                TextButton(onClick = { GoogleDocumentConsent.respond(context, current, false) }) { Text("허용하지 않음") }
            },
        )
    }
}
