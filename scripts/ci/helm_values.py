#!/usr/bin/env python3
"""Create secret-free Helm release values from an already published digest manifest."""
import argparse
import json
from pathlib import Path
from release import validate_release

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.release.read_text())
    validate_release(data)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"images": data["images"], "revision": data["revision"]}, indent=2) + "\n")

if __name__ == "__main__":
    main()
