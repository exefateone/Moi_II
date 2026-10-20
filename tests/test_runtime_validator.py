from __future__ import annotations

import hashlib
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import validate_runtime_bundle as v  # noqa: E402


class RuntimeValidatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.archive = self.root / "spider-linux-aarch64.tar.gz"
        self.manifest_path = self.root / "manifest.json"
        self._write_tar([("bin", "dir", ""), ("bin/sh", "file", "#!/system/bin/sh\n"), ("bin/env", "file", "env\n")])
        self.manifest = {
            "schemaVersion": 1,
            "status": "ready",
            "runtimeId": "spider-linux-userland",
            "runtimeVersion": "0.1.0-test",
            "applicationId": "com.iipauk.spider",
            "supportedAbis": ["arm64-v8a"],
            "archive": {
                "format": "tar.gz",
                "fileName": self.archive.name,
                "sha256": hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                "sizeBytes": self.archive.stat().st_size,
            },
            "prefixLayout": ".",
            "requiredPaths": ["bin/sh"],
            "provenance": {
                "buildSystem": "custom-prefix test fixture",
                "sourceRepository": "https://github.com/example/spider-runtime",
                "sourceCommit": "0123456789abcdef0123456789abcdef01234567",
                "builtAtUtc": "2026-10-12T00:00:00Z",
                "customPackageIdentityVerified": True,
            },
        }
        self._write_manifest()

    def tearDown(self):
        self.tmp.cleanup()

    def _write_tar(self, entries):
        with tarfile.open(self.archive, "w:gz") as tf:
            for name, kind, content in entries:
                info = tarfile.TarInfo(name)
                if kind == "dir":
                    info.type = tarfile.DIRTYPE
                    info.mode = 0o755
                    tf.addfile(info)
                elif kind == "symlink":
                    info.type = tarfile.SYMTYPE
                    info.linkname = content
                    tf.addfile(info)
                elif kind == "hardlink":
                    info.type = tarfile.LNKTYPE
                    info.linkname = content
                    tf.addfile(info)
                elif kind == "fifo":
                    info.type = tarfile.FIFOTYPE
                    tf.addfile(info)
                else:
                    data = content.encode("utf-8")
                    info.size = len(data)
                    info.mode = 0o755 if name.endswith("/sh") else 0o644
                    import io
                    tf.addfile(info, io.BytesIO(data))

    def _write_manifest(self):
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def _refresh_digest(self):
        self.manifest["archive"]["sizeBytes"] = self.archive.stat().st_size
        self.manifest["archive"]["sha256"] = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self._write_manifest()

    def test_template_is_intentionally_not_ready(self):
        template = ROOT / "runtime" / "linux" / "linux-runtime-manifest.template.json"
        with self.assertRaises(v.NotReadyError):
            v.validate(template, None, "com.iipauk.spider", "arm64-v8a")

    def test_valid_custom_prefix_archive_passes(self):
        result = v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")
        self.assertEqual(result["status"], "valid")
        self.assertEqual(result["archive"], self.archive.name)
        self.assertEqual(result["entries"], 3)
        self.assertGreater(result["unpackedBytes"], 0)

    def test_wrong_application_id_fails_closed(self):
        self.manifest["applicationId"] = "com.termux"
        self._write_manifest()
        with self.assertRaisesRegex(v.ValidationError, "applicationId"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_wrong_abi_fails(self):
        with self.assertRaisesRegex(v.ValidationError, "Target ABI"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "x86_64")

    def test_hash_mismatch_fails(self):
        self.manifest["archive"]["sha256"] = "0" * 64
        self._write_manifest()
        with self.assertRaisesRegex(v.ValidationError, "SHA-256 mismatch"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_parent_traversal_member_fails(self):
        self._write_tar([("../escape", "file", "no"), ("bin/sh", "file", "#!/bin/sh")])
        self._refresh_digest()
        with self.assertRaisesRegex(v.ValidationError, "Parent traversal"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_absolute_member_fails(self):
        self._write_tar([("/data/local/tmp/escape", "file", "no"), ("bin/sh", "file", "#!/bin/sh")])
        self._refresh_digest()
        with self.assertRaisesRegex(v.ValidationError, "Absolute archive path"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_duplicate_archive_member_path_fails(self):
        self._write_tar([("bin", "dir", ""), ("bin/sh", "file", "first"), ("bin/sh", "file", "second")])
        self._refresh_digest()
        with self.assertRaisesRegex(v.ValidationError, "Duplicate archive member path"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_symlink_escape_fails(self):
        self._write_tar([("bin", "dir", ""), ("bin/sh", "symlink", "../../outside"), ("bin/env", "file", "env")])
        self._refresh_digest()
        with self.assertRaisesRegex(v.ValidationError, "Link escapes"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_special_device_entries_fail(self):
        self._write_tar([("bin", "dir", ""), ("dev/mem", "fifo", ""), ("bin/sh", "file", "#!/bin/sh")])
        self._refresh_digest()
        with self.assertRaisesRegex(v.ValidationError, "Special archive entry"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_missing_required_path_fails(self):
        self._write_tar([("bin", "dir", ""), ("bin/env", "file", "env")])
        self._refresh_digest()
        with self.assertRaisesRegex(v.ValidationError, "Required runtime paths missing"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")

    def test_custom_package_identity_must_be_confirmed(self):
        self.manifest["provenance"]["customPackageIdentityVerified"] = False
        self._write_manifest()
        with self.assertRaisesRegex(v.ValidationError, "identity must be explicitly verified"):
            v.validate(self.manifest_path, self.archive, "com.iipauk.spider", "arm64-v8a")


if __name__ == "__main__":
    unittest.main(verbosity=2)
