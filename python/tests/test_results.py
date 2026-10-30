import itertools
import math

import numpy as np
import pytest

import tda4j

SQRT2 = math.sqrt(2.0)


def loop_row(result):
    return int(np.flatnonzero(result.bars[:, 0] == 1)[0])


def edge_length(points, edge):
    return float(np.linalg.norm(points[edge[0]] - points[edge[1]]))


def test_the_representative_of_a_loop_is_a_cycle_made_of_edges_that_exist_at_its_birth(circle):
    points = circle(12)
    result = tda4j.persistence(points, max_dimension=1, field="R")
    i = loop_row(result)
    cells, coefficients = result.representative(i)
    assert all(len(cell) == 2 for cell in cells)
    # the boundary of the chain is zero: each vertex is entered as often as it is left
    boundary = np.zeros(len(points))
    for (a, b), c in zip(cells, coefficients):
        boundary[b] += c
        boundary[a] -= c
    assert np.allclose(boundary, 0.0)
    assert len(cells) >= 3 and np.all(coefficients != 0)
    assert max(edge_length(points, cell) for cell in cells) <= result.bars[i, 1] + 1e-12


def test_a_cocycle_representative_is_asked_for_by_name(circle):
    result = tda4j.persistence(circle(10), max_dimension=1, representative_type="cocycles")
    cells, coefficients = result.representative(loop_row(result))
    assert len(cells) == len(coefficients) > 0


def test_a_bar_is_created_by_an_edge_and_destroyed_by_a_triangle(circle):
    points = circle(12)
    result = tda4j.persistence(points, max_dimension=2)
    i = loop_row(result)
    birth, death = result.bar_cells(i)
    assert len(birth) == 2 and len(death) == 3
    assert edge_length(points, birth) == pytest.approx(result.bars[i, 1])
    assert max(edge_length(points, e) for e in itertools.combinations(death, 2)) == pytest.approx(result.bars[i, 2])
    essential = int(np.flatnonzero(np.isinf(result.bars[:, 2]))[0])
    assert result.bar_cells(essential).death is None


def test_asking_for_a_bar_that_is_not_there_is_an_index_error(square):
    result = tda4j.persistence(square, max_dimension=1)
    with pytest.raises(IndexError, match="no bar 5"):
        result.representative(5)
    with pytest.raises(IndexError, match="no bar -1"):
        result.bar_cells(-1)


def test_the_boundary_matrix_is_the_boundary_of_the_cells_in_filtration_order(circle):
    points = circle(7)
    result = tda4j.persistence(points, max_dimension=2, field="R")
    cells = result.cells()
    boundary = result.boundary()
    n = result.num_cells
    assert boundary.shape == (n, n) == (len(cells), len(cells))
    assert np.all(boundary.rows < boundary.cols), "a face comes before the cell it bounds"
    dense = np.zeros(boundary.shape)
    dense[boundary.rows, boundary.cols] = boundary.values
    assert np.allclose(dense @ dense, 0.0), "the boundary of a boundary is zero"
    for j, cell in enumerate(cells):
        faces = {cells[r].vertices for r in boundary.rows[boundary.cols == j]}
        assert all(cells[r].filtration_value <= cell.filtration_value for r in boundary.rows[boundary.cols == j])
        expected = {cell.vertices[:k] + cell.vertices[k + 1 :] for k in range(len(cell.vertices))} if cell.dimension else set()
        assert faces == expected
        assert len(cell.vertices) == cell.dimension + 1


