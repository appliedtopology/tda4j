"""The objects the functions return: wrappers over the Java result classes, with NumPy in and out."""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple

import numpy as np

from . import _jvm
from ._jvm import translating


class Representative(NamedTuple):
    """A cycle (or a cocycle) that witnesses a bar: ``coefficients[k]`` times the cell ``cells[k]``.

    A cell is the sorted tuple of the 0-based numbers of its vertices (the points of the input). For an image the cell is
    a cube: along each axis ``2a`` for the vertex coordinate ``a`` and ``2a + 1`` for the unit interval from ``a`` to
    ``a + 1``, so the pixel ``(i, j)`` is ``(2i + 1, 2j + 1)``.
    """

    cells: list[tuple[int, ...]]
    coefficients: np.ndarray


class BarCells(NamedTuple):
    """The cell whose arrival created a bar and the cell that destroyed it (``None`` for a bar that never dies)."""

    birth: tuple[int, ...]
    death: tuple[int, ...] | None


class Cell(NamedTuple):
    """A cell of the filtered complex: its dimension, its vertices and the filtration value at which it enters."""

    dimension: int
    vertices: tuple[int, ...]
    filtration_value: float


class Boundary(NamedTuple):
    """The boundary matrix of the filtered complex as its nonzero entries: ``values[k]`` at row ``rows[k]``, column
    ``cols[k]``, in the order of ``Result.cells()`` (a face comes before the cells it bounds); ``shape`` is ``(n, n)``
    for ``n`` cells."""

    rows: np.ndarray
    cols: np.ndarray
    values: np.ndarray
    shape: tuple[int, int]


class Plot:
    """A picture: ``svg()`` and ``html()`` give its text, ``save(path)`` writes an ``.svg`` or ``.html`` file, and a
    notebook shows it. ``view()`` opens it in the browser, through the library's live viewer."""

    def __init__(self, java):
        self._j = java

    def _text(self, suffix: str, theme: str | None) -> str:
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "plot" + suffix)
            if theme is None:
                self._j.save(path)
            else:
                self._j.save(path, theme)
            return Path(path).read_text(encoding="utf-8")

    def svg(self, theme: str | None = None) -> str:
        """The plot as an SVG document, in plain light colors unless ``theme`` is ``"dark"`` or ``"auto"``."""
        return self._text(".svg", theme)

    def html(self, theme: str | None = None) -> str:
        """The plot as a complete HTML page, which follows the reader's light or dark setting."""
        return self._text(".html", theme)

    def save(self, path: str | os.PathLike, theme: str | None = None) -> str:
        """Writes the plot to ``path`` (``.svg`` or ``.html``) and returns the absolute path."""
        if theme is None:
            return str(self._j.save(str(path)))
        return str(self._j.save(str(path), theme))

    def view(self) -> "Plot":
        """Adds the plot to the live viewer, a browser tab."""
        self._j.view()
        return self

    def _repr_svg_(self) -> str:
        return self.svg()

    def __repr__(self) -> str:
        return f"<tda4j.Plot {self._j.title()!s}>"


translating(Plot)


