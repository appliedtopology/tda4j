"""TDA4j for Python: persistent homology and topological data analysis, on the JVM library, with NumPy in and out.

>>> import numpy as np, tda4j
>>> square = np.array([[0, 0], [1, 0], [1, 1], [0, 1]])
>>> result = tda4j.persistence(square, max_dimension=1)
>>> result.bars                    # rows (dimension, birth, death); death is inf for a class that never dies
>>> result.representative(4)       # the loop: its edges and coefficients
>>> result.bar_cells(4)            # the edge that opened the loop and the triangle that closed it

Every function takes the library's options as keyword arguments, in snake_case: ``max_dimension=1`` is the option
``maxDimension`` of the MATLAB and Java interface, ``prime=3`` the field with three elements, ``complex="alpha"`` the alpha
complex. By default the field is ``Z/17`` and the degrees are 0 to 2. The options and their defaults are those of the
interface described at https://tda4j.appliedtopology.org (the page "Calling from MATLAB or Java"); a mistyped one
raises ``ValueError`` and lists the accepted ones.

The library runs in a JVM (Java 21 or newer) that starts when the first function needs it. To give it a bigger heap, call
``tda4j.start_jvm(["-Xmx16g"])`` before that. A wheel carries the jar; from a source checkout, set ``TDA4J_JAR``.
"""

from __future__ import annotations

import os
from importlib import metadata

import jpype
import numpy as np

from . import _jvm
from ._jvm import Tda4jError, jar_path, start_jvm, translating
from ._results import (
    BarCells,
    BootstrapBand,
    Boundary,
    Cell,
    CircularCoordinates,
    EulerCurve,
    Landmarks,
    MapDegree,
    MapResult,
    MorseReduction,
    PersistentLaplacian,
    Plot,
    Representative,
    Result,
    ToroidalCoordinates,
    Zigzag,
)

try:
    __version__ = metadata.version("tda4j")
except metadata.PackageNotFoundError:  # a source checkout on sys.path
    __version__ = "0+unknown"

__all__ = [
    "BarCells",
    "BootstrapBand",
    "Boundary",
    "Cell",
    "CircularCoordinates",
    "EulerCurve",
    "Landmarks",
    "MapDegree",
    "MapResult",
    "MorseReduction",
    "PersistentLaplacian",
    "Plot",
    "Representative",
    "Result",
    "Tda4jError",
    "ToroidalCoordinates",
    "Zigzag",
    "bootstrap_band",
    "circular_coordinates",
    "clear_plot_defaults",
    "covering_radius",
    "covering_radius_from_distances",
    "delay_by_autocorrelation",
    "dual_bars",
    "euler_curve",
    "euler_curve_from_distances",
    "h1_bars",
    "hom_bars",
    "hom_dimension",
    "inclusion_map",
    "inclusion_map_from_distances",
    "jar_path",
    "load_result",
    "morse_reduction",
    "morse_reduction_from_distances",
    "persistence",
    "persistence_from_distances",
    "persistence_from_distances_and_landmarks",
    "persistence_from_image",
    "persistence_from_landmarks",
    "persistence_from_relation",
    "persistent_laplacian",
    "persistent_laplacian_from_distances",
    "product_bars",
    "relative_persistence",
    "relative_persistence_from_distances",
    "select_landmarks",
    "select_landmarks_from_distances",
    "set_plot_defaults",
    "side",
    "simplicial_map",
    "simplicial_map_from_distances",
    "start_jvm",
    "takens_embedding",
    "tomato_clusters",
    "tomato_diagram",
    "toroidal_coordinates",
    "zigzag_from_complexes",
    "zigzag_from_steps",
]


def _java():
    return _jvm.facade()


# ---------------------------------------------------------------------------------------------------------------------
# persistent homology
# ---------------------------------------------------------------------------------------------------------------------

@translating
def persistence(points, **options) -> Result:
    """Persistent homology of a point cloud: an array with one row per point, with Euclidean distances.

    Options (keyword arguments): ``complex`` (``"vr"`` by default, or ``"cech"``, ``"alpha"``, ``"witness"``,
    ``"dtm-rips"``, ``"dtm-alpha"``, ``"sparse-rips"``), ``engine``, ``max_dimension`` (2), ``max_filtration_value``,
    ``prime`` (17), ``field`` (``"Z"``, ``"R"`` for the reals or ``"Q"`` for exact rationals),
    ``representative_type`` (``"cycles"`` or ``"cocycles"``), ``min_persistence``, ``min_persistence_fraction``,
    ``include_zero_length`` and the rest of the interface's options.
    """
    return Result(_java().computeFromPoints(_jvm.points_in(points), _jvm.options_in(options)))


