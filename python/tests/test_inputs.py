import math

import numpy as np
import pytest

import tda4j

SQRT2 = math.sqrt(2.0)


# -- images ---------------------------------------------------------------------------------------------------------

def test_an_image_is_read_row_major_and_its_cells_are_in_doubled_coordinates():
    image = np.array([[5.0, 5.0, 5.0, 5.0, 5.0], [5.0, 5.0, 1.0, 5.0, 5.0], [5.0, 5.0, 5.0, 5.0, 5.0]])

    def where_the_lowest_pixel_is_seen(array):
        result = tda4j.persistence_from_image(array, engine="cohomology")
        essential = int(np.flatnonzero(np.isinf(result.bars[:, 2]))[0])
        assert result.bars[essential, 1] == 1.0
        x, y = result.bar_cells(essential).birth  # a vertex, doubled: grid point (x / 2, y / 2)
        assert x % 2 == 0 and y % 2 == 0
        return x // 2, y // 2

    # the lowest pixel is in row 1, column 2, and the class is born at one of its four corners
    assert where_the_lowest_pixel_is_seen(image) in {(1, 2), (1, 3), (2, 2), (2, 3)}
    # the transpose has it in row 2, column 1
    assert where_the_lowest_pixel_is_seen(image.T) in {(2, 1), (2, 2), (3, 1), (3, 2)}
    assert where_the_lowest_pixel_is_seen(np.asfortranarray(image)) in {(1, 2), (1, 3), (2, 2), (2, 3)}


def test_a_ring_of_dark_pixels_around_a_bright_one_is_a_loop_that_fills_in_at_the_bright_value():
    image = np.zeros((5, 5))
    image[2, 2] = 3.0
    bars = tda4j.persistence_from_image(image).bars
    loops = bars[bars[:, 0] == 1]
    assert loops[:, 1:].tolist() == [[0.0, 3.0]]


def test_a_three_dimensional_grid_is_a_cubical_complex_and_nan_is_missing_data():
    grid = np.zeros((3, 3, 3))
    grid[1, 1, 1] = np.nan
    bars = tda4j.persistence_from_image(grid, max_dimension=2).bars
    # a cube with its centre missing is a hollow shell: a sphere, so one class in degree 2
    assert (bars[:, 0] == 2).sum() == 1
    with pytest.raises(ValueError, match="pixel"):
        tda4j.persistence_from_image(grid, nan_mask=False)


def test_an_empty_image_is_refused():
    with pytest.raises(ValueError, match="at least one pixel"):
        tda4j.persistence_from_image(np.zeros((0, 3)))


# -- relations ------------------------------------------------------------------------------------------------------

def test_a_relation_need_not_be_square():
    relation = np.array([[0.0, 1.0, math.inf], [math.inf, 1.0, 0.0]])
    bars = tda4j.persistence_from_relation(relation, engine="cohomology").bars
    assert (bars[:, 0] == 0).sum() >= 1


# -- relative persistence -------------------------------------------------------------------------------------------

def test_modulo_one_point_the_essential_bar_of_degree_zero_is_gone_and_no_cell_lies_in_the_subcomplex(circle):
    points = circle(10)
    absolute = tda4j.persistence(points, max_dimension=1)
    relative = tda4j.relative_persistence(points, [4], max_dimension=1)
    assert np.isinf(absolute.bars[:, 2]).sum() == 1  # the one component
    assert not np.isinf(relative.bars[relative.bars[:, 0] == 0, 2]).any()
    cells = [relative.representative(i).cells for i in range(len(relative))]
    assert all(cell != (4,) for rep in cells for cell in rep)
    assert (relative.bars[:, 0] == 1).sum() == (absolute.bars[:, 0] == 1).sum()


def test_relative_persistence_modulo_everything_is_empty_and_a_bad_vertex_is_a_value_error(circle):
    points = circle(6)
    assert len(tda4j.relative_persistence(points, range(6))) == 0
    with pytest.raises(ValueError, match="out of range"):
        tda4j.relative_persistence(points, [-1])


def test_relative_persistence_from_distances_is_that_of_the_points(circle):
    points = circle(9)
    distances = np.linalg.norm(points[:, None] - points[None, :], axis=2)
    assert np.allclose(
        tda4j.relative_persistence_from_distances(distances, [0, 3]).bars,
        tda4j.relative_persistence(points, [0, 3]).bars,
    )


# -- maps -----------------------------------------------------------------------------------------------------------

def test_the_inclusion_of_all_the_points_is_the_identity_with_nothing_in_kernel_or_cokernel(circle):
    points = circle(10) + np.random.default_rng(0).normal(scale=0.01, size=(10, 2))
    result = tda4j.inclusion_map(points, range(10), max_dimension=1)
    for degree in result.degrees:
        assert sorted(map(tuple, degree.image.tolist())) == sorted(map(tuple, degree.source_bars.tolist()))
        assert degree.kernel.shape == (0, 2) and degree.cokernel.shape == (0, 2)
        matrix = np.zeros((len(degree.target_bars), len(degree.source_bars)))
        for source, target, c in degree.entries:
            matrix[int(target), int(source)] = c
        assert np.all(np.abs(matrix).sum(axis=0) == 1) and np.all(np.abs(matrix).sum(axis=1) == 1)
    assert result.characteristic == 17 and result.max_dimension == 1


