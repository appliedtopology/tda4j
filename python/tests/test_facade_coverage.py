"""Every public method of the Java facade is wrapped, or listed here with the reason it is not.

The package is a thin layer over `org.appliedtopology.tda4j.matlab`, so a new facade method that nobody wraps would be
a silent gap in Python. This test turns it into a failure that names the method.
"""

import re
from pathlib import Path

import jpype
import pytest

import tda4j

PACKAGE = Path(tda4j.__file__).parent
FACADE_PACKAGE = "org.appliedtopology.tda4j.matlab"

# facade method -> why Python has no function for it
NOT_WRAPPED = {
    "TDA4j": {
        "computeFromImage": "persistence_from_image takes an array of any dimension, through computeFromCubicalImage",
        "layout": "a free-form board of plots: not wrapped yet; tda4j.side puts plots in a row",
    },
    "PersistenceResult": {},
    "PersistenceMapResult": {"toString": "Python's repr"},
    "CircularCoordinatesResult": {"hasCoordinate": "nan in theta"},
    "ToroidalCoordinatesResult": {"hasCoordinate": "nan in theta"},
    "EulerCurveResult": {},
    "BootstrapBandResult": {},
    "LandmarkSelectionResult": {},
}

# public in the bytecode, internal in Scala (`private[matlab]`, compiler-generated)
INTERNAL = re.compile(r"\$|^drawnComplex$|^sessionLook$|^noCells$|^withSource$|^withPersistenceThreshold$|^withoutZeroLength$")


def public_methods(name: str, static: bool) -> set[str]:
    modifier = jpype.JClass("java.lang.reflect.Modifier")
    cls = jpype.JClass(f"{FACADE_PACKAGE}.{name}")
    return {
        str(m.getName())
        for m in cls.class_.getMethods()
        if modifier.isStatic(m.getModifiers()) == static
        and m.getDeclaringClass().getName() != "java.lang.Object"
        and not INTERNAL.search(str(m.getName()))
    }


@pytest.mark.parametrize("name", sorted(NOT_WRAPPED))
def test_every_public_facade_method_is_called_by_the_package(name):
    used = set(re.findall(r"\.(\w+)\(", "".join(p.read_text() for p in PACKAGE.glob("*.py"))))
    missing = public_methods(name, static=(name == "TDA4j")) - used - set(NOT_WRAPPED[name])
    assert not missing, f"{name}: wrap these in python/src/tda4j, or list them in NOT_WRAPPED with the reason: {sorted(missing)}"