@translating
def persistence_from_distances(distances, **options) -> Result:
    """Persistent homology of a finite metric space given as a square, symmetric matrix of distances.

    The complexes that need only distances: ``vr``, ``witness``, ``dtm-rips``, ``sparse-rips``.
    """
    return Result(_java().computeFromDistanceMatrix(_jvm.points_in(distances, "distances"), _jvm.options_in(options)))


@translating
def persistence_from_image(image, **options) -> Result:
    """Persistent homology of an image or a voxel grid of any dimension, as a cubical complex.

    ``sublevel=False`` takes superlevel sets, a ``nan`` pixel is missing data (not in the complex). The cells of a
    representative are cubes in doubled coordinates: the pixel ``(i, j)`` is ``(2i + 1, 2j + 1)``.
    """
    array = np.ascontiguousarray(image, dtype=np.float64)
    if array.ndim == 0 or array.size == 0:
        raise ValueError("tda4j: image must have at least one pixel")
    return Result(
        _java().computeFromCubicalImage(
            _jvm.ints_in(array.shape), _jvm.doubles_in(array.reshape(-1)), _jvm.options_in(options)
        )
    )


@translating
def persistence_from_relation(relation, **options) -> Result:
    """Persistent homology of the Dowker complex of a relation: ``relation[x, w]`` is the strength of the tie between row
    ``x`` and column ``w``, smaller being stronger and ``inf`` meaning never. The matrix need not be square."""
    return Result(_java().computeFromRelation(_jvm.points_in(relation, "relation"), _jvm.options_in(options)))


@translating
def persistence_from_landmarks(points, landmarks, **options) -> Result:
    """The witness complex of a point cloud on the landmarks (row numbers of ``points``)."""
    return Result(
        _java().computeFromPointsAndLandmarks(
            _jvm.points_in(points), _jvm.ints_in(landmarks, "landmarks"), _jvm.options_in(options)
        )
    )


@translating
def persistence_from_distances_and_landmarks(distances, landmarks, **options) -> Result:
    """``persistence_from_landmarks`` for a matrix of distances."""
    return Result(
        _java().computeFromDistanceMatrixAndLandmarks(
            _jvm.points_in(distances, "distances"), _jvm.ints_in(landmarks, "landmarks"), _jvm.options_in(options)
        )
    )


@translating
def relative_persistence(points, subcomplex, **options) -> Result:
    """Relative persistent homology of the Vietoris-Rips complex of a point cloud modulo the subcomplex on the points
    ``subcomplex`` (row numbers): the bars of the pair ``(X, A)``. Modulo one point of a connected cloud the result is
    the reduced homology. Options: ``engine`` (``"cohomology"``, ``"naive"``, ``"chunks"``), ``max_dimension``,
    ``max_filtration_value``, ``field``, ``prime``, ``representative_type`` and the bar options."""
    return Result(
        _java().computeRelativeFromPoints(
            _jvm.points_in(points), _jvm.ints_in(subcomplex, "subcomplex"), _jvm.options_in(options)
        )
    )


@translating
def relative_persistence_from_distances(distances, subcomplex, **options) -> Result:
    """``relative_persistence`` for a matrix of distances."""
    return Result(
        _java().computeRelativeFromDistanceMatrix(
            _jvm.points_in(distances, "distances"), _jvm.ints_in(subcomplex, "subcomplex"), _jvm.options_in(options)
        )
    )


@translating
def load_result(path: str | os.PathLike) -> Result:
    """A result saved by ``Result.save``, the MATLAB or Java ``save``, or the command line's ``--save-result``."""
    return Result(_java().loadResult(_jvm.path_in(path)))


# ---------------------------------------------------------------------------------------------------------------------
# maps between spaces
# ---------------------------------------------------------------------------------------------------------------------

@translating
def inclusion_map(points, subsample, **options) -> MapResult:
    """The map on persistent homology induced by the inclusion of the Vietoris-Rips complex of a subsample (row numbers
    ``subsample``) into that of all the points: what survives from the subsample, and what only the sample sees.
    Options: ``engine`` (``"ripser"`` or ``"cohomology"``), ``max_dimension``, ``max_filtration_value`` (shared by both
    complexes), ``prime``."""
    return MapResult(
        _java().computeInclusionFromPoints(
            _jvm.points_in(points), _jvm.ints_in(subsample, "subsample"), _jvm.options_in(options)
        )
    )


