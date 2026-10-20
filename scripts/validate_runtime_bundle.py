#!/usr/bin/env python3
"""Fail-closed validator for a future app-specific Spider Linux userland archive.

This script never downloads or extracts the archive. It verifies release metadata,
SHA-256/size, archive entry paths, symlink targets, supported ABI, and required paths.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tarfile
from pathlib import Path, PurePosixPath
from typing import Any

EXPECTED_APP_ID = "com.iipauk.spider"
SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-fA-F]{40,64}$")


class ValidationError(Exception):
    """Release bundle failed an integrity or safety gate."""


class NotReadyError(ValidationError):
    """The template intentionally does not contain a releasable runtime."""


def load_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"Cannot read manifest: {exc}") from exc
    if not isinstance(data, dict):
        raise ValidationError("Manifest root must be a JSON object")
    return data


def normalize_member(name: str) -> str:
    """Return a safe normalized archive-relative path or reject it."""
    if not isinstance(name, str) or not name or "\x00" in name:
        raise ValidationError("Archive contains an empty or invalid member name")
    # ZIP/TAR formats use POSIX paths even when validated on Windows.
    path = PurePosixPath(name)
    if path.is_absolute() or name.startswith("/") or re.match(r"^[A-Za-z]:", name):
        raise ValidationError(f"Absolute archive path is forbidden: {name!r}")
    parts: list[str] = []
    for part in path.parts:
        if part in ("", "."):
            continue
        if part == "..":
            raise ValidationError(f"Parent traversal is forbidden: {name!r}")
        parts.append(part)
    return "/".join(parts)


def safe_link_target(member_path: str, target: str, is_symlink: bool) -> str:
    """Normalize a relative link and reject links escaping the archive prefix.

    Symlink targets are relative to their member's parent. Tar hardlink names are
    archive-root-relative. Absolute links are rejected because the installer cannot
    safely reinterpret another product's prefix in Spider's private directory.
    """
    if not target or "\x00" in target:
        raise ValidationError(f"Invalid link target for {member_path!r}")
    raw = PurePosixPath(target)
    if raw.is_absolute() or target.startswith("/") or re.match(r"^[A-Za-z]:", target):
        raise ValidationError(f"Absolute link target is forbidden: {member_path!r} -> {target!r}")
    base = PurePosixPath(member_path).parent.parts if is_symlink else ()
    stack = [p for p in base if p not in ("", ".")]
    for part in raw.parts:
        if part in ("", "."):
            continue
        if part == "..":
            if not stack:
                raise ValidationError(f"Link escapes runtime prefix: {member_path!r} -> {target!r}")
            stack.pop()
        else:
            stack.append(part)
    return "/".join(stack)


def inspect_tar(archive: Path, required_paths: list[str]) -> dict[str, int]:
    files: set[str] = set()
    seen_names: set[str] = set()
    links = 0
    total_unpacked = 0
    try:
        with tarfile.open(archive, mode="r:gz") as tf:
            members = tf.getmembers()
            if not members:
                raise ValidationError("Runtime archive is empty")
            if len(members) > 250_000:
                raise ValidationError("Archive has too many entries (>250000)")
            for member in members:
                normalized = normalize_member(member.name)
                if not normalized:
                    # A single '.' root marker is harmless.
                    if member.isdir():
                        continue
                    raise ValidationError("Archive has a non-directory root entry")
                if normalized in seen_names:
                    raise ValidationError(f"Duplicate archive member path: {normalized!r}")
                seen_names.add(normalized)
                if member.isfile():
                    files.add(normalized)
                    total_unpacked += member.size
                    if member.size < 0:
                        raise ValidationError(f"Negative file size: {member.name!r}")
                elif member.isdir():
                    files.add(normalized)
                elif member.issym():
                    safe_link_target(normalized, member.linkname, is_symlink=True)
                    files.add(normalized)
                    links += 1
                elif member.islnk():
                    safe_link_target(normalized, member.linkname, is_symlink=False)
                    files.add(normalized)
                    links += 1
                else:
                    # Reject character/block devices, FIFOs, sockets and unknown entries.
                    raise ValidationError(f"Special archive entry is forbidden: {member.name!r}")
                if total_unpacked > 2 * 1024 * 1024 * 1024:
                    raise ValidationError("Unpacked runtime exceeds the 2 GiB safety limit")
    except (OSError, tarfile.TarError, EOFError) as exc:
        raise ValidationError(f"Runtime archive cannot be read as gzip TAR: {exc}") from exc

    missing = [normalize_member(p) for p in required_paths if normalize_member(p) not in files]
    if missing:
        raise ValidationError("Required runtime paths missing: " + ", ".join(missing))
    return {"entries": len(members), "links": links, "unpackedBytes": total_unpacked}


def validate(manifest_path: Path, archive_path: Path | None, expected_app_id: str, target_abi: str | None) -> dict[str, Any]:
    manifest = load_json(manifest_path)
    if manifest.get("schemaVersion") != 1:
        raise ValidationError("Unsupported manifest schemaVersion (expected 1)")
    if manifest.get("status") != "ready":
        raise NotReadyError(f"Runtime is not releasable: status={manifest.get('status')!r}; template intentionally ships without a runtime")
    if manifest.get("applicationId") != expected_app_id or expected_app_id != EXPECTED_APP_ID:
        raise ValidationError(f"applicationId must exactly match {EXPECTED_APP_ID!r}")
    supported = manifest.get("supportedAbis")
    if not isinstance(supported, list) or not supported or any(abi not in SUPPORTED_ABIS for abi in supported):
        raise ValidationError("supportedAbis must be a non-empty list of supported Android ABIs")
    if target_abi and (target_abi not in SUPPORTED_ABIS or target_abi not in supported):
        raise ValidationError(f"Target ABI {target_abi!r} is not supported by this manifest")

    archive_meta = manifest.get("archive")
    if not isinstance(archive_meta, dict) or archive_meta.get("format") != "tar.gz":
        raise ValidationError("archive.format must be 'tar.gz'")
    file_name = archive_meta.get("fileName")
    digest = archive_meta.get("sha256")
    size_bytes = archive_meta.get("sizeBytes")
    if not isinstance(file_name, str) or Path(file_name).name != file_name or file_name in ("", ".", ".."):
        raise ValidationError("archive.fileName must be a plain file name, not a path or URL")
    if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
        raise ValidationError("archive.sha256 must be a lowercase 64-character SHA-256 digest")
    if not isinstance(size_bytes, int) or isinstance(size_bytes, bool) or size_bytes <= 0:
        raise ValidationError("archive.sizeBytes must be a positive integer")

    provenance = manifest.get("provenance")
    if not isinstance(provenance, dict):
        raise ValidationError("provenance object is required")
    source_repo = provenance.get("sourceRepository")
    source_commit = provenance.get("sourceCommit")
    built_at = provenance.get("builtAtUtc")
    if not isinstance(source_repo, str) or not source_repo.startswith("https://"):
        raise ValidationError("provenance.sourceRepository must be a verified HTTPS source URL")
    if not isinstance(source_commit, str) or not COMMIT_RE.fullmatch(source_commit):
        raise ValidationError("provenance.sourceCommit must pin a 40-64 character commit hash")
    if not isinstance(built_at, str) or not built_at.endswith("Z"):
        raise ValidationError("provenance.builtAtUtc must be a UTC timestamp ending in Z")
    if provenance.get("customPackageIdentityVerified") is not True:
        raise ValidationError("Custom package identity must be explicitly verified before release")

    if archive_path is None:
        archive_path = manifest_path.parent / file_name
    if archive_path.name != file_name:
        raise ValidationError(f"Archive file name mismatch: expected {file_name!r}, got {archive_path.name!r}")
    try:
        actual_size = archive_path.stat().st_size
    except OSError as exc:
        raise ValidationError(f"Archive file is unavailable: {exc}") from exc
    if actual_size != size_bytes:
        raise ValidationError(f"Archive size mismatch: manifest={size_bytes}, actual={actual_size}")

    digest_hasher = hashlib.sha256()
    with archive_path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest_hasher.update(chunk)
    actual_digest = digest_hasher.hexdigest()
    if actual_digest != digest:
        raise ValidationError(f"SHA-256 mismatch: expected {digest}, actual {actual_digest}")

    required_paths = manifest.get("requiredPaths", ["bin/sh"])
    if not isinstance(required_paths, list) or not required_paths or not all(isinstance(p, str) and p for p in required_paths):
        raise ValidationError("requiredPaths must be a non-empty list of relative paths")
    archive_summary = inspect_tar(archive_path, required_paths)
    return {
        "status": "valid",
        "applicationId": expected_app_id,
        "targetAbi": target_abi or "not specified",
        "archive": archive_path.name,
        "sha256": actual_digest,
        **archive_summary,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--expected-app-id", default=EXPECTED_APP_ID)
    parser.add_argument("--target-abi", choices=sorted(SUPPORTED_ABIS))
    args = parser.parse_args(argv)
    try:
        result = validate(args.manifest, args.archive, args.expected_app_id, args.target_abi)
    except NotReadyError as exc:
        print(f"NOT READY: {exc}", file=sys.stderr)
        return 3
    except ValidationError as exc:
        print(f"INVALID: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
