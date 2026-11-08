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
    name = adb(serial, "emu", "avd", "name").decode().splitlines()[0].strip()
    if not name.lower().startswith(("usagetime-qa", "test", "ui_qa_")):
        raise ValueError("Use an isolated AVD named usagetime-qa* or test*.")
    existing = adb(serial, "shell", "pm", "list", "packages", PACKAGE).decode()
    if f"package:{PACKAGE}" in existing and not arguments.reuse_qa_install:
        raise ValueError("The QA package already exists; explicitly pass --reuse-qa-install on this disposable AVD.")
    install_flags = ["-r", "-d"] if arguments.reuse_qa_install else ["-r"]
    adb(serial, "install", *install_flags, str(arguments.old_apk.resolve()))
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    preferences = (
        '<?xml version="1.0" encoding="utf-8"?><map>'
        '<boolean name="recording" value="false"/>'
        '<boolean name="show-confirmed" value="true"/>'
        '<string name="theme">dark</string></map>'
    ).encode()
    with tempfile.TemporaryDirectory(prefix="usagetime-upgrade-") as temporary:
        database = Path(temporary) / "fixture.db"
        fixture(database)
        expected = fingerprint(database)
        preferences_path = Path(temporary) / "settings.xml"
        preferences_path.write_bytes(preferences)
        remote = f"/data/local/tmp/usagetime-upgrade-{uuid.uuid4().hex}"
        adb(serial, "push", str(database.resolve()), remote + ".db")
        adb(serial, "push", str(preferences_path.resolve()), remote + ".xml")
        adb(serial, "shell", "chmod", "644", remote + ".db", remote + ".xml")
        adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "databases", "shared_prefs")
        adb(serial, "shell", "run-as", PACKAGE, "rm", "-f",
            "databases/usage-history.db-wal", "databases/usage-history.db-shm")
        adb(serial, "shell", "run-as", PACKAGE, "cp", remote + ".db", "databases/usage-history.db")
        adb(serial, "shell", "run-as", PACKAGE, "cp", remote + ".xml", "shared_prefs/usage-settings.xml")
        adb(serial, "shell", "rm", "-f", remote + ".db", remote + ".xml")
        transferred = adb(serial, "exec-out", "run-as", PACKAGE, "cat", "databases/usage-history.db")
        if transferred != database.read_bytes():
            raise AssertionError("Synthetic fixture transfer was not byte-for-byte identical.")
        old = adb(serial, "shell", "dumpsys", "package", PACKAGE).decode()
        adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        wait_started(serial)
        time.sleep(2)
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        database.write_bytes(adb(serial, "exec-out", "run-as", PACKAGE, "cat", "databases/usage-history.db"))
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
        database.write_bytes(adb(serial, "exec-out", "run-as", PACKAGE, "cat", "databases/usage-history.db"))
        actual = fingerprint(database)
        if actual != expected:
            raise AssertionError("Synthetic history changed during the APK upgrade.")
        saved = read_preferences(adb(serial, "exec-out", "run-as", PACKAGE, "cat", "shared_prefs/usage-settings.xml"))
        if saved.get("recording") != "false" or saved.get("show-confirmed") != "true" or saved.get("theme") != "dark":
            raise AssertionError("Preferences were lost during the APK upgrade.")
        current = adb(serial, "shell", "dumpsys", "package", PACKAGE).decode()
        report = {
            "passed": True, "avd": name, "syntheticHistory": actual,
            "preferencesPreserved": True, "installAndLaunchSeconds": round(time.monotonic() - began, 2),
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
    main(parser.parse_args())
