#!/usr/bin/env python3
"""Writes the release's version into pyproject.toml, as the PEP 440 version it corresponds to.

    set_version.py 0.7.0          -> 0.7.0
    set_version.py 0.7.0-RC1      -> 0.7.0rc1      (also -rc.1, -rc1)
    set_version.py 0.7.0-alpha2   -> 0.7.0a2       (also -a2); -beta3 -> 0.7.0b3
    set_version.py 0.7.0-SNAPSHOT -> 0.7.0.dev0    (and any other suffix, e.g. -preview)
"""

import re
import sys
from pathlib import Path


def pep440(version: str) -> str:
    match = re.fullmatch(r"(\d+\.\d+\.\d+)(?:-(.+))?", version)
    if not match:
        raise ValueError(f"not a release version: {version!r}")
    base, suffix = match.groups()
    if suffix is None:
        return base
    pre = re.fullmatch(r"(?i)(rc|alpha|a|beta|b)[.-]?(\d*)", suffix)
    if pre:
        kind = {"rc": "rc", "alpha": "a", "a": "a", "beta": "b", "b": "b"}[pre.group(1).lower()]
        return f"{base}{kind}{int(pre.group(2) or 0)}"
    return f"{base}.dev0"


def main(argv: list[str]) -> None:
    if len(argv) != 2:
        sys.exit(__doc__)
    path = Path(__file__).resolve().parent.parent / "pyproject.toml"
    text = path.read_text()
    updated, count = re.subn(r'(?m)^version = ".*"$', f'version = "{pep440(argv[1])}"', text, count=1)
    if count != 1:
        sys.exit("no version line in pyproject.toml")
    path.write_text(updated)
    print(pep440(argv[1]))


if __name__ == "__main__":
    main(sys.argv)
