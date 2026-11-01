"""Every ```python fence in the documentation and the READMEs, run as it stands, with the numbers its page quotes checked.

Each fence runs in a fresh namespace in a scratch directory where `_docs` is linked, so the data paths of the pages
resolve and the files a page writes land away from the checkout. The fences of one page run in order and share that
directory (a page that saves a result and reads it back keeps working). `Plot.view` is replaced by a no-op: it opens a
browser. A page with a fence and no entry in ORACLES fails, so a new example has to say what it must print or hold.

The oracles are the figures of the Scala tabs next to each fence (the same specs assert them), or an invariant the
numbers must satisfy, not a re-run of the page's code. Skipped when the documentation is not next to the tests, as in the
job that tests the installed wheel from a copy outside the checkout.
"""

import contextlib
import io
import re
from collections import Counter
from pathlib import Path

import numpy as np
import pytest

import tda4j

ROOT = Path(__file__).resolve().parents[2]
if not (ROOT / "_docs").is_dir():
    pytest.skip("the documentation is not next to the tests", allow_module_level=True)

FENCE = re.compile(r"^```python\n(.*?)^```", re.S | re.M)
PAGES = [*sorted((ROOT / "_docs").rglob("*.md")), ROOT / "README.md", ROOT / "python" / "README.md"]


def name(page: Path) -> str:
    return str(page.relative_to(ROOT / "_docs")) if page.is_relative_to(ROOT / "_docs") else str(page.relative_to(ROOT))


def fences(page: Path) -> list[str]:
    return FENCE.findall(page.read_text(encoding="utf-8"))


def close(actual, expected, places=3):
    """The pages quote three decimals; `inf` must match `inf`."""
    np.testing.assert_allclose(actual, expected, atol=0.5 * 10 ** -places, rtol=0)


def rows(bars, places=3):
    """Bars as a sorted list of rounded rows, for comparing barcodes whatever order the engine reports them in."""
    return sorted(tuple(float(x) for x in row) for row in np.round(bars, places))


def circular_distance(a, b):
    d = np.abs(a - b) % 1.0
    return np.minimum(d, 1.0 - d)


def alignment_error(theta, truth):
    """The pages' measure: the best match to the true angles over both orientations and 200 offsets, in turns."""
    return min(
        circular_distance((sign * theta + offset) % 1.0, truth).mean()
        for sign in (1.0, -1.0)
        for offset in np.arange(200) / 200.0
    )


def turn(y, x):
    return (np.arctan2(y, x) / (2 * np.pi) + 1.0) % 1.0


# -- the oracles, one function per fence ---------------------------------------------------------------------------------

def readme_square(ns, out):
    bars = ns["result"].bars
    assert (bars[:, 0] == 1).sum() == 1 and np.isinf(bars[:, 2]).sum() == 1


def python_page(ns, out):
    result = ns["result"]
    close(result.diagram(1), [[1.0, np.sqrt(2)]])
    assert sorted(result.representative(4).cells) == [(0, 1), (0, 3), (1, 2), (2, 3)]
    assert result.bar_cells(4) == tda4j.BarCells((2, 3), (0, 1, 2))


def all_ways_to_call(ns, out):
    forever = ns["octahedron"].bars
    assert rows(forever[np.isinf(forever[:, 2])]) == [(0.0, 0.0, np.inf), (2.0, 1.0, np.inf)]
    close(ns["result"].bars[ns["result"].bars[:, 0] == 1], [[1, 0.261, 1.732]])
    assert ns["result"].hidden_count == 1
    assert ns["theta"].shape == (25,) and np.all((ns["theta"] >= 0) & (ns["theta"] < 1))
    close(ns["r"], 0.261 + 0.5 * (1.732 - 0.261))


