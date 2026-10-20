"""Inspect Malody V score records and replay metadata; field names are unverified."""

import hashlib
import json
import struct
import sys
from collections import Counter
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "python-libs"))
from unqlite import UnQLite


def varint(data, pos):
    value = 0
    for shift in range(0, 70, 7):
        if pos >= len(data):
            raise ValueError("Truncated varint")
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
    raise ValueError("Invalid varint")


def fields(data):
    pos = 0
    while pos < len(data):
        tag, pos = varint(data, pos)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("Invalid field number")
        if wire == 0:
            value, pos = varint(data, pos)
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            if pos + size > len(data):
                raise ValueError("Truncated fixed field")
            value = struct.unpack_from("<d" if wire == 1 else "<f", data, pos)[0]
            pos += size
        elif wire == 2:
            size, pos = varint(data, pos)
            if pos + size > len(data):
                raise ValueError("Truncated byte field")
            value = data[pos:pos + size]
            pos += size
        else:
            raise ValueError(f"Unsupported wire type {wire}")
        yield number, wire, value


def printable(wire, value):
    if wire != 2:
        return value
    try:
        return value.decode("utf-8")
    except UnicodeError:
        return {"hex": value.hex()}


def database_records(path):
    before = hashlib.sha256(path.read_bytes()).hexdigest()
    db = UnQLite(str(path), flags=1)
    try:
        with db.cursor() as cursor:
            records = [(key, bytes(value)) for key, value in cursor]
    finally:
        db.close()
    assert before == hashlib.sha256(path.read_bytes()).hexdigest()
    return records


def inspect(folder):
    records = database_records(folder / "score.bin")
    result = {"database_entries": len(records), "records": [],
              "warning": "Field numbers are decoded; semantic mappings remain hypotheses."}
    for key, raw in records:
        decoded = list(fields(raw))
        item = {"key": key, "value_bytes": len(raw), "raw_hex": raw.hex(),
                "fields": [{"number": n, "wire": w, "value": printable(w, v)}
                           for n, w, v in decoded]}
        if not key.endswith("_local"):
            values = {n: printable(w, v) for n, w, v in decoded}
            item["candidate_score_unverified"] = values.get(5)
            item["candidate_accuracy_unverified"] = values.get(11)
            timestamp = values.get(12)
            if isinstance(timestamp, int):
                item["field_12_as_china_time"] = datetime.fromtimestamp(
                    timestamp, timezone(timedelta(hours=8))).isoformat()
        else:
            nested = [(n, w, v) for n, w, v in decoded if n == 1 and w == 2]
            item["wraps_existing_record"] = any(
                v == other for _, _, v in nested
                for other_key, other in records if not other_key.endswith("_local"))
        result["records"].append(item)
    replay = folder / "play.mrv"
    if replay.exists():
        decoded = list(fields(replay.read_bytes()))
        result["replay"] = {
            "bytes": replay.stat().st_size,
            "field_counts": dict(Counter(n for n, _, _ in decoded)),
            "text_fields": {str(n): printable(w, v) for n, w, v in decoded
                            if n in (1, 2, 3, 4, 5, 6) and w == 2},
        }
    return result


if __name__ == "__main__":
    folder = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "device-inspection" / "after-play"
    result = inspect(folder)
    output = folder / "decoded-score.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "entries": result["database_entries"],
                      "records": [{"key": r["key"],
                                   "candidate_score": r.get("candidate_score_unverified"),
                                   "candidate_accuracy": r.get("candidate_accuracy_unverified"),
                                   "same_record_wrapper": r.get("wraps_existing_record")}
                                  for r in result["records"]]}, ensure_ascii=True, indent=2))