class Result:
    """Persistent homology of one input: its bars, with a representative and the creating and destroying cells for each.

    ``bars`` is an array with one row ``(dimension, birth, death)`` per reported bar, ``death`` equal to ``inf`` for a
    class that never dies. By default a bar much shorter than the scale of the data is not reported
    (``hidden_count`` says how many were left out; ``all_bars`` has every bar), and a bar of length zero is left out
    unless the call passed ``include_zero_length=True``.

    A bar is identified by its row in ``bars``: ``representative(i)`` and ``bar_cells(i)`` are those of ``bars[i]``.
    """

    def __init__(self, java):
        self._j = java

    # -- the bars ------------------------------------------------------------------------------------------------

    @property
    def bars(self) -> np.ndarray:
        """The reported bars, shape ``(n, 3)``: dimension, birth, death."""
        return _jvm.matrix_out(self._j.toArray(), 3)

    @property
    def all_bars(self) -> np.ndarray:
        """Every computed bar, hidden or not, in the shape of ``bars``."""
        return _jvm.matrix_out(self._j.toArrayUnfiltered(), 3)

    @property
    def hidden_count(self) -> int:
        """How many bars the persistence threshold left out of ``bars``."""
        return int(self._j.hiddenCount())

    @property
    def persistence_threshold(self) -> float:
        """The length a finite bar needs to be reported (essential bars always are)."""
        return float(self._j.persistenceThreshold())

    def diagram(self, dimension: int) -> np.ndarray:
        """The ``(birth, death)`` pairs of the reported bars of one dimension, shape ``(n, 2)``."""
        bars = self.bars
        return bars[bars[:, 0] == dimension, 1:]

    def __len__(self) -> int:
        return int(self._j.size())

    # -- what witnesses a bar ----------------------------------------------------------------------------------------

    def representative(self, i: int) -> Representative:
        """A cycle (a cocycle, if the call asked ``representative_type="cocycles"``) for the bar ``bars[i]``."""
        self._check(i)
        cells = [tuple(int(v) for v in cell) for cell in self._j.cycleVertices(i)]
        return Representative(cells, _jvm.vector_out(self._j.cycleCoefficients(i)))

    def bar_cells(self, i: int) -> BarCells:
        """The cell that created the bar ``bars[i]`` and the cell that destroyed it.

        Which of several cells that enter at the same value gets the credit depends on the engine, so two engines can
        name different cells for the same bar; each is valid. Engines that cannot say (``fast-cubical``,
        ``fast-alpha``) raise ``NotImplementedError``.
        """
        self._check(i)
        birth = tuple(int(v) for v in self._j.birthCell(i))
        death = tuple(int(v) for v in self._j.deathCell(i))
        return BarCells(birth, death if death else None)

    def _check(self, i: int) -> None:
        if not 0 <= i < len(self):
            raise IndexError(f"tda4j: no bar {i}; the result reports {len(self)} bars, numbered from 0")

    # -- the filtered complex ------------------------------------------------------------------------------------------

    @property
    def num_cells(self) -> int:
        return int(self._j.numCells())

    def boundary(self) -> Boundary:
        """The boundary matrix of the filtered complex the bars were computed from."""
        n = self.num_cells
        return Boundary(
            _jvm.vector_out(self._j.boundaryRows(), np.int64),
            _jvm.vector_out(self._j.boundaryCols(), np.int64),
            _jvm.vector_out(self._j.boundaryValues()),
            (n, n),
        )

    def cells(self) -> list[Cell]:
        """The cells of the filtered complex in the order of the columns of ``boundary()``: a cell comes after its faces,
        and no cell enters before a face of it."""
        return [
            Cell(
                int(self._j.columnDimension(j)),
                tuple(int(v) for v in self._j.columnVertices(j)),
                float(self._j.columnFiltrationValue(j)),
            )
            for j in range(self.num_cells)
        ]

    # -- distances and vectorizations --------------------------------------------------------------------------------

    def bottleneck_distance(self, other: "Result", dimension: int, ground_norm: float | None = None) -> float:
        if ground_norm is None:
            return float(self._j.bottleneckDistance(other._j, dimension))
        return float(self._j.bottleneckDistance(other._j, dimension, float(ground_norm)))

    def wasserstein_distance(
        self, other: "Result", dimension: int, order: float = 1.0, ground_norm: float | None = None
    ) -> float:
        if ground_norm is None:
            return float(self._j.wassersteinDistance(other._j, dimension, float(order)))
        return float(self._j.wassersteinDistance(other._j, dimension, float(order), float(ground_norm)))

    def sliced_wasserstein_distance(self, other: "Result", dimension: int, directions: int = 50) -> float:
        return float(self._j.slicedWassersteinDistance(other._j, dimension, directions))

    def bottleneck_matching(self, other: "Result", dimension: int) -> np.ndarray:
        """Rows ``(i, j)`` of an optimal matching of the bars of one dimension, numbered from 0 in the order of
        ``all_bars`` restricted to that dimension; ``-1`` stands for the diagonal."""
        return _jvm.int_matrix_out(self._j.bottleneckMatching(other._j, dimension), 2)

    def wasserstein_matching(self, other: "Result", dimension: int, order: float = 1.0) -> np.ndarray:
        """As ``bottleneck_matching``, for the Wasserstein distance of the given order."""
        return _jvm.int_matrix_out(self._j.wassersteinMatching(other._j, dimension, float(order)), 2)

    def landscape(self, dimension: int, num_levels: int, t_min: float, t_max: float, resolution: int) -> np.ndarray:
        """The first ``num_levels`` persistence landscapes sampled at ``resolution`` points; shape ``(levels, resolution)``."""
        return _jvm.matrix_out(
            self._j.landscape(dimension, num_levels, float(t_min), float(t_max), resolution), resolution
        )

    def betti_curve(self, dimension: int, t_min: float, t_max: float, resolution: int) -> np.ndarray:
        return _jvm.vector_out(self._j.bettiCurve(dimension, float(t_min), float(t_max), resolution), np.int64)

    def silhouette(
        self, dimension: int, t_min: float, t_max: float, resolution: int, power: float = 1.0
    ) -> np.ndarray:
        return _jvm.vector_out(self._j.silhouette(dimension, float(power), float(t_min), float(t_max), resolution))

    def persistence_entropy(self, dimension: int, normalized: bool = False) -> float:
        return float(self._j.persistenceEntropy(dimension, bool(normalized)))

    def persistence_image(
        self,
        dimension: int,
        sigma: float,
        birth_range: tuple[float, float],
        persistence_range: tuple[float, float],
        resolution: tuple[int, int] = (20, 20),
        weight_cap: float | None = None,
    ) -> np.ndarray:
        """The persistence image over ``birth_range`` by ``persistence_range``, ``resolution`` pixels (birth, persistence)."""
        args = (
            dimension,
            float(sigma),
            float(birth_range[0]),
            float(birth_range[1]),
            float(persistence_range[0]),
            float(persistence_range[1]),
            int(resolution[0]),
            int(resolution[1]),
        )
        rows = self._j.persistenceImage(*args) if weight_cap is None else self._j.persistenceImage(*args, float(weight_cap))
        return _jvm.matrix_out(rows, int(resolution[1]))

    # -- plots --------------------------------------------------------------------------------------------------------

    def plot_barcode(self, longest: int | None = None, order: str | None = None) -> Plot:
        """The barcode; ``longest`` keeps that many bars of each dimension, ``order`` is ``"birth"``, ``"persistence"`` or ``"death"``."""
        if longest is not None:
            return Plot(self._j.plotBarcode(int(longest)))
        if order is not None:
            return Plot(self._j.plotBarcode(str(order)))
        return Plot(self._j.plotBarcode())

    def plot_diagram(self) -> Plot:
        return Plot(self._j.plotDiagram())

    def plot_landscape(self, dimension: int) -> Plot:
        return Plot(self._j.plotLandscape(dimension))

    def plot_persistence_image(self, dimension: int) -> Plot:
        return Plot(self._j.plotPersistenceImage(dimension))

    def plot_input(self) -> Plot:
        return Plot(self._j.plotInput())

    def plot_complex(self, at: float) -> Plot:
        """The complex at filtration value ``at`` (up to its triangles)."""
        return Plot(self._j.plotComplex(float(at)))

    def plot_representative(self, i: int, at: float | None = None) -> Plot:
        self._check(i)
        return Plot(self._j.plotRepresentative(i) if at is None else self._j.plotRepresentative(i, float(at)))

    def plot_input_3d(self) -> Plot:
        """The first three coordinates of the points, to turn and zoom (save it as ``.html``)."""
        return Plot(self._j.plotInput3D())

    def plot_complex_3d(self, at: float) -> Plot:
        return Plot(self._j.plotComplex3D(float(at)))

    def plot_representative_3d(self, i: int) -> Plot:
        self._check(i)
        return Plot(self._j.plotRepresentative3D(i))

    def most_persistent_bar(self, dimension: int) -> int:
        """The row of ``bars`` of the longest bar of a dimension (an essential bar first; the first among equals)."""
        return int(self._j.mostPersistentBar(dimension))

    # -- files --------------------------------------------------------------------------------------------------------

    def save(self, path: str | os.PathLike) -> str:
        """Writes the result as a JSON file ``load_result`` reads back; returns its absolute path."""
        return str(self._j.save(str(path)))

    def __repr__(self) -> str:
        return f"<tda4j.Result: {len(self)} bars, {self.hidden_count} hidden>"