@translating
def inclusion_map_from_distances(distances, subsample, **options) -> MapResult:
    """``inclusion_map`` for a matrix of distances."""
    return MapResult(
        _java().computeInclusionFromDistanceMatrix(
            _jvm.points_in(distances, "distances"), _jvm.ints_in(subsample, "subsample"), _jvm.options_in(options)
        )
    )


@translating
def simplicial_map(source_points, target_points, vertex_map, **options) -> MapResult:
    """The map induced by the simplicial map that sends point ``i`` of ``source_points`` to point ``vertex_map[i]`` of
    ``target_points``. It must not increase a distance. Options as in ``inclusion_map``."""
    return MapResult(
        _java().computeMapFromPoints(
            _jvm.points_in(source_points, "source_points"),
            _jvm.points_in(target_points, "target_points"),
            _jvm.ints_in(vertex_map, "vertex_map"),
            _jvm.options_in(options),
        )
    )


@translating
def simplicial_map_from_distances(source_distances, target_distances, vertex_map, **options) -> MapResult:
    """``simplicial_map`` between two metric spaces given as matrices of distances."""
    return MapResult(
        _java().computeMapFromDistanceMatrices(
            _jvm.points_in(source_distances, "source_distances"),
            _jvm.points_in(target_distances, "target_distances"),
            _jvm.ints_in(vertex_map, "vertex_map"),
            _jvm.options_in(options),
        )
    )


# ---------------------------------------------------------------------------------------------------------------------
# landmarks, curves, bands, clusters
# ---------------------------------------------------------------------------------------------------------------------

@translating
def select_landmarks(points, num_landmarks: int, **options) -> Landmarks:
    """Landmarks of a point cloud by farthest-point sampling (option ``landmark_selector``, ``landmark_seed``)."""
    chosen = _java().selectLandmarksFromPoints(
        _jvm.points_in(points), _jvm.options_in({"num_landmarks": num_landmarks, **options})
    )
    return Landmarks(_jvm.vector_out(chosen.landmarks(), np.int64), float(chosen.coveringRadius()))


@translating
def select_landmarks_from_distances(distances, num_landmarks: int, **options) -> Landmarks:
    """``select_landmarks`` for a matrix of distances."""
    chosen = _java().selectLandmarksFromDistanceMatrix(
        _jvm.points_in(distances, "distances"), _jvm.options_in({"num_landmarks": num_landmarks, **options})
    )
    return Landmarks(_jvm.vector_out(chosen.landmarks(), np.int64), float(chosen.coveringRadius()))


@translating
def covering_radius(points, landmarks) -> float:
    """The largest distance from a point to its nearest landmark."""
    return float(_java().coveringRadiusFromPoints(_jvm.points_in(points), _jvm.ints_in(landmarks, "landmarks")))


@translating
def covering_radius_from_distances(distances, landmarks) -> float:
    return float(
        _java().coveringRadiusFromDistanceMatrix(
            _jvm.points_in(distances, "distances"), _jvm.ints_in(landmarks, "landmarks")
        )
    )


def _euler(java) -> EulerCurve:
    return EulerCurve(_jvm.vector_out(java.scales()), _jvm.vector_out(java.values(), np.int64), java)


@translating
def euler_curve(points, **options) -> EulerCurve:
    """The Euler characteristic curve of the Vietoris-Rips complex (options ``max_simplex_dimension`` (2) and
    ``max_filtration_value``)."""
    return _euler(_java().eulerCurveFromPoints(_jvm.points_in(points), _jvm.options_in(options)))


@translating
def euler_curve_from_distances(distances, **options) -> EulerCurve:
    return _euler(_java().eulerCurveFromDistanceMatrix(_jvm.points_in(distances, "distances"), _jvm.options_in(options)))


@translating
def bootstrap_band(points, **options) -> BootstrapBand:
    """The resampling confidence band of the bars of one degree. Options: ``dimension`` (1), ``rounds`` (100), ``alpha``
    (0.05), ``seed`` (0), ``subsample_size`` (resample without replacement; by default with replacement), ``prime``,
    ``max_filtration_value``."""
    band = _java().bootstrapBand(_jvm.points_in(points), _jvm.options_in(options))
    return BootstrapBand(
        float(band.width()), float(band.threshold()), float(band.alpha()), _jvm.vector_out(band.distances())
    )


