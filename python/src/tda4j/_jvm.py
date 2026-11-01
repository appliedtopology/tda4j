"""The Java side: finding the jar, starting the JVM, and moving numbers across the boundary.

Everything else in the package is a thin layer over ``org.appliedtopology.tda4j.matlab.TDA4j``, the facade that MATLAB
and Java use, so this module is the only place that knows about JPype.
"""

from __future__ import annotations

import functools
import inspect
import math
import os
import re
from pathlib import Path

import jpype
import numpy as np

FACADE = "org.appliedtopology.tda4j.matlab.TDA4j"

_NO_JAR = (
    "tda4j: no tda4j jar found. A wheel carries one; from a source checkout, build it with "
    "`sbt tda4j/assembly` and point TDA4J_JAR at target/out/jvm/scala-*/tda4j/tda4j-*-assembly.jar."
)


class Tda4jError(RuntimeError):
    """An error from the library that is not a bad argument (those are ``ValueError``)."""


def jar_path() -> Path:
    """The jar the JVM loads: ``$TDA4J_JAR`` if set, else the one inside this package."""
    override = os.environ.get("TDA4J_JAR")
    if override:
        path = Path(override)
        if not path.is_file():
            raise FileNotFoundError(f"tda4j: TDA4J_JAR={override} is not a file")
        return path
    bundled = sorted((Path(__file__).parent / "jars").glob("*-assembly.jar"))
    if not bundled:
        raise FileNotFoundError(_NO_JAR)
    return bundled[-1]


def start_jvm(jvm_args: tuple[str, ...] | list[str] = (), jar: str | os.PathLike | None = None) -> None:
    """Starts the JVM with the tda4j jar on its class path.

    Calling it is optional: the first function that needs the library starts the JVM with its defaults. Call it first to
    choose the heap (``start_jvm(["-Xmx16g"])``), since a JVM's options cannot change once it runs. Starting a JVM that
    is already running is an error, unless it was started with the tda4j jar on its class path.
    """
    if jpype.isJVMStarted():
        if not _facade_loadable():
            raise RuntimeError(
                "tda4j: a JVM is already running without the tda4j jar on its class path; start it with "
                "classpath=[tda4j.jar_path()], or call tda4j.start_jvm() before the other library starts one"
            )
        return
    classpath = str(jar) if jar is not None else str(jar_path())
    jpype.startJVM(*jvm_args, classpath=[classpath], convertStrings=True)


def _facade_loadable() -> bool:
    try:
        jpype.JClass(FACADE)
        return True
    except Exception:  # noqa: BLE001 -- JPype raises its own type for a missing class
        return False


@functools.cache
def facade():
    """The ``TDA4j`` Java class, loaded on first use."""
    if not jpype.isJVMStarted():
        start_jvm()
    return jpype.JClass(FACADE)


# ---------------------------------------------------------------------------------------------------------------------
# errors: a Java exception becomes the Python one a caller expects
# ---------------------------------------------------------------------------------------------------------------------

def _translate(error: jpype.JException) -> Exception:
    message = str(error.getMessage()) if error.getMessage() is not None else str(error.getClass().getSimpleName())
    name = str(error.getClass().getName())
    if name in ("java.lang.IllegalArgumentException", "java.lang.NumberFormatException"):
        return ValueError(message)
    if name == "java.lang.IndexOutOfBoundsException" or name.endswith("ArrayIndexOutOfBoundsException"):
        return IndexError(message)
    if name == "java.lang.UnsupportedOperationException":
        return NotImplementedError(message)
    if name == "java.lang.OutOfMemoryError":
        return MemoryError(
            "tda4j: the JVM ran out of memory; start it with a larger heap before the first call: "
            'tda4j.start_jvm(["-Xmx16g"])'
        )
    if name == "java.io.FileNotFoundException" or name == "java.nio.file.NoSuchFileException":
        return FileNotFoundError(message)
    short = name.rsplit(".", 1)[-1]
    return Tda4jError(message if short in message else f"{short}: {message}")


