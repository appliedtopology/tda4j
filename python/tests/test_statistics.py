
import numpy as np
import pytest

import tda4j


def test_the_euler_curve_of_the_square_counts_vertices_edges_and_triangles_by_hand(square):
    curve = tda4j.euler_curve(square, max_simplex_dimension=2)
    assert curve(0.5) == 4  # four points
    assert curve(1.0) == 0  # four sides: 4 - 4
    assert curve(1.5) == 2  # the diagonals and all four triangles: 4 - 6 + 4
    assert curve.sample(0.5, 1.5, 3).tolist() == [4, 0, 2]
    assert curve.values[0] == 4 and curve.scales[0] == 0.0
    distances = np.linalg.norm(square[:, None] - square[None, :], axis=2)
    assert tda4j.euler_curve_from_distances(distances, max_simplex_dimension=2)(1.5) == 2


def test_the_bootstrap_band_is_repeatable_and_a_clear_loop_stands_out(circle):
    points = circle(40) + np.random.default_rng(5).normal(scale=0.02, size=(40, 2))
    band = tda4j.bootstrap_band(points, rounds=20, seed=7)
    again = tda4j.bootstrap_band(points, rounds=20, seed=7)
    assert band.threshold == again.threshold and np.array_equal(band.distances, again.distances)
    assert band.alpha == 0.05 and len(band.distances) == 20
    assert band.threshold > 0
    bars = tda4j.persistence(points, max_dimension=1).bars
    loop = bars[bars[:, 0] == 1]
    assert loop[:, 2].max() - loop[:, 1].max() > band.threshold


def test_tomato_finds_two_blobs_and_the_diagram_has_one_long_valley():
    rng = np.random.default_rng(0)
    blobs = np.vstack([rng.normal(0.0, 0.15, (40, 2)), rng.normal(5.0, 0.15, (40, 2))])
    labels = tda4j.tomato_clusters(blobs, 8, 0.5)
    assert labels.shape == (80,)
    assert len(set(labels[:40].tolist())) == 1 and len(set(labels[40:].tolist())) == 1
    assert labels[0] != labels[-1]
    assert tda4j.tomato_diagram(blobs, 8).shape[1] == 2


def test_tomato_merges_every_peak_of_one_blob_for_an_enormous_tau_and_none_for_zero():
    blob = np.random.default_rng(1).normal(0.0, 1.0, (200, 2))
    assert tda4j.tomato_clusters(blob, 6, 1e9).max() == 0
    assert tda4j.tomato_clusters(blob, 6, 0.0).max() > 0


def test_circular_coordinates_of_a_circle_are_evenly_spaced_angles(circle):
    points = circle(30)
    loops = tda4j.h1_bars(points)
    assert loops.shape[1] == 2 and len(loops) >= 1
    birth, death = loops[0]
    result = tda4j.circular_coordinates(points, (birth + death) / 2)
    assert result.theta.shape == (30,)
    angles = np.sort(result.theta)
    steps = np.diff(np.append(angles, angles[0] + 1.0))
    assert np.allclose(steps, 1 / 30, atol=1e-6)
    assert result.birth == pytest.approx(birth) and result.death == pytest.approx(death)
    assert result.prime == 47


def test_a_prime_without_an_integer_lift_is_reported_as_the_library_error_that_says_so(circle):
    points = circle(30)
    birth, death = tda4j.h1_bars(points)[0]
    with pytest.raises(ValueError, match="odd prime"):
        tda4j.circular_coordinates(points, (birth + death) / 2, prime=2)


def test_toroidal_coordinates_of_a_torus_have_one_row_per_loop():
    u = np.linspace(0, 2 * np.pi, 12, endpoint=False)
    a, b = np.meshgrid(u, u)
    torus = np.column_stack([np.cos(a).ravel(), np.sin(a).ravel(), np.cos(b).ravel(), np.sin(b).ravel()])
    torus = torus + np.random.default_rng(4).normal(scale=1e-3, size=torus.shape)  # a grid has ties the library refuses
    loops = tda4j.h1_bars(torus)
    r = float(np.median(loops[:2].mean(axis=1)))
    result = tda4j.toroidal_coordinates(torus, r, [0, 1])
    assert result.theta.shape == (2, len(torus))
    assert result.basis_change.shape == (2, 2)
    assert abs(round(np.linalg.det(result.basis_change))) == 1  # a change of basis of the lattice
    assert result.reduced_gram.shape == (2, 2)
    assert result.cocycle_indices.tolist() == [0, 1]


def test_coordinates_draw_themselves(circle):
    points = circle(30)
    birth, death = tda4j.h1_bars(points)[0]
    assert "<svg" in tda4j.circular_coordinates(points, (birth + death) / 2).plot().svg()
