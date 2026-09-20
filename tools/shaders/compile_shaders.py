
"""Compile the complete RtRenderExperiment Slang variant set transactionally and reproducibly."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any

sys.dont_write_bytecode = True


class CompileError(RuntimeError):
    """Raised when no complete shader set may be published."""


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _source_tree_sha256(source_dir: Path) -> str:
    files = sorted(source_dir.rglob("*.slang"), key=lambda item: item.relative_to(source_dir).as_posix())
    if not files:
        raise CompileError(f"shader source tree is empty: {source_dir}")
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(source_dir).as_posix().encode("utf-8")
        data = path.read_bytes()
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def _relative_file(value: Any, *, suffix: str, label: str) -> Path:
    if not isinstance(value, str) or not value:
        raise CompileError(f"{label} must be a non-empty string")
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or path.suffix != suffix:
        raise CompileError(f"unsafe {label}: {value!r}")
    return path


def _compiler_path(value: str) -> Path:
    resolved = Path(value).resolve() if "/" in value else Path(shutil.which(value) or "")
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise CompileError(f"Slang compiler is missing or not executable: {value}")
    return resolved


def _compiler_version(executable: Path) -> str:
    run = subprocess.run([str(executable), "-v"], check=False, capture_output=True, text=True)
    version = (run.stdout + run.stderr).strip()
    if run.returncode or not version:
        raise CompileError(f"cannot identify Slang compiler {executable}: {version}")
    return version


def _validate_contract(contract: dict[str, Any], source_dir: Path) -> tuple[dict[str, str], list[dict[str, Any]]]:
    policy = contract.get("compilerPolicy")
    variants = contract.get("variants")
    expected_policy = {
        "target", "profile", "optimization", "warningPolicy", "matrixLayout", "capability",
        "spirvMatrixMajor", "uniformMatrixStrideBytes",
    }
    if not isinstance(policy, dict) or set(policy) != expected_policy or not all(
        isinstance(value, str) and value for key, value in policy.items()
        if key != "uniformMatrixStrideBytes"
    ):
        raise CompileError("compilerPolicy has an unsupported shape")
    if not isinstance(policy["uniformMatrixStrideBytes"], int) or policy["uniformMatrixStrideBytes"] <= 0:
        raise CompileError("uniformMatrixStrideBytes must be positive")
    if not isinstance(variants, list) or not variants:
        raise CompileError("contract variants must be a non-empty list")
    required = {"source", "file", "entry", "stage", "layout", "capabilities", "defines"}
    outputs: set[Path] = set()
    normalized: list[dict[str, Any]] = []
    for index, variant in enumerate(variants):
        if not isinstance(variant, dict) or set(variant) != required:
            raise CompileError(f"variant {index} has an unsupported shape")
        source = _relative_file(variant["source"], suffix=".slang", label=f"variant {index} source")
        output = _relative_file(variant["file"], suffix=".spv", label=f"variant {index} output")
        if len(output.parts) != 1 or output in outputs:
            raise CompileError(f"variant output must be a unique file name: {output}")
        if not (source_dir / source).is_file():
            raise CompileError(f"variant source is missing: {source}")
        outputs.add(output)
        for field in ("entry", "stage", "layout"):
            if not isinstance(variant[field], str) or not variant[field]:
                raise CompileError(f"variant {output} has invalid {field}")
        if not isinstance(variant["capabilities"], list) or not all(
            isinstance(value, str) and value for value in variant["capabilities"]
        ):
            raise CompileError(f"variant {output} has invalid capabilities")
        if not isinstance(variant["defines"], list) or not all(
            isinstance(value, str) and re.fullmatch(r"[A-Z0-9_]+(?:=[A-Za-z0-9_.+-]+)?", value)
            for value in variant["defines"]
        ):
            raise CompileError(f"variant {output} has invalid defines")
        normalized.append({**variant, "source": source.as_posix(), "file": output.name})
    return policy, normalized


def _compile_one(
    executable: Path,
    source_dir: Path,
    output_dir: Path,
    policy: dict[str, Any],
    variant: dict[str, Any],
) -> dict[str, Any]:
    destination = output_dir / "rt_render_experiment/shaders" / variant["file"]
    command = [
        str(executable),
        str(source_dir / variant["source"]),
        "-I", str(source_dir),
        "-target", policy["target"],
        "-profile", policy["profile"],
        "-capability", policy["capability"],
        policy["optimization"],
        "-warnings-as-errors", policy["warningPolicy"],
        f"-matrix-layout-{policy['matrixLayout']}",
        "-entry", variant["entry"],
        "-o", str(destination),
    ]
    for define in variant["defines"]:
        command.extend(("-D", define))
    run = subprocess.run(command, check=False, capture_output=True, text=True)
    output = run.stdout + run.stderr
    if run.returncode:
        raise CompileError(f"slangc failed for {variant['file']}:\n{output}")
    if re.search(r"(?im)\bwarning(?:\[[^\]]+\])?\s*:", output):
        raise CompileError(f"slangc warning is fatal for {variant['file']}:\n{output}")
    if not destination.is_file() or destination.stat().st_size < 20:
        raise CompileError(f"slangc produced no valid-sized module for {variant['file']}")
    return {
        **variant,
        "sourceSha256": _sha256_file(source_dir / variant["source"]),
        "moduleSha256": _sha256_file(destination),
        "moduleBytes": destination.stat().st_size,
        "compilerOutput": output.strip(),
    }


def compile_all(
    *,
    slangc: str,
    source_dir: Path,
    output_dir: Path,
    contract: dict[str, Any],
    jobs: int,
) -> dict[str, Any]:
    source_dir = source_dir.resolve()
    output_dir = output_dir.resolve()
    if not source_dir.is_dir():
        raise CompileError(f"shader source directory is missing: {source_dir}")
    if jobs <= 0 or jobs > 64:
        raise CompileError(f"jobs must be between 1 and 64: {jobs}")
    executable = _compiler_path(slangc)
    version = _compiler_version(executable)
    policy, variants = _validate_contract(contract, source_dir)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}-staging-", dir=output_dir.parent))
    published = False
    try:
        (staging / "rt_render_experiment/shaders").mkdir(parents=True)
        with ThreadPoolExecutor(max_workers=min(jobs, len(variants)), thread_name_prefix="rt_render_experiment-slang") as executor:
            futures = [
                executor.submit(_compile_one, executable, source_dir, staging, policy, variant)
                for variant in variants
            ]
            results = [future.result() for future in futures]
        for result in results:
            if result["compilerOutput"]:
                print(f"slangc {result['file']}:\n{result['compilerOutput']}")
            result.pop("compilerOutput")
        manifest = {
            "schemaVersion": 1,
            "compiler": {
                "name": executable.name,
                "version": version,
                "executableSha256": _sha256_file(executable),
            },
            "policy": policy,
            "contractSha256": _sha256_bytes(_canonical_bytes(contract)),
            "sourceTreeSha256": _source_tree_sha256(source_dir),
            "variants": results,
        }
        (staging / "rt_render_experiment/shader-build.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        retired = output_dir.parent / f".{output_dir.name}-retired"
        if retired.exists():
            raise CompileError(f"stale transactional shader directory exists: {retired}")
        if output_dir.exists():
            output_dir.rename(retired)
        try:
            staging.rename(output_dir)
            published = True
        except BaseException:
            if retired.exists() and not output_dir.exists():
                retired.rename(output_dir)
            raise
        if retired.exists():
            shutil.rmtree(retired)
        return manifest
    finally:
        if not published and staging.exists():
            shutil.rmtree(staging)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", default="slangc")
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--contract-json", required=True)
    parser.add_argument("--jobs", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        contract = json.loads(args.contract_json)
        manifest = compile_all(
            slangc=args.slangc,
            source_dir=args.source_dir,
            output_dir=args.output_dir,
            contract=contract,
            jobs=args.jobs,
        )
    except (CompileError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"RT_RENDER_EXPERIMENT SHADER COMPILE ERROR: {error}", file=sys.stderr)
        return 1
    print(
        f"RtRenderExperiment shader compilation complete: {len(manifest['variants'])} variants; "
        f"manifest={args.output_dir.resolve() / 'rt_render_experiment/shader-build.json'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