@translating
def tomato_clusters(points, k: int, tau: float) -> np.ndarray:
    """ToMATo clusters: the cluster number of every point (0 for the highest peak). ``k`` nearest neighbours define the
    graph and the density, ``tau`` is the depth below which two peaks merge."""
    return _jvm.vector_out(_java().tomatoClusters(_jvm.points_in(points), int(k), float(tau)), np.int64)


@translating
def tomato_diagram(points, k: int) -> np.ndarray:
    """The ``(birth, death)`` pairs of ToMATo's peaks, to choose ``tau`` from; shape ``(n, 2)``."""
    return _jvm.matrix_out(_java().tomatoDiagram(_jvm.points_in(points), int(k)), 2)


# ---------------------------------------------------------------------------------------------------------------------
# inputs from time series
# ---------------------------------------------------------------------------------------------------------------------

@translating
def takens_embedding(series, dimension: int, delay: int = 1, stride: int = 1) -> np.ndarray:
    """The sliding-window embedding of a time series (1-D) or of a series of vectors (2-D, one row per time step): row
    ``t`` is the window ``series[t], series[t + delay], ..., series[t + (dimension - 1) * delay]``, and ``stride`` skips
    windows. The result is a point cloud for ``persistence``."""
    array = np.asarray(series, dtype=np.float64)
    if array.ndim == 1:
        rows = _java().takensEmbedding(_jvm.doubles_in(array), int(dimension), int(delay), int(stride))
        return _jvm.matrix_out(rows, int(dimension))
    if array.ndim == 2:
        rows = _java().takensEmbeddingOfVectors(_jvm.points_in(array, "series"), int(dimension), int(delay), int(stride))
        return _jvm.matrix_out(rows, int(dimension) * array.shape[1])
    raise ValueError(f"tda4j: series must be 1-D or 2-D, got {array.ndim} dimensions")


@translating
def delay_by_autocorrelation(series, max_lag: int) -> int:
    """The first lag in ``1..max_lag`` where the autocorrelation of the series is zero or negative: a first guess for the
    ``delay`` of ``takens_embedding``."""
    return int(_java().delayByAutocorrelation(_jvm.doubles_in(series), int(max_lag)))


# ---------------------------------------------------------------------------------------------------------------------
# discrete Morse reduction
# ---------------------------------------------------------------------------------------------------------------------

def _morse(java) -> MorseReduction:
    return MorseReduction(
        cell_counts=_jvm.vector_out(java.cellCounts(), np.int64),
        critical_counts=_jvm.vector_out(java.criticalCounts(), np.int64),
        pair_count=int(java.pairCount()),
        critical_cells=[tuple(int(v) for v in cell) for cell in java.criticalCells()],
        bars=_jvm.matrix_out(java.bars(), 3),
    )


@translating
def morse_reduction(points, **options) -> MorseReduction:
    """Discrete Morse reduction of the Vietoris-Rips complex of a point cloud: cells that enter together are paired off, and
    the bars are computed on the cells left, which are the bars of the complex. Options: ``max_dimension`` (2),
    ``max_filtration_value``, ``strategy`` (``"reduction"``, or ``"coreduction"``), ``prime``, ``threads``."""
    return _morse(_java().morseReductionFromPoints(_jvm.points_in(points), _jvm.options_in(options)))


@translating
def morse_reduction_from_distances(distances, **options) -> MorseReduction:
    """``morse_reduction`` for a matrix of distances."""
    return _morse(
        _java().morseReductionFromDistanceMatrix(_jvm.points_in(distances, "distances"), _jvm.options_in(options))
    )


# ---------------------------------------------------------------------------------------------------------------------
# zigzag persistence
# ---------------------------------------------------------------------------------------------------------------------

def _simplices_in(simplices, name: str):
    """A ``int[][]`` of simplices, each a sequence of vertex numbers."""
    rows = [[int(v) for v in simplex] for simplex in simplices]
    if any(not row for row in rows):
        raise ValueError(f"tda4j: {name} must be simplices with at least one vertex each")
    return jpype.JArray(jpype.JInt, 2)(rows)


def _zigzag(java) -> Zigzag:
    return Zigzag(
        bars=_jvm.matrix_out(java.toArray(), 3),
        representatives=[
            Representative(
                [tuple(int(v) for v in cell) for cell in java.cycleVertices(i)],
                _jvm.vector_out(java.cycleCoefficients(i)),
            )
            for i in range(int(java.size()))
        ],
        last_position=int(java.lastPosition()),
    )