translating(Result)


class MapDegree(NamedTuple):
    """The map on persistent homology in one degree.

    ``entries`` has one row ``(source bar, target bar, coefficient)`` per nonzero entry of the matrix, the bars as 0-based
    positions in ``source_bars`` and ``target_bars`` (rows ``(birth, death)``); ``image``, ``kernel`` and ``cokernel``
    are barcodes in the same shape.
    """

    dimension: int
    source_bars: np.ndarray
    target_bars: np.ndarray
    entries: np.ndarray
    image: np.ndarray
    kernel: np.ndarray
    cokernel: np.ndarray


class MapResult:
    """The map on persistent homology induced by a map of complexes, degree by degree: see ``MapDegree``."""

    def __init__(self, java):
        self._j = java

    @property
    def max_dimension(self) -> int:
        return int(self._j.maxDimension())

    @property
    def characteristic(self) -> int:
        return int(self._j.characteristic())

    def degree(self, k: int) -> MapDegree:
        if not 0 <= k <= self.max_dimension:
            raise IndexError(f"tda4j: no degree {k}: the map was computed in degrees 0 to {self.max_dimension}")
        j = self._j
        return MapDegree(
            k,
            _jvm.matrix_out(j.sourceBars(k), 2),
            _jvm.matrix_out(j.targetBars(k), 2),
            _jvm.matrix_out(j.entries(k), 3),
            _jvm.matrix_out(j.image(k), 2),
            _jvm.matrix_out(j.kernel(k), 2),
            _jvm.matrix_out(j.cokernel(k), 2),
        )

    @property
    def degrees(self) -> list[MapDegree]:
        return [self.degree(k) for k in range(self.max_dimension + 1)]

    def __repr__(self) -> str:
        return f"<tda4j.MapResult: degrees 0 to {self.max_dimension}, characteristic {self.characteristic}>"


