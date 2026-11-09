#!/usr/bin/env python3
"""Check source and APK/JAR/AAB contents without printing configured secrets.

Reads only the named private config files and selected secret environment fields.
Archive members are inspected in memory; nothing is extracted or executed.
"""
import argparse
import base64
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, quote_plus
import zipfile
import zlib


SECRET_FIELDS = ("OPENAI_API_KEY", "GOOGLE_CLIENT_SECRET", "SPOTIFY_CLIENT_SECRET", "PLATFORM_TOKEN_SECRET")
ARCHIVES = (".zip", ".jar", ".apk", ".aab", ".aar")
SOURCE_DIRS = ("android", "backend", "core", "catalog", "deploy", "tools", "docs", ".github")
SKIP_DIRS = {".git", ".gradle", ".idea", ".codex", ".agents", "build", "output", "node_modules", "__pycache__", ".venv"}


def private_source_config(name):
    return name == "local.properties" or name == ".env" or name.startswith(".env.") and not name.endswith((".example", ".sample", ".template"))


def load_secrets(configs):
    values = {name: set() for name in SECRET_FIELDS}
    missing = []
    for config in configs:
        if not config.is_file():
            missing.append(config.name)
            continue
        for line in config.read_text().splitlines():
            match = re.fullmatch(r"\s*(?:export\s+)?([A-Z_]+)\s*=\s*(.*?)\s*", line)
            if match and match[1] in values:
                value = match[2]
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                if value:
                    values[match[1]].add(value)
    for name in SECRET_FIELDS:
        if os.environ.get(name):
            values[name].add(os.environ[name])
    return values, missing


class Scan:
    def __init__(self, values, max_entry=64 * 1024 * 1024, max_total=1024 * 1024 * 1024):
        self.needles, self.findings, self.errors = [], [], []
        self.max_entry, self.max_total, self.bytes_read, self.members, self.source_files = max_entry, max_total, 0, 0, 0
        for name, candidates in values.items():
            for value in candidates:
                raw = value.encode()
                variants = {"literal": raw, "base64": base64.b64encode(raw), "url": quote(value, safe="").encode(), "form": quote_plus(value).encode()}
                if name == "PLATFORM_TOKEN_SECRET":
                    try:
                        decoded = base64.b64decode(raw, validate=True)
                        if len(decoded) == 32:
                            variants["decoded-key"] = decoded
                    except ValueError:
                        pass
                seen = set()
                for encoding, needle in variants.items():
                    if needle and needle not in seen:
                        self.needles.append((name, encoding, needle))
                        seen.add(needle)

    def safe(self, value):
        for _, _, needle in self.needles:
            try:
                value = value.replace(needle.decode(), "[redacted]")
            except UnicodeDecodeError:
                pass
        return value

    def error(self, location, kind):
        self.errors.append({"location": self.safe(location), "kind": kind})

    def read(self, stream, size, location):
        if size > self.max_entry or self.bytes_read + size > self.max_total:
            self.error(location, "scan_size_limit")
            return None
        data = stream.read(self.max_entry + 1)
        self.bytes_read += len(data)
        if len(data) > self.max_entry or self.bytes_read > self.max_total:
            self.error(location, "scan_size_limit")
            return None
        return data

    def content(self, data, location):
        for name, encoding, needle in self.needles:
            if needle in data:
                self.findings.append({"location": self.safe(location), "kind": "configured_secret", "field": name, "encoding": encoding})

    def archive(self, source, location, depth=0):
        if depth > 6:
            self.error(location, "archive_depth_limit")
            return
        try:
            with zipfile.ZipFile(source) as archive:
                for member in archive.infolist():
                    if member.is_dir():
                        continue
                    self.members += 1
                    if self.members > 100_000:
                        self.error(location, "archive_member_limit")
                        return
                    entry = location + "!" + member.filename
                    name = member.filename.replace("\\", "/").rsplit("/", 1)[-1].lower()
                    if name == "local.properties" or name == ".env" or name.startswith(".env.") or name.endswith((".db", ".db-wal", ".db-shm", ".sqlite", ".sqlite3")):
                        self.findings.append({"location": self.safe(entry), "kind": "packaged_private_config_or_database"})
                    with archive.open(member) as stream:
                        data = self.read(stream, member.file_size, entry)
                    if data is None:
                        continue
                    self.content(data, entry)
                    if name.endswith(ARCHIVES):
                        self.archive(io.BytesIO(data), entry, depth + 1)
        except (OSError, ValueError, RuntimeError, EOFError, zipfile.BadZipFile, zlib.error, NotImplementedError) as error:
            self.error(location, "unreadable_archive_" + type(error).__name__)

    def sources(self, root):
        files = [p for p in root.iterdir() if p.is_file() and not private_source_config(p.name)]
        for directory in SOURCE_DIRS:
            folder = root / directory
            if not folder.is_dir():
                continue
            for current, directories, names in os.walk(folder, followlinks=False):
                retained = []
                for name in directories:
                    path = Path(current) / name
                    if name in SKIP_DIRS or path == root / "backend/data":
                        continue
                    if path.is_symlink():
                        self.error(str(path.relative_to(root)), "source_symlink_not_scanned")
                    else:
                        retained.append(name)
                directories[:] = retained
                files.extend(Path(current) / name for name in names if not private_source_config(name) and name != ".DS_Store")
        for path in files:
            location = str(path.relative_to(root))
            if path.is_symlink():
                self.error(location, "source_symlink_not_scanned")
                continue
            try:
                with path.open("rb") as stream:
                    data = self.read(stream, path.stat().st_size, location)
                if data is not None:
                    self.source_files += 1
                    self.content(data, location)
            except OSError as error:
                self.error(location, "unreadable_source_" + type(error).__name__)