def _translating(function):
    @functools.wraps(function)
    def wrapper(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except jpype.JException as error:
            raise _translate(error) from None

    return wrapper


def translating(target):
    """Makes a function, or every public method and property of a class, raise Python errors, not Java ones."""
    if not inspect.isclass(target):
        return _translating(target)
    for name, member in list(vars(target).items()):
        if name.startswith("_") and name not in ("__call__", "__len__", "__getitem__"):
            continue
        if isinstance(member, property):
            setattr(target, name, property(_translating(member.fget), doc=member.__doc__))
        elif inspect.isfunction(member):
            setattr(target, name, _translating(member))
    return target


# ---------------------------------------------------------------------------------------------------------------------
# data going in
# ---------------------------------------------------------------------------------------------------------------------

def path_in(path) -> str:
    """An absolute path, relative to Python's working directory. The JVM keeps the directory it started in as its own
    (``os.chdir`` does not move it, and a relative path it reads or writes would mean two places), so paths are made
    absolute here."""
    return os.path.abspath(os.fspath(path))


def points_in(points, name: str = "points"):
    """A ``double[][]`` from anything NumPy reads as a 2-D array with one row per point."""
    array = np.ascontiguousarray(points, dtype=np.float64)
    if array.ndim != 2:
        raise ValueError(
            f"tda4j: {name} must be a 2-D array with one row per point, got {array.ndim} dimension(s)"
            + ("; use .reshape(-1, 1) for one-dimensional data" if array.ndim == 1 else "")
        )
    return jpype.JArray(jpype.JDouble, 2)(array)


def doubles_in(values):
    return jpype.JArray(jpype.JDouble)(np.ascontiguousarray(values, dtype=np.float64).reshape(-1))


def ints_in(values, name: str = "indices"):
    array = np.asarray(values)
    if array.size and not np.issubdtype(array.dtype, np.integer):
        if not np.all(np.equal(np.mod(array, 1), 0)):
            raise ValueError(f"tda4j: {name} must be whole numbers")
    return jpype.JArray(jpype.JInt)(np.ascontiguousarray(array, dtype=np.int32).reshape(-1))


def _option_value(value) -> str:
    if isinstance(value, (bool, np.bool_)):
        return "true" if value else "false"
    if isinstance(value, (int, np.integer)):
        return str(int(value))
    if isinstance(value, (float, np.floating)):
        number = float(value)
        if math.isnan(number):
            return "NaN"
        if math.isinf(number):
            return "Infinity" if number > 0 else "-Infinity"
        return repr(number)
    return str(value)


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


def options_in(options: dict):
    """The facade's flat name/value ``String[]`` from keyword arguments: ``max_dimension=1`` is ``maxDimension``, ``1``.

    A value of ``None`` leaves the option out, so a caller can forward a default it does not set.
    """
    flat: list[str] = []
    for name, value in options.items():
        if value is None:
            continue
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name):
            raise ValueError(f"tda4j: {name!r} is not an option name")
        flat.extend((_camel(name), _option_value(value)))
    return jpype.JArray(jpype.JString)(flat)


# ---------------------------------------------------------------------------------------------------------------------
# data coming out
# ---------------------------------------------------------------------------------------------------------------------

def matrix_out(rows, columns: int) -> np.ndarray:
    """A ``double[][]`` as an array of shape ``(len(rows), columns)``, also when it has no rows."""
    if len(rows) == 0:
        return np.empty((0, columns))
    return np.array([np.asarray(row, dtype=np.float64) for row in rows], dtype=np.float64).reshape(len(rows), columns)


def ragged_out(rows) -> list[np.ndarray]:
    return [np.asarray(row, dtype=np.float64) for row in rows]


def int_matrix_out(rows, columns: int) -> np.ndarray:
    if len(rows) == 0:
        return np.empty((0, columns), dtype=np.int64)
    return np.array([np.asarray(row, dtype=np.int64) for row in rows], dtype=np.int64).reshape(len(rows), columns)


def vector_out(values, dtype=np.float64) -> np.ndarray:
    return np.array(values, dtype=dtype)
