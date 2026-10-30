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
