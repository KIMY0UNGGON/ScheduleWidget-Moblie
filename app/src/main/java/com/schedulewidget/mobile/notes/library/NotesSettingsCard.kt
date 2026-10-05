package com.schedulewidget.mobile.notes.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.NotesSettings
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTheme
import com.schedulewidget.mobile.notes.ui.NotesTokens

/**
 * Settings section "노트": the feature switch, 변환 방식 (자동 / 구글 드라이브 / 폰에서), 손가락으로도 필기.
 * Drawn in the notes design language but on a transparent background with the settings screen's 16dp gutters, and
 * the section title styled like the screen's other section titles, so it sits naturally between them.
 */
@Composable
fun NotesSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val notes = data.notes
    fun set(f: (NotesSettings) -> NotesSettings) = repo.update { it.copy(notes = f(it.notes)) }

    // The title matches ui/Screens.kt SectionTitle (outside NotesTheme on purpose: it belongs to the settings screen).
    Column(modifier.fillMaxWidth()) {
        Text(
            "노트", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        NotesTheme {
            Column(Modifier.fillMaxWidth()) {
                NotesSwitchRow("노트 기능", "PDF·PPT·Word 위에 S펜으로 필기 · 노트 탭", notes.enabled, { on -> set { it.copy(enabled = on) } })
                if (notes.enabled) {
                    val c = NotesTokens.colors
                    Column(Modifier.padding(horizontal = NotesSpace.md, vertical = NotesSpace.sm)) {
                        Text("PPT·Word 변환 방식", style = NotesTokens.type.body)
                        NotesSegmented(
                            options = listOf("auto" to "자동", "drive" to "구글 드라이브", "offline" to "폰에서"),
                            selected = notes.convert.takeIf { it == "drive" || it == "offline" } ?: "auto",
                            onSelect = { key -> set { it.copy(convert = key) } },
                            modifier = Modifier.fillMaxWidth().padding(top = NotesSpace.sm),
                        )
                        Text(
                            when (notes.convert) {
                                "drive" -> "문서 업로드에 동의하면 구글 Drive에서 PDF로 바꿔요. 인터넷과 구글 로그인이 필요해요. 변환 뒤 임시 사본을 지우지만, 오류가 나면 Drive에 남을 수 있어요."
                                "offline" -> "인터넷 없이 폰에서 바꿔요. 글자·그림·표 위주로 단순하게 그려져서 모양이 조금 다를 수 있어요."
                                else -> "문서 업로드 동의와 구글 Drive 권한이 있으면 Drive로, 아니면 폰에서 바꿔요. 처음 구글로 보낼 때 별도로 확인합니다."
                            },
                            style = NotesTokens.type.bodySm.copy(color = c.muted),
                            modifier = Modifier.padding(top = NotesSpace.sm),
                        )
                    }
                    NotesSwitchRow(
                        "손가락으로도 필기", "끄면 S펜으로만 쓰고, 손가락은 넘기기·확대에 써요", notes.fingerDraws,
                        { on -> set { it.copy(fingerDraws = on) } },
                    )
                }
            }
        }
    }
}
