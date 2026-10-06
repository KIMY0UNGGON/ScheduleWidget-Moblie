"""Prepare Calendar/Drive registration values locally; never calls Google."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--signing-report", required=True, type=Path)
args = parser.parse_args()
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
sources = ROOT / "app/src/main/java/com/schedulewidget/mobile"
scope_pattern = r'"(https://www\.googleapis\.com/auth/[^"\s]+)"'
scopes = sorted({scope for path in ("calendar/GoogleCalendarAuthorization.kt", "pet/DrivePets.kt")
                 for scope in re.findall(scope_pattern, (sources / path).read_text(encoding="utf-8-sig"))})
expected = ["https://www.googleapis.com/auth/" + name for name in
            ("calendar.calendars.readonly", "calendar.events", "drive.file")]
if scopes != expected:
    raise SystemExit("Calendar/Drive scopes changed; review the publishing draft before updating it.")
report = args.signing_report.read_text(encoding="utf-8-sig")
debug = re.search(r"Variant: debug\s+Config: debug\s+(.*?)(?:----------|$)", report, re.S)
fingerprint = re.search(r"SHA1: ([0-9A-F:]{59})", debug[1]) if debug else None
if not fingerprint:
    raise SystemExit("No debug SHA-1 in the report; run :app:signingReport first.")
for path in ("docs/index.html", "docs/privacy.html", "app/src/main/assets/privacy-policy.txt"):
    if not (ROOT / path).is_file():
        raise SystemExit(f"Missing policy asset: {path}")
draft = {
    "status": "local preparation only; console state not inspected or changed",
    "project_from_existing_handoff": "gen-lang-client-0088309798",
    "branding": {"app_name": "ScheduleWidget Notes", "support_email": "ddd84860@gmail.com",
                 "developer_email": "ddd84860@gmail.com", "homepage_url": None, "privacy_policy_url": None},
    "suggested_audience": "External / Testing until publication is requested",
    "android": {"package_name": re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)[1],
                "version_name": re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1],
                "version_code": int(re.search(r"versionCode\s*=\s*(\d+)", gradle)[1]),
                "debug_sha1": fingerprint[1], "production_signing_sha1": None},
    "apis": ["calendar-json.googleapis.com", "drive.googleapis.com"],
    "scopes": scopes,
    "local_pages": {"homepage": "docs/index.html", "privacy_policy": "docs/privacy.html"},
    "pending_for_publication": ["Choose and verify public HTTPS domain and URLs",
        "Confirm production signing certificate and Android OAuth client",
        "Register branding, audience, APIs and scopes in the selected project",
        "Record Calendar/Drive consent, account switching and revocation with test data",
        "Submit applicable Google verification and confirm its result"]
}
destination = ROOT / "docs/gcp-registration.json"
destination.write_text(json.dumps(draft, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Prepared {destination.name}: {draft['android']['package_name']}, {len(scopes)} scopes; no Google calls.")
