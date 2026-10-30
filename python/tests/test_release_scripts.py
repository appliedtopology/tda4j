import importlib.util
from pathlib import Path

import pytest

script = Path(__file__).parent.parent / "scripts" / "set_version.py"
if not script.exists():  # the tests run from outside the checkout against the installed wheel
    pytest.skip("the release scripts are not in the installed package", allow_module_level=True)

spec = importlib.util.spec_from_file_location("set_version", script)
set_version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(set_version)


@pytest.mark.parametrize(
    "tag, expected",
    [
        ("0.7.0", "0.7.0"),
        ("0.7.0-RC1", "0.7.0rc1"),
        ("0.7.0-rc.2", "0.7.0rc2"),
        ("0.7.0-alpha", "0.7.0a0"),
        ("0.7.0-alpha2", "0.7.0a2"),
        ("0.7.0-beta3", "0.7.0b3"),
        ("0.7.0-SNAPSHOT", "0.7.0.dev0"),
        ("0.7.0-preview", "0.7.0.dev0"),
    ],
)
def test_a_release_version_becomes_the_pep_440_version(tag, expected):
    assert set_version.pep440(tag) == expected


def test_something_that_is_not_a_version_is_refused():
    with pytest.raises(ValueError):
        set_version.pep440("v0.7")
