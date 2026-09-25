"""Seeded, Windows-only Rust/Java protocol framing differential campaign.

The shared JSONL corpus and both raw evaluator outputs stay in the selected
output directory. Generated access-token strings are inert test fixtures.
"""

import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import struct
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
MAX_FRAME = 65536


class MemoryStatus(ctypes.Structure):
    _fields_ = [("dwLength", wintypes.DWORD), ("dwMemoryLoad", wintypes.DWORD),
                ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]


class SystemPowerStatus(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                ("BatteryLifeTime", wintypes.DWORD), ("BatteryFullLifeTime", wintypes.DWORD)]


def command_output(command: list[str]) -> str:
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True, timeout=10)
    return (result.stdout + result.stderr).strip()[:512]


def machine_metadata() -> dict:
    architecture = platform.machine()
    if architecture.lower() not in ("amd64", "x86_64"):
        raise RuntimeError(f"unsupported benchmark architecture: {architecture}; Windows x86-64 is required")
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.GlobalMemoryStatusEx.argtypes = [ctypes.POINTER(MemoryStatus)]
    kernel32.GlobalMemoryStatusEx.restype = wintypes.BOOL
    kernel32.GetSystemPowerStatus.argtypes = [ctypes.POINTER(SystemPowerStatus)]
    kernel32.GetSystemPowerStatus.restype = wintypes.BOOL
    memory = MemoryStatus()
    memory.dwLength = ctypes.sizeof(memory)
    if not kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
        raise OSError(ctypes.get_last_error(), "cannot read physical RAM")
    power = SystemPowerStatus()
    if not kernel32.GetSystemPowerStatus(ctypes.byref(power)):
        raise OSError(ctypes.get_last_error(), "cannot read AC power status")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java.exe") if "JAVA_HOME" in os.environ else "java.exe"
    return {"os": platform.platform(), "windowsBuild": platform.version(),
            "architecture": architecture,
            "cpu": platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", "unknown"),
            "logicalCores": os.cpu_count(), "ramBytes": memory.ullTotalPhys,
            "acPower": {0: False, 1: True}.get(power.ACLineStatus),
            "javaVersion": command_output([java, "-version"]).splitlines()[0],
            "rustVersion": command_output(["rustc.exe", "--version"]),
            "gitCommit": command_output(["git.exe", "rev-parse", "HEAD"])}


def frame(payload: bytes, declared: int | None = None) -> bytes:
    return struct.pack(">I", len(payload) if declared is None else declared) + payload


