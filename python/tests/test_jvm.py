
import jpype
import numpy as np
import pytest

import tda4j
from tda4j import _jvm


def test_the_jar_comes_from_the_environment_variable_first(monkeypatch, tmp_path):
    monkeypatch.setenv("TDA4J_JAR", str(tmp_path / "nothing.jar"))
    with pytest.raises(FileNotFoundError, match="TDA4J_JAR"):
        tda4j.jar_path()
    jar = tmp_path / "x-assembly.jar"
    jar.write_bytes(b"")
    monkeypatch.setenv("TDA4J_JAR", str(jar))
    assert tda4j.jar_path() == jar


def test_starting_a_running_jvm_again_is_harmless_when_it_has_the_jar():
    tda4j.start_jvm(["-Xmx1g"])
    assert jpype.isJVMStarted()
    tda4j.start_jvm()


def test_options_become_a_flat_camel_case_list():
    flat = [str(x) for x in _jvm.options_in({"max_dimension": 2, "include_zero_length": True, "x_y_z": 0.5, "skip": None})]
    assert flat == ["maxDimension", "2", "includeZeroLength", "true", "xYZ", "0.5"]
    flat = [str(x) for x in _jvm.options_in({"max_filtration_value": float("inf"), "a": np.float32(0.25), "b": np.int64(3)})]
    assert flat == ["maxFiltrationValue", "Infinity", "a", "0.25", "b", "3"]
    with pytest.raises(ValueError, match="not an option name"):
        _jvm.options_in({"bad name": 1})


def test_a_java_exception_that_is_not_a_bad_argument_keeps_its_class_name():
    error = _jvm._translate(jpype.JClass("java.lang.IllegalStateException")("boom"))
    assert isinstance(error, tda4j.Tda4jError) and "IllegalStateException" in str(error) and "boom" in str(error)
    assert isinstance(_jvm._translate(jpype.JClass("java.lang.IllegalArgumentException")("bad")), ValueError)


def test_the_package_has_a_version():
    assert isinstance(tda4j.__version__, str) and tda4j.__version__
