"""Read the USB-copied Malody databases without modifying them."""

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "python-libs"))
from unqlite import UnQLite


def inspect(path):
    original_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    # UNQLITE_OPEN_READONLY = 0x00000001.
    db = UnQLite(str(path), flags=1)
    count = 0
    samples = []
    try:
        with db.cursor() as cursor:
            for key, value in cursor:
                count += 1
                if len(samples) < 3:
                    samples.append({"key": key, "value_bytes": len(value)})
    finally:
        db.close()
    if hashlib.sha256(path.read_bytes()).hexdigest() != original_hash:
        raise RuntimeError(f"Database changed during inspection: {path.name}")
    return {
        "file": path.name,
        "format": "UnQLite",
        "bytes": path.stat().st_size,
        "sha256": original_hash,
        "record_count": count,
        "samples": samples,
    }


if __name__ == "__main__":
    results = [inspect(ROOT / "device-inspection" / name)
               for name in ("score.bin", "chart.bin", "song.bin")]
    print(json.dumps(results, ensure_ascii=True, indent=2))
