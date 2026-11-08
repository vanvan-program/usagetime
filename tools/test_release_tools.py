import json
from contextlib import closing
from pathlib import Path
import tempfile
import unittest

from release_package import digest, package
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


if __name__ == "__main__":
    unittest.main()