def generated_case(rng: random.Random, index: int) -> tuple[str, bytes, str, str, str]:
    kind = index % 47
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
    if kind == 17:
        return "close-before-register", frame(b'{"type":"close","reason":"test"}'), "control", "pre", "session-test"
    if kind in (26, 27):
        client_id = "\u00e9" * (64 if kind == 26 else 65)
        message = {"type": "register", "version": 1, "accessToken": "synthetic-test-token",
                   "clientInstanceId": client_id}
        payload = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        name = "unicode-client-id-at-limit" if kind == 26 else "unicode-client-id-over-limit"
        return name, frame(payload), "control", "pre", "session-test"
    if kind in (28, 29, 30, 31, 32, 46):
        message = {"type": "register", "version": 1,
                   "accessToken": "synthetic-test-token", "clientInstanceId": f"case-{sequence}",
                   "features": ["streamEofBytes"]}
        names = {28: "feature-register-valid", 29: "feature-register-duplicate",
                 30: "feature-register-non-array", 31: "feature-register-too-many",
                 32: "feature-register-duplicate-top-level", 46: "feature-register-unknown-field"}
        if kind == 29:
            message["features"] = ["streamEofBytes", "streamEofBytes"]
        elif kind == 30:
            message["features"] = "streamEofBytes"
        elif kind == 31:
            message["features"] = [f"feature-{item}" for item in range(17)]
        elif kind == 32:
            payload = (b'{"type":"register","version":1,"accessToken":"synthetic-test-token",'
                       b'"clientInstanceId":"client","features":[],"features":["streamEofBytes"]}')
            return names[kind], frame(payload), "control", "pre", "session-test"
        elif kind == 46:
            message["futureField"] = {"feature": "ignored"}
        return names[kind], frame(json.dumps(message, separators=(",", ":")).encode()), "control", "pre", "session-test"
    if 33 <= kind <= 45:
        names = {33: "stream-eof-accepted", 34: "stream-eof-pre",
                 35: "stream-eof-unnegotiated", 36: "stream-eof-late",
                 37: "stream-eof-duplicate", 38: "stream-eof-short",
                 39: "stream-eof-negative", 40: "stream-eof-string",
                 41: "stream-eof-overflow", 42: "stream-eof-duplicate-bytes",
                 43: "stream-eof-duplicate-id", 44: "stream-eof-max",
                 45: "stream-eof-unknown-field"}
        if kind == 42:
            payload = b'{"type":"streamEof","connectionId":"connection-test","bytes":5,"bytes":6}'
        elif kind == 43:
            payload = (b'{"type":"streamEof","connectionId":"connection-test",'
                       b'"connectionId":"other","bytes":5}')
        else:
            message = {"type": "streamEof", "connectionId": "connection-test", "bytes": sequence}
            if kind == 36:
                message["connectionId"] = "other-connection"
            elif kind == 38:
                message["bytes"] = 4
            elif kind == 39:
                message["bytes"] = -1
            elif kind == 40:
                message["bytes"] = "5"
            elif kind == 41:
                message["bytes"] = 1 << 63
            elif kind == 44:
                message["bytes"] = (1 << 63) - 1
            elif kind == 45:
                message["futureField"] = {"ignored": True}
            payload = json.dumps(message, separators=(",", ":")).encode()
        return names[kind], frame(payload), "control", "pre" if kind == 34 else "active", "session-test"
    connection = {"version": 1, "sessionId": "session-test", "connectionId": f"connection-{sequence}",
                  "remoteAddress": "127.0.0.1:25565"}
    if kind == 18:
        connection["version"] = 2
        name = "connection-wrong-version"
    elif kind == 19:
        connection["sessionId"] = "other-session"
        name = "connection-wrong-session"
    elif kind == 20:
        connection["connectionId"] = ""
        name = "connection-blank-id"
    elif kind == 21:
        connection["remoteAddress"] = "host:not-a-port"
        name = "connection-bad-remote"
    elif kind == 22:
        payload = json.dumps(connection, separators=(",", ":")).encode()
        return "connection-truncated", frame(payload[:-rng.randrange(1, 8)], len(payload)), "connection", "active", "session-test"
    elif kind == 23:
        payload = (b'{"version":1,"sessionId":"session-test","sessionId":"other",'
                   b'"connectionId":"c","remoteAddress":"127.0.0.1:1"}')
        return "connection-duplicate-session", frame(payload), "connection", "active", "session-test"
    elif kind == 24:
        connection["futureField"] = {"safe": [sequence]}
        name = "connection-unknown-field"
    else:
        payload = (b'{"version":1,"sessionId":"session-test","connectionId":"c",'
                   b'"remoteAddress":"127.0.0.1:1","futureField":"\xff"}')
        return "connection-invalid-utf8", frame(payload), "connection", "active", "session-test"
    return name, frame(json.dumps(connection, separators=(",", ":")).encode()), "connection", "active", "session-test"


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


def numeric_boundary_cases() -> list[tuple[str, bytes, str, str, str]]:
    vectors = ROOT / "protocol" / "test-vectors" / "structural-v1.json"
    document = json.loads(vectors.read_text(encoding="utf-8"))
    if document["schemaVersion"] != 1:
        raise ValueError("unsupported structural boundary vector schema")
    cases = []
    for case in document["cases"]:
        if not case["name"].startswith("number-"):
            continue
        framed = bytes.fromhex(case["frameHex"])
        if (len(framed) < 4 or len(framed) - 4 > MAX_FRAME
                or int.from_bytes(framed[:4], "big") != len(framed) - 4):
            raise ValueError(f"invalid shared numeric frame: {case['name']}")
        cases.append((case["name"], framed, case["target"], "active", "session-test"))
    return cases


def typed_boundary_cases() -> list[tuple[str, bytes, str, str, str]]:
    vectors = ROOT / "protocol" / "test-vectors" / "typed-boundaries-v1.json"
    document = json.loads(vectors.read_text(encoding="utf-8"))
    if document["schemaVersion"] != 1:
        raise ValueError("unsupported typed boundary vector schema")
    cases = []
    for case in document["cases"]:
        payload = case["payloadUtf8"].encode("utf-8")
        if len(payload) > MAX_FRAME:
            raise ValueError(f"typed boundary exceeds 64 KiB: {case['name']}")
        cases.append((case["name"], frame(payload), case["target"],
                      case["phase"], case["expectedSession"]))
    return cases


