package org.appliedtopology.tda4j.algebra.autogen.array;


public class LongArrayMath {
	/*
	 * Vector operations
	 */
	
	public static long[] negate(long[] vector) {
		int n = vector.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = -vector[i];
		}
		return result;
	}
	
	public static long[] add(long[] vector1, long[] vector2) {
		int n = vector1.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] + vector2[i];
		}
		return result;
	}
	
	public static long[] subtract(long[] vector1, long[] vector2) {
		int n = vector1.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] - vector2[i];
		}
		return result;
	}
	
	public static long[] componentwiseMultiply(long[] vector1, long[] vector2) {
		int n = vector1.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] * vector2[i];
		}
		return result;
	}
	
	public static long[] scalarAdd(long[] vector, long scalar) {
		int n = vector.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] + scalar;
		}
		return result;
	}
	
	public static long[] scalarMultiply(long[] vector, long scalar) {
		int n = vector.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] * scalar;
		}
		return result;
	}
	
	public static void accumulate(long[] vector1, long[] vector2) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += vector2[i];
		}
	}
	
	public static void accumulate(long[] vector1, long[] vector2, long coefficient) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += coefficient * vector2[i];
		}
	}
	
	public static void inPlaceMultiply(long[] vector, long scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= scalar;
		}
	}
	
	public static void inPlaceAdd(long[] vector, long scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] += scalar;
		}
	}
	
	public static void inPlaceNegate(long[] vector) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= -1;
		}
	}
	
	/*
	 * Matrix Operations
	 */
	
	public static long[][] add(long[][] matrix1, long[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		long[][] result = new long[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] + matrix2[i][j];
			}
		}
		return result;
	}
	
	public static long[][] subtract(long[][] matrix1, long[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		long[][] result = new long[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] - matrix2[i][j];
			}
		}
		return result;
	}
	
	public static long[][] componentwiseMultiply(long[][] matrix1, long[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		long[][] result = new long[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] * matrix2[i][j];
			}
		}
		return result;
	}
	
	public static long[][] multiply(long[][] matrix1, long[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int p = matrix2[0].length;
		
		long[][] result = new long[m][p];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				for (int k = 0; k < n; k++) {
					result[i][j] += matrix1[i][k] * matrix2[k][j];
				}
			}
		}
		return result;
	}
	
	public static long[] multiply(long[][] matrix, long[] vector) {
		int m = matrix.length;
		int n = matrix[0].length;
		long[] result = new long[m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i] += matrix[i][j] * vector[j];
			}
		}
		return result;
	}
	
	public static long[] multiply(long[] vector, long[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		long[] result = new long[n];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < m; i++) {
				result[j] += vector[i] * matrix[i][j];
			}
		}
		return result;
	}
	
	public static long[][] transpose(long[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		long[][] result = new long[n][m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[j][i] = matrix[i][j];
			}
		}
		return result; 
	}
	
	public static double frobeniusNorm(long[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		long sum = 0;
		long entry = 0;
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
	
	public static long infinityNorm(long[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		long max = 0;
		long sum = 0;
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
	
	public static long oneNorm(long[][] matrix) {
		return infinityNorm(transpose(matrix));
	}
	
	public static long innerProduct(long[][] matrix1, long[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		long sum = 0;
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				sum += matrix1[i][j] * matrix2[i][j];
			}
		}
		return sum;
	}
	
	public static long trace(long[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		long sum = 0;
		int m = Math.min(matrix.length, matrix[0].length);
		for (int i = 0; i < m; i++) {
			sum += matrix[i][i];
		}
		return sum;
	}
	
	public static void normalizeRows(long[][] matrix, long p) {
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
	
	public static void normalizeRows(long[][] matrix) {
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
	
	public static long[] abs(long[] vector) {
		int n = vector.length;
		long[] result = new long[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.abs(vector[i]);
		}
		return result;
	}
	
	public static double[] sqrt(long[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.sqrt(vector[i]);
		}
		return result;
	}
	
	public static double[] log(long[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.log(vector[i]);
		}
		return result;
	}
	
	public static double[] reciprocal(long[] vector) {
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
	
	public static long max(long[] vector) {
		long max = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] > max) { 
				max = vector[i];
			}
		}
		return max;
	}
	
	public static long min(long[] vector) {
		long min = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] < min) { 
				min = vector[i];
			}
		}
		return min;
	}
	
	public static long sum(long[] vector) {
		long sum = 0;
		for (int i = 0; i < vector.length; i++) {
			sum += vector[i];
		}
		return sum;
	}
	
	public static long product(long[] vector) {
		long product = 1;
		for (int i = 0; i < vector.length; i++) {
			product *= vector[i];
		}
		return product;
	}
	
	
	public static double mean(long[] vector) {
		if (vector.length == 0) {
			return 0;
		}
		return sum(vector) / (double) vector.length;
	}

	public static double standardDeviation(long[] array) {
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
	
	public static long[] firstDifferences(long[] vector) {
		long[] result = new long[vector.length - 1];
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
	public static double distance(long[] x1, long[] x2, long p) {
		double dist = 0;
		for (int i = 0; i < x1.length; i++) {
			dist += Math.pow(Math.abs(x1[i] - x2[i]), p);
		}
		dist = Math.pow(dist, 1.0 / p);
		return dist;
	}
	
	public static double distance(long[] x1, long[] x2) {
		return Math.sqrt(squaredDistance(x1, x2));
	}
	
	public static long squaredDistance(long[] point1, long[] point2) {
		long squaredDistance = 0;
		long difference = 0;
		for (int i = 0; i < point1.length; i++) {
			difference = (point1[i] - point2[i]);
			squaredDistance += difference * difference;
		}
		return squaredDistance;
	}

	public static long squaredDistance(long point1, long point2) {
		long difference = (point1 - point2);
		return difference * difference;
	}
	
	public static double norm(long[] vector, long p) {
		int n = vector.length;
		long norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.pow(Math.abs(vector[i]), p);
		}
		return Math.pow(norm, 1.0 / p);
	}
	
	public static long squaredNorm(long[] vector) {
		int n = vector.length;
		long squaredNorm = 0;
		for (int i = 0; i < n; i++) {
			squaredNorm += vector[i] * vector[i];
		}
		return squaredNorm;
	}
	
	public static double norm(long[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static double twoNorm(long[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static long oneNorm(long[] vector) {
		int n = vector.length;
		long norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.abs(vector[i]);
		}
		return norm;
	}
	
	public static long infinityNorm(long[] vector) {
		return max(abs(vector));
	}
	
	public static long innerProduct(long[] vector1, long[] vector2) {
		int n = vector1.length;
		long innerProduct = 0;
		for (int i = 0; i < n; i++) {
			innerProduct += vector1[i] * vector2[i];
		}
		return innerProduct;
	}
}