def self_test():
    secret = "sk-release-scan-fixture-only-1234567890"
    values = {"OPENAI_API_KEY": {secret}}
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as archive:
        archive.writestr("app.properties", base64.b64encode(secret.encode()))
    outer = io.BytesIO()
    with zipfile.ZipFile(outer, "w") as archive:
        archive.writestr("BOOT-INF/lib/fixture.jar", inner.getvalue())
        archive.writestr("assets/.env", "OPENAI_API_KEY=" + secret)
        archive.writestr("private.sqlite", b"private fixture")
        archive.writestr("public-client-ids.txt", "KAKAO_NATIVE_APP_KEY=public-native-key")
    scan = Scan(values)
    scan.archive(io.BytesIO(outer.getvalue()), "fixture.jar")
    assert len([f for f in scan.findings if f["kind"] == "configured_secret"]) >= 2
    assert len([f for f in scan.findings if f["kind"] == "packaged_private_config_or_database"]) == 2
    assert not scan.errors and secret not in json.dumps(scan.findings)
    assert scan.safe("path/" + secret) == "path/[redacted]"
    bounded = Scan(values, max_entry=8)
    bounded.archive(io.BytesIO(outer.getvalue()), "fixture.jar")
    assert bounded.errors and all(e["kind"] == "scan_size_limit" for e in bounded.errors)
    broken = Scan(values)
    broken.archive(io.BytesIO(b"not an archive"), "broken.jar")
    assert broken.errors
    with tempfile.TemporaryDirectory(prefix="taptap-secret-scan-") as folder:
        root = Path(folder)
        (root / "public.java").write_text(secret)
        (root / ".env").write_text("OPENAI_API_KEY=" + secret)
        (root / ".env.example").write_text("OPENAI_API_KEY=" + secret)
        scanned = Scan(values)
        scanned.sources(root)
        assert len(scanned.findings) == 2 and scanned.source_files == 2
    print(json.dumps({"selfTest": "passed", "scope": "synthetic nested archive, private package files, redaction, bounds, invalid archive and source exclusion"}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="*", help="Final debug APK, server JAR and release AAB to inspect")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--env", action="append", type=Path, help="Private secret config; default backend/.env")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    root = args.root.resolve()
    resolve = lambda p: p if p.is_absolute() else root / p
    try:
        values, missing = load_secrets([resolve(p) for p in (args.env or [Path("backend/.env")])])
    except (OSError, UnicodeError):
        print(json.dumps({"status": "incomplete", "error": "private_config_unreadable"}))
        return 2
    scan = Scan(values)
    scan.sources(root)
    artifacts = []
    for value in args.artifacts:
        artifact = resolve(Path(value))
        try:
            with artifact.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            scan.archive(artifact, artifact.name)
            artifacts.append({"name": scan.safe(artifact.name), "sha256": digest})
        except OSError:
            scan.error(artifact.name, "artifact_unreadable")
    loaded = [name for name, candidates in values.items() if candidates]
    present = {Path(a["name"]).suffix.lower() for a in artifacts}
    missing_kinds = sorted({".apk", ".jar", ".aab"} - present)
    status = "failed" if scan.findings else "incomplete" if scan.errors or not loaded or missing_kinds else "passed"
    report = {"status": status, "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "secretFieldsLoaded": loaded,
              "secretFieldsNotConfigured": [name for name in SECRET_FIELDS if name not in loaded], "missingConfigFiles": [scan.safe(p) for p in missing],
              "publicIdsIntentionallyExcluded": ["KAKAO_NATIVE_APP_KEY", "KAKAO_APP_ID", "GOOGLE_CLIENT_ID", "SPOTIFY_CLIENT_ID"],
              "sourceFiles": scan.source_files, "archiveMembers": scan.members, "bytesInspected": scan.bytes_read, "artifacts": artifacts, "missingArtifactKinds": missing_kinds,
              "findings": scan.findings, "errors": scan.errors,
              "scope": "Configured secret values and their literal/URL/base64 forms plus packaged private config/DB. Not a proof that unknown or encrypted secrets are absent."}
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")
    return 0 if status == "passed" else 1 if status == "failed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
