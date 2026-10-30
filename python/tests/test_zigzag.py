import numpy as np
import pytest

import tda4j

V = [[0], [1], [2]]
PATH = [[0, 1], [1, 2]]


def rows(zigzag):
    return sorted(tuple(row) for row in zigzag.bars.tolist())


def test_a_loop_closed_by_an_edge_and_opened_by_removing_it_has_a_bar_of_length_one():
    simplices = V + PATH + [[0, 2], [0, 2]]
    additions = [True] * 6 + [False]
    zigzag = tda4j.zigzag_from_steps([], simplices, additions)
    assert zigzag.last_position == 7
    assert rows(zigzag) == [(0, 1.0, np.inf), (0, 2.0, 4.0), (0, 3.0, 5.0), (1, 6.0, 7.0)]
    (loop,) = [zigzag.representatives[i] for i, row in enumerate(zigzag.bars) if row[0] == 1]
    assert sorted(loop.cells) == [(0, 1), (0, 2), (1, 2)]
    assert len(loop.coefficients) == 3


def test_the_union_of_two_paths_closes_a_loop_only_between_them():
    first = V + PATH
    second = V + [[1, 2], [0, 2]]
    joined = tda4j.zigzag_from_complexes([first, second], link="union")
    assert [row for row in rows(joined) if row[0] == 1] == [(1, 1.0, 2.0)]
    assert joined.last_position == 2
    with pytest.raises(ValueError):
        tda4j.zigzag_from_complexes([first, second])


def test_a_simplex_before_its_facets_is_a_value_error():
    with pytest.raises(ValueError):
        tda4j.zigzag_from_steps([], [[0, 1]], [True])
    with pytest.raises(ValueError):
        tda4j.zigzag_from_steps([], [[0]], [True, False])
    with pytest.raises(ValueError):
        tda4j.zigzag_from_steps([], [[]], [True])
