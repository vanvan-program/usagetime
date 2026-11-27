import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.usagetime.app"
TABLES = ("intervals", "coverage", "apps", "usage_events", "usage_intervals", "daily_usage", "history_days", "history_seals")


def adb(serial, *args, data=None):
    return subprocess.run(
        ["adb", "-s", serial, *args], input=data, capture_output=True, check=True,
        timeout=120,
    ).stdout


def fixture(path):
    schema = json.loads(
        (ROOT / "app/schemas/dev.usagetime.app.data.UsageDatabase/6.json").read_text(encoding="utf-8")
    )["database"]
    with closing(sqlite3.connect(path)) as database, database:
        for entity in schema["entities"]:
            database.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                database.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for sql in schema["setupQueries"]:
            database.execute(sql)
        database.execute("PRAGMA user_version=6")
        database.execute("INSERT INTO apps VALUES (?,?)", ("fixture.upgrade", "Upgrade fixture"))
        start = 1704067200000
        for day in range(730):
            at = start + day * 86400000
            end = at + 60000
            database.execute("INSERT INTO intervals VALUES (?,?,?)", ("fixture.upgrade", at, end))
            database.execute("INSERT INTO coverage VALUES (?,?,1)", (at, end))
            database.execute(
                "INSERT INTO usage_events VALUES (?,?, 'RESUME', 'fixture.upgrade', 'Fixture', "
                "NULL, 'USAGE_EVENTS', 'UTC', 'LOCAL', NULL)", (f"event-{day}", at),
            )
            for metric in ("PRIMARY", "VISIBLE"):
                database.execute(
                    "INSERT INTO usage_intervals VALUES (?,?,?,?,?, 'INFERRED', 'UPGRADE_FIXTURE', "
                    "'USAGE_EVENTS', '', 0, 2)",
                    (f"{metric}-{day}", metric, "fixture.upgrade", at, end),
                )
                database.execute(
                    "INSERT INTO daily_usage VALUES (?,?,?,?,?,?, 'INFERRED', ?,0,3)",
                    (f"day-{metric}-{day}", at, at + 86400000, "UTC", metric, "fixture.upgrade", end - at),
                )
                history = [
                    ("__QUALITY_SUMMARY__", "UNKNOWN", 0),
                    ("__CONFIRMED_COVERAGE__", "CONFIRMED", 0),
                    ("fixture.upgrade", "INFERRED", end - at),
                ]
                canonical = []
                for app, evidence, millis in sorted(history, key=lambda row: ("1" + row[0], row[1])):
                    app_key = "1" + app
                    values = ("UTC", at, metric, app_key, evidence, at + 86400000, millis, 0, 3)
                    canonical.append("".join(f"{len(str(value))}:{value}" for value in values))
                    database.execute("INSERT INTO history_days VALUES (?,?,?,?,?,?,?,?,?,?)",
                        ("UTC", at, metric, app_key, evidence, at + 86400000, app, millis, 0, 3))
                seal = hashlib.sha256("\n".join(canonical).encode()).hexdigest()
                database.execute("INSERT INTO history_seals VALUES (?,?,?,?,?,?,?)",
                    ("UTC", at, metric, at + 86400000, len(history), seal, "LOCAL"))


def fingerprint(path):
    with closing(sqlite3.connect(path)) as database:
        result = {}
        for table in TABLES:
            rows = sorted(database.execute(f"SELECT * FROM {table}").fetchall(), key=repr)
            result[table] = {"rows": len(rows), "sha256": hashlib.sha256(repr(rows).encode()).hexdigest()}
        if database.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("Database integrity check failed.")
        return result


def read_preferences(raw):
    return {item.attrib["name"]: item.attrib.get("value", item.text) for item in ET.fromstring(raw)}


def wait_started(serial):
    for attempt in range(40):
        output = adb(serial, "shell", "pidof", PACKAGE).decode().strip()
        if output:
            return
        time.sleep(0.25)
    raise RuntimeError("App did not start.")


