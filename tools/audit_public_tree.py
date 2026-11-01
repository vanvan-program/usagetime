"""Check the distributable source tree without reading user files or Git credentials."""
from pathlib import Path
import json
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
allowed_roots = {"app", "core", "gradle", "tools", "docs"}
allowed_files = {
    ".gitignore", "README.md", "CHANGELOG.md", "build-local.ps1", "build.gradle.kts",
    "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
}
allowed_suffixes = {".kt", ".kts", ".xml", ".json", ".md", ".py", ".pro", ".properties"}
patterns = {
    "absolute_windows_path": r"(?<![A-Za-z])[A-Za-z]:[\\/]",
    "personal_home_path": r"(?i)/(?:Users|home)/[^/\s]+/",
    "private_key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "github_token": r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b",
    "aws_access_key": r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "email_address": r"\b[A-Za-z0-9._%+-]+@(?:[a-z0-9-]+\.)+[a-z]{2,}\b",
    "device_identifier": r"(?i)\b(?:android_id|imei|meid|ro\.serialno|ro\.product\.model)\b",
}
if (root / ".git").exists():
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True
    )
    paths = [root / p.decode("utf-8") for p in result.stdout.split(b"\0") if p]
else:
    paths = [
        p for p in root.rglob("*") if p.is_file()
        and not any(part in {".gradle", ".kotlin", "build", "__pycache__"} for part in p.relative_to(root).parts)
    ]
findings = []
for path in paths:
    rel = path.relative_to(root)
    name = rel.as_posix()
    if len(rel.parts) == 1:
        if name not in allowed_files:
            findings.append({"file": name, "rule": "unexpected_root_file"})
    elif rel.parts[0] not in allowed_roots:
        findings.append({"file": name, "rule": "unexpected_directory"})
    if name == "gradle/wrapper/gradle-wrapper.jar":
        continue
    if name not in allowed_files and path.suffix not in allowed_suffixes:
        findings.append({"file": name, "rule": "unexpected_file_type"})
        continue
    if "build" in rel.parts or any(part.startswith(".") for part in rel.parts[:-1]):
        findings.append({"file": name, "rule": "generated_or_hidden_directory"})
    if path.is_symlink():
        findings.append({"file": name, "rule": "symbolic_link"})
        continue
    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeError:
        findings.append({"file": name, "rule": "unexpected_binary"})
        continue
    if name == "tools/audit_public_tree.py":
        continue
    for rule, pattern in patterns.items():
        for match in re.finditer(pattern, text):
            findings.append({
                "file": name, "line": text[:match.start()].count("\n") + 1, "rule": rule
            })
print(json.dumps({"files": len(paths), "findings": findings}, ensure_ascii=False, indent=2))
sys.exit(bool(findings))
