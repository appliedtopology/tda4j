package org.appliedtopology.tda4j.algebra.array;

import java.lang.reflect.Array;

public class ObjectArrayUtility {
	@SuppressWarnings("unchecked")
	public static <T> T[] createArray(int length, T initializer) {
		//return ((T[]) new Object[length]);
		return ((T[]) Array.newInstance(initializer.getClass(), length));
	}

	@SuppressWarnings("unchecked")
	public static <T> T[][] createMatrix(int rows, int columns, T initializer) {
		//return ((T[]) new Object[length]);
		return ((T[][]) Array.newInstance(initializer.getClass(), new int[]{rows, columns}));
	}
}
