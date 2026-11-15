"""Check the distributable source tree without reading user files or Git credentials."""
import fnmatch
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_ROOTS = {"app", "core", "gradle", "tools", "docs", ".github"}
ALLOWED_FILES = {
    ".gitignore", "README.md", "CHANGELOG.md", "LICENSE", "build-local.ps1", "build.gradle.kts",
    "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
}
ALLOWED_SUFFIXES = {".kt", ".kts", ".xml", ".json", ".md", ".py", ".pro", ".properties", ".yml"}
PATTERNS = {
    "absolute_windows_path": r"(?<![A-Za-z])[A-Za-z]:[\\/]",
    "personal_home_path": r"(?i)/(?:Users|home)/[^/\s]+/",
    "private_key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "github_token": r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b",
    "aws_access_key": r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "email_address": r"\b[A-Za-z0-9._%+-]+@(?:[a-z0-9-]+\.)+[a-z]{2,}\b",
    "device_identifier": r"(?i)\b(?:android_id|imei|meid|ro\.serialno|ro\.product\.model)\b",
}
FALLBACK_PRUNE_DIRS = {".gradle", ".kotlin", "build", "__pycache__", ".git"}


def load_gitignore(root: Path) -> list[str]:
    ignore_file = root / ".gitignore"
    if not ignore_file.exists():
        return []
    rules = []
    for raw in ignore_file.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        rules.append(line)
    return rules


def _match_rule(rel: Path, rule: str) -> bool:
    posix = rel.as_posix()
    dir_only = rule.endswith("/")
    pattern = rule.lstrip("/").rstrip("/")
    if not pattern:
        return False
    if pattern.startswith("**/"):
        sub = pattern[3:]
        if dir_only:
            return sub in rel.parts[:-1]
        return any(fnmatch.fnmatch(part, sub) for part in rel.parts) or fnmatch.fnmatch(posix, f"*/{sub}")
    if dir_only:
        if "/" in pattern:
            return posix == pattern or posix.startswith(pattern + "/")
        return pattern in rel.parts[:-1] or posix == pattern
    if "/" in pattern:
        return fnmatch.fnmatch(posix, pattern)
    return fnmatch.fnmatch(rel.name, pattern)


def is_ignored(rel: Path, rules: list[str]) -> bool:
    ignored = False
    for raw in rules:
        negate = raw.startswith("!")
        pattern = raw[1:] if negate else raw
        if _match_rule(rel, pattern):
            ignored = not negate
    return ignored


def audit_tree(root: Path = ROOT) -> dict:
    if (root / ".git").exists():
        result = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=root,
            check=True,
            capture_output=True,
        )
        paths = [
            candidate
            for p in result.stdout.split(b"\0")
            if p
            for candidate in (root / p.decode("utf-8"),)
            if candidate.exists() or candidate.is_symlink()
        ]
    else:
        rules = load_gitignore(root)
        paths = []
        for candidate in root.rglob("*"):
            if not candidate.is_file():
                continue
            rel = candidate.relative_to(root)
            if any(part in FALLBACK_PRUNE_DIRS for part in rel.parts[:-1]):
                continue
            if is_ignored(rel, rules):
                continue
            paths.append(candidate)

    findings = []
    for path in sorted(paths):
        rel = path.relative_to(root)
        name = rel.as_posix()
        if len(rel.parts) == 1:
            if name not in ALLOWED_FILES:
                findings.append({"file": name, "rule": "unexpected_root_file"})
        elif rel.parts[0] not in ALLOWED_ROOTS:
            findings.append({"file": name, "rule": "unexpected_directory"})
        if name == "gradle/wrapper/gradle-wrapper.jar":
            continue
        if name not in ALLOWED_FILES and path.suffix not in ALLOWED_SUFFIXES:
            findings.append({"file": name, "rule": "unexpected_file_type"})
            continue
        if "build" in rel.parts or any(part.startswith(".") and part != ".github" for part in rel.parts[:-1]):
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
        for rule, pattern in PATTERNS.items():
            for match in re.finditer(pattern, text):
                findings.append({
                    "file": name,
                    "line": text[:match.start()].count("\n") + 1,
                    "rule": rule,
                })
    return {"files": len(paths), "findings": findings}


def main():
    report = audit_tree(ROOT)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    sys.exit(bool(report["findings"]))


if __name__ == "__main__":
    main()