STATE_KINDS = {"valid-ping", "valid-register", "invalid-auth", "unsupported-version",
               "register-active", "empty-client-id", "ping-before-register",
               "close-before-register", "unicode-client-id-at-limit",
               "unicode-client-id-over-limit"} | {
                   "feature-register-valid", "feature-register-duplicate", "feature-register-non-array",
                   "feature-register-too-many", "feature-register-duplicate-top-level",
                   "feature-register-unknown-field",
               } | {f"stream-eof-{suffix}" for suffix in (
                   "accepted", "pre", "unnegotiated", "late", "duplicate", "short",
                   "negative", "string", "overflow", "duplicate-bytes", "duplicate-id",
                   "max", "unknown-field")}


def notice_model(kind: str) -> dict:
    if not kind.startswith("stream-eof-"):
        return {}
    return {"featureNegotiated": kind != "stream-eof-unnegotiated",
            "activeConnectionId": "connection-test",
            "noticeAlreadySent": kind == "stream-eof-duplicate",
            "copiedBytes": 5 if kind == "stream-eof-short" else 0}


def write_corpus(path: Path, seed: int, count: int, target_group: str = "all") -> list[str]:
    if target_group not in ("all", "framing", "control", "connection", "state"):
        raise ValueError(f"unknown protocol target group: {target_group}")
    if count < 1:
        raise ValueError("corpus count must be positive")
    rng = random.Random(seed)
    kinds = []
    edges = boundary_cases() if target_group in ("all", "framing") else []
    if target_group in ("all", "framing", "control", "state"):
        edges += numeric_boundary_cases()
    edges += [case for case in typed_boundary_cases()
              if target_group in ("all", "framing")
              or target_group == case[2]
              or target_group == "state" and case[2] == "control"]
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
                                     "expectedSession": expected_session, "frameHex": payload.hex(),
                                     **notice_model(kind)},
                                    separators=(",", ":")) + "\n")
    return kinds


class EvaluatorFailure(RuntimeError):
    """A failed evaluator with a bounded diagnostic retained beside the corpus."""

    def __init__(self, stage: str, detail: str, diagnostic_file: str):
        super().__init__(f"{stage} evaluator {detail}; see {diagnostic_file}")
        self.stage = stage
        self.diagnostic_file = diagnostic_file


def run(command: list[str], environment: dict[str, str], stage: str,
        output: Path, timeout_seconds: float = 600) -> float:
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=ROOT, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output_tail = bytearray()

    def drain_output() -> None:
        assert process.stdout is not None
        try:
            while chunk := process.stdout.read1(8192):
                output_tail.extend(chunk)
                if len(output_tail) > 4096:
                    del output_tail[:-4096]
        except (OSError, ValueError):
            # A timed-out child may leave an inherited pipe open in a grandchild.
            pass

    reader = threading.Thread(target=drain_output, daemon=True)
    reader.start()
    try:
        process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        detail = f"exceeded {timeout_seconds:g}-second timeout"
    else:
        detail = f"exited with status {process.returncode}" if process.returncode else ""
    reader.join(timeout=10)
    assert process.stdout is not None
    if reader.is_alive():
        detail = "output pipe did not close"
    else:
        process.stdout.close()
    if detail:
        diagnostic_file = f"{stage}-evaluator-tail.txt"
        (output / diagnostic_file).write_bytes(bytes(output_tail))
        raise EvaluatorFailure(stage, detail, diagnostic_file)
    return time.monotonic() - started


def read_results(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle]


