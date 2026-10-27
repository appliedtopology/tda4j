#!/usr/bin/env python3
"""The changelog fragments of changelog.d/: checking them, and folding them into CHANGELOG.md at a release.

A pull request that changes what a user sees or calls adds ONE new file, changelog.d/<topic>.md (format: README.md),
never a line of CHANGELOG.md: two pull requests that insert into the same list conflict, and in a merge queue the
second one is dropped. A new file conflicts with nothing.

    python3 changelog.d/assemble.py check                  # every fragment is well-formed; CHANGELOG.md has its Unreleased section
    python3 changelog.d/assemble.py check --base origin/scala   # also: CHANGELOG.md's Unreleased section gained no entry
    python3 changelog.d/assemble.py release 0.7.0          # fold the fragments into CHANGELOG.md as `## 0.7.0 (today)`

`release` appends each fragment's entries, in file name order, to the entries already under `## Unreleased`, writes the
result as the new version's section, deletes the fragments and leaves `## Unreleased` empty again (RELEASE.md). Run
from anywhere; the tests are test_assemble.py (CI's `changelog` job runs both).
"""
import argparse
import datetime
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The sections of a release, in the order it lists them (Breaking first: it says what to write instead).
SECTIONS = ["Breaking", "Added", "Changed", "Fixed", "Build, CI and Documentation"]
UNRELEASED = "## Unreleased"
# Under `## Unreleased` between releases; `release` drops it from the version's section and writes it again below the new one.
POINTER = (
    "Entries since the last release that are not listed below are the files of [`changelog.d/`](changelog.d/), "
    "one per pull request; the release folds them in here."
)
HEADING = re.compile(r"^### (.+?)\s*$")
VERSION = re.compile(r"^\d+\.\d+\.\d+(-.+)?$")


def section_named(heading):
    """The canonical spelling of a `###` heading, or None."""
    return next((s for s in SECTIONS if s.lower() == heading.lower()), None)


def split_sections(lines):
    """(text before the first `###` heading, {section: lines}, [unknown headings]); blank edges stripped."""
    pre, sections, unknown, current = [], {}, [], None
    for line in lines:
        m = HEADING.match(line)
        if m:
            current = section_named(m.group(1))
            if current is None:
                unknown.append(m.group(1))
                current = "?"
            sections.setdefault(current, [])
        elif current is None:
            pre.append(line)
        else:
            sections[current].append(line)
    return strip_blank(pre), {k: strip_blank(v) for k, v in sections.items()}, unknown


def strip_blank(lines):
    lines = list(lines)
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return lines


def parse_fragment(path):
    """({section: lines}, [errors]) of one fragment; an error names the file."""
    name = path.name
    lines = path.read_text(encoding="utf-8").split("\n")
    pre, sections, unknown = split_sections(lines)
    errors = [f"{name}: '### {u}' is not a section; use {', '.join(SECTIONS)}" for u in unknown]
    if pre:
        errors.append(f"{name}: text before the first '### <section>' heading")
    sections.pop("?", None)
    for section, body in sections.items():
        if not body:
            errors.append(f"{name}: section '{section}' is empty")
        for line in body:
            if line.strip() and not line.startswith((" ", "- ")):
                errors.append(f"{name}: in '{section}', a line that starts in column 0 must start an entry with '- ': {line[:60]!r}")
                break
    if not sections and not errors:
        errors.append(f"{name}: no entries (a '### <section>' heading, then '- ' entries)")
    return sections, errors


def fragment_paths(root):
    return sorted(p for p in (root / "changelog.d").glob("*.md") if p.name != "README.md")


def load_fragments(root):
    """([(path, {section: lines})], [errors]) of every fragment, in file name order."""
    fragments, errors = [], []
    for path in fragment_paths(root):
        sections, errs = parse_fragment(path)
        fragments.append((path, sections))
        errors += errs
    return fragments, errors


def unreleased_span(lines):
    """(index of the `## Unreleased` line, index of the next `## ` heading or the end), or None."""
    starts = [i for i, l in enumerate(lines) if l.rstrip() == UNRELEASED]
    if len(starts) != 1:
        return None
    end = next((i for i in range(starts[0] + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    return starts[0], end


def entry_count(text):
    """The entries ('- ' or '* ' lines in column 0) of the Unreleased section of a CHANGELOG.md text."""
    lines = text.split("\n")
    span = unreleased_span(lines)
    return 0 if span is None else sum(1 for l in lines[span[0]:span[1]] if l.startswith(("- ", "* ")))


def check(root, base=None):
    errors = load_fragments(root)[1]
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    if unreleased_span(changelog.split("\n")) is None:
        errors.append(f"CHANGELOG.md has to have exactly one '{UNRELEASED}' heading (the release folds the fragments into it)")
    if base is not None:
        before = subprocess.run(["git", "show", f"{base}:CHANGELOG.md"], cwd=root, capture_output=True, text=True, check=True).stdout
        if entry_count(changelog) > entry_count(before):
            errors.append(
                "CHANGELOG.md: this change adds an entry under Unreleased. Put it in its own file, changelog.d/<topic>.md "
                "(format: changelog.d/README.md): two pull requests that add to the same list conflict in the merge queue"
            )
    return errors


def release(root, version, date):
    """Folds the fragments into CHANGELOG.md as `## <version> (<date>)`, deletes them; returns the number folded."""
    if not VERSION.match(version):
        raise SystemExit(f"'{version}' is not a version (X.Y.Z)")
    errors = check(root)
    if errors:
        raise SystemExit("\n".join(errors))
    path = root / "CHANGELOG.md"
    lines = path.read_text(encoding="utf-8").split("\n")
    if any(l.startswith(f"## {version} ") for l in lines):
        raise SystemExit(f"CHANGELOG.md already has a section for {version}")
    start, end = unreleased_span(lines)
    pre, legacy, unknown = split_sections(lines[start + 1 : end])
    if unknown:
        raise SystemExit(f"CHANGELOG.md, Unreleased: '### {unknown[0]}' is not a section; use {', '.join(SECTIONS)}")
    fragments = load_fragments(root)[0]
    section = [f"## {version} ({date})", ""]
    intro = [l for l in pre if l.strip() != POINTER]
    if intro:
        section += intro + [""]
    for name in SECTIONS:
        entries = legacy.get(name, []) + [l for _, sections in fragments for l in sections.get(name, [])]
        if entries:
            section += [f"### {name}", ""] + entries + [""]
    new = lines[:start] + [UNRELEASED, "", POINTER, "", ""] + section + lines[end:]
    path.write_text("\n".join(new), encoding="utf-8")
    for p, _ in fragments:
        p.unlink()
    return len(fragments)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check", help="validate the fragments and CHANGELOG.md")
    c.add_argument("--base", help="a git ref (origin/scala): fail if CHANGELOG.md's Unreleased section gained an entry since it")
    r = sub.add_parser("release", help="fold the fragments into CHANGELOG.md as a new version's section")
    r.add_argument("version", help="X.Y.Z")
    r.add_argument("--date", default=datetime.date.today().isoformat(), help="YYYY-MM-DD (default: today)")
    args = parser.parse_args()
    if args.command == "check":
        errors = check(ROOT, args.base)
        print("\n".join(errors) if errors else f"changelog.d: {len(fragment_paths(ROOT))} fragments, all well-formed")
        sys.exit(1 if errors else 0)
    n = release(ROOT, args.version, args.date)
    print(f"CHANGELOG.md: {n} fragments folded into {args.version} and deleted. Read `git diff CHANGELOG.md`, then commit.")


if __name__ == "__main__":
    main()