@translating
def zigzag_from_steps(initial, simplices, additions, **options) -> Zigzag:
    """Zigzag persistent homology of the sequence of complexes that starts at ``initial`` (a list of simplices, each a
    sequence of vertex numbers) and in step ``k`` adds the simplex ``simplices[k]`` if ``additions[k]`` is true and
    removes it otherwise. A simplex is added after its facets and removed before the simplices it is a facet of. A bar
    ``(dimension, birth, death)`` is a class present in the homology at the positions ``birth`` to ``death - 1``.
    Options: ``max_dimension`` (2) and ``prime`` (17)."""
    flags = [bool(a) for a in additions]
    return _zigzag(
        _java().zigzagFromSteps(
            _simplices_in(initial, "initial"),
            _simplices_in(simplices, "simplices"),
            jpype.JArray(jpype.JBoolean)(flags),
            _jvm.options_in(options),
        )
    )


@translating
def zigzag_from_complexes(complexes, **options) -> Zigzag:
    """Zigzag persistent homology of a list of complexes (each a list of simplices), each included in the next or the next
    in it. With ``link="union"`` or ``link="intersection"`` two consecutive complexes are joined through their union or
    intersection, which takes a position of its own. Options: ``link``, ``max_dimension`` (2) and ``prime`` (17)."""
    nested = [[[int(v) for v in simplex] for simplex in complex_] for complex_ in complexes]
    if any(not simplex for complex_ in nested for simplex in complex_):
        raise ValueError("tda4j: complexes must contain simplices with at least one vertex each")
    return _zigzag(_java().zigzagFromComplexes(jpype.JArray(jpype.JInt, 3)(nested), _jvm.options_in(options)))


# ---------------------------------------------------------------------------------------------------------------------
# the persistent Laplacian
# ---------------------------------------------------------------------------------------------------------------------

def _laplacian(java) -> PersistentLaplacian:
    return PersistentLaplacian(
        dimension=int(java.dimension()),
        start=float(java.start()),
        stop=float(java.stop()),
        cells=[tuple(int(v) for v in cell) for cell in java.cells()],
        matrix=_jvm.matrix_out(java.matrix(), int(java.size())),
        eigenvalues=_jvm.vector_out(java.eigenvalues()),
        betti_number=int(java.bettiNumber()),
        spectral_gap=float(java.spectralGap()) if java.hasSpectralGap() else None,
    )


@translating
def persistent_laplacian(points, dimension: int, start: float, stop: float) -> PersistentLaplacian:
    """The persistent Laplacian of the Vietoris-Rips complex of a point cloud: a symmetric matrix on the simplices of the
    given dimension that have entered by the scale ``start``, for the complex at the scale ``stop`` (``start <= stop``).
    Its kernel has the dimension of the persistent Betti number: the classes of that dimension at ``start`` that are still
    there at ``stop``; the smallest eigenvalue that is not zero is its ``spectral_gap``."""
    return _laplacian(_java().persistentLaplacianFromPoints(_jvm.points_in(points), int(dimension), float(start), float(stop)))


@translating
def persistent_laplacian_from_distances(distances, dimension: int, start: float, stop: float) -> PersistentLaplacian:
    """``persistent_laplacian`` for a matrix of distances."""
    return _laplacian(
        _java().persistentLaplacianFromDistanceMatrix(
            _jvm.points_in(distances, "distances"), int(dimension), float(start), float(stop)
        )
    )


# ---------------------------------------------------------------------------------------------------------------------
# algebra of barcodes: products, Hom and duals
# ---------------------------------------------------------------------------------------------------------------------

def _bars_in(bars, name: str):
    """Rows ``(dimension, birth, death)`` from a result (all of its bars, also the short ones) or any array of rows."""
    if hasattr(bars, "all_bars"):
        bars = bars.all_bars
    array = np.asarray(bars, dtype=np.float64)
    if array.size == 0:
        array = np.empty((0, 3))
    return _jvm.points_in(array, name)


@translating
def product_bars(first, second, filtration: str = "max") -> np.ndarray:
    """The bars of the homology of the product of two filtered complexes, from the barcodes of the factors (each a
    result or an array of rows ``(dimension, birth, death)``, every dimension of it). A cell of the product enters at
    the larger (``filtration="max"``, the default) or the sum (``"sum"``) of the values of its two factors. Right in
    the dimensions below the highest dimension both were computed to."""
    return _jvm.matrix_out(_java().productBars(_bars_in(first, "first"), _bars_in(second, "second"), str(filtration)), 3)


