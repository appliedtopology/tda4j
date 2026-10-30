import numpy as np
import pytest

import tda4j

GRID = np.array([[i // 4, i % 4] for i in range(16)], dtype=float)


def rounded(bars):
    rows = [tuple(round(x, 9) if np.isfinite(x) else x for x in row) for row in np.asarray(bars)]
    return sorted(rows)


def test_a_lattice_loses_most_of_its_cells_and_keeps_its_bars_for_both_strategies():
    whole = tda4j.persistence(GRID, max_dimension=2, min_persistence=0.0).all_bars
    for strategy in ("reduction", "coreduction"):
        reduced = tda4j.morse_reduction(GRID, max_dimension=2, strategy=strategy)
        assert reduced.critical_counts.sum() < reduced.cell_counts.sum() / 2
        assert len(reduced.critical_cells) == reduced.critical_counts.sum()
        assert reduced.cell_counts.sum() - reduced.critical_counts.sum() == 2 * reduced.pair_count
        assert rounded(reduced.bars) == rounded(whole)


def test_every_critical_cell_is_a_tuple_of_vertices_of_the_dimension_it_is_counted_in():
    reduced = tda4j.morse_reduction(GRID, max_dimension=1)
    by_dimension = np.zeros_like(reduced.critical_counts)
    for cell in reduced.critical_cells:
        by_dimension[len(cell) - 1] += 1
    assert (by_dimension == reduced.critical_counts).all()


def test_a_matrix_of_distances_gives_the_same_counts_and_bad_arguments_are_value_errors():
    distances = np.linalg.norm(GRID[:, None, :] - GRID[None, :, :], axis=2)
    by_points = tda4j.morse_reduction(GRID)
    by_distances = tda4j.morse_reduction_from_distances(distances)
    assert (by_points.critical_counts == by_distances.critical_counts).all()
    with pytest.raises(ValueError):
        tda4j.morse_reduction(GRID, strategy="greedy")
    with pytest.raises(ValueError):
        tda4j.morse_reduction(GRID, dimension=2)
