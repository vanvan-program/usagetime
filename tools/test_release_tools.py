import json
from contextlib import closing
from pathlib import Path
import tempfile
import unittest

from release_package import digest, package
from audit_public_tree import audit_tree, is_ignored, load_gitignore
from upgrade_check import fixture, fingerprint, read_preferences


class ReleaseToolsTest(unittest.TestCase):
    def test_fixture_detects_a_single_lost_history_row(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "history.db"
            fixture(path)
            expected = fingerprint(path)
            self.assertEqual(730, expected["usage_events"]["rows"])
            self.assertEqual(1460, expected["usage_intervals"]["rows"])
            import sqlite3
            with closing(sqlite3.connect(path)) as database, database:
                database.execute("DELETE FROM usage_intervals WHERE id='PRIMARY-0'")
            self.assertNotEqual(expected, fingerprint(path))

    def test_package_rejects_a_tag_not_matching_source(self):
        with self.assertRaisesRegex(ValueError, "Tag"):
            package(Path("unused.apk"), Path("unused"), "v0.0.0", "HEAD")

    def test_package_rejects_stale_apk_metadata(self):
        from release_package import version
        name, code = version()
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / "output-metadata.json").write_text(json.dumps({
                "elements": [{"versionName": name, "versionCode": code - 1, "outputFile": "app.apk"}]
            }))
            with self.assertRaisesRegex(ValueError, "metadata"):
                package(folder / "app.apk", folder / "out", f"v{name}", "HEAD")

    def test_digest_and_preferences_are_content_based(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture"
            path.write_bytes(b"abc")
            self.assertEqual("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest(path))
        self.assertEqual({"theme": "dark", "recording": "false"},
            read_preferences(b'<map><string name="theme">dark</string><boolean name="recording" value="false"/></map>'))

    def test_audit_tree_honors_gitignore_when_git_absent_and_flags_unexpected_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".gitignore").write_text("*.log\n.qa-temp/\n**/build/\n", encoding="utf-8")
            (root / ".qa-temp").mkdir()
            (root / ".qa-temp" / "ignored.txt").write_text("temp", encoding="utf-8")
            (root / "run.log").write_text("ignored log", encoding="utf-8")
            (root / "app" / "src").mkdir(parents=True)
            (root / "app" / "src" / "Sample.kt").write_text("package demo\n", encoding="utf-8")
            rules = load_gitignore(root)
            self.assertTrue(is_ignored(Path(".qa-temp/ignored.txt"), rules))
            self.assertTrue(is_ignored(Path("app/build/classes/A.kt"), rules))
            self.assertFalse(is_ignored(Path("app/src/Sample.kt"), rules))

            clean_report = audit_tree(root)
            self.assertEqual([], clean_report["findings"])

            (root / "stray.json").write_text("{}", encoding="utf-8")
            dirty_report = audit_tree(root)
            self.assertTrue(any(f["file"] == "stray.json" and f["rule"] == "unexpected_root_file" for f in dirty_report["findings"]))


if __name__ == "__main__":
    unittest.main()
