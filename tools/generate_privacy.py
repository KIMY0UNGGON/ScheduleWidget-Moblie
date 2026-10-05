"""Generate the offline app policy and static policy page from PRIVACY.md (standard library only)."""
from pathlib import Path
import html
import re

ROOT = Path(__file__).resolve().parents[1]
policy = (ROOT / "PRIVACY.md").read_text(encoding="utf-8-sig")
plain = re.sub(r"^#+ ", "", policy, flags=re.M)
(ROOT / "app/src/main/assets/privacy-policy.txt").write_text(plain, encoding="utf-8")

style = (
    "body{font:17px/1.75 system-ui,sans-serif;color:#17212b;background:#fafafa;margin:auto;"
    "padding:32px 24px;max-width:820px}a{color:#1559a4}h1{font-size:1.9rem;line-height:1.3}"
    "h2{font-size:1.2rem;margin-top:2.2rem}nav{margin-bottom:24px}p{white-space:pre-line}"
)
blocks = []
for block in policy.strip().split("\n\n"):
    tag = "h1" if block.startswith("# ") else "h2" if block.startswith("## ") else "p"
    content = html.escape(re.sub(r"^#+ ", "", block))
    content = re.sub(
        r"(https://[^\s<)]+)",
        lambda match: '<a href="' + match[1] + '">' + match[1] + "</a>",
        content,
    )
    content = content.replace("ddd84860@gmail.com", '<a href="mailto:ddd84860@gmail.com">ddd84860@gmail.com</a>')
    blocks.append(f"<{tag}>{content}</{tag}>")
header = (
    '<!doctype html>\n<html lang="ko"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1">'
    "<title>ScheduleWidget Notes</title><style>" + style + "</style></head><body>"
)
(ROOT / "docs/privacy.html").write_text(
    header + '<nav><a href="./index.html">ScheduleWidget Notes</a></nav>'
    + "".join(blocks) + "</body></html>\n",
    encoding="utf-8",
)
print("Generated app privacy policy and docs/privacy.html from PRIVACY.md")
