import numpy as np
import pytest

import tda4j

SQUARE = np.array([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]])


def test_the_squares_loop_is_in_the_kernel_at_its_scale_and_gone_when_the_diagonals_come_in():
    alive = tda4j.persistent_laplacian(SQUARE, 1, 1.0, 1.0)
    gone = tda4j.persistent_laplacian(SQUARE, 1, 1.0, np.sqrt(2.0))
    assert alive.betti_number == 1 and gone.betti_number == 0
    assert alive.matrix.shape == (4, 4) and len(alive.cells) == 4
    assert np.allclose(alive.matrix, alive.matrix.T)
    # the kernel is the loop: the number of eigenvalues that are zero is the persistent Betti number
    assert np.sum(np.abs(alive.eigenvalues) < 1e-9) == 1
    assert alive.spectral_gap is not None and alive.spectral_gap > 0


def test_the_kernel_is_an_orthonormal_basis_the_matrix_sends_to_zero():
    alive = tda4j.persistent_laplacian(SQUARE, 1, 1.0, 1.0)
    gone = tda4j.persistent_laplacian(SQUARE, 1, 1.0, np.sqrt(2.0))
    assert alive.kernel.shape == (1, 4) and gone.kernel.shape == (0, 4)
    assert np.allclose(alive.kernel @ alive.kernel.T, np.eye(1))
    assert np.allclose(alive.matrix @ alive.kernel.T, 0.0, atol=1e-9)
    # the loop of the four sides weighs every side the same
    assert np.allclose(np.abs(alive.kernel), 0.5)


def test_the_eigenvalues_are_those_of_the_matrix_and_the_kernel_counts_zeros():
    laplacian = tda4j.persistent_laplacian(SQUARE, 0, 0.0, 1.0)
    assert np.allclose(np.sort(np.linalg.eigvalsh(laplacian.matrix)), laplacian.eigenvalues, atol=1e-9)
    assert laplacian.betti_number == 1  # the four corners are one component once the sides are there


def test_a_matrix_of_distances_gives_the_same_laplacian():
    distances = np.linalg.norm(SQUARE[:, None, :] - SQUARE[None, :, :], axis=2)
    by_points = tda4j.persistent_laplacian(SQUARE, 1, 1.0, 1.0)
    by_distances = tda4j.persistent_laplacian_from_distances(distances, 1, 1.0, 1.0)
    assert np.allclose(by_points.eigenvalues, by_distances.eigenvalues)


def test_points_far_apart_have_no_spectral_gap_and_bad_arguments_are_value_errors():
    far = np.array([[10.0 * i, 0.0] for i in range(5)])
    result = tda4j.persistent_laplacian(far, 0, 0.0, 1.0)
    assert result.betti_number == 5 and result.spectral_gap is None
    with pytest.raises(ValueError):
        tda4j.persistent_laplacian(SQUARE, 1, 2.0, 1.0)
    with pytest.raises(ValueError):
        tda4j.persistent_laplacian(SQUARE, -1, 0.0, 1.0)


SEGMENT = np.array([[0.0, 0.0], [1.0, 0.0]])


def test_the_laplacian_of_a_map_that_folds_the_square_onto_a_segment_has_one_simplex_of_weight_two():
    # the vertices 0, 1, 2, 3 go to 0, 1, 1, 0: two sides land on the segment, two are squeezed to a point
    folded = tda4j.persistent_laplacian_of_map(SQUARE, SEGMENT, [0, 1, 1, 0], 1, 1.0, 1.0)
    assert folded.cells == [(0, 1)]
    assert np.allclose(folded.weights, [2.0]) and np.allclose(folded.matrix, [[2.0]])
    assert folded.betti_number == 0 and folded.kernel.shape == (0, 1)


def test_the_identity_map_is_the_persistent_laplacian_of_the_points_and_distances_give_the_same():
    identity = list(range(4))
    by_map = tda4j.persistent_laplacian_of_map(SQUARE, SQUARE, identity, 1, 1.0, np.sqrt(2.0))
    plain = tda4j.persistent_laplacian(SQUARE, 1, 1.0, np.sqrt(2.0))
    assert by_map.cells == plain.cells and np.allclose(by_map.matrix, plain.matrix)
    assert np.allclose(by_map.weights, np.ones(4))
    distances = np.linalg.norm(SQUARE[:, None, :] - SQUARE[None, :, :], axis=2)
    by_distances = tda4j.persistent_laplacian_of_map_from_distances(distances, distances, identity, 1, 1.0, 1.0)
    assert by_distances.betti_number == 1  # the loop of the square goes to itself
    assert np.allclose(plain.weights, np.ones(4))


def test_a_map_that_is_not_simplicial_or_has_the_wrong_length_is_a_value_error():
    with pytest.raises(ValueError, match="simplicial"):
        tda4j.persistent_laplacian_of_map(SQUARE, SQUARE, [0, 1, 2, 3], 1, 1.0, 0.5)
    with pytest.raises(ValueError, match="entries"):
        tda4j.persistent_laplacian_of_map(SQUARE, SQUARE, [0, 1], 1, 1.0, 1.0)