translating(MapResult)


@dataclass(frozen=True, eq=False)  # arrays do not compare with ==
class Landmarks:
    """Landmark points: their row numbers and the covering radius they leave."""

    indices: np.ndarray
    covering_radius: float


@dataclass(frozen=True, eq=False)
class EulerCurve:
    """The Euler characteristic of the Vietoris-Rips complex as a step function of the scale: ``values[k]`` from
    ``scales[k]`` on. Call it, ``curve(t)``, for the value at ``t``."""

    scales: np.ndarray
    values: np.ndarray
    _java: object

    def __call__(self, t: float) -> int:
        return int(self._java.at(float(t)))

    def sample(self, t_min: float, t_max: float, resolution: int) -> np.ndarray:
        return _jvm.vector_out(self._java.sample(float(t_min), float(t_max), resolution), np.int64)


translating(EulerCurve)


@dataclass(frozen=True, eq=False)
class PersistentLaplacian:
    """The persistent Laplacian of the Vietoris-Rips complex (see ``persistent_laplacian``): ``matrix`` is symmetric on
    the simplices ``cells`` (tuples of vertex numbers) that have entered by ``start``; its kernel has the dimension
    ``betti_number``, the number of classes of that dimension at ``start`` still there at ``stop``. ``eigenvalues`` are
    ascending and ``spectral_gap`` is the smallest one that is not zero (``None`` if all are)."""

    dimension: int
    start: float
    stop: float
    cells: list
    matrix: np.ndarray
    eigenvalues: np.ndarray
    betti_number: int
    spectral_gap: object


@dataclass(frozen=True, eq=False)
class BootstrapBand:
    """The resampling confidence band of the bars of one degree: a bar that persists longer than ``threshold`` survives
    the resampling at level ``alpha``; ``distances`` are the bottleneck distances the rounds measured."""

    width: float
    threshold: float
    alpha: float
    distances: np.ndarray


@dataclass(frozen=True, eq=False)
class CircularCoordinates:
    """One angle in [0, 1) per point (``nan`` for a point outside the complex) for a loop of the point cloud."""

    theta: np.ndarray
    birth: float
    death: float
    r: float
    prime: int
    _java: object = field(default=None, repr=False)

    def plot(self) -> Plot:
        """The points on a cyclic color map by angle."""
        return Plot(self._java.plot())


@dataclass(frozen=True, eq=False)
class ToroidalCoordinates:
    """``theta[c][i]`` is coordinate ``c`` of point ``i`` for loops that live together at the scale ``r``."""

    theta: np.ndarray
    cocycle_indices: np.ndarray
    basis_change: np.ndarray
    original_gram: np.ndarray
    reduced_gram: np.ndarray
    r: float
    prime: int
    _java: object = field(default=None, repr=False)

    def plot(self, c1: int | None = None, c2: int | None = None) -> Plot:
        """Coordinates ``c1`` and ``c2`` (by default the first two) against each other on the torus."""
        if c1 is None and c2 is None:
            return Plot(self._java.plot())
        return Plot(self._java.plot(int(c1 or 0), int(c2 if c2 is not None else 1)))


translating(CircularCoordinates)
translating(ToroidalCoordinates)
