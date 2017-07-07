package org.appliedtopology.tda4j.algebra.autogen.array;

import java.util.Collection;


public class FloatArrayManipulation {
	public static float[] extractRow(float[][] matrix, int row) {
		float[] result = matrix[row];
		return result;
	}
	
	public static float[] extractColumn(float[][] matrix, int column) {
		int m = matrix[0].length;
		float[] result = new float[m];
		for (int i = 0; i < m; i++) {
			result[i] = matrix[i][column];
		}
		return result;
	}
	
	public static float[][] stackRows(float[] vector1, float[] vector2) {
		int n = vector1.length;
		int m = 2;
		float[][] result = new float[m][n];
		for (int j = 0; j < n; j++) {
			result[0][j] = vector1[j];
			result[1][j] = vector2[j];
		}
		return result;
	}
	
	public static float[][] stackColumns(float[] vector1, float[] vector2) {
		int n = 2;
		int m = vector1.length;
		float[][] result = new float[m][n];
		for (int i = 0; i < m; i++) {
			result[i][0] = vector1[i];
			result[i][1] = vector2[i];
		}
		return result;
	}
	
	public static float[][] stackRows(Collection<float[]> vectors) {
		int m = vectors.size();
		int n = vectors.iterator().next().length;
		float[][] result = new float[m][n];
		int i = 0;
		for (float[] row: vectors) {
			for (int j = 0; j < n; j++) {
				result[i][j] = row[j];
			}
			i++;
		}
		return result;
	}
	
	public static float[][] stackColumns(Collection<float[]> vectors) {
		int m = vectors.size();
		int n = vectors.iterator().next().length;
		float[][] result = new float[n][m];
		int i = 0;
		for (float[] row: vectors) {
			for (int j = 0; j < n; j++) {
				result[j][i] = row[j];
			}
			i++;
		}
		return result;
	}
	
	public static float[] concatenate(float[] vector1, float[] vector2) {
		int n1 = vector1.length;
		int n2 = vector2.length;
		float[] result = new float[n1 + n2];
		for (int i = 0; i < n1; i++) {
			result[i] = vector1[i];
		}
		for (int i = 0; i < n2; i++) {
			result[i + n1] = vector2[i];
		}
		return result;
	}
	
	public static float[] concatenate(Iterable<float[]> vectors) {
		int totalSize = 0;
		for (float[] vector: vectors) {
			totalSize += vector.length;
		}
		float[] result = new float[totalSize];
		int writeIndex = 0;
		for (float[] vector: vectors) {
			for (int i = 0; i < vector.length; i++) {
				result[writeIndex] = vector[i];
				writeIndex++;
			}
		}
		return result;
	}
	
	public static float[][] embedAsRowMatrix(float[] vector) {
		int m = 1;
		int n = vector.length;
		float[][] result = new float[m][n];
		for (int j = 0; j < n; j++) {
			result[0][j] = vector[j];
		}
		return result;
	}
	
	public static float[][] embedAsColumnMatrix(float[] vector) {
		int m = vector.length;
		int n = 1;
		float[][] result = new float[m][n];
		for (int i = 0; i < m; i++) {
			result[i][0] = vector[i];
		}
		return result;
	}
	
	public static float[][] insertRow(float[][] matrix, float[] vector, int index) {
		if (matrix.length == 0) {
			return embedAsRowMatrix(vector);
		}
		int m = matrix.length;
		int n = matrix[0].length;

		float[][] result = new float[m + 1][n];
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
	
	public static float[][] insertColumn(float[][] matrix, float[] vector, int index) {
		if (matrix.length == 0) {
			return matrix;
		}
		int m = matrix.length;
		int n = matrix[0].length;

		float[][] result = new float[m][n + 1];
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
	
	public static float[][] appendRow(float[][] matrix, float[] vector) {
		return insertRow(matrix, vector, matrix.length);
	}
		
	public static float[][] appendColumn(float[][] matrix, float[] vector) {
		if (matrix.length == 0) {
			return embedAsColumnMatrix(vector);
		}
		return insertColumn(matrix, vector, matrix[0].length);
	}
	
	public static float[][] prependRow(float[] vector, float[][] matrix) {
		return insertRow(matrix, vector, 0);
	}
	
	public static float[][] prependColumn(float[] vector, float[][] matrix) {
		return insertColumn(matrix, vector, 0);
	}
	
	public static float[][] removeRow(float[][] matrix, int index) {
		int m = matrix.length;
		int n = matrix[0].length;
		float[][] result = new float[m - 1][n];
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
	
	public static float[][] removeColumn(float[][] matrix, int index) {
		int m = matrix.length;
		int n = matrix[0].length;
		float[][] result = new float[m][n - 1];
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
	
	public static float[] sortDescending(float[] array) {
		float[] result = new float[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = array[i];
		}
		java.util.Arrays.sort(result);
		reverse(result);
		return result;
	}

	public static float[] sortAscending(float[] array) {
		float[] result = new float[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = array[i];
		}
		java.util.Arrays.sort(result);
		return result;
	}
	
	public static void reverse(float[] array) {
		for (int left = 0, right = array.length - 1; left < right; left++, right--) {
			float temp = array[left];
			array[left] = array[right];
			array[right] = temp;
		}
	}
	
	public static float[] flatten(float[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		float[] array = new float[m * n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				array[i * n + j] = matrix[i][j];
			}
		}
		
		return array;
	}
	
	public static float[][] unflatten(float[] array, int rows, int columns) {
		float[][] matrix = new float[rows][columns];
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < columns; j++) {
				matrix[i][j] = array[i * columns + j];
			}
		}
		return matrix;
	}
}
