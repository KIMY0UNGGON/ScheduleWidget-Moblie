# Calendar·Drive 앱 게시 준비

**다음 게시 작업은 아래 등록값에서 시작합니다.** 2026-10-06 로컬 준비 자료입니다. Google 계정 연결, 콘솔 변경, 사이트 배포, 검증 제출은 실행하지 않았습니다.

## 준비된 자료

| 자료 | 내용 |
| --- | --- |
| [등록값 JSON](gcp-registration.json) | 현재 소스의 패키지·버전·요청 범위와 실제 debug 서명 SHA-1 |
| [홈페이지](index.html) · [개인정보처리방침](privacy.html) | 공개 HTTPS 사이트에 올릴 정적 페이지 |
| [정책 원문](../PRIVACY.md) | 앱 안 정책과 웹 정책의 공통 원문 |
| [전체 OAuth 안내](GCP_SETUP.md) | 서명·기존 Windows/YouTube 클라이언트를 포함한 운영 참고 |

소스에는 Calendar·Drive용 Android `AuthorizationClient`, 계정 전환 보호, 연결 해제, 문서 업로드 별도 동의가 있습니다. 실제 요청 범위에 맞춘 준비 자료이며 계정 연동 성공 기록은 아닙니다.

## 콘솔 입력값

| 항목 | 준비값 |
| --- | --- |
| 앱 이름 | `ScheduleWidget Notes` |
| 앱 설명 | 일정·필기·녹음을 함께 관리하는 Android 앱. 선택적으로 Google Calendar 기본 캘린더와 일정을 맞추고, Drive로 사용자 캐릭터를 동기화하거나 별도 동의한 문서를 PDF로 변환합니다. |
| 지원·개발자 이메일 | `ddd84860@gmail.com` |
| 기존 인계 프로젝트 ID | `gen-lang-client-0088309798` — 실제 소유·기존 등록 상태는 향후 확인 |
| Android 패키지 | `com.schedulewidget.mobile` |
| 현재 시험 APK | `Ver 0.0`, `versionCode 4` |
| 현재 debug SHA-1 | `9A:16:08:76:BB:B1:A6:36:12:19:44:40:CB:8D:8C:11:2A:60:4F:F7` — 2026-10-06 `signingReport` 재확인 |
| 실제 배포 인증서 | 미정. 자체 release 키 또는 Play의 **앱 서명 키** SHA-1 별도 확인 |
| 대상 사용자 준비값 | 개인 Google 계정을 포함하면 `External`; 시험은 `Testing`과 지정 테스트 사용자 |
| 홈페이지·정책 URL·도메인 | 미정. 로그인 없이 열리는 공개 HTTPS 주소와 도메인 소유 확인 필요 |
| API | Google Calendar API (`calendar-json.googleapis.com`), Google Drive API (`drive.googleapis.com`) |

Android 클라이언트는 패키지와 배포 인증서 SHA-1로 등록합니다. 현재 앱은 기기에서 권한을 받아 API를 호출하므로 서버용 코드 교환이나 client secret 추가가 필요하지 않습니다. [Android 권한 등록](https://developer.android.com/identity/authorization)

## Data Access 사용 사유 초안

| 요청 범위 | 제출용 사용 사유 |
| --- | --- |
| `https://www.googleapis.com/auth/calendar.events.owned` | 소유한 기본 캘린더 일정을 앱에 표시하고 앱에서 만든 일정의 추가·수정·삭제를 반영합니다. 삭제는 앱에서 연결한 일정에 한해 사용자의 동작과 동기화 설정에 따라 처리합니다. |
| `https://www.googleapis.com/auth/calendar.calendarlist.readonly` | 기본 캘린더 ID로 연결 계정을 구분합니다. 계정 변경 시 이전 계정의 이벤트 ID를 새 계정에 잘못 적용하지 않도록 연결 정보를 분리합니다. |
| `https://www.googleapis.com/auth/drive.file` | 앱이 만든 사용자 캐릭터 ZIP을 동기화하고, 별도 업로드 동의가 있는 문서의 임시 변환 사본을 생성·읽기·삭제합니다. Drive 전체 탐색 권한은 요청하지 않습니다. |

범위는 현재 소스와 일치합니다. `drive.file`은 Google의 비민감 권장 범위이며 공개 앱의 범위별 검증 요구사항은 게시 시 확인합니다. [Calendar 범위](https://developers.google.com/workspace/calendar/api/auth), [Drive 범위](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)

Windows판과 프로젝트를 공유하면 다른 클라이언트의 기존 범위도 확인합니다. 여기서는 Calendar·Drive 세 범위를 준비했습니다. 기존 YouTube 인증의 배포 적합성은 [전체 안내](GCP_SETUP.md)를 참고합니다.

## 향후 게시 단계

1. 공개 홈페이지·정책을 HTTPS 사이트에 배포하고 도메인 소유를 확인합니다. 홈페이지에는 앱 목적·운영자·지원 연락처·정책 링크를 표시합니다.
2. 실제 배포 서명을 정하고 해당 패키지·SHA-1의 Android OAuth 클라이언트를 확인합니다. 기존 Desktop 클라이언트는 Android 등록을 대신하지 않습니다.
3. 선택한 프로젝트의 Branding·Audience·Data Access와 API 활성화를 위 자료에 맞춰 등록하고 현재 상태를 기록합니다.
4. 테스트 계정·가상 일정·가상 문서로 아래 시연을 촬영합니다. 실제 개인 자료는 시연에 사용하지 않습니다.
5. 적용되는 브랜드·민감 범위 검증을 제출하고 결과를 확인한 뒤 게시 상태를 바꿉니다.

도메인과 홈페이지는 [Google 브랜드 검증 조건](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification)에 맞춰 준비합니다. 저장소 공개와 정책 사이트 공개는 별도로 결정할 수 있습니다.

### 시연 순서

| 순서 | 보여 줄 동작 |
| --- | --- |
| 1 | 깨끗한 설치 → 앱 안 정책 → Calendar 연결 → 실제 권한의 Google 동의 화면 |
| 2 | 테스트 일정 읽기·생성·수정·삭제 → 다른 계정으로 전환 시 연결 분리 |
| 3 | Drive 권한 동의 → 테스트 캐릭터 업로드·다운로드·삭제 반영 |
| 4 | 문서 업로드 별도 동의 → 가상 PPTX/DOCX 변환 → 임시 사본 삭제 결과 → 업로드 동의 철회 |
| 5 | 앱 연결 해제 → Google 서드 파티 권한 관리 → 재연결 시 다시 동의 |

## 로컬 자료 갱신

프로젝트 루트에서 실행합니다. 아래 명령은 Google에 요청을 보내지 않습니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:signingReport | Out-File signing-report.txt -Encoding utf8
python tools/prepare_gcp.py --signing-report signing-report.txt
python tools/generate_privacy.py
```

`prepare_gcp.py`는 소스의 요청 범위가 위 세 범위와 달라지면 중단합니다. 이때 게시 사용 사유도 함께 갱신합니다. debug 인증서는 현재 시험 APK용이며 실제 배포 인증서를 대체하지 않습니다.