def main(arguments):
    serial = arguments.serial
    if not serial.startswith("emulator-"):
        raise ValueError("Only isolated Android emulators are accepted.")
    avd_name = adb(serial, "emu", "avd", "name").decode().splitlines()[0].strip()
    if not avd_name.lower().startswith(("usagetime-qa", "test", "ui_qa_")):
        raise ValueError("Use an isolated AVD named usagetime-qa* or test*.")
    existing = adb(serial, "shell", "pm", "list", "packages", PACKAGE).decode()
    if f"package:{PACKAGE}" in existing.splitlines() and not arguments.reuse_qa_install:
        raise ValueError("The QA package already exists; explicitly pass --reuse-qa-install on this disposable AVD.")
    install_flags = ["-r", "-d"] if arguments.reuse_qa_install else ["-r"]
    if arguments.root_adb:
        adb(serial, "root")
        adb(serial, "wait-for-device")
        if adb(serial, "shell", "id", "-u").strip() != b"0":
            raise RuntimeError("Use a root-capable system image on the isolated upgrade emulator.")
    adb(serial, "install", *install_flags, str(arguments.old_apk.resolve()))
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    app_root = f"/data/user/0/{PACKAGE}" if arguments.root_adb else "."
    as_app = () if arguments.root_adb else ("run-as", PACKAGE)
    databases = f"{app_root}/databases"
    shared_prefs = f"{app_root}/shared_prefs"
    device_database = f"{databases}/usage-history.db"
    owner = adb(serial, "shell", "stat", "-c", "%u:%g", app_root).decode().strip() if arguments.root_adb else None

    def put_private(source, destination):
        adb(serial, "shell", *as_app, "cp", source, destination)
        if owner is not None:
            adb(serial, "shell", "chown", owner, destination)
            adb(serial, "shell", "chmod", "600", destination)
            adb(serial, "shell", "restorecon", destination)

    def read_private(path):
        return adb(serial, "exec-out", *as_app, "cat", path)

    preferences = {
        "usage-settings": (
            '<map><boolean name="recording" value="false"/>'
            '<boolean name="show-confirmed" value="true"/>'
            '<string name="theme">dark</string></map>'
        ).encode(),
        "update-settings": b'<map><string name="source-mode">mirror</string></map>',
        "usage-categories": (
            '<map><string name="assignments">'
            '{&quot;fixture.upgrade&quot;:&quot;STUDY&quot;}</string></map>'
        ).encode(),
    }
    with tempfile.TemporaryDirectory(prefix="usagetime-upgrade-") as temporary:
        database = Path(temporary) / "fixture.db"
        fixture(database)
        expected = fingerprint(database)
        remote = f"/data/local/tmp/usagetime-upgrade-{uuid.uuid4().hex}"
        adb(serial, "push", str(database.resolve()), remote + ".db")
        adb(serial, "shell", "chmod", "644", remote + ".db")
        adb(serial, "shell", *as_app, "mkdir", "-p", databases, shared_prefs)
        if owner is not None:
            adb(serial, "shell", "chown", owner, databases, shared_prefs)
            adb(serial, "shell", "chmod", "700", databases, shared_prefs)
            adb(serial, "shell", "restorecon", databases, shared_prefs)
        adb(serial, "shell", *as_app, "rm", "-f", device_database + "-wal", device_database + "-shm")
        put_private(remote + ".db", device_database)
        adb(serial, "shell", "rm", "-f", remote + ".db")
        for name, contents in preferences.items():
            preferences_path = Path(temporary) / f"{name}.xml"
            preferences_path.write_bytes(contents)
            remote_preferences = f"{remote}-{name}.xml"
            adb(serial, "push", str(preferences_path.resolve()), remote_preferences)
            adb(serial, "shell", "chmod", "644", remote_preferences)
            put_private(remote_preferences, f"{shared_prefs}/{name}.xml")
            adb(serial, "shell", "rm", "-f", remote_preferences)
        transferred = read_private(device_database)
        if transferred != database.read_bytes():
            raise AssertionError("Synthetic fixture transfer was not byte-for-byte identical.")
        old = adb(serial, "shell", "dumpsys", "package", PACKAGE).decode()
        adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        wait_started(serial)
        time.sleep(2)
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        database.write_bytes(read_private(device_database))
        if fingerprint(database) != expected:
            raise AssertionError("The old APK did not preserve/read the synthetic fixture.")
        began = time.monotonic()
        adb(serial, "install", "-r", str(arguments.new_apk.resolve()))
        adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        wait_started(serial)
        time.sleep(3)
        wait_started(serial)
        ui = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty").decode("utf-8", errors="replace")
        if "读取记录失败" in ui or "校验失败" in ui:
            raise AssertionError("The upgraded app displayed a history read error.")
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        database.write_bytes(read_private(device_database))
        actual = fingerprint(database)
        if actual != expected:
            raise AssertionError("Synthetic history changed during the APK upgrade.")
        for name, contents in preferences.items():
            saved = read_preferences(read_private(f"{shared_prefs}/{name}.xml"))
            if any(saved.get(key) != value for key, value in read_preferences(contents).items()):
                raise AssertionError(f"Preferences in {name} were lost during the APK upgrade.")
        current = adb(serial, "shell", "dumpsys", "package", PACKAGE).decode()
        report = {
            "passed": True, "avd": avd_name, "syntheticHistory": actual,
            "preferencesPreserved": True, "updateSourcePreserved": True, "manualCategoriesPreserved": True,
            "installAndLaunchSeconds": round(time.monotonic() - began, 2),
            "oldVersion": next(line.strip() for line in old.splitlines() if "versionName=" in line),
            "newVersion": next(line.strip() for line in current.splitlines() if "versionName=" in line),
        }
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--old-apk", type=Path, required=True)
    parser.add_argument("--new-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reuse-qa-install", action="store_true")
    parser.add_argument("--root-adb", action="store_true", help="Read fixtures on a disposable AVD when the APK is non-debuggable.")
    main(parser.parse_args())
