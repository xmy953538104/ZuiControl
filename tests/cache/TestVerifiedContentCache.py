#!/usr/bin/env python3
"""Host contracts for the verified content cache."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts" / "build" / "VerifiedContentCache.py"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def invoke(*args):
    result = subprocess.run(
        [sys.executable, str(TOOL)] + list(args), check=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    values = {}
    for line in result.stdout.splitlines():
        if "=" in line:
            name, value = line.split("=", 1)
            values[name] = value
    return values


def invoke_failure(*args):
    return subprocess.run(
        [sys.executable, str(TOOL)] + list(args), check=False,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def lpunpack_args(source, fake_tool, cache, operation_version="fixture-v1", partitions=None):
    args = [
        "lpunpack", "--source-super", str(source), "--lpunpack-tool", str(fake_tool),
        "--python", sys.executable, "--cache-root", str(cache),
        "--operation-version", operation_version,
    ]
    for partition in partitions or ["system_a", "vendor_a"]:
        args.extend(["--partition", partition])
    return args


def write_fake_tool(path, salt):
    path.write_text(
        "from pathlib import Path\n"
        "import hashlib, sys\n"
        "source=Path(sys.argv[1]).read_bytes()\n"
        "out=Path(sys.argv[-1]); out.mkdir(parents=True, exist_ok=True)\n"
        "for name in sys.argv[2:-1]:\n"
        " (out/(name+'.img')).write_bytes(hashlib.sha256(source+name.encode()+%r).digest())\n"
        % salt,
        encoding="utf-8")


def manifest(values):
    return json.loads(Path(values["CACHE_MANIFEST"]).read_text(encoding="utf-8"))


def main():
    temp_root = Path(tempfile.mkdtemp(prefix="zui cache unicode "))
    try:
        cache = temp_root / "cache root"
        source = temp_root / "original super.img"
        source.write_bytes(b"source-v1")
        fake_tool = temp_root / "fake lpunpack.py"
        write_fake_tool(fake_tool, b"tool-v1")

        cold = invoke(*lpunpack_args(source, fake_tool, cache))
        warm = invoke(*lpunpack_args(source, fake_tool, cache))
        assert cold["CACHE_STATUS"] == "MISS"
        assert warm["CACHE_STATUS"] == "HIT"
        cold_manifest = manifest(cold)
        warm_manifest = manifest(warm)
        assert cold_manifest["output_sha256"] == warm_manifest["output_sha256"]
        assert cold["CACHE_KEY"] == warm["CACHE_KEY"]
        print("CACHE_HIT_FIXTURE=PASS")
        print("COLD_WARM_EQUIVALENCE=PASS")

        outside = temp_root / "outside sentinel"
        outside.write_bytes(b"untouched")
        tampered = manifest(warm)
        tampered["outputs"][0] = "../../outside sentinel"
        Path(warm["CACHE_MANIFEST"]).write_text(
            json.dumps(tampered), encoding="utf-8")
        repaired = invoke(*lpunpack_args(source, fake_tool, cache))
        assert repaired["CACHE_STATUS"] == "MISS"
        assert outside.read_bytes() == b"untouched"
        print("CACHE_OUTPUT_PATH_SAFETY=PASS")

        repaired_manifest = manifest(repaired)
        repaired_output = Path(repaired["CACHE_MANIFEST"]).parent / repaired_manifest["outputs"][0]
        repaired_output.write_bytes(b"corrupt")
        reverified = invoke(*lpunpack_args(source, fake_tool, cache))
        assert reverified["CACHE_STATUS"] == "MISS"
        print("CACHE_OUTPUT_HASH_REVALIDATION=PASS")

        Path(reverified["CACHE_MANIFEST"]).write_text("[]", encoding="utf-8")
        malformed_manifest = invoke(*lpunpack_args(source, fake_tool, cache))
        assert malformed_manifest["CACHE_STATUS"] == "MISS"
        print("CACHE_MALFORMED_MANIFEST_RECOVERY=PASS")

        entry = Path(malformed_manifest["CACHE_MANIFEST"]).parent
        link_target = entry.with_name(entry.name + "-target")
        entry.rename(link_target)
        try:
            os.symlink(str(link_target), str(entry), target_is_directory=True)
        except OSError:
            link_target.rename(entry)
            print("CACHE_ENTRY_SYMLINK_SAFETY=SKIP_PLATFORM")
        else:
            linked = invoke_failure(*lpunpack_args(source, fake_tool, cache))
            assert linked.returncode != 0
            assert "symbolic-link cache entry" in linked.stdout
            assert link_target.is_dir()
            entry.unlink()
            link_target.rename(entry)
            print("CACHE_ENTRY_SYMLINK_SAFETY=PASS")

        source.write_bytes(b"source-v2")
        changed_input = invoke(*lpunpack_args(source, fake_tool, cache))
        assert changed_input["CACHE_STATUS"] == "MISS"
        assert changed_input["CACHE_KEY"] != cold["CACHE_KEY"]
        print("INPUT_CHANGE_INVALIDATION=PASS")

        source.write_bytes(b"source-v1")
        write_fake_tool(fake_tool, b"tool-v2")
        changed_tool = invoke(*lpunpack_args(source, fake_tool, cache))
        assert changed_tool["CACHE_STATUS"] == "MISS"
        assert changed_tool["CACHE_KEY"] != cold["CACHE_KEY"]
        print("TOOL_CHANGE_INVALIDATION=PASS")

        write_fake_tool(fake_tool, b"tool-v1")
        changed_version = invoke(*lpunpack_args(
            source, fake_tool, cache, operation_version="fixture-v2"))
        assert changed_version["CACHE_STATUS"] == "MISS"
        assert changed_version["CACHE_KEY"] != cold["CACHE_KEY"]
        print("OPERATION_VERSION_INVALIDATION=PASS")

        changed_options = invoke(*lpunpack_args(
            source, fake_tool, cache, partitions=["system_a"]))
        assert changed_options["CACHE_STATUS"] == "MISS"
        assert changed_options["CACHE_KEY"] != cold["CACHE_KEY"]
        print("OPTION_CHANGE_INVALIDATION=PASS")

        movable = temp_root / "Mi A"
        movable.mkdir()
        moved_source = movable / "original super.img"
        moved_source.write_bytes(b"relocation-source")
        moved_tool = movable / "fake lpunpack.py"
        write_fake_tool(moved_tool, b"relocation-tool")
        moved_cache = movable / "cache"
        before_move = invoke(*lpunpack_args(moved_source, moved_tool, moved_cache))
        relocated = temp_root / "Mi B"
        shutil.move(str(movable), str(relocated))
        after_move = invoke(*lpunpack_args(
            relocated / moved_source.name, relocated / moved_tool.name,
            relocated / moved_cache.name))
        assert before_move["CACHE_STATUS"] == "MISS"
        assert after_move["CACHE_STATUS"] == "HIT"
        assert before_move["CACHE_KEY"] == after_move["CACHE_KEY"]
        print("CACHE_PATH_RELOCATION_FIXTURE=PASS")

        archive = temp_root / "artifact fixture.zip"
        with zipfile.ZipFile(str(archive), "w", zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr("nested/result.txt", "verified artifact\n")
        digest = sha256(archive)
        ci_args = [
            "ci-artifact", "--run-id", "33883778279", "--artifact-id", "9941022463",
            "--digest", "sha256:" + digest, "--source-archive", str(archive),
            "--cache-root", str(cache),
        ]
        ci_cold = invoke(*ci_args)
        ci_warm = invoke(*ci_args)
        assert ci_cold["CACHE_STATUS"] == "MISS"
        assert ci_warm["CACHE_STATUS"] == "HIT"
        ci_manifest = manifest(ci_warm)
        assert len(ci_manifest["outputs"]) == 2
        assert ci_manifest["output_sha256"] == manifest(ci_cold)["output_sha256"]
        print("CI_ARTIFACT_CACHE=PASS")

        unsafe_archive = temp_root / "unsafe artifact.zip"
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = (0o120777 << 16)
        with zipfile.ZipFile(str(unsafe_archive), "w") as bundle:
            bundle.writestr(link, "../../outside")
        unsafe = invoke_failure(
            "ci-artifact", "--run-id", "1", "--artifact-id", "2",
            "--digest", sha256(unsafe_archive), "--source-archive", str(unsafe_archive),
            "--cache-root", str(cache))
        assert unsafe.returncode != 0
        assert "Symbolic-link ZIP member" in unsafe.stdout
        print("CACHE_ZIP_SYMLINK_SAFETY=PASS")
        print("PORTABLE_PATHS=PASS")
        print("CACHE_TESTS=PASS")
    finally:
        shutil.rmtree(str(temp_root))


if __name__ == "__main__":
    main()
