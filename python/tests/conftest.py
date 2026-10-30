"""Shared fixtures. The tests need the jar: `TDA4J_JAR`, or one inside the installed package (see tda4j.jar_path)."""

import numpy as np
import pytest


@pytest.fixture(scope="session", autouse=True)
def _jvm():
    import tda4j

    tda4j.jar_path()  # fails with the message that says how to get a jar, rather than skipping the suite
    tda4j.start_jvm()


@pytest.fixture
def square():
    return np.array([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]])


@pytest.fixture
def circle():
    def make(n, radius=1.0):
        t = 2 * np.pi * np.arange(n) / n
        return radius * np.column_stack([np.cos(t), np.sin(t)])

    return make
