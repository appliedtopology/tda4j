package org.appliedtopology.tda4j.algebra.autogen.array;


public class IntArrayMath {
	/*
	 * Vector operations
	 */
	
	public static int[] negate(int[] vector) {
		int n = vector.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = -vector[i];
		}
		return result;
	}
	
	public static int[] add(int[] vector1, int[] vector2) {
		int n = vector1.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] + vector2[i];
		}
		return result;
	}
	
	public static int[] subtract(int[] vector1, int[] vector2) {
		int n = vector1.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] - vector2[i];
		}
		return result;
	}
	
	public static int[] componentwiseMultiply(int[] vector1, int[] vector2) {
		int n = vector1.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] * vector2[i];
		}
		return result;
	}
	
	public static int[] scalarAdd(int[] vector, int scalar) {
		int n = vector.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] + scalar;
		}
		return result;
	}
	
	public static int[] scalarMultiply(int[] vector, int scalar) {
		int n = vector.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] * scalar;
		}
		return result;
	}
	
	public static void accumulate(int[] vector1, int[] vector2) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += vector2[i];
		}
	}
	
	public static void accumulate(int[] vector1, int[] vector2, int coefficient) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += coefficient * vector2[i];
		}
	}
	
	public static void inPlaceMultiply(int[] vector, int scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= scalar;
		}
	}
	
	public static void inPlaceAdd(int[] vector, int scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] += scalar;
		}
	}
	
	public static void inPlaceNegate(int[] vector) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= -1;
		}
	}
	
	/*
	 * Matrix Operations
	 */
	
	public static int[][] add(int[][] matrix1, int[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int[][] result = new int[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] + matrix2[i][j];
			}
		}
		return result;
	}
	
	public static int[][] subtract(int[][] matrix1, int[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int[][] result = new int[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] - matrix2[i][j];
			}
		}
		return result;
	}
	
	public static int[][] componentwiseMultiply(int[][] matrix1, int[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int[][] result = new int[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] * matrix2[i][j];
			}
		}
		return result;
	}
	
	public static int[][] multiply(int[][] matrix1, int[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int p = matrix2[0].length;
		
		int[][] result = new int[m][p];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				for (int k = 0; k < n; k++) {
					result[i][j] += matrix1[i][k] * matrix2[k][j];
				}
			}
		}
		return result;
	}
	
	public static int[] multiply(int[][] matrix, int[] vector) {
		int m = matrix.length;
		int n = matrix[0].length;
		int[] result = new int[m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i] += matrix[i][j] * vector[j];
			}
		}
		return result;
	}
	
	public static int[] multiply(int[] vector, int[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		int[] result = new int[n];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < m; i++) {
				result[j] += vector[i] * matrix[i][j];
			}
		}
		return result;
	}
	
	public static int[][] transpose(int[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		int[][] result = new int[n][m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[j][i] = matrix[i][j];
			}
		}
		return result; 
	}
	
	public static double frobeniusNorm(int[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		int sum = 0;
		int entry = 0;
		int m = matrix.length;
		int n = matrix[0].length;
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				entry = matrix[i][j];
				sum += entry * entry;
			}
		}
		return Math.sqrt(sum);
	}
	
	public static int infinityNorm(int[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		int max = 0;
		int sum = 0;
		int m = matrix.length;
		int n = matrix[0].length;
		for (int i = 0; i < m; i++) {
			sum = 0;
			for (int j = 0; j < n; j++) {
				sum += Math.abs(matrix[i][j]);
			}
			if (sum > max) {
				max = sum;
			}
		}
		return max;
	}
	
	public static int oneNorm(int[][] matrix) {
		return infinityNorm(transpose(matrix));
	}
	
	public static int innerProduct(int[][] matrix1, int[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int sum = 0;
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				sum += matrix1[i][j] * matrix2[i][j];
			}
		}
		return sum;
	}
	
	public static int trace(int[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		int sum = 0;
		int m = Math.min(matrix.length, matrix[0].length);
		for (int i = 0; i < m; i++) {
			sum += matrix[i][i];
		}
		return sum;
	}
	
	public static void normalizeRows(int[][] matrix, int p) {
		int m = matrix.length;
		if (m == 0) {
			return;
		}
		int n = matrix[0].length;
		double norm = 0;
		for (int i = 0; i < m; i++) {
			norm = norm(matrix[i], p);
			if (norm != 0) {
				for (int j = 0; j < n; j++) {
					matrix[i][j] /= norm;
				}
			}
		}
	}
	
	public static void normalizeRows(int[][] matrix) {
		int m = matrix.length;
		if (m == 0) {
			return;
		}
		int n = matrix[0].length;
		double norm = 0;
		for (int i = 0; i < m; i++) {
			norm = norm(matrix[i]);
			if (norm != 0) {
				for (int j = 0; j < n; j++) {
					matrix[i][j] /= norm;
				}
			}
		}
	}
	
	/*
	 * Componentwise functions
	 */
	
	public static int[] abs(int[] vector) {
		int n = vector.length;
		int[] result = new int[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.abs(vector[i]);
		}
		return result;
	}
	
	public static double[] sqrt(int[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.sqrt(vector[i]);
		}
		return result;
	}
	
	public static double[] log(int[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.log(vector[i]);
		}
		return result;
	}
	
	public static double[] reciprocal(int[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			if (vector[i] != 0) {
				result[i] = 1.0 / (double) vector[i];
			}
		}
		return result;
	}
	
	/*
	 * Aggregate functions
	 */
	
	public static int max(int[] vector) {
		int max = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] > max) { 
				max = vector[i];
			}
		}
		return max;
	}
	
	public static int min(int[] vector) {
		int min = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] < min) { 
				min = vector[i];
			}
		}
		return min;
	}
	
	public static int sum(int[] vector) {
		int sum = 0;
		for (int i = 0; i < vector.length; i++) {
			sum += vector[i];
		}
		return sum;
	}
	
	public static int product(int[] vector) {
		int product = 1;
		for (int i = 0; i < vector.length; i++) {
			product *= vector[i];
		}
		return product;
	}
	
	
	public static double mean(int[] vector) {
		if (vector.length == 0) {
			return 0;
		}
		return sum(vector) / (double) vector.length;
	}

	public static double standardDeviation(int[] array) {
		if (array.length <= 1) {
			return 0;
		}
		int n = array.length;
		double mean = mean(array);
		double sd = 0;
		for (int i = 0; i < n; i++) {
			sd += (array[i] - mean) * (array[i] - mean);
		}
		sd = Math.sqrt(sd / ((double) (n - 1)));
		return sd;
	}
	
	public static int[] firstDifferences(int[] vector) {
		int[] result = new int[vector.length - 1];
		for (int i = 1; i < vector.length; i++) {
			result[i - 1] = vector[i] - vector[i - 1];
		}
		return result;
	}
	
	/**
	 * Returns the distance between x1 and x2 using the p-norm, where x1 and x2
	 * are vectors with the same number of components.
	 * 
	 * @param x1
	 * @param x2
	 * @param p
	 * @return
	 */
	public static double distance(int[] x1, int[] x2, int p) {
		double dist = 0;
		for (int i = 0; i < x1.length; i++) {
			dist += Math.pow(Math.abs(x1[i] - x2[i]), p);
		}
		dist = Math.pow(dist, 1.0 / p);
		return dist;
	}
	
	public static double distance(int[] x1, int[] x2) {
		return Math.sqrt(squaredDistance(x1, x2));
	}
	
	public static int squaredDistance(int[] point1, int[] point2) {
		int squaredDistance = 0;
		int difference = 0;
		for (int i = 0; i < point1.length; i++) {
			difference = (point1[i] - point2[i]);
			squaredDistance += difference * difference;
		}
		return squaredDistance;
	}

	public static int squaredDistance(int point1, int point2) {
		int difference = (point1 - point2);
		return difference * difference;
	}
	
	public static double norm(int[] vector, int p) {
		int n = vector.length;
		int norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.pow(Math.abs(vector[i]), p);
		}
		return Math.pow(norm, 1.0 / p);
	}
	
	public static int squaredNorm(int[] vector) {
		int n = vector.length;
		int squaredNorm = 0;
		for (int i = 0; i < n; i++) {
			squaredNorm += vector[i] * vector[i];
		}
		return squaredNorm;
	}
	
	public static double norm(int[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static double twoNorm(int[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static int oneNorm(int[] vector) {
		int n = vector.length;
		int norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.abs(vector[i]);
		}
		return norm;
	}
	
	public static int infinityNorm(int[] vector) {
		return max(abs(vector));
	}
	
	public static int innerProduct(int[] vector1, int[] vector2) {
		int n = vector1.length;
		int innerProduct = 0;
		for (int i = 0; i < n; i++) {
			innerProduct += vector1[i] * vector2[i];
		}
		return innerProduct;
	}
}