def find_a_loop(ns, out):
    assert ns["bars"].shape == (61, 3) and ns["result"].hidden_count == 3
    close(ns["result"].diagram(1), [[0.595, 1.707]])
    close(ns["longest_gap"], 0.484)
    edges = ns["edges"]  # a cycle: a closed path, so every point on it lies on exactly two of its edges
    assert len(edges) == 18 and set(Counter(v for edge in edges for v in edge).values()) == {2}
    close(ns["short"].diagram(1), [[0.595, np.inf]])


def choosing_a_complex(ns, out):
    expected = {  # complex: (simplices, loop birth, loop death), as in the Scala tab
        "vr": (24711, 0.595, 1.707),
        "cech": (36050, 0.297, 0.935),
        "alpha": (313, 0.297, 0.935),
        "sparse-rips": (1730, 0.708, 1.789),
        "witness": (441, 0.278, 0.742),
    }
    got = {
        m[0]: (int(m[1]), float(m[2]), float(m[3]))
        for m in re.findall(r"(\S+)\s+(\d+) simplices, loop \[([\d.]+), ([\d.]+)\)", out)
    }
    assert got.keys() == expected.keys()
    for key, (cells, birth, death) in expected.items():
        assert got[key][0] == cells
        close(got[key][1:], [birth, death])


def noise_and_outliers(ns, out):
    close(ns["loop_lengths"](ns["vr"])[:2], [0.901, 0.127])
    close(ns["loop_lengths"](ns["dtm"])[:2], [0.815, 0.005])
    assert re.findall(r"(\d+) points, (\d+) of them outliers", out) == [("25", "8"), ("7", "5")]


def scaling_up(ns, out):
    answers = ns["answers"]
    assert [len(a) for a in answers] == [61, 61, 61, 61] and all(a == answers[0] for a in answers)
    assert ns["collapsed"].num_cells == 1547
    assert ns["rounded"](ns["collapsed"]) == answers[0]  # edge collapse keeps the barcode


def networks_and_relations(ns, out):
    seven = [(0.0, 1.0, 2.0)] * 5 + [(0.0, 1.0, np.inf), (1.0, 2.0, 5.0)]
    assert rows(ns["people"].bars) == seven and rows(ns["clubs"].bars) == seven


def comparing_barcodes(ns, out):
    a, b, eight = ns["circle_a"], ns["circle_b"], ns["eight"]
    close([a.bottleneck_distance(b, 1), a.bottleneck_distance(eight, 1), b.bottleneck_distance(eight, 1)], [0.040, 0.556, 0.518])
    close([a.wasserstein_distance(b, 1), a.wasserstein_distance(eight, 1), b.wasserstein_distance(eight, 1)], [0.045, 0.864, 0.830])
    landscape, image = ns["landscape"], ns["image"]
    close(np.linalg.norm(landscape(a) - landscape(b)), 0.279)
    close(np.linalg.norm(landscape(a) - landscape(eight)), 2.430)
    close(np.linalg.norm(image(a) - image(b)), 0.080)
    close(np.linalg.norm(image(a) - image(eight)), 0.163)


def images(ns, out):
    assert rows(ns["dark"].bars) == [(0.0, 0.0, np.inf), (0.0, 0.001, 1.002), (1.0, 0.062, 1.099), (1.0, 0.064, 0.9)]
    assert rows(ns["bright"].bars) == [(0.0, -1.099, np.inf), (0.0, -0.9, -0.063), (1.0, -1.004, -0.001)]
    assert ns["rows"](ns["fast"]) == ns["rows"](ns["dark"])  # the fast engine gives the same bars
    assert len(ns["dark"].all_bars) == 221


def circular_and_toroidal(ns, out):
    close(ns["bars"], [[0.595, 1.707]])
    points, theta = ns["points"], ns["theta"]
    assert theta.shape == (60,) and ns["coordinate"].prime == 47
    close(alignment_error(theta, turn(points[:, 1], points[:, 0])), 0.064)
    torus = ns["torus_points"]
    close(ns["torus_bars"][:4], [[0.680, 1.751], [0.699, 1.755], [0.771, 1.419], [0.771, 1.407]])
    angles = [turn(torus[:, 1], torus[:, 0]), turn(torus[:, 3], torus[:, 2])]
    errors = [min(alignment_error(t, angle) for angle in angles) for t in (ns["theta1"], ns["theta2"])]
    assert ns["coordinates"].theta.shape == (2, 120)
    assert max(errors) < 0.2  # each follows one true angle; unrelated angles give 0.25 (the page quotes 0.131 and 0.148)


