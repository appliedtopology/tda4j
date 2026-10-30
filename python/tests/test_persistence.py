import math

import numpy as np
import pytest

import tda4j

SQRT2 = math.sqrt(2.0)


def test_the_unit_square_has_one_loop_born_at_a_side_and_dead_at_the_diagonal(square):
    bars = tda4j.persistence(square, max_dimension=1).bars
    assert bars.shape == (5, 3)
    loops = bars[bars[:, 0] == 1]
    assert loops.tolist() == [[1.0, 1.0, SQRT2]]
    essential = bars[np.isinf(bars[:, 2])]
    assert essential.tolist() == [[0.0, 0.0, math.inf]]
    assert (bars[:, 0] == 0).sum() == 4


@pytest.mark.parametrize(
    "make",
    [
        lambda a: a.tolist(),
        lambda a: a.astype(np.float32),
        lambda a: np.asfortranarray(a),
        lambda a: a[:, ::-1][:, ::-1],  # a non-contiguous view of the same numbers
        lambda a: tuple(map(tuple, a)),
    ],
)
def test_the_memory_layout_of_the_input_does_not_change_the_answer(circle, make):
    points = circle(16)
    expected = tda4j.persistence(points).bars
    assert np.allclose(tda4j.persistence(make(points)).bars, expected)


def test_a_rectangular_cloud_is_read_row_by_row_not_column_by_column():
    points = np.array([[0.0, 0.0, 0.0], [3.0, 0.0, 0.0], [0.0, 4.0, 0.0]])
    bars = tda4j.persistence(points, max_dimension=0).bars
    # distances 3, 4 and 5: the components merge at 3, then at 4
    assert sorted(bars[np.isfinite(bars[:, 2]), 2].tolist()) == [3.0, 4.0]


def test_a_distance_matrix_gives_the_bars_of_its_points(circle):
    points = circle(14)
    distances = np.linalg.norm(points[:, None, :] - points[None, :, :], axis=2)
    assert np.allclose(
        tda4j.persistence_from_distances(distances).bars, tda4j.persistence(points).bars
    )


def test_options_are_snake_case_booleans_and_numpy_numbers(square):
    plain = tda4j.persistence(square, max_dimension=1)
    with_zero = tda4j.persistence(square, max_dimension=1, include_zero_length=True)
    assert len(with_zero.all_bars) > len(plain.all_bars)
    assert (with_zero.all_bars[:, 1] == with_zero.all_bars[:, 2]).any()
    assert not (plain.all_bars[:, 1] == plain.all_bars[:, 2]).any()
    capped = tda4j.persistence(square, max_dimension=1, max_filtration_value=np.float64(1.2))
    loops = capped.bars[capped.bars[:, 0] == 1]
    assert loops.tolist() == [[1.0, 1.0, math.inf]]  # not closed before 1.2
    assert len(tda4j.persistence(square, max_dimension=np.int64(0), engine=None)) == len(
        tda4j.persistence(square, max_dimension=0)
    )


def test_a_mistyped_option_is_a_value_error_that_lists_the_right_ones(square):
    with pytest.raises(ValueError, match="unrecognized option 'maxDimenson'.*maxdimension"):
        tda4j.persistence(square, max_dimenson=1)


def test_one_dimensional_data_must_be_a_column_and_the_message_says_so():
    with pytest.raises(ValueError, match=r"reshape\(-1, 1\)"):
        tda4j.persistence(np.array([0.0, 1.0, 3.0]))
    bars = tda4j.persistence(np.array([0.0, 1.0, 3.0]).reshape(-1, 1), max_dimension=0).bars
    assert sorted(bars[np.isfinite(bars[:, 2]), 2].tolist()) == [1.0, 2.0]


def test_the_alpha_complex_and_the_witness_complex_are_reachable(circle):
    points = circle(20)
    alpha = tda4j.persistence(points, complex="alpha", max_dimension=1).bars
    assert (alpha[:, 0] == 1).sum() == 1
    witness = tda4j.persistence(points, complex="witness", num_landmarks=8, max_dimension=1).bars
    assert (witness[:, 0] == 1).sum() >= 1


def test_the_reals_and_the_rationals_agree_with_a_large_prime_on_generic_data(circle):
    rng = np.random.default_rng(3)
    points = circle(12) + rng.normal(scale=0.01, size=(12, 2))
    def canonical(bars):
        return bars[np.lexsort(bars.T[::-1])]  # the order of rows is not part of the answer

    reference = canonical(tda4j.persistence(points, prime=17).bars)
    for field in ("R", "Q"):
        assert np.allclose(canonical(tda4j.persistence(points, field=field).bars), reference), field


def test_hidden_bars_are_counted_and_all_bars_has_them(circle):
    rng = np.random.default_rng(1)
    result = tda4j.persistence(circle(40) + rng.normal(scale=0.02, size=(40, 2)))
    assert result.hidden_count == len(result.all_bars) - len(result.bars)
    assert result.hidden_count > 0
    assert result.persistence_threshold > 0


def test_diagram_is_the_birth_death_pairs_of_one_dimension(square):
    result = tda4j.persistence(square, max_dimension=1)
    assert result.diagram(1).tolist() == [[1.0, SQRT2]]
    assert result.diagram(0).shape == (4, 2)
    assert result.diagram(2).shape == (0, 2)
