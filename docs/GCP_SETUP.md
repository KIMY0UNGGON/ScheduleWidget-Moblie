# ScheduleWidget Notes Google OAuth 등록

2026-10-06 기준. 업로드할 소스: [KIMY0UNGGON/ScheduleWidget-Moblie](https://github.com/KIMY0UNGGON/ScheduleWidget-Moblie).

**현재 작업은 게시 준비까지입니다.** Calendar·Drive 입력값과 사용 사유, 시연 순서는 [앱 게시 준비](GCP_PUBLISHING_PREP.md)에 정리했습니다. Google 계정 연결·콘솔 등록·사이트 배포·검증 제출은 이번 작업에서 실행하지 않았습니다.

앱 코드와 정책 파일을 준비하는 것과 Cloud Console 등록·Google 검증 승인은 별도 작업입니다. 이 문서는 실제 승인 완료를 의미하지 않습니다. 기존 README의 프로젝트는 `gen-lang-client-0088309798`이며 Windows판과 공유합니다. 프로젝트의 실제 소유·설정은 운영자의 로그인으로 확인해야 합니다.

## 공개 전에 필요한 정보

| 항목 | 현재 확인 / 필요한 작업 |
| --- | --- |
| 앱 이름 | ScheduleWidget Notes / 일정 위젯, Android 노트 배포본 |
| 운영자 | GitHub KIMY0UNGGON |
| 사용자 지원 이메일 | `ddd84860@gmail.com` (운영자가 제공) |
| 개발자 연락처 | `ddd84860@gmail.com` |
| 홈페이지·정책 | `docs/index.html`, `docs/privacy.html` 준비. 비공개 소스 저장소 자체는 공개 정책 URL이 아님 |
| 정책 소스 | `PRIVACY.md`; 앱 안 설정 > 개인정보에도 같은 내용 포함 |
| Android 패키지 | `com.schedulewidget.mobile` |
| 로컬 debug SHA-1 | `9A:16:08:76:BB:B1:A6:36:12:19:44:40:CB:8D:8C:11:2A:60:4F:F7` — 2026-10-06 `signingReport` 재확인 |
| 배포 SHA-1 | 별도 release/Play 앱 서명 인증서로 등록 필요. debug 인증서는 Play 배포 인증서가 아님 |
| 검증 상태 | 로그인한 Cloud Console과 실제 계정으로 확인 필요 |

현재는 사용자가 지정한 **테스트 단계**입니다. 저장소는 비공개이며 OAuth Audience는 향후 콘솔 확인 시 Testing으로 설정합니다. 실제 현재 Audience 상태는 확인하지 않았습니다. 테스트 사용자에는 운영자가 지정한 시험 계정을 추가합니다. 게시 단계에서 공개 정책 URL의 무로그인 접근·소유 확인과 실제 배포 인증서 등록을 완료한 후 해당 검증을 진행합니다.

홈페이지와 개인정보처리방침은 로그인 없이 읽을 수 있는 동일 도메인의 HTTPS 페이지로 게시합니다. 홈페이지는 앱 목적·기능·운영자·지원 창구·개인정보처리방침 링크를 보여 주어야 합니다. 해당 도메인을 프로젝트 소유자/편집자가 Search Console에서 소유 확인하고 Google Auth Platform의 Authorized domains에 넣습니다. 비공개 소스 저장소의 공개 정책 사이트는 별도로 운영할 수 있습니다. 소스 공개나 유료 요금제 변경은 이 등록 작업의 전제가 아닙니다.

## API 및 클라이언트

Cloud Console의 프로젝트에서 Google Calendar API, Google Drive API, YouTube Data API v3를 기능 사용에 맞게 활성화합니다.

캘린더·Drive는 Android Google Play services AuthorizationClient를 사용합니다. Google Auth Platform > Clients에서 Android 클라이언트를 만들고 패키지 이름과 해당 설치본의 서명 SHA-1을 등록합니다. Android 클라이언트에 웹 리디렉트 URL이나 client secret을 넣을 필요는 없습니다. Play App Signing을 쓰면 업로드 키가 아닌 실제 앱 서명 인증서도 등록해야 합니다.

YouTube 재생목록 로그인은 기존 Windows용 설치형 OAuth 클라이언트를 이용한 시스템 브라우저·PKCE·루프백 흐름을 유지합니다. APK 안의 `google_client.bin`은 이 설치형 클라이언트 설정을 담으며, 코드의 고정 키로 읽을 수 있어 비밀 저장소가 아닙니다. 설치형 OAuth client secret은 기밀로 간주할 수 없습니다. Google의 Android OAuth 클라이언트는 루프백 흐름을 지원하지 않으므로, YouTube의 현행 Desktop 클라이언트 재사용을 승인된 Android 인증 흐름으로 취급하면 안 됩니다. 배포 전에 프로젝트 구성과 모바일 인증 방식의 적합성을 확인해야 합니다. 기존 코드에는 Drive와 YouTube 권한을 함께 요청할 때의 실패를 피하려는 분리가 있으므로, 실제 기존 계정의 두 기능을 함께 시험하기 전에는 인증 방식을 일괄 교체하지 않습니다.

## Data Access에 등록할 범위와 사용 이유

| 범위 | 앱에서 쓰는 이유 |
| --- | --- |
| `https://www.googleapis.com/auth/calendar.events` | 기본 캘린더 일정을 앱 일정과 양방향 동기화, 사용자가 만든 일정 추가·수정 및 제한된 삭제 |
| `https://www.googleapis.com/auth/calendar.calendars.readonly` | `calendars/primary?fields=id`로 계정을 식별하여 계정 전환 때 잘못된 이벤트 연결 방지 |
| `https://www.googleapis.com/auth/drive.file` | 앱이 만든 캐릭터 파일 동기화 및 사용자가 별도 동의한 PPT/Word의 임시 변환 사본 관리 |
| `https://www.googleapis.com/auth/youtube.readonly` | 로그인 계정의 재생목록·좋아요 목록 읽기 |
| `email` (`https://www.googleapis.com/auth/userinfo.email`) | YouTube 로그인한 계정 표시 |

캘린더의 이벤트 범위만으로는 기본 캘린더 메타데이터 API를 호출할 수 없습니다. 전체 캘린더/전체 Drive 범위로 확대하지 않습니다. 앱이 실제 요청하는 범위와 콘솔 등록, 정책, 시연 영상이 일치해야 합니다. 캘린더·YouTube 등 민감 범위는 외부 공개 앱의 Google 검증 요구사항을 확인하고 범위별 이유·기능 시연을 제출합니다. `drive.file`은 권장 비민감 범위입니다.

## 동의 화면과 시연

1. Branding에서 앱 이름, 실제 지원 이메일, 홈페이지·정책 URL, 확인된 도메인과 개발자 연락처를 입력합니다. 공유 프로젝트를 계속 쓴다면 Windows판의 정책/홈페이지도 함께 검토합니다.
2. Audience에서 대상 사용자에 맞게 설정합니다. 개인 구글 계정 등 조직 외 사용자를 받으면 External을 사용합니다. Testing 중에는 운영자가 지정한 테스트 계정으로 확인하며, 테스트 모드 제한을 확인합니다.
3. Data Access에 위 실제 범위를 등록하고 최소 범위 사용 사유를 씁니다.
4. 깨끗한 설치본에서 구글 동의 화면, 캘린더 연결·수정·삭제 안내, 다른 계정 재연결 보호, Drive 캐릭터 동기화, PPT/Word 업로드의 별도 동의·삭제 실패 안내, YouTube 목록 가져오기, 연결 해제·Google 계정 권한 관리 링크를 보여 주는 영상을 준비합니다. 실제 개인 일정이나 문서 대신 테스트 데이터를 사용합니다.
5. Branding verification과 민감 범위 검증을 각각 제출합니다. 검증 결과를 받은 뒤 실제 Audience 게시 상태를 확인합니다. 앱 코드에 정책을 추가했다는 이유만으로 ‘Google 검증 완료’라고 표시하지 않습니다.

## 서명과 배포

로컬 회귀 검사용 APK는 기존 debug 서명을 유지합니다. release 빌드는 debug 키로 대체 서명하지 않습니다. 실제 release 서명은 다음 환경 변수가 모두 주어졌을 때만 적용됩니다.

```text
SCHEDULEWIDGET_KEYSTORE
SCHEDULEWIDGET_STORE_PASSWORD
SCHEDULEWIDGET_KEY_ALIAS
SCHEDULEWIDGET_KEY_PASSWORD
```

키·비밀번호·개인 문서·로그인 토큰은 저장소에 올리지 않습니다. `gradlew.bat :app:signingReport`로 등록 대상 인증서를 확인합니다. 키가 없으면 release 결과는 서명되지 않으므로 설치·공개 배포본으로 사용하지 않습니다.

## 근거

- [Google 민감 범위 검증 요구사항](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)
- [Android Google 데이터 접근 권한](https://developer.android.com/identity/authorization)
- [Calendar calendars.get 허용 범위](https://developers.google.com/workspace/calendar/api/v3/reference/calendars/get)
- [Drive 범위별 분류](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [모바일 루프백 OAuth 이전 안내](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration)
- [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
