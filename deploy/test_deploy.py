"""Offline safety checks for deployment configuration and restore preflight.

These are script tests, not evidence of a running Docker/PostgreSQL deployment.
Run: python3 -m unittest discover -s deploy -p 'test_*.py' -v
"""
import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.dont_write_bytecode = True
DIRECTORY = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("verify_backup", DIRECTORY / "verify-backup.py")
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)


class DeploymentSafetyTests(unittest.TestCase):
    def setUp(self):
        self.test_root = (DIRECTORY.parent / "data" / "run" / "deploy-tests").resolve()
        self.test_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="lumen-deploy-", dir=self.test_root)
        self.directory = Path(self.temporary.name)

    def tearDown(self):
        # TemporaryDirectory.cleanup recursively removes only this verified test tree.
        self.assertTrue(self.directory.resolve().is_relative_to(self.test_root))
        self.temporary.cleanup()

    def initialize(self, origin="https://lumen.example.test", *extra):
        return subprocess.run(
            [sys.executable, str(DIRECTORY / "init-config.py"), "--origin", origin,
             "--output", str(self.directory / ".env"), *extra],
            capture_output=True, text=True, encoding="utf-8")

    def test_secrets_are_random_and_not_printed(self):
        result = self.initialize()
        self.assertEqual(0, result.returncode, result.stderr)
        values = dict(line.split("=", 1) for line in (self.directory / ".env").read_text().splitlines()
                      if line and not line.startswith("#"))
        self.assertEqual(64, len(values["LUMEN_DB_PASSWORD"]))
        self.assertEqual(96, len(values["APP_JWT_SECRET"]))
        self.assertNotEqual(values["LUMEN_DB_PASSWORD"], values["APP_JWT_SECRET"][:64])
        for secret in (values["LUMEN_DB_PASSWORD"], values["APP_JWT_SECRET"]):
            int(secret, 16)
            self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertEqual("https://lumen.example.test", values["LUMEN_PUBLIC_ORIGIN"])
        self.assertEqual("false", values["APP_ALLOW_REGISTRATION"])

    def test_existing_configuration_is_never_overwritten(self):
        self.assertEqual(0, self.initialize().returncode)
        previous = (self.directory / ".env").read_bytes()
        self.assertNotEqual(0, self.initialize().returncode)
        self.assertEqual(previous, (self.directory / ".env").read_bytes())

    def test_invalid_or_injectable_origins_are_rejected(self):
        for origin in ("http://public.example", "https://user:password@host.example",
                       "https://host.example/path", "https://host.example?value=1",
                       "https://host.example\nAPP_ALLOW_REGISTRATION=true", "https://host.example:$PORT"):
            with self.subTest(origin=origin):
                self.assertNotEqual(0, self.initialize(origin).returncode)
                self.assertFalse((self.directory / ".env").exists())

    def test_http_is_only_allowed_for_an_explicit_local_smoke_test(self):
        self.assertNotEqual(0, self.initialize("http://localhost:8080").returncode)
        self.assertNotEqual(0, self.initialize("http://public.example", "--allow-local-http").returncode)
        self.assertEqual(0, self.initialize("http://localhost:8080", "--allow-local-http").returncode)

    def make_backup(self, extra=None, root=True):
        # Minimal PGDMP header is a format fixture, not a real pg_dump/restore test.
        (self.directory / "database.dump").write_bytes(b"PGDMP\x01fixture")
        (self.directory / "images.txt").write_text("test image versions\n")
        (self.directory / "revision.txt").write_text("test revision\n")
        with tarfile.open(self.directory / "storage.tar.gz", "w:gz") as archive:
            if root:
                directory = tarfile.TarInfo("storage")
                directory.type = tarfile.DIRTYPE
                archive.addfile(directory)
            file = tarfile.TarInfo("storage/original.bin")
            file.size = 8
            archive.addfile(file, io.BytesIO(b"original"))
            if extra is not None:
                archive.addfile(extra, io.BytesIO(b"x") if extra.isfile() else None)
        self.write_manifest()

    def write_manifest(self):
        names = ("database.dump", "storage.tar.gz", "images.txt", "revision.txt")
        (self.directory / "SHA256SUMS").write_text("".join(
            hashlib.sha256((self.directory / name).read_bytes()).hexdigest() + "  " + name + "\n"
            for name in names))

    def test_valid_backup_verifies_real_bytes_and_archive_paths(self):
        self.make_backup()
        self.assertEqual((1, 8), BACKUP.verify(self.directory))

    def test_changed_backup_bytes_fail_before_restore(self):
        self.make_backup()
        (self.directory / "database.dump").write_bytes(b"PGDMPchanged")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            BACKUP.verify(self.directory)

    def test_path_traversal_and_symbolic_links_are_rejected(self):
        for name, kind in (("storage/../../outside", tarfile.REGTYPE),
                           ("/absolute", tarfile.REGTYPE),
                           ("storage/link", tarfile.SYMTYPE),
                           ("storage/hard-link", tarfile.LNKTYPE)):
            with self.subTest(name=name, kind=kind):
                member = tarfile.TarInfo(name)
                member.type = kind
                member.size = 1 if kind == tarfile.REGTYPE else 0
                member.linkname = "../../outside"
                self.make_backup(member)
                with self.assertRaisesRegex(ValueError, "Unsafe storage archive"):
                    BACKUP.verify(self.directory)

    def test_missing_storage_root_or_invalid_database_format_is_rejected(self):
        self.make_backup(root=False)
        with self.assertRaisesRegex(ValueError, "no storage/ root"):
            BACKUP.verify(self.directory)
        self.make_backup()
        (self.directory / "database.dump").write_bytes(b"not a database")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "custom format"):
            BACKUP.verify(self.directory)


if __name__ == "__main__":
    unittest.main()
