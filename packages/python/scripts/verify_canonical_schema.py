"""Fail if the packaged schema drifts from the monorepo canonical schema."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PACKAGED = Path(__file__).resolve().parents[1] / "dt3_sdk" / "log-event.schema.json"
CANONICAL = ROOT / "schemas" / "log-event.schema.json"


def _normalize(path: Path) -> str:
    return json.dumps(json.loads(path.read_text(encoding="utf-8")), sort_keys=True)


def main() -> int:
    if not CANONICAL.is_file():
        print(f"Canonical schema missing: {CANONICAL}", file=sys.stderr)
        return 1
    if not PACKAGED.is_file():
        print(f"Packaged schema missing: {PACKAGED}", file=sys.stderr)
        return 1
    if _normalize(CANONICAL) != _normalize(PACKAGED):
        print(
            "Python packaged log-event schema differs from schemas/log-event.schema.json. "
            "Copy the canonical schema into packages/python/dt3_sdk/ before releasing.",
            file=sys.stderr,
        )
        return 1
    print("Canonical Python schema verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
