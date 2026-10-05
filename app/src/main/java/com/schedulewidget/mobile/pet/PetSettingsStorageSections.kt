package com.schedulewidget.mobile.pet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PetDriveSettings(
    enabled: Boolean,
    busy: Boolean,
    status: String?,
    onManageAccount: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSync: () -> Unit,
) {
    Text("구글 드라이브 캐릭터 보관", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy) { onEnabledChange(!enabled) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("PC 앱과 캐릭터 동기화", style = MaterialTheme.typography.bodyLarge)
            Text(
                "PC 앱의 ‘캐릭터도 구글 드라이브에 보관’과 같은 구글 계정으로 로그인하면, PC에서 가져온 캐릭터가 여기로 오고 " +
                    "여기서 가져온 캐릭터도 PC로 갑니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, enabled = !busy, onCheckedChange = onEnabledChange)
    }
    Text(
        "스위치를 끄면 자동 동기화만 멈춥니다. Google 계정 권한과 Drive 파일은 남습니다.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onManageAccount) { Text("Google 계정 권한 관리") }
    if (enabled) Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onSync, enabled = !busy) { Text("지금 동기화") }
        if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}

@Composable
internal fun PetImportSettings(
    message: String?,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
) {
    Text("캐릭터 가져오기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImportFiles) { Text("pet.json · .zip") }
        OutlinedButton(onClick = onImportFolder) { Text("캐릭터 폴더") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Text(
        "PC 앱에서 캐릭터 설정 → ‘캐릭터 내보내기’로 만든 .zip을 휴대폰에 옮겨 고르면 같은 캐릭터가 들어옵니다. " +
            "pet.json을 고를 때는 스프라이트 이미지도 함께 선택하세요(안 고르면 이어서 물어봅니다). " +
            "PC의 %LocalAppData%\\ScheduleWidget\\Pet\\<캐릭터> 폴더를 통째로 옮겨 ‘캐릭터 폴더’로 골라도 됩니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "사진, PNG, WebP, GIF 이미지를 캐릭터로 쓸 수 있습니다. GIF는 첫 장면만 표시됩니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(1.dp))
}
