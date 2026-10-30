import numpy as np
import pytest

import tda4j


def _cloud(n, seed):
    return np.random.default_rng(seed).random((n, 2))


def _canonical(bars):
    """Rows as sorted, rounded tuples: equal sums of the same values compare equal whatever the order of addition."""
    rows = [(int(k), round(float(b), 9), round(float(d), 9)) for k, b, d in bars]
    return sorted(rows)


def test_the_product_of_two_complexes_has_the_bars_of_the_product_metric():
    # The Vietoris-Rips complex of the product with the maximum of the two distances is, at every scale, homotopy
    # equivalent to the product of the two complexes: its barcode is the one the algebra predicts, with nothing shared.
    x, y = _cloud(6, 1), _cloud(5, 51)
    options = dict(max_dimension=2, max_filtration_value=100.0, min_persistence=0.0)
    first, second = tda4j.persistence(x, **options), tda4j.persistence(y, **options)
    pairs = [(a, b) for a in range(len(x)) for b in range(len(y))]
    distances = np.array(
        [
            [
                max(np.linalg.norm(x[a] - x[c]), np.linalg.norm(y[b] - y[d]))
                for (c, d) in pairs
            ]
            for (a, b) in pairs
        ]
    )
    found = tda4j.persistence_from_distances(distances, **options)
    predicted = tda4j.product_bars(first, second)
    assert _canonical(found.all_bars) == _canonical(row for row in predicted if row[0] <= 2)


def test_the_sum_of_two_segments_is_a_square_whose_side_cycle_is_filled_a_step_later():
    # two points joined by an edge at 1; the Tor term is the cycle of the four sides, alive from 1 to 2
    segment = np.array([[0, 0, 1.0], [0, 0, np.inf]])
    rows = _canonical(tda4j.product_bars(segment, segment, filtration="sum"))
    assert (1, 1.0, 2.0) in rows
    assert rows.count((0, 0.0, 1.0)) == 3 and (0, 0.0, np.inf) in rows
    assert len(tda4j.product_bars(segment, segment)) == 4


def test_a_bad_rule_or_a_row_that_is_not_a_bar_is_a_value_error():
    segment = np.array([[0, 0, 1.0]])
    with pytest.raises(ValueError, match="max or sum"):
        tda4j.product_bars(segment, segment, filtration="mean")
    with pytest.raises(ValueError):
        tda4j.product_bars(np.array([[0.5, 0.0, 1.0]]), segment)
    with pytest.raises(ValueError):
        tda4j.product_bars(np.array([[0.0, 1.0]]), segment)


def test_hom_of_two_bars_is_the_bar_the_shifts_with_a_map_make_and_the_dual_reflects_it():
    a, b = np.array([[0, 0.0, 3.0]]), np.array([[0, 1.0, 5.0]])
    assert _canonical(tda4j.hom_bars(a, b)) == [(0, 2.0, 5.0)]
    assert tda4j.hom_dimension(a, b) == 0 and tda4j.hom_dimension(b, a) == 1
    dual = tda4j.dual_bars(np.array([[1, 1.0, 4.0], [0, 2.0, np.inf]]))
    assert _canonical(dual) == [(0, -np.inf, -2.0), (1, -4.0, -1.0)]


def test_a_result_stands_for_all_of_its_bars():
    circle = np.array([[np.cos(t), np.sin(t)] for t in np.linspace(0, 2 * np.pi, 12, endpoint=False)])
    result = tda4j.persistence(circle, max_dimension=1)
    assert len(tda4j.hom_bars(result, result)) > 0
    assert len(tda4j.dual_bars(result)) == len(result.all_bars)