def vectorization(ns, out):
    curve = ns["curve"]
    assert curve(0) == 30 and curve(0.5) == 0  # 30 points; then a circle, whose Euler characteristic is 0
    sample = curve.sample(0, 1, 50)
    assert list(sample) == [curve(t) for t in np.linspace(0, 1, 50)]
    assert list(ns["curve"].values[:3]) == [30, 29, 27]


def plotting_tab(ns, out):
    here = Path.cwd()
    assert "<svg" in (here / "loop.svg").read_text(encoding="utf-8")
    assert (here / "circle.json").is_file()
    assert ns["loop"] == int(np.flatnonzero(ns["result"].bars[:, 0] == 1)[0])  # the one loop of the circle
    fresh = tda4j.persistence(ns["points"], max_dimension=1)
    assert np.array_equal(ns["result"].bars, fresh.bars)  # read back from the file: the same bars


def plotting_json(ns, out):
    # the file holds every bar, hidden or not, and an infinite death is read back as inf
    points = np.loadtxt("_docs/tutorials/data/noisy-circle.csv", delimiter=",")
    every_bar = tda4j.persistence(points, max_dimension=1).all_bars
    assert sorted(ns["deaths"]) == sorted(every_bar[:, 2]) and np.isinf(ns["deaths"]).sum() == 1


def plotting_tweaks(ns, out):
    here = Path.cwd()
    diagram = (here / "diagram.svg").read_text(encoding="utf-8")
    assert "A noisy circle" in diagram and 'width="800"' in diagram and 'height="600"' in diagram
    plain = ns["result"].plot_barcode().svg()
    assert (here / "barcode.svg").read_text(encoding="utf-8") != plain  # the scale and the text size changed the picture


ORACLES = {
    "README.md": [readme_square],
    "python/README.md": [readme_square],
    "tutorials/all-ways-to-call.md": [all_ways_to_call],
    "tutorials/find-a-loop.md": [find_a_loop],
    "tutorials/choosing-a-complex.md": [choosing_a_complex],
    "tutorials/noise-and-outliers.md": [noise_and_outliers],
    "tutorials/scaling-up.md": [scaling_up],
    "tutorials/networks-and-relations.md": [networks_and_relations],
    "tutorials/comparing-barcodes.md": [comparing_barcodes],
    "tutorials/images.md": [images],
    "tutorials/circular-and-toroidal-coordinates.md": [circular_and_toroidal],
    "user-guide/python.md": [python_page],
    "user-guide/vectorization.md": [vectorization],
    "user-guide/plotting.md": [plotting_tab, plotting_json, plotting_tweaks],
}


def test_every_python_fence_has_an_oracle():
    found = {name(page): len(fences(page)) for page in PAGES if fences(page)}
    assert found == {page: len(checks) for page, checks in ORACLES.items()}, (
        "a page has Python fences without an oracle in ORACLES (or an oracle for a fence that is gone)"
    )


@pytest.mark.parametrize("page", [p for p in PAGES if fences(p)], ids=name)
def test_the_python_fences_run_and_say_what_they_quote(page, tmp_path, monkeypatch):
    (tmp_path / "_docs").symlink_to(ROOT / "_docs")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(tda4j.Plot, "view", lambda self: self)
    for number, (code, oracle) in enumerate(zip(fences(page), ORACLES[name(page)])):
        namespace, out = {"__name__": "__docs__"}, io.StringIO()
        with contextlib.redirect_stdout(out):
            exec(compile(code, f"{name(page)}, fence {number}", "exec"), namespace)
        oracle(namespace, out.getvalue())
