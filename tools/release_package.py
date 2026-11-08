import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def version():
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    return (
        re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1),
        int(re.search(r"versionCode\s*=\s*(\d+)", text).group(1)),
    )


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def package(apk, output, tag, source_ref):
    name, code = version()
    if tag != f"v{name}":
        raise ValueError("Tag must match the versionName in the source.")
    metadata = json.loads((apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0]["versionName"] != name or elements[0]["versionCode"] != code:
        raise ValueError("APK metadata does not match the source version.")
    if elements[0]["outputFile"] != apk.name:
        raise ValueError("APK path does not match the build output metadata.")
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or "classes.dex" not in archive.namelist():
            raise ValueError("Incomplete APK.")
    committed = subprocess.check_output(
        ["git", "show", f"{source_ref}:app/build.gradle.kts"], cwd=ROOT, text=True, encoding="utf-8",
    )
    if f'versionName = "{name}"' not in committed or f"versionCode = {code}" not in committed:
        raise ValueError("Source archive version differs from the APK.")
    subprocess.run(["git", "diff", "--exit-code", source_ref, "--"], cwd=ROOT, check=True, capture_output=True)
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT)
    if untracked.strip():
        raise ValueError("Commit new source files before packaging a public source archive.")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, output / f"usagetime-{name}.apk")
    subprocess.run(
        ["git", "archive", "--format=zip", f"--prefix=usagetime-{name}/",
         f"--output={output.resolve() / f'usagetime-{name}-source.zip'}", source_ref],
        cwd=ROOT, check=True,
    )
    shutil.copyfile(ROOT / "CHANGELOG.md", output / "CHANGELOG.md")
    filenames = [f"usagetime-{name}.apk", f"usagetime-{name}-source.zip", "CHANGELOG.md"]
    (output / "SHA256SUMS.txt").write_text(
        "".join(f"{digest(output / filename)}  {filename}\n" for filename in filenames),
        encoding="utf-8",
    )
    print(json.dumps({"version": name, "versionCode": code, "files": filenames}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-ref", default="HEAD")
    arguments = parser.parse_args()
    package(arguments.apk, arguments.output, arguments.tag, arguments.source_ref)
