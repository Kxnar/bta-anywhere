"""Seeded, Windows-only Rust/Java protocol framing differential campaign.

The shared JSONL corpus and both raw evaluator outputs stay in the selected
output directory. Generated access-token strings are inert test fixtures.
"""

import argparse
import json
import os
from pathlib import Path
import random
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
MAX_FRAME = 65536


def frame(payload: bytes, declared: int | None = None) -> bytes:
    return struct.pack(">I", len(payload) if declared is None else declared) + payload


def generated_case(rng: random.Random, index: int) -> tuple[str, bytes, str, str, str]:
    kind = index % 18
    sequence = rng.randrange(0, 1_000_000)
    if kind == 0:
        payload = json.dumps({"type": "ping", "sequence": sequence}, separators=(",", ":")).encode()
        return "valid-ping", frame(payload), "control", "active", "session-test"
    if kind == 1:
        payload = json.dumps({"type": "register", "version": 1,
                              "accessToken": "synthetic-test-token",
                              "clientInstanceId": f"case-{sequence}"}, separators=(",", ":")).encode()
        return "valid-register", frame(payload), "control", "pre", "session-test"
    if kind == 2:
        payload = json.dumps({"version": 1, "sessionId": f"session-{sequence}",
                              "connectionId": f"connection-{sequence}",
                              "remoteAddress": "127.0.0.1:25565"}, separators=(",", ":")).encode()
        return "valid-connection", frame(payload), "connection", "active", f"session-{sequence}"
    if kind == 3:
        payload = json.dumps({"type": "ping", "sequence": sequence,
                              "futureField": [sequence, {"safe": True}]}, separators=(",", ":")).encode()
        return "unknown-field", frame(payload), "control", "active", "session-test"
    if kind == 4:
        payload = f'{{"type":"ping","sequence":{sequence},"sequence":{sequence + 1}}}'.encode()
        return "repeated-field", frame(payload), "control", "active", "session-test"
    if kind == 5:
        payload = b'{"type":"ping","sequence":' + str(sequence).encode() + b',"bad":"\xff"}'
        return "invalid-utf8", frame(payload), "control", "active", "session-test"
    if kind == 6:
        payload = b'{"type":"ping","sequence":' + str(sequence).encode()
        return "truncated-json", frame(payload), "control", "active", "session-test"
    if kind == 7:
        payload = json.dumps(["ping", sequence], separators=(",", ":")).encode()
        return "non-object", frame(payload), "control", "active", "session-test"
    if kind == 8:
        payload = json.dumps({"type": "close", "reason": "x" * rng.randrange(0, 128)},
                             separators=(",", ":")).encode()
        return "truncated-frame", frame(payload, len(payload) + rng.randrange(1, 20)), "control", "active", "session-test"
    if kind == 9:
        payload = json.dumps({"type": "pong", "sequence": sequence}, separators=(",", ":")).encode()
        return "extra-bytes", frame(payload) + bytes([rng.randrange(256)]), "control", "active", "session-test"
    if kind == 10:
        return "oversized-prefix", frame(b"", MAX_FRAME + rng.randrange(1, 8192)), "control", "active", "session-test"
    if kind == 11:
        depth = rng.randrange(1, 125)
        payload = (b'{"nested":' + b"[" * depth + str(sequence).encode()
                   + b"]" * depth + b"}")
        return "nested-json", frame(payload), "connection", "active", "session-test"
    if kind in (12, 13, 14, 15):
        message = {"type": "register", "version": 1, "accessToken": "synthetic-test-token",
                   "clientInstanceId": f"case-{sequence}"}
        name = {12: "invalid-auth", 13: "unsupported-version", 14: "register-active",
                15: "empty-client-id"}[kind]
        if kind == 12:
            message["accessToken"] = "wrong-synthetic-token"
        elif kind == 13:
            message["version"] = 2
        elif kind == 15:
            message["clientInstanceId"] = ""
        return name, frame(json.dumps(message, separators=(",", ":")).encode()), "control", (
            "active" if kind == 14 else "pre"), "session-test"
    if kind == 16:
        return "ping-before-register", frame(f'{{"type":"ping","sequence":{sequence}}}'.encode()), "control", "pre", "session-test"
    return "close-before-register", frame(b'{"type":"close","reason":"test"}'), "control", "pre", "session-test"


def boundary_cases() -> list[tuple[str, bytes, str, str, str]]:
    empty_object = b"{}"
    exact_max = b'{"pad":"' + b"x" * (MAX_FRAME - len(b'{"pad":""}')) + b'"}'
    assert len(exact_max) == MAX_FRAME
    too_large = exact_max + b" "
    deep = b'{"nested":' + b"[" * 129 + b"0" + b"]" * 129 + b"}"
    deepest_under_limit = (b'{"nested":' + b"[" * 30_000 + b"0" + b"]" * 30_000 + b"}")
    assert len(deepest_under_limit) < MAX_FRAME
    return [
        ("length-zero", frame(b""), "control", "active", "session-test"),
        ("length-one", frame(b"{"), "control", "active", "session-test"),
        ("length-two-object", frame(empty_object), "control", "active", "session-test"),
        ("length-65536-valid-json", frame(exact_max), "control", "active", "session-test"),
        ("length-65537-reject", frame(too_large), "control", "active", "session-test"),
        ("length-65536-truncated", frame(b"", MAX_FRAME), "control", "active", "session-test"),
        ("deep-129", frame(deep), "connection", "active", "session-test"),
        ("deep-30000", frame(deepest_under_limit), "connection", "active", "session-test"),
        ("coerced-sequence", frame(b'{"type":"ping","sequence":"7"}'), "control", "active", "session-test"),
        ("wrong-session", frame(b'{"version":1,"sessionId":"other","connectionId":"c","remoteAddress":"127.0.0.1:1"}'),
         "connection", "active", "session-test"),
        ("invalid-utf8-connection", frame(b'{"version":1,"sessionId":"\xff","connectionId":"c","remoteAddress":"127.0.0.1:1"}'),
         "connection", "active", "session-test"),
    ]