@translating
def hom_bars(first, second) -> np.ndarray:
    """The internal Hom of two barcodes (results or arrays of rows ``(dimension, birth, death)``): the module of maps from
    the first to the second shifted by ``t``. A bar of dimension ``p`` and one of dimension ``q`` give a bar of dimension
    ``q - p``."""
    return _jvm.matrix_out(_java().homBars(_bars_in(first, "first"), _bars_in(second, "second")), 3)


@translating
def hom_dimension(first, second) -> int:
    """The dimension of the space of maps of persistence modules from ``first`` to ``second``, degree by degree."""
    return int(_java().homDimension(_bars_in(first, "first"), _bars_in(second, "second")))


@translating
def dual_bars(bars) -> np.ndarray:
    """The dual of a barcode: the row ``(dimension, birth, death)`` of ``[b, d)`` becomes ``(dimension, -d, -b)``, the
    interval ``(-d, -b]`` (``-inf`` as the first end for a bar that never dies)."""
    return _jvm.matrix_out(_java().dualBars(_bars_in(bars, "bars")), 3)


# ---------------------------------------------------------------------------------------------------------------------
# circular and toroidal coordinates
# ---------------------------------------------------------------------------------------------------------------------

@translating
def h1_bars(points) -> np.ndarray:
    """The loops of the Vietoris-Rips complex as ``(birth, death)`` rows, most persistent first: the numbering that
    ``circular_coordinates`` and ``toroidal_coordinates`` use."""
    return _jvm.matrix_out(_java().h1Bars(_jvm.points_in(points)), 2)


@translating
def circular_coordinates(points, r: float, cocycle_index: int = 0, prime: int = 47) -> CircularCoordinates:
    """One angle in [0, 1) per point for loop ``cocycle_index`` of ``h1_bars``, at a scale ``r`` between its birth and
    death. Raises ``Tda4jError`` if the class has no integer lift at ``prime``: retry with a larger prime."""
    result = _java().circularCoordinates(_jvm.points_in(points), float(r), int(cocycle_index), int(prime))
    return CircularCoordinates(
        _jvm.vector_out(result.theta()),
        float(result.birth()),
        float(result.death()),
        float(result.r()),
        int(result.prime()),
        result,
    )


@translating
def toroidal_coordinates(
    points, r: float, cocycle_indices, prime: int = 47, reduce: bool = True
) -> ToroidalCoordinates:
    """One angle per point for each of several loops of ``h1_bars`` alive together at ``r`` (``reduce`` applies the
    lattice reduction): ``theta[c][i]`` is coordinate ``c`` of point ``i``."""
    result = _java().toroidalCoordinates(
        _jvm.points_in(points), float(r), _jvm.ints_in(cocycle_indices, "cocycle_indices"), int(prime), bool(reduce)
    )
    count = int(result.dimension())
    n = len(points)
    return ToroidalCoordinates(
        np.array([_jvm.vector_out(result.theta(c)) for c in range(count)]).reshape(count, n),
        _jvm.vector_out(result.cocycleIndices(), np.int64),
        _jvm.int_matrix_out(result.basisChange(), count),
        _jvm.matrix_out(result.originalGram(), count),
        _jvm.matrix_out(result.reducedGram(), count),
        float(result.r()),
        int(result.prime()),
        result,
    )


# ---------------------------------------------------------------------------------------------------------------------
# plots
# ---------------------------------------------------------------------------------------------------------------------

@translating
def set_plot_defaults(*settings: str) -> None:
    """Changes to the look of every plot drawn from now on, written ``element.property=value``
    (``set_plot_defaults("palette=print")``); see the plotting page of the user guide."""
    _java().setPlotDefaults(jpype.JArray(jpype.JString)(list(settings)))


@translating
def clear_plot_defaults() -> None:
    _java().clearPlotDefaults()


@translating
def side(*plots: Plot) -> Plot:
    """Plots in one row, as one plot: ``tda4j.side(result.plot_barcode(), result.plot_diagram()).save("both.svg")``."""
    if len(plots) < 2:
        raise ValueError("tda4j: side needs at least two plots")
    figures = jpype.JArray(jpype.JClass("org.appliedtopology.tda4j.plot.Figure"))([p._j for p in plots])
    return Plot(_java().side(figures))
