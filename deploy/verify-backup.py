#!/usr/bin/env python3
"""Verify paired backup checksums and refuse links/path traversal before restoration."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import tarfile


def verify(directory: Path):
    expected = {"database.dump", "storage.tar.gz", "images.txt", "revision.txt"}
    manifest = {}
    for line in (directory / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        checksum, name = line.split(maxsplit=1)
        name = name.lstrip(" *")
        if name not in expected or name in manifest or len(checksum) != 64:
            raise ValueError("Invalid checksum manifest")
        int(checksum, 16)
        manifest[name] = checksum.lower()
    if manifest.keys() != expected:
        raise ValueError("Backup must contain exactly the four expected manifest entries")
    for name, checksum in manifest.items():
        source = directory / name
        if not source.is_file() or source.is_symlink():
            raise ValueError("Backup contains a missing file or a symbolic link")
        digest = hashlib.sha256()
        with source.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != checksum:
            raise ValueError(f"Checksum mismatch: {name}")
    with (directory / "database.dump").open("rb") as stream:
        if stream.read(5) != b"PGDMP":
            raise ValueError("Database dump is not in PostgreSQL custom format")
    files = 0
    expanded_bytes = 0
    root_seen = False
    with tarfile.open(directory / "storage.tar.gz", "r:gz") as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or ".." in path.parts or not path.parts
                    or path.parts[0] != "storage" or "\\" in member.name
                    or not (member.isfile() or member.isdir())):
                raise ValueError("Unsafe storage archive: only regular files/directories under storage/ are allowed")
            if path.parts == ("storage",):
                if not member.isdir():
                    raise ValueError("Storage root must be a directory")
                root_seen = True
            if member.isfile():
                files += 1
                expanded_bytes += member.size
    if not root_seen:
        raise ValueError("Storage archive has no storage/ root directory")
    return files, expanded_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        count, size = verify(args.directory.resolve())
    except (OSError, ValueError, tarfile.TarError) as error:
        parser.exit(1, f"Backup verification failed: {error}\n")
    print(f"Backup checksums and storage paths verified: {count} files, {size} uncompressed bytes.")


if __name__ == "__main__":
    main()
