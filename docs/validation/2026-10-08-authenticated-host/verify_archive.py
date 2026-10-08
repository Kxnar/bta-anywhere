"""Verify published journey evidence; this does not assert multiplayer success."""
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    expected = manifest["files_sha256"]
    actual = {
        p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in root.rglob("*")
        if p.is_file() and p.name != "manifest.json" and "__pycache__" not in p.parts
    }
    if actual != expected:
        bad = sorted(k for k in actual.keys() | expected.keys() if actual.get(k) != expected.get(k))
        raise SystemExit(f"Archive mismatch: {bad}")
    print(f"Verified {len(expected)} files. Journey outcome: {manifest['journey_outcome']}")


if __name__ == "__main__":
    main()
