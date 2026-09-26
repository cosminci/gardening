import importlib.util
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("local", Path(__file__).with_name("local.py"))
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


class LocalDevelopmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.data = Path(self.directory.name)
        self.database = self.data / "gardening.db"
        self.source = self.data / "source.db"
        data_patch = patch.object(local, "DATA", self.data)
        database_patch = patch.object(local, "DATABASE", self.database)
        data_patch.start()
        database_patch.start()
        self.addCleanup(data_patch.stop)
        self.addCleanup(database_patch.stop)

    def create_journal(self, path, value):
        with sqlite3.connect(path) as connection:
            for table in ("plant", "operation", "pesticide", "substrate_component", "flyway_schema_history"):
                connection.execute(f"CREATE TABLE {table} (value TEXT)")
            connection.execute("INSERT INTO plant VALUES (?)", (value,))

    def mock_ssh(self, command, input, stdout, stderr, timeout, check):
        with sqlite3.connect(self.source) as source, sqlite3.connect(stdout.name) as destination:
            source.backup(destination)
        return subprocess.CompletedProcess(command, 0)

    def test_refresh_replaces_a_local_journal_from_a_valid_snapshot(self):
        self.create_journal(self.database, "local edit")
        self.create_journal(self.source, "NAS value")

        with patch.dict(local.os.environ, {"GARDENING_NAS_DB_PATH": "/data/gardening.db", "GARDENING_NAS_SSH": "nas"}):
            with patch.object(local.subprocess, "run", side_effect=self.mock_ssh):
                local.refresh(confirm=True)

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT value FROM plant").fetchone(), ("NAS value",))
        self.assertEqual(list(self.data.glob(".snapshot-*.db")), [])

    def test_refresh_preserves_local_journal_when_snapshot_is_invalid(self):
        self.create_journal(self.database, "local edit")

        def invalid_snapshot(command, input, stdout, stderr, timeout, check):
            stdout.write(b"not a database")
            return subprocess.CompletedProcess(command, 0)

        with patch.dict(local.os.environ, {"GARDENING_NAS_DB_PATH": "/data/gardening.db", "GARDENING_NAS_SSH": "nas"}):
            with patch.object(local.subprocess, "run", side_effect=invalid_snapshot):
                with self.assertRaises(sqlite3.DatabaseError):
                    local.refresh(confirm=True)

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT value FROM plant").fetchone(), ("local edit",))
        self.assertEqual(list(self.data.glob(".snapshot-*.db")), [])

    def test_refresh_requires_confirmation_before_replacing_edits(self):
        self.create_journal(self.database, "local edit")

        with patch("builtins.input", return_value="no"):
            with self.assertRaisesRegex(RuntimeError, "Refresh cancelled"):
                local.refresh(confirm=False)

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT value FROM plant").fetchone(), ("local edit",))

    def test_refresh_accepts_explicit_confirmation(self):
        self.create_journal(self.database, "local edit")
        self.create_journal(self.source, "NAS value")

        with patch.dict(local.os.environ, {"GARDENING_NAS_DB_PATH": "/data/gardening.db", "GARDENING_NAS_SSH": "nas"}):
            with patch("builtins.input", return_value="replace"):
                with patch.object(local.subprocess, "run", side_effect=self.mock_ssh):
                    local.refresh(confirm=False)

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT value FROM plant").fetchone(), ("NAS value",))

    def test_preflight_rejects_missing_nas_configuration(self):
        (self.data / "frontend/node_modules").mkdir(parents=True)

        with patch.dict(local.os.environ, {"GARDENING_NAS_SSH": "", "GARDENING_NAS_DB_PATH": ""}):
            with patch.object(local.shutil, "which", return_value="/usr/bin/tool"):
                with patch.object(local.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stderr='openjdk version "25.0.4"')):
                    with patch.object(local, "port", return_value=18080):
                        with patch.object(local, "ROOT", self.data):
                            with self.assertRaisesRegex(RuntimeError, "GARDENING_NAS_SSH"):
                                local.preflight(refresh=True)

    def test_port_rejects_invalid_or_out_of_range_values(self):
        with patch.dict(local.os.environ, {"GARDENING_PORT": "nope"}):
            with self.assertRaisesRegex(RuntimeError, "GARDENING_PORT"):
                local.port()
        with patch.dict(local.os.environ, {"GARDENING_PORT": "0"}):
            with self.assertRaisesRegex(RuntimeError, "GARDENING_PORT"):
                local.port()

    def test_refresh_preserves_local_journal_when_transfer_fails(self):
        self.create_journal(self.database, "local edit")

        with patch.dict(local.os.environ, {"GARDENING_NAS_DB_PATH": "/data/gardening.db", "GARDENING_NAS_SSH": "nas"}):
            with patch.object(local.subprocess, "run", return_value=subprocess.CompletedProcess([], 255)):
                with self.assertRaisesRegex(RuntimeError, "NAS snapshot failed"):
                    local.refresh(confirm=True)

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT value FROM plant").fetchone(), ("local edit",))
        self.assertEqual(list(self.data.glob(".snapshot-*.db")), [])


if __name__ == "__main__":
    unittest.main()
