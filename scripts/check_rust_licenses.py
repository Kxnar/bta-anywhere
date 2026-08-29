#!/usr/bin/env python3
"""Fail closed on unexpected Cargo licence expressions and optionally write an inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


APPROVED_EXPRESSIONS = {
    "(MIT OR Apache-2.0) AND Unicode-3.0",
    "Apache-2.0",
    "Apache-2.0 AND ISC",
    "Apache-2.0 OR BSL-1.0",
    "Apache-2.0 OR ISC OR MIT",
    "Apache-2.0 OR MIT",
    "Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT",
    "BSD-2-Clause OR Apache-2.0 OR MIT",
    "BSD-3-Clause",
    "CC0-1.0 OR MIT-0 OR Apache-2.0",
    "CDLA-Permissive-2.0",
    "ISC",
    "ISC AND (Apache-2.0 OR ISC)",
    "ISC AND (Apache-2.0 OR ISC) AND Apache-2.0 AND MIT AND BSD-3-Clause AND "
    "(Apache-2.0 OR ISC OR MIT) AND (Apache-2.0 OR ISC OR MIT-0)",
    "MIT",
    "MIT AND BSD-3-Clause",
    "MIT OR Apache-2.0",
    "MIT OR Apache-2.0 OR LGPL-2.1-or-later",
    "MIT OR Apache-2.0 OR Zlib",
    "MIT/Apache-2.0",
    "Unlicense OR MIT",
    "Unlicense/MIT",
    "Zlib",
    "Zlib OR Apache-2.0 OR MIT",
}

FALLBACK_MIT_NOTICES = {
    ("governor", "0.10.4"): """MIT License

Copyright (c) 2023 Andreas Fuchs

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
""",
    ("valuable", "0.1.1"): """Copyright (c) 2021 Valuable Contributors

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
""",
}


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--notices-output", type=Path)
    return parser.parse_args()


def package_notice_files(package: dict[str, object]) -> list[Path]:
    root = Path(str(package["manifest_path"])).parent
    candidates = {
        path.resolve()
        for path in root.iterdir()
        if path.is_file()
        and path.name.lower().startswith(("license", "licence", "copying", "notice", "copyright"))
    }
    license_file = package.get("license_file")
    if license_file:
        candidate = (root / str(license_file)).resolve()
        if candidate.is_file():
            candidates.add(candidate)
    return sorted(candidates, key=lambda path: str(path).lower())


def write_notices(
    destination: Path,
    packages: list[dict[str, object]],
    workspace_members: set[str],
) -> None:
    repository_license = Path("LICENSE").read_text(encoding="utf-8")
    grouped: dict[str, dict[str, object]] = {}
    missing: list[str] = []

    for package in packages:
        if str(package["id"]) in workspace_members:
            continue
        identity = (str(package["name"]), str(package["version"]))
        expression = str(package.get("license") or "")
        notices = package_notice_files(package)
        if notices:
            texts = [(path.name, path.read_text(encoding="utf-8", errors="replace")) for path in notices]
        elif identity in FALLBACK_MIT_NOTICES:
            texts = [("upstream MIT licence fallback", FALLBACK_MIT_NOTICES[identity])]
        elif "Apache-2.0" in expression:
            texts = [("Apache-2.0 selected from declared alternatives", repository_license)]
        else:
            missing.append(f"{identity[0]} {identity[1]} ({expression})")
            continue

        for source_name, content in texts:
            if not content.strip():
                missing.append(f"{identity[0]} {identity[1]} ({source_name} is empty)")
                continue
            digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
            entry = grouped.setdefault(digest, {"content": content.rstrip() + "\n", "sources": set()})
            sources = entry["sources"]
            assert isinstance(sources, set)
            sources.add(f"{identity[0]} {identity[1]} — {source_name} — declared {expression}")

    if missing:
        raise SystemExit("Rust packages without redistributable licence text:\n" + "\n".join(missing))

    lines = [
        "BTA Anywhere Rust dependency licence and notice texts",
        "Generated from the exact package sources resolved by Cargo.lock.",
        "Identical texts are grouped; declared SPDX alternatives are shown per package.",
        "",
    ]
    for digest, entry in sorted(grouped.items()):
        sources = entry["sources"]
        content = entry["content"]
        assert isinstance(sources, set) and isinstance(content, str)
        lines.extend([
            "=" * 80,
            f"SHA-256: {digest}",
            "Packages/files:",
            *[f"  - {source}" for source in sorted(sources)],
            "-" * 80,
            content.rstrip(),
            "",
        ])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    arguments = parse_arguments()
    completed = subprocess.run(
        ["cargo", "metadata", "--locked", "--format-version", "1"],
        check=True,
        text=True,
        capture_output=True,
    )
    metadata = json.loads(completed.stdout)
    packages = sorted(metadata["packages"], key=lambda item: (item["name"], item["version"]))
    failures = [
        f"{package['name']} {package['version']}: {package.get('license') or '(missing)'}"
        for package in packages
        if package.get("license") not in APPROVED_EXPRESSIONS
    ]
    if failures:
        raise SystemExit("Unreviewed Cargo licence expressions:\n" + "\n".join(failures))

    if arguments.output is not None:
        lines = [
            "# Rust dependency licence inventory",
            "",
            "Generated from the release's committed `Cargo.lock`. Licence alternatives are used under a "
            "permissive option where the SPDX expression permits one.",
            "",
            "| Package | Version | SPDX licence expression |",
            "| --- | --- | --- |",
        ]
        for package in packages:
            lines.append(
                f"| {package['name'].replace('|', '&#124;')} | "
                f"{package['version'].replace('|', '&#124;')} | "
                f"{package['license'].replace('|', '&#124;')} |"
            )
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    if arguments.notices_output is not None:
        write_notices(arguments.notices_output, packages, set(metadata["workspace_members"]))

    print(f"Validated licence expressions for {len(packages)} Cargo packages.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