STATE_KINDS = {"valid-ping", "valid-register", "invalid-auth", "unsupported-version",
               "register-active", "empty-client-id", "ping-before-register",
               "close-before-register"}


def write_corpus(path: Path, seed: int, count: int, target_group: str = "all") -> list[str]:
    rng = random.Random(seed)
    kinds = []
    edges = boundary_cases() if target_group in ("all", "framing") else []
    generated_index = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for index in range(count):
            if index < len(edges):
                kind, payload, target, phase, expected_session = edges[index]
            else:
                while True:
                    kind, payload, target, phase, expected_session = generated_case(rng, generated_index)
                    generated_index += 1
                    if (target_group in ("all", "framing")
                            or target_group == target
                            or target_group == "state" and kind in STATE_KINDS):
                        break
            kinds.append(kind)
            handle.write(json.dumps({"id": index, "kind": kind, "target": target, "phase": phase,
                                     "expectedSession": expected_session, "frameHex": payload.hex()},
                                    separators=(",", ":")) + "\n")
    return kinds


def run(command: list[str], environment: dict[str, str]) -> float:
    started = time.monotonic()
    subprocess.run(command, cwd=ROOT, env=environment, check=True, timeout=600)
    return time.monotonic() - started


def read_results(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle]


def compare_results(rust: list[dict], java: list[dict], kinds: list[str]) -> dict:
    if len(rust) != len(kinds) or len(java) != len(kinds):
        raise ValueError("evaluator output count does not match corpus count")
    mismatches = []
    framing_mismatches = 0
    typed_mismatches = 0
    counts = {}
    for index, (left, right) in enumerate(zip(rust, java)):
        if left.get("id") != index or right.get("id") != index:
            raise ValueError(f"evaluator output ID is missing or out of order at case {index}")
        kind = kinds[index]
        counts[kind] = counts.get(kind, 0) + 1
        if left != right:
            if (left["accepted"], left["semantic"]) != (right["accepted"], right["semantic"]):
                framing_mismatches += 1
            if (left["typedAccepted"], left["typedSemantic"], left["stateOutcome"]) != (
                    right["typedAccepted"], right["typedSemantic"], right["stateOutcome"]):
                typed_mismatches += 1
            mismatches.append({"id": index, "kind": kind, "rust": left, "java": right})
    return {"caseCounts": counts, "mismatchCount": len(mismatches),
            "framingMismatchCount": framing_mismatches,
            "typedMismatchCount": typed_mismatches, "mismatches": mismatches[:50]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=20260925)
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != "win32":
        parser.error("the supported protocol campaign target is Windows x86-64")
    if args.count < 1 or args.count > 1_000_000:
        parser.error("--count must be between 1 and 1,000,000")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    corpus = output / "corpus.jsonl"
    kinds = write_corpus(corpus, args.seed, args.count)
    rust_output = output / "rust-results.jsonl"
    java_output = output / "java-results.jsonl"
    environment = os.environ.copy()
    environment["BTA_PROTOCOL_CORPUS"] = str(corpus)
    environment["BTA_PROTOCOL_RUST_OUTPUT"] = str(rust_output)
    timings = {}
    cargo = "cargo.exe" if sys.platform == "win32" else "cargo"
    try:
        timings["rustSeconds"] = run([cargo, "test", "--locked", "--release", "--package",
                                      "bta-anywhere-relay", "--test", "protocol_corpus",
                                      "shared_corpus", "--", "--exact", "--nocapture"], environment)
        gradle = str(ROOT / "gradlew.bat")
        timings["javaSeconds"] = run([gradle, "--no-daemon", ":tunnel-client:protocolCorpus",
                                      f"-PprotocolCorpus={corpus}",
                                      f"-PprotocolCorpusOutput={java_output}"], environment)
    except (subprocess.SubprocessError, OSError) as failure:
        (output / "summary.json").write_text(json.dumps({"schemaVersion": 1, "seed": args.seed,
            "cases": args.count, "status": "evaluator_failed", "error": str(failure)[:1000]}, indent=2)
            + "\n", encoding="utf-8")
        raise
    rust = read_results(rust_output)
    java = read_results(java_output)
    comparison = compare_results(rust, java, kinds)
    summary = {"schemaVersion": 1, "seed": args.seed, "cases": args.count,
               **comparison, "timings": timings,
               "comparison": "framing and JSON-object semantics; typed client control or connection-open semantics; modelled pre-registration/active authentication and state decisions (no relay process)"}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"cases": args.count, "mismatches": comparison["mismatchCount"], "output": str(output)}))
    return 1 if comparison["mismatchCount"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
