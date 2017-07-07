package org.appliedtopology.tda4j.algebra.autogen.array;

import java.util.Collection;


public class DoubleArrayManipulation {
	public static double[] extractRow(double[][] matrix, int row) {
		double[] result = matrix[row];
		return result;
	}
	
	public static double[] extractColumn(double[][] matrix, int column) {
		int m = matrix[0].length;
		double[] result = new double[m];
		for (int i = 0; i < m; i++) {
			result[i] = matrix[i][column];
		}
		return result;
	}
	
	public static double[][] stackRows(double[] vector1, double[] vector2) {
		int n = vector1.length;
		int m = 2;
		double[][] result = new double[m][n];
		for (int j = 0; j < n; j++) {
			result[0][j] = vector1[j];
			result[1][j] = vector2[j];
		}
		return result;
	}
	
	public static double[][] stackColumns(double[] vector1, double[] vector2) {
		int n = 2;
		int m = vector1.length;
		double[][] result = new double[m][n];
		for (int i = 0; i < m; i++) {
			result[i][0] = vector1[i];
			result[i][1] = vector2[i];
		}
		return result;
	}
	
	public static double[][] stackRows(Collection<double[]> vectors) {
		int m = vectors.size();
		int n = vectors.iterator().next().length;
		double[][] result = new double[m][n];
		int i = 0;
		for (double[] row: vectors) {
			for (int j = 0; j < n; j++) {
				result[i][j] = row[j];
			}
			i++;
		}
		return result;
	}
	
	public static double[][] stackColumns(Collection<double[]> vectors) {
		int m = vectors.size();
		int n = vectors.iterator().next().length;
		double[][] result = new double[n][m];
		int i = 0;
		for (double[] row: vectors) {
			for (int j = 0; j < n; j++) {
				result[j][i] = row[j];
			}
			i++;
		}
		return result;
	}
	
	public static double[] concatenate(double[] vector1, double[] vector2) {
		int n1 = vector1.length;
		int n2 = vector2.length;
		double[] result = new double[n1 + n2];
		for (int i = 0; i < n1; i++) {
			result[i] = vector1[i];
		}
		for (int i = 0; i < n2; i++) {
			result[i + n1] = vector2[i];
		}
		return result;
	}
	
	public static double[] concatenate(Iterable<double[]> vectors) {
		int totalSize = 0;
		for (double[] vector: vectors) {
			totalSize += vector.length;
		}
		double[] result = new double[totalSize];
		int writeIndex = 0;
		for (double[] vector: vectors) {
			for (int i = 0; i < vector.length; i++) {
				result[writeIndex] = vector[i];
				writeIndex++;
			}
		}
		return result;
	}
	
	public static double[][] embedAsRowMatrix(double[] vector) {
		int m = 1;
		int n = vector.length;
		double[][] result = new double[m][n];
		for (int j = 0; j < n; j++) {
			result[0][j] = vector[j];
		}
		return result;
	}
	
	public static double[][] embedAsColumnMatrix(double[] vector) {
		int m = vector.length;
		int n = 1;
		double[][] result = new double[m][n];
		for (int i = 0; i < m; i++) {
			result[i][0] = vector[i];
		}
		return result;
	}
	
	public static double[][] insertRow(double[][] matrix, double[] vector, int index) {
		if (matrix.length == 0) {
			return embedAsRowMatrix(vector);
		}
		int m = matrix.length;
		int n = matrix[0].length;

		double[][] result = new double[m + 1][n];
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
	
	public static double[][] insertColumn(double[][] matrix, double[] vector, int index) {
		if (matrix.length == 0) {
			return matrix;
		}
		int m = matrix.length;
		int n = matrix[0].length;

		double[][] result = new double[m][n + 1];
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
	
	public static double[][] appendRow(double[][] matrix, double[] vector) {
		return insertRow(matrix, vector, matrix.length);
	}
		
	public static double[][] appendColumn(double[][] matrix, double[] vector) {
		if (matrix.length == 0) {
			return embedAsColumnMatrix(vector);
		}
		return insertColumn(matrix, vector, matrix[0].length);
	}
	
	public static double[][] prependRow(double[] vector, double[][] matrix) {
		return insertRow(matrix, vector, 0);
	}
	
	public static double[][] prependColumn(double[] vector, double[][] matrix) {
		return insertColumn(matrix, vector, 0);
	}
	
	public static double[][] removeRow(double[][] matrix, int index) {
		int m = matrix.length;
		int n = matrix[0].length;
		double[][] result = new double[m - 1][n];
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
	
	public static double[][] removeColumn(double[][] matrix, int index) {
		int m = matrix.length;
		int n = matrix[0].length;
		double[][] result = new double[m][n - 1];
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
	
	public static double[] sortDescending(double[] array) {
		double[] result = new double[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = array[i];
		}
		java.util.Arrays.sort(result);
		reverse(result);
		return result;
	}

	public static double[] sortAscending(double[] array) {
		double[] result = new double[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = array[i];
		}
		java.util.Arrays.sort(result);
		return result;
	}
	
	public static void reverse(double[] array) {
		for (int left = 0, right = array.length - 1; left < right; left++, right--) {
			double temp = array[left];
			array[left] = array[right];
			array[right] = temp;
		}
	}
	
	public static double[] flatten(double[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		double[] array = new double[m * n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				array[i * n + j] = matrix[i][j];
			}
		}
		
		return array;
	}
	
	public static double[][] unflatten(double[] array, int rows, int columns) {
		double[][] matrix = new double[rows][columns];
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < columns; j++) {
				matrix[i][j] = array[i * columns + j];
			}
		}
		return matrix;
	}
}
