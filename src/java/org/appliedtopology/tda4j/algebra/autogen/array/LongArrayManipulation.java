package org.appliedtopology.tda4j.algebra.autogen.array;

import java.util.Collection;


public class LongArrayManipulation {
	public static long[] extractRow(long[][] matrix, int row) {
		long[] result = matrix[row];
		return result;
	}
	
	public static long[] extractColumn(long[][] matrix, int column) {
		int m = matrix[0].length;
		long[] result = new long[m];
		for (int i = 0; i < m; i++) {
			result[i] = matrix[i][column];
		}
		return result;
	}
	
	public static long[][] stackRows(long[] vector1, long[] vector2) {
		int n = vector1.length;
		int m = 2;
		long[][] result = new long[m][n];
		for (int j = 0; j < n; j++) {
			result[0][j] = vector1[j];
			result[1][j] = vector2[j];
		}
		return result;
	}
	
	public static long[][] stackColumns(long[] vector1, long[] vector2) {
		int n = 2;
		int m = vector1.length;
		long[][] result = new long[m][n];
		for (int i = 0; i < m; i++) {
			result[i][0] = vector1[i];
			result[i][1] = vector2[i];
		}
		return result;
	}
	
	public static long[][] stackRows(Collection<long[]> vectors) {
		int m = vectors.size();
		int n = vectors.iterator().next().length;
		long[][] result = new long[m][n];
		int i = 0;
		for (long[] row: vectors) {
			for (int j = 0; j < n; j++) {
				result[i][j] = row[j];
			}
			i++;
		}
		return result;
	}
	
	public static long[][] stackColumns(Collection<long[]> vectors) {
		int m = vectors.size();
		int n = vectors.iterator().next().length;
		long[][] result = new long[n][m];
		int i = 0;
		for (long[] row: vectors) {
			for (int j = 0; j < n; j++) {
				result[j][i] = row[j];
			}
			i++;
		}
		return result;
	}
	
	public static long[] concatenate(long[] vector1, long[] vector2) {
		int n1 = vector1.length;
		int n2 = vector2.length;
		long[] result = new long[n1 + n2];
		for (int i = 0; i < n1; i++) {
			result[i] = vector1[i];
		}
		for (int i = 0; i < n2; i++) {
			result[i + n1] = vector2[i];
		}
		return result;
	}
	
	public static long[] concatenate(Iterable<long[]> vectors) {
		int totalSize = 0;
		for (long[] vector: vectors) {
			totalSize += vector.length;
		}
		long[] result = new long[totalSize];
		int writeIndex = 0;
		for (long[] vector: vectors) {
			for (int i = 0; i < vector.length; i++) {
				result[writeIndex] = vector[i];
				writeIndex++;
			}
		}
		return result;
	}
	
	public static long[][] embedAsRowMatrix(long[] vector) {
		int m = 1;
		int n = vector.length;
		long[][] result = new long[m][n];
		for (int j = 0; j < n; j++) {
			result[0][j] = vector[j];
		}
		return result;
	}
	
	public static long[][] embedAsColumnMatrix(long[] vector) {
		int m = vector.length;
		int n = 1;
		long[][] result = new long[m][n];
		for (int i = 0; i < m; i++) {
			result[i][0] = vector[i];
		}
		return result;
	}
	
	public static long[][] insertRow(long[][] matrix, long[] vector, int index) {
		if (matrix.length == 0) {
			return embedAsRowMatrix(vector);
		}
		int m = matrix.length;
		int n = matrix[0].length;

		long[][] result = new long[m + 1][n];
		for (int i = 0; i < index; i++) {
			for (int j = 0; j < index; j++) {
				result[i][j] = matrix[i][j];
			}
		}
		for (int j = 0; j < n; j++) {
			result[index][j] = vector[j];
		}
		for (int i = index; i < m; i++) {
			for (int j = 0; j < index; j++) {
				result[i + 1][j] = matrix[i][j];
			}
		}
		return result;
	}
	
	public static long[][] insertColumn(long[][] matrix, long[] vector, int index) {
		if (matrix.length == 0) {
			return matrix;
		}
		int m = matrix.length;
		int n = matrix[0].length;

		long[][] result = new long[m][n + 1];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < index; j++) {
				result[i][j] = matrix[i][j];
			}
			
			result[i][index] = vector[i];
			
			for (int j = index; j < n; j++) {
				result[i][j + 1] = matrix[i][j];
			}
		}
		return result;
	}
	
	public static long[][] appendRow(long[][] matrix, long[] vector) {
		return insertRow(matrix, vector, matrix.length);
	}
		
	public static long[][] appendColumn(long[][] matrix, long[] vector) {
		if (matrix.length == 0) {
			return embedAsColumnMatrix(vector);
		}
		return insertColumn(matrix, vector, matrix[0].length);
	}
	
	public static long[][] prependRow(long[] vector, long[][] matrix) {
		return insertRow(matrix, vector, 0);
	}
	
	public static long[][] prependColumn(long[] vector, long[][] matrix) {
		return insertColumn(matrix, vector, 0);
	}
	
	public static long[][] removeRow(long[][] matrix, int index) {
		int m = matrix.length;
		int n = matrix[0].length;
		long[][] result = new long[m - 1][n];
		for (int i = 0; i < index; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix[i][j];
			}
		}
		for (int i = index + 1; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i - 1][j] = matrix[i][j];
			}
		}
		
		return result;
	}
	
	public static long[][] removeColumn(long[][] matrix, int index) {
		int m = matrix.length;
		int n = matrix[0].length;
		long[][] result = new long[m][n - 1];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < index; j++) {
				result[i][j] = matrix[i][j];
			}
			for (int j = index + 1; j < n; j++) {
				result[i][j - 1] = matrix[i][j];
			}
		}
		return result;
	}
	
	public static long[] sortDescending(long[] array) {
		long[] result = new long[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = array[i];
		}
		java.util.Arrays.sort(result);
		reverse(result);
		return result;
	}

	public static long[] sortAscending(long[] array) {
		long[] result = new long[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = array[i];
		}
		java.util.Arrays.sort(result);
		return result;
	}
	
	public static void reverse(long[] array) {
		for (int left = 0, right = array.length - 1; left < right; left++, right--) {
			long temp = array[left];
			array[left] = array[right];
			array[right] = temp;
		}
	}
	
	public static long[] flatten(long[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		long[] array = new long[m * n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				array[i * n + j] = matrix[i][j];
			}
		}
		
		return array;
	}
	
	public static long[][] unflatten(long[] array, int rows, int columns) {
		long[][] matrix = new long[rows][columns];
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < columns; j++) {
				matrix[i][j] = array[i * columns + j];
			}
		}
		return matrix;
	}
}
