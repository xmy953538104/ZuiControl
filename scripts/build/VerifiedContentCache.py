#!/usr/bin/env python3
"""Verified content-addressed caches for stable host-side artifacts."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile


SCHEMA_VERSION = 1
LPUNPACK_OPERATION = "original-super-lpunpack"
CI_OPERATION = "github-ci-artifact"


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def cache_key(operation_version, source_sha256, tool_sha256, tool_version, options):
    identity = {
        "operation_version": operation_version,
        "source_sha256": source_sha256,
        "tool_sha256": tool_sha256,
        "tool_version": tool_version,
        "options": options,
    }
    return hashlib.sha256(canonical_json(identity).encode("utf-8")).hexdigest()


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def read_json(path):
    with path.open("r", encoding="utf-8-sig") as stream:
        return json.load(stream)


def write_json(path, value):
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def checked_cache_path(cache_root, operation, key):
    root = cache_root.resolve()
    path = (root / operation / key).resolve()
    if (path.parent != (root / operation).resolve() or len(key) != 64 or
            any(char not in "0123456789abcdef" for char in key)):
        raise RuntimeError("Refusing cache path outside the selected operation root")
    return path


def remove_cache_entry(cache_root, operation, key):
    path = checked_cache_path(cache_root, operation, key)
    if path.exists():
        shutil.rmtree(str(path))


def safe_relative_path(value, label):
    normalized = str(value).replace("\\", "/")
    path = PurePosixPath(normalized)
    if (not path.parts or path.is_absolute() or "." in path.parts or
            ".." in path.parts or ":" in path.parts[0]):
        raise RuntimeError("Unsafe {} path: {}".format(label, value))
    return Path(*path.parts)


def output_records(root, outputs):
    resolved_root = root.resolve()
    records = []
    for value in outputs:
        relative = safe_relative_path(value, "cache output")
        path = root / relative
        resolved = path.resolve()
        if resolved.parent != resolved_root and resolved_root not in resolved.parents:
            raise RuntimeError("Cache output escapes the selected entry: {}".format(value))
        cursor = path
        while cursor != root:
            if cursor.is_symlink():
                raise RuntimeError("Cache output uses a symbolic link: {}".format(value))
            cursor = cursor.parent
        if not path.is_file():
            raise RuntimeError("Expected cache output is missing: {}".format(path))
        records.append((relative.as_posix(), path.stat().st_size, sha256_file(path)))
    return records


def make_manifest(operation, operation_version, key, source_path, source_sha256,
                  tool_path, tool_sha256, tool_version, options, records):
    return {
        "schema_version": SCHEMA_VERSION,
        "cache_key": key,
        "operation": operation,
        "operation_version": operation_version,
        "source": str(source_path.resolve()),
        "source_sha256": source_sha256,
        "source_bytes": source_path.stat().st_size,
        "tool": str(tool_path.resolve()),
        "tool_sha256": tool_sha256,
        "tool_version": tool_version,
        "options": options,
        "outputs": [row[0] for row in records],
        "output_sha256": [row[2] for row in records],
        "output_bytes": [row[1] for row in records],
        "created_at": utc_now(),
        "verified": True,
    }


def validate_hit(entry, expected, expected_outputs):
    manifest_path = entry / "CACHE_MANIFEST.json"
    if not manifest_path.is_file():
        return None
    try:
        manifest = read_json(manifest_path)
        for name, value in expected.items():
            if manifest.get(name) != value:
                return None
        if manifest.get("schema_version") != SCHEMA_VERSION or manifest.get("verified") is not True:
            return None
        outputs = manifest.get("outputs", [])
        if (outputs != [Path(value).as_posix() for value in expected_outputs] or
                len(outputs) != len(manifest.get("output_sha256", []))):
            return None
        records = output_records(entry, outputs)
        if [row[2] for row in records] != manifest["output_sha256"]:
            return None
        if [row[1] for row in records] != manifest.get("output_bytes"):
            return None
        return manifest
    except (OSError, ValueError, TypeError, RuntimeError):
        return None


def emit_result(status, operation, key, entry, started, manifest):
    print("CACHE_{}".format(status))
    print("CACHE_STATUS={}".format(status))
    print("CACHE_OPERATION={}".format(operation))
    print("CACHE_KEY={}".format(key))
    print("CACHE_ENTRY={}".format(entry))
    print("CACHE_MANIFEST={}".format(entry / "CACHE_MANIFEST.json"))
    print("CACHE_OUTPUT_BYTES={}".format(sum(manifest["output_bytes"])))
    print("CACHE_ELAPSED_SECONDS={:.3f}".format(time.perf_counter() - started))


def python_version(python_exe):
    result = subprocess.run(
        [str(python_exe), "--version"], check=True, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True)
    return result.stdout.strip()


def lpunpack(args):
    started = time.perf_counter()
    source = Path(args.source_super).resolve()
    tool = Path(args.lpunpack_tool).resolve()
    python_exe = Path(args.python).resolve()
    cache_root = Path(args.cache_root).resolve()
    for path, label in ((source, "source super"), (tool, "lpunpack tool"),
                        (python_exe, "Python runtime")):
        if not path.is_file():
            raise RuntimeError("Missing {}: {}".format(label, path))
    partitions = list(args.partition)
    if not partitions or len(partitions) != len(set(partitions)):
        raise RuntimeError("At least one unique partition is required")
    if any(len(safe_relative_path(name, "partition").parts) != 1 for name in partitions):
        raise RuntimeError("Partition names must be simple relative names")
    source_hash = sha256_file(source)
    tool_hash = sha256_file(tool)
    tool_version = "{}; {}".format(python_version(python_exe), tool.name)
    options = {"partitions": partitions, "image_suffix": ".img"}
    key = cache_key(args.operation_version, source_hash, tool_hash, tool_version, options)
    entry = checked_cache_path(cache_root, LPUNPACK_OPERATION, key)
    expected = {
        "cache_key": key,
        "operation": LPUNPACK_OPERATION,
        "operation_version": args.operation_version,
        "source_sha256": source_hash,
        "source_bytes": source.stat().st_size,
        "tool_sha256": tool_hash,
        "tool_version": tool_version,
        "options": options,
    }
    outputs = [Path("files") / (name + ".img") for name in partitions]
    manifest = validate_hit(entry, expected, outputs)
    if manifest is not None:
        emit_result("HIT", LPUNPACK_OPERATION, key, entry, started, manifest)
        return 0

    operation_root = entry.parent
    operation_root.mkdir(parents=True, exist_ok=True)
    free_bytes = shutil.disk_usage(str(operation_root)).free
    if free_bytes < source.stat().st_size + 2 * 1024 ** 3:
        raise RuntimeError("Insufficient free space for controlled extraction")
    remove_cache_entry(cache_root, LPUNPACK_OPERATION, key)
    staging = operation_root / (".tmp-{}-{}".format(key, uuid.uuid4().hex))
    output_root = staging / "files"
    output_root.mkdir(parents=True)
    try:
        command = [str(python_exe), str(tool), str(source)] + partitions + [str(output_root)]
        subprocess.run(command, check=True)
        records = output_records(staging, outputs)
        manifest = make_manifest(
            LPUNPACK_OPERATION, args.operation_version, key, source, source_hash,
            tool, tool_hash, tool_version, options, records)
        write_json(staging / "CACHE_MANIFEST.json", manifest)
        os.replace(str(staging), str(entry))
    finally:
        if staging.exists():
            shutil.rmtree(str(staging))
    emit_result("MISS", LPUNPACK_OPERATION, key, entry, started, manifest)
    return 0


def zip_output_paths(bundle):
    outputs = [Path("artifact.zip")]
    seen = set()
    for info in bundle.infolist():
        path = safe_relative_path(info.filename, "ZIP member")
        key = path.as_posix().casefold()
        if key in seen:
            raise RuntimeError("Duplicate ZIP member: {}".format(info.filename))
        seen.add(key)
        if stat.S_IFMT(info.external_attr >> 16) == stat.S_IFLNK:
            raise RuntimeError("Symbolic-link ZIP member: {}".format(info.filename))
        if not info.is_dir():
            outputs.append(Path("files") / path)
    return outputs


def download_or_copy(args, destination):
    if args.source_archive:
        source = Path(args.source_archive).resolve()
        if not source.is_file():
            raise RuntimeError("CI artifact source archive is missing: {}".format(source))
        shutil.copyfile(str(source), str(destination))
        return source
    request = urllib.request.Request(args.url)
    if args.token_env:
        token = os.environ.get(args.token_env, "")
        if not token:
            raise RuntimeError("Token environment variable is empty: {}".format(args.token_env))
        request.add_header("Authorization", "Bearer {}".format(token))
    request.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(request, timeout=args.timeout_seconds) as response:
        with destination.open("wb") as stream:
            shutil.copyfileobj(response, stream, length=4 * 1024 * 1024)
    return destination


def ci_artifact(args):
    started = time.perf_counter()
    cache_root = Path(args.cache_root).resolve()
    tool = Path(__file__).resolve()
    digest = args.digest.lower()
    if digest.startswith("sha256:"):
        digest = digest[7:]
    if len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest):
        raise RuntimeError("Artifact digest must be a SHA-256 value")
    tool_hash = sha256_file(tool)
    tool_version = "{}; schema={}".format(sys.version.split()[0], SCHEMA_VERSION)
    options = {
        "run_id": str(args.run_id),
        "artifact_id": str(args.artifact_id),
        "digest": "sha256:" + digest,
        "extract": True,
    }
    key = cache_key(args.operation_version, digest, tool_hash, tool_version, options)
    entry = checked_cache_path(cache_root, CI_OPERATION, key)
    expected = {
        "cache_key": key,
        "operation": CI_OPERATION,
        "operation_version": args.operation_version,
        "source_sha256": digest,
        "tool_sha256": tool_hash,
        "tool_version": tool_version,
        "options": options,
    }
    outputs = None
    cached_archive = entry / "artifact.zip"
    try:
        if (cached_archive.is_file() and not cached_archive.is_symlink() and
                sha256_file(cached_archive) == digest):
            with zipfile.ZipFile(str(cached_archive), "r") as bundle:
                outputs = zip_output_paths(bundle)
    except (OSError, RuntimeError, zipfile.BadZipFile):
        outputs = None
    manifest = validate_hit(entry, expected, outputs) if outputs is not None else None
    if manifest is not None:
        emit_result("HIT", CI_OPERATION, key, entry, started, manifest)
        return 0

    operation_root = entry.parent
    operation_root.mkdir(parents=True, exist_ok=True)
    remove_cache_entry(cache_root, CI_OPERATION, key)
    staging = operation_root / (".tmp-{}-{}".format(key, uuid.uuid4().hex))
    files_root = staging / "files"
    staging.mkdir(parents=True)
    archive = staging / "artifact.zip"
    try:
        source_path = download_or_copy(args, archive)
        actual_digest = sha256_file(archive)
        if actual_digest != digest:
            raise RuntimeError("CI artifact digest mismatch: expected {}, got {}".format(
                digest, actual_digest))
        with zipfile.ZipFile(str(archive), "r") as bundle:
            outputs = zip_output_paths(bundle)
            bundle.extractall(str(files_root))
        records = output_records(staging, outputs)
        source_for_manifest = source_path if source_path.is_file() else archive
        manifest = make_manifest(
            CI_OPERATION, args.operation_version, key, source_for_manifest, digest,
            tool, tool_hash, tool_version, options, records)
        manifest["source"] = args.url or str(Path(args.source_archive).resolve())
        manifest["source_bytes"] = archive.stat().st_size
        write_json(staging / "CACHE_MANIFEST.json", manifest)
        os.replace(str(staging), str(entry))
    finally:
        if staging.exists():
            shutil.rmtree(str(staging))
    emit_result("MISS", CI_OPERATION, key, entry, started, manifest)
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    lp = subparsers.add_parser("lpunpack", help="cache verified logical images")
    lp.add_argument("--source-super", required=True)
    lp.add_argument("--lpunpack-tool", required=True)
    lp.add_argument("--python", default=sys.executable)
    lp.add_argument("--cache-root", required=True)
    lp.add_argument("--operation-version", default="lpunpack-v1")
    lp.add_argument("--partition", action="append", required=True)
    lp.set_defaults(func=lpunpack)

    ci = subparsers.add_parser("ci-artifact", help="cache a verified GitHub Actions artifact")
    ci.add_argument("--run-id", required=True)
    ci.add_argument("--artifact-id", required=True)
    ci.add_argument("--digest", required=True)
    source = ci.add_mutually_exclusive_group(required=True)
    source.add_argument("--source-archive")
    source.add_argument("--url")
    ci.add_argument("--token-env", default="")
    ci.add_argument("--timeout-seconds", type=int, default=120)
    ci.add_argument("--cache-root", required=True)
    ci.add_argument("--operation-version", default="github-artifact-v1")
    ci.set_defaults(func=ci_artifact)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("CACHE_ERROR={}".format(error), file=sys.stderr)
        raise SystemExit(1)