def test_a_distance_to_itself_is_zero_and_a_scaled_square_is_where_the_matching_says(square):
    one = tda4j.persistence(square, max_dimension=1)
    two = tda4j.persistence(2 * square, max_dimension=1)
    assert one.bottleneck_distance(one, 1) == 0.0
    assert one.wasserstein_distance(one, 1) == 0.0
    # the loops [1, sqrt 2) and [2, 2 sqrt 2): matched to each other it costs 2 sqrt 2 - sqrt 2, to the diagonal
    # it costs half their lengths, and the diagonal is cheaper.
    assert one.bottleneck_distance(two, 1) == pytest.approx((2 * SQRT2 - 2) / 2)
    assert one.wasserstein_distance(two, 1) == pytest.approx((SQRT2 - 1) / 2 + (2 * SQRT2 - 2) / 2)
    assert one.sliced_wasserstein_distance(one, 1, 10) == 0.0
    matching = one.bottleneck_matching(two, 1)
    assert sorted(map(tuple, matching.tolist())) == [(-1, 0), (0, -1)]


def test_vectorizations_of_the_square(square):
    result = tda4j.persistence(square, max_dimension=1)
    betti = result.betti_curve(0, 0.0, 2.0, 3)
    assert betti.tolist() == [4, 1, 1]
    assert result.betti_curve(1, 0.0, 2.0, 3).tolist() == [0, 1, 0]
    landscape = result.landscape(1, 2, 1.0, SQRT2, 3)  # the loop's birth, its middle, its death
    assert landscape.shape == (2, 3)
    assert landscape[0] == pytest.approx([0.0, (SQRT2 - 1) / 2, 0.0])
    assert np.all(landscape[1] == 0)
    assert result.silhouette(1, 0.0, 2.0, 5).shape == (5,)
    assert result.persistence_entropy(1) == pytest.approx(0.0)
    image = result.persistence_image(1, 0.1, (0.0, 2.0), (0.0, 1.0), resolution=(6, 4))
    assert image.shape == (6, 4) and image.sum() > 0


def test_plots_are_pictures_that_can_be_saved(tmp_path, square):
    result = tda4j.persistence(square, max_dimension=1)
    figure = result.plot_barcode()
    assert "<svg" in figure.svg() and "<svg" in figure._repr_svg_()
    assert "<html" in figure.html().lower()
    written = figure.save(tmp_path / "barcode.svg")
    assert written.endswith("barcode.svg") and (tmp_path / "barcode.svg").read_text().count("<svg") == 1
    assert "<svg" in result.plot_diagram().svg()
    assert "<svg" in result.plot_representative(loop_row(result)).svg()
    assert "<svg" in result.plot_barcode(order="persistence").svg()


def test_a_saved_result_reads_back_with_its_bars_and_representatives(tmp_path, circle):
    result = tda4j.persistence(circle(10), max_dimension=1)
    path = result.save(tmp_path / "result.json")
    again = tda4j.load_result(path)
    assert np.array_equal(again.bars, result.bars)
    i = loop_row(result)
    assert again.representative(i).cells == result.representative(i).cells
    assert again.bar_cells(i) == result.bar_cells(i)


def test_loading_a_file_that_is_not_there_is_a_file_not_found_error(tmp_path):
    with pytest.raises(Exception) as caught:
        tda4j.load_result(tmp_path / "missing.json")
    assert isinstance(caught.value, (FileNotFoundError, tda4j.Tda4jError, ValueError))


def test_the_most_persistent_bar_and_the_3d_plots(tmp_path, circle):
    points = np.column_stack([circle(16), np.zeros(16)])
    result = tda4j.persistence(points, max_dimension=1)
    i = result.most_persistent_bar(1)
    assert result.bars[i, 0] == 1
    assert i == loop_row(result)
    written = result.plot_input_3d().save(tmp_path / "points.html")
    assert "<html" in (tmp_path / "points.html").read_text().lower() and written.endswith("points.html")
    assert result.plot_complex_3d(1.0).save(tmp_path / "complex.html")
    assert result.plot_representative_3d(i).save(tmp_path / "loop.html")


def test_plots_go_side_by_side(tmp_path, square):
    result = tda4j.persistence(square, max_dimension=1)
    both = tda4j.side(result.plot_barcode(), result.plot_diagram())
    text = both.svg()
    assert text.count("<svg") >= 2
    with pytest.raises(ValueError, match="at least two"):
        tda4j.side(result.plot_barcode())
