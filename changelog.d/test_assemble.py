"""Tests of assemble.py: python3 -m unittest discover -s changelog.d"""
import pathlib
import subprocess
import tempfile
import unittest

import assemble

CHANGELOG = f"""# Changelog

Intro.

{assemble.UNRELEASED}

{assemble.POINTER}

### Breaking

- legacy break

### Added

- legacy add one
  continued

- legacy add two

## 0.6.2 (2026-10-15)

### Added

- old
"""


def make_root(fragments, changelog=CHANGELOG):
    root = pathlib.Path(tempfile.mkdtemp())
    (root / "changelog.d").mkdir()
    (root / "CHANGELOG.md").write_text(changelog)
    (root / "changelog.d" / "README.md").write_text("# not a fragment\n")
    for name, text in fragments.items():
        (root / "changelog.d" / name).write_text(text)
    return root


class AssembleSpec(unittest.TestCase):
    def test_release_folds_fragments_in_after_the_legacy_entries_in_section_order(self):
        root = make_root(
            {
                "b-second.md": "### Fixed\n\n- fixed b\n\n### Breaking\n\n- break b\n",
                "a-first.md": "### added\n\n- add a\n  with a second line\n",
            }
        )
        self.assertEqual(assemble.release(root, "0.7.0", "2026-11-01"), 2)
        text = (root / "CHANGELOG.md").read_text()
        expected = f"""# Changelog

Intro.

{assemble.UNRELEASED}

{assemble.POINTER}


## 0.7.0 (2026-11-01)

### Breaking

- legacy break
- break b

### Added

- legacy add one
  continued

- legacy add two
- add a
  with a second line

### Fixed

- fixed b

## 0.6.2 (2026-10-15)

### Added

- old
"""
        self.assertEqual(text, expected)
        self.assertEqual([p.name for p in (root / "changelog.d").iterdir()], ["README.md"])
        self.assertEqual(assemble.check(root), [])

    def test_a_malformed_fragment_is_named_and_stops_the_release(self):
        root = make_root(
            {
                "unknown.md": "### Features\n\n- x\n",
                "prose.md": "Some text\n\n### Added\n\n- x\n",
                "stray.md": "### Added\n\n- x\n\nA paragraph in column 0.\n",
                "empty-section.md": "### Added\n",
                "empty-file.md": "\n",
            }
        )
        errors = "\n".join(assemble.check(root))
        for name in ["unknown.md", "prose.md", "stray.md", "empty-section.md", "empty-file.md"]:
            self.assertIn(name, errors)
        with self.assertRaises(SystemExit):
            assemble.release(root, "0.7.0", "2026-11-01")
        self.assertIn("## Unreleased", (root / "CHANGELOG.md").read_text())
        self.assertEqual(len(list((root / "changelog.d").glob("*.md"))), 6)

    def test_release_without_fragments_renames_the_unreleased_section(self):
        root = make_root({})
        self.assertEqual(assemble.release(root, "0.7.0", "2026-11-01"), 0)
        self.assertIn("## 0.7.0 (2026-11-01)\n\n### Breaking\n\n- legacy break\n", (root / "CHANGELOG.md").read_text())

    def test_release_refuses_a_version_that_has_a_section(self):
        with self.assertRaises(SystemExit):
            assemble.release(make_root({}), "0.6.2", "2026-11-01")

    def test_check_needs_one_unreleased_heading(self):
        self.assertEqual(len(assemble.check(make_root({}, "# Changelog\n\n## 0.6.2\n"))), 1)

    def test_check_against_a_base_rejects_a_new_entry_but_not_a_rewording(self):
        root = make_root({})
        git = lambda *args: subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=root, check=True, capture_output=True)
        git("init", "-q")
        git("add", "-A")
        git("commit", "-qm", "base")
        changelog = root / "CHANGELOG.md"
        changelog.write_text(CHANGELOG.replace("legacy add two", "legacy add two, reworded"))
        self.assertEqual(assemble.check(root, "HEAD"), [])
        changelog.write_text(CHANGELOG.replace("- legacy add two", "- legacy add two\n- a new entry"))
        self.assertEqual(len(assemble.check(root, "HEAD")), 1)


if __name__ == "__main__":
    unittest.main()