def test_a_subsample_sees_the_loop_of_the_circle_for_a_while(circle):
    result = tda4j.inclusion_map(circle(24), [0, 6, 12, 18], max_dimension=1, max_filtration_value=2.5)
    degree = result.degree(1)
    assert degree.source_bars.tolist() == [pytest.approx([SQRT2, 2.0])]
    (target_birth, target_death), = degree.target_bars.tolist()
    assert target_birth < SQRT2 < target_death < 2.0
    assert degree.image.tolist() == [pytest.approx([SQRT2, target_death])]
    assert degree.kernel.tolist() == [pytest.approx([target_death, 2.0])]
    assert degree.cokernel.tolist() == [pytest.approx([target_birth, SQRT2])]
    assert len(degree.entries) == 1 and abs(degree.entries[0, 2]) == 1


def test_a_simplicial_map_and_the_inclusion_agree(circle):
    points = circle(12) + np.random.default_rng(2).normal(scale=0.01, size=(12, 2))
    subsample = [1, 4, 7, 10]
    by_inclusion = tda4j.inclusion_map(points, subsample, max_dimension=1)
    by_map = tda4j.simplicial_map(points[subsample], points, subsample, max_dimension=1)
    for k in (0, 1):
        assert np.array_equal(by_inclusion.degree(k).entries, by_map.degree(k).entries)
        assert np.array_equal(by_inclusion.degree(k).image, by_map.degree(k).image)
    distances = np.linalg.norm(points[:, None] - points[None, :], axis=2)
    by_distances = tda4j.inclusion_map_from_distances(distances, subsample, max_dimension=1)
    assert np.allclose(by_distances.degree(1).image, by_inclusion.degree(1).image)


def test_a_map_that_increases_a_distance_is_refused_and_a_missing_degree_is_an_index_error():
    near = np.array([[0.0, 0.0], [0.1, 0.0]])
    far = np.array([[0.0, 0.0], [5.0, 0.0]])
    with pytest.raises(ValueError, match="not a map of persistence modules"):
        tda4j.simplicial_map(near, far, [0, 1])
    result = tda4j.simplicial_map(far, near, [0, 1], max_dimension=1)
    with pytest.raises(IndexError, match="no degree 2"):
        result.degree(2)


# -- landmarks ------------------------------------------------------------------------------------------------------

def test_landmarks_by_farthest_point_sampling_on_a_line_and_their_covering_radius():
    line = np.arange(11.0).reshape(-1, 1)
    chosen = tda4j.select_landmarks(line, 2)
    assert len(chosen.indices) == 2 and len(set(chosen.indices.tolist())) == 2
    assert chosen.covering_radius == pytest.approx(tda4j.covering_radius(line, chosen.indices))
    # two landmarks at the ends cover a line of length 10 to within 5
    assert tda4j.covering_radius(line, [0, 10]) == 5.0
    assert tda4j.covering_radius(line, [5]) == 5.0
    distances = np.abs(line - line.T)
    assert tda4j.covering_radius_from_distances(distances, [0, 10]) == 5.0
    assert tda4j.select_landmarks_from_distances(distances, 2).covering_radius == chosen.covering_radius


def test_the_witness_complex_on_landmarks_sees_the_circle(circle):
    points = circle(40)
    landmarks = tda4j.select_landmarks(points, 8).indices
    result = tda4j.persistence_from_landmarks(points, landmarks, max_dimension=1)
    assert (result.bars[:, 0] == 1).sum() >= 1
    distances = np.linalg.norm(points[:, None] - points[None, :], axis=2)
    by_distances = tda4j.persistence_from_distances_and_landmarks(distances, landmarks, max_dimension=1)
    assert np.allclose(np.sort(by_distances.bars, axis=0), np.sort(result.bars, axis=0))


# -- time series ----------------------------------------------------------------------------------------------------

def test_takens_windows_use_the_delay_inside_a_window_and_the_stride_between_windows():
    series = np.arange(10.0)
    windows = tda4j.takens_embedding(series, dimension=3, delay=2, stride=3)
    assert windows.tolist() == [[0.0, 2.0, 4.0], [3.0, 5.0, 7.0]]
    assert tda4j.takens_embedding(series, 2).shape == (9, 2)


def test_takens_of_a_series_of_vectors_has_a_window_of_vectors_per_row():
    series = np.column_stack([np.arange(5.0), 10 * np.arange(5.0)])
    windows = tda4j.takens_embedding(series, dimension=2)
    assert windows.tolist() == [[0.0, 0.0, 1.0, 10.0], [1.0, 10.0, 2.0, 20.0], [2.0, 20.0, 3.0, 30.0], [3.0, 30.0, 4.0, 40.0]]


def test_the_delay_guess_is_a_quarter_period_and_a_periodic_signal_embeds_as_a_loop():
    t = np.arange(400)
    series = np.sin(2 * np.pi * t / 40)
    delay = tda4j.delay_by_autocorrelation(series, 30)
    assert 9 <= delay <= 11
    cloud = tda4j.takens_embedding(series, dimension=2, delay=delay, stride=2)
    bars = tda4j.persistence(cloud, max_dimension=1).bars
    loops = bars[bars[:, 0] == 1]
    assert len(loops) == 1 and loops[0, 2] - loops[0, 1] > 0.5


def test_a_series_of_three_dimensions_is_refused():
    with pytest.raises(ValueError, match="1-D or 2-D"):
        tda4j.takens_embedding(np.zeros((2, 2, 2)), 2)