def prepare_output_directory(path: Path) -> None:
    if path.exists() and any(path.iterdir()):
        raise FileExistsError(f"campaign output is not empty; choose a fresh directory: {path}")
    path.mkdir(parents=True, exist_ok=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_first_failures(corpus_path: Path, output_path: Path, case_ids: set[int]) -> None:
    with corpus_path.open(encoding="utf-8") as source, output_path.open("w", encoding="utf-8", newline="\n") as output:
        for line in source:
            if json.loads(line)["id"] in case_ids:
                output.write(line)


def compare_results(rust: list[dict], java: list[dict], kinds: list[str]) -> dict:
    if len(rust) != len(kinds) or len(java) != len(kinds):
        raise ValueError("evaluator output count does not match corpus count")
    first_mismatches = []
    mismatch_count = 0
    framing_mismatches = 0
    typed_mismatches = 0
    counts = {}
    for index, (left, right) in enumerate(zip(rust, java)):
        if left.get("id") != index or right.get("id") != index:
            raise ValueError(f"evaluator output ID is missing or out of order at case {index}")
        kind = kinds[index]
        counts[kind] = counts.get(kind, 0) + 1
        framing_differs = (left["accepted"], left["semantic"]) != (right["accepted"], right["semantic"])
        typed_differs = (left["typedAccepted"], left["typedSemantic"], left["stateOutcome"]) != (
            right["typedAccepted"], right["typedSemantic"], right["stateOutcome"])
        framing_mismatches += int(framing_differs)
        typed_mismatches += int(typed_differs)
        if framing_differs or typed_differs:
            mismatch_count += 1
            if len(first_mismatches) < 50:
                first_mismatches.append({"id": index, "kind": kind,
                                         "rustAccepted": left["accepted"], "javaAccepted": right["accepted"],
                                         "rustTypedAccepted": left["typedAccepted"],
                                         "javaTypedAccepted": right["typedAccepted"],
                                         "rustStateOutcome": left["stateOutcome"],
                                         "javaStateOutcome": right["stateOutcome"],
                                         "semanticEqual": left["semantic"] == right["semantic"],
                                         "typedSemanticEqual": left["typedSemantic"] == right["typedSemantic"]})
    return {"caseCounts": counts, "mismatchCount": mismatch_count,
            "framingMismatchCount": framing_mismatches,
            "typedMismatchCount": typed_mismatches, "mismatches": first_mismatches}


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
    metadata = machine_metadata()
    prepare_output_directory(output)
    corpus = output / "corpus.jsonl"
    kinds = write_corpus(corpus, args.seed, args.count)
    rust_output = output / "rust-results.jsonl"
    java_output = output / "java-results.jsonl"
    environment = os.environ.copy()
    environment["BTA_PROTOCOL_CORPUS"] = str(corpus)
    environment["BTA_PROTOCOL_RUST_OUTPUT"] = str(rust_output)
    timings = {}
    cargo = "cargo.exe" if sys.platform == "win32" else "cargo"
    rust_command = [cargo, "test", "--locked", "--release", "--package",
                    "bta-anywhere-relay", "--test", "protocol_corpus",
                    "shared_corpus", "--", "--exact", "--nocapture"]
    gradle = str(ROOT / "gradlew.bat")
    java_command = [gradle, "--no-daemon", ":tunnel-client:protocolCorpus",
                    f"-PprotocolCorpus={corpus}", f"-PprotocolCorpusOutput={java_output}"]
    try:
        timings["rustSeconds"] = run(rust_command, environment, "rust", output)
        timings["javaSeconds"] = run(java_command, environment, "java", output)
    except (EvaluatorFailure, OSError) as failure:
        (output / "summary.json").write_text(json.dumps({"schemaVersion": 1, "seed": args.seed,
            "cases": args.count, "status": "evaluator_failed", "error": str(failure)[:1000],
            "diagnosticFile": failure.diagnostic_file if isinstance(failure, EvaluatorFailure) else None,
            "machine": metadata, "commands": {"rust": rust_command, "java": java_command}}, indent=2)
            + "\n", encoding="utf-8")
        raise
    try:
        rust = read_results(rust_output)
        java = read_results(java_output)
        comparison = compare_results(rust, java, kinds)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        (output / "summary.json").write_text(json.dumps({"schemaVersion": 1, "seed": args.seed,
            "cases": args.count, "status": "failed_comparison", "error": str(failure)[:1000],
            "machine": metadata, "commands": {"rust": rust_command, "java": java_command},
            "corpusSha256": sha256_file(corpus)}, indent=2) + "\n", encoding="utf-8")
        raise
    if comparison["mismatchCount"]:
        write_first_failures(corpus, output / "first-failures.jsonl",
                             {item["id"] for item in comparison["mismatches"]})
    summary = {"schemaVersion": 1, "seed": args.seed, "cases": args.count,
               "status": "failed_conformance" if comparison["mismatchCount"] else "complete",
               **comparison, "timings": timings,
               "pythonVersion": sys.version.split()[0], "machine": metadata,
               "commands": {"rust": rust_command, "java": java_command},
               "corpusSha256": sha256_file(corpus),
               "firstFailureCorpus": "first-failures.jsonl" if comparison["mismatchCount"] else None,
               "comparison": "framing and JSON-object semantics; typed client control or connection-open semantics; modelled pre-registration/active authentication and state decisions (no relay process)"}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"cases": args.count, "mismatches": comparison["mismatchCount"], "output": str(output)}))
    return 1 if comparison["mismatchCount"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
