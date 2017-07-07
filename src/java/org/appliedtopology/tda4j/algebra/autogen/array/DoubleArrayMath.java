package org.appliedtopology.tda4j.algebra.autogen.array;


public class DoubleArrayMath {
	/*
	 * Vector operations
	 */
	
	public static double[] negate(double[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = -vector[i];
		}
		return result;
	}
	
	public static double[] add(double[] vector1, double[] vector2) {
		int n = vector1.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] + vector2[i];
		}
		return result;
	}
	
	public static double[] subtract(double[] vector1, double[] vector2) {
		int n = vector1.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] - vector2[i];
		}
		return result;
	}
	
	public static double[] componentwiseMultiply(double[] vector1, double[] vector2) {
		int n = vector1.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] * vector2[i];
		}
		return result;
	}
	
	public static double[] scalarAdd(double[] vector, double scalar) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] + scalar;
		}
		return result;
	}
	
	public static double[] scalarMultiply(double[] vector, double scalar) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] * scalar;
		}
		return result;
	}
	
	public static void accumulate(double[] vector1, double[] vector2) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += vector2[i];
		}
	}
	
	public static void accumulate(double[] vector1, double[] vector2, double coefficient) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += coefficient * vector2[i];
		}
	}
	
	public static void inPlaceMultiply(double[] vector, double scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= scalar;
		}
	}
	
	public static void inPlaceAdd(double[] vector, double scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] += scalar;
		}
	}
	
	public static void inPlaceNegate(double[] vector) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= -1;
		}
	}
	
	/*
	 * Matrix Operations
	 */
	
	public static double[][] add(double[][] matrix1, double[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		double[][] result = new double[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] + matrix2[i][j];
			}
		}
		return result;
	}
	
	public static double[][] subtract(double[][] matrix1, double[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		double[][] result = new double[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] - matrix2[i][j];
			}
		}
		return result;
	}
	
	public static double[][] componentwiseMultiply(double[][] matrix1, double[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		double[][] result = new double[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] * matrix2[i][j];
			}
		}
		return result;
	}
	
	public static double[][] multiply(double[][] matrix1, double[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int p = matrix2[0].length;
		
		double[][] result = new double[m][p];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				for (int k = 0; k < n; k++) {
					result[i][j] += matrix1[i][k] * matrix2[k][j];
				}
			}
		}
		return result;
	}
	
	public static double[] multiply(double[][] matrix, double[] vector) {
		int m = matrix.length;
		int n = matrix[0].length;
		double[] result = new double[m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i] += matrix[i][j] * vector[j];
			}
		}
		return result;
	}
	
	public static double[] multiply(double[] vector, double[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		double[] result = new double[n];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < m; i++) {
				result[j] += vector[i] * matrix[i][j];
			}
		}
		return result;
	}
	
	public static double[][] transpose(double[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		double[][] result = new double[n][m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[j][i] = matrix[i][j];
			}
		}
		return result; 
	}
	
	public static double frobeniusNorm(double[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		double sum = 0;
		double entry = 0;
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
	
	public static double infinityNorm(double[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		double max = 0;
		double sum = 0;
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
	
	public static double oneNorm(double[][] matrix) {
		return infinityNorm(transpose(matrix));
	}
	
	public static double innerProduct(double[][] matrix1, double[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		double sum = 0;
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				sum += matrix1[i][j] * matrix2[i][j];
			}
		}
		return sum;
	}
	
	public static double trace(double[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		double sum = 0;
		int m = Math.min(matrix.length, matrix[0].length);
		for (int i = 0; i < m; i++) {
			sum += matrix[i][i];
		}
		return sum;
	}
	
	public static void normalizeRows(double[][] matrix, double p) {
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
	
	public static void normalizeRows(double[][] matrix) {
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
	
	public static double[] abs(double[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.abs(vector[i]);
		}
		return result;
	}
	
	public static double[] sqrt(double[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.sqrt(vector[i]);
		}
		return result;
	}
	
	public static double[] log(double[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.log(vector[i]);
		}
		return result;
	}
	
	public static double[] reciprocal(double[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			if (vector[i] != 0) {
				result[i] = 1.0 / vector[i];
			}
		}
		return result;
	}
	
	/*
	 * Aggregate functions
	 */
	
	public static double max(double[] vector) {
		double max = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] > max) { 
				max = vector[i];
			}
		}
		return max;
	}
	
	public static double min(double[] vector) {
		double min = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] < min) { 
				min = vector[i];
			}
		}
		return min;
	}
	
	public static double sum(double[] vector) {
		double sum = 0;
		for (int i = 0; i < vector.length; i++) {
			sum += vector[i];
		}
		return sum;
	}
	
	public static double product(double[] vector) {
		double product = 1;
		for (int i = 0; i < vector.length; i++) {
			product *= vector[i];
		}
		return product;
	}
	
	
	public static double mean(double[] vector) {
		if (vector.length == 0) {
			return 0;
		}
		return sum(vector) / (double) vector.length;
	}

	public static double standardDeviation(double[] array) {
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
	
	public static double[] firstDifferences(double[] vector) {
		double[] result = new double[vector.length - 1];
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
	public static double distance(double[] x1, double[] x2, double p) {
		double dist = 0;
		for (int i = 0; i < x1.length; i++) {
			dist += Math.pow(Math.abs(x1[i] - x2[i]), p);
		}
		dist = Math.pow(dist, 1.0 / p);
		return dist;
	}
	
	public static double distance(double[] x1, double[] x2) {
		return Math.sqrt(squaredDistance(x1, x2));
	}
	
	public static double squaredDistance(double[] point1, double[] point2) {
		double squaredDistance = 0;
		double difference = 0;
		for (int i = 0; i < point1.length; i++) {
			difference = (point1[i] - point2[i]);
			squaredDistance += difference * difference;
		}
		return squaredDistance;
	}

	public static double squaredDistance(double point1, double point2) {
		double difference = (point1 - point2);
		return difference * difference;
	}
	
	public static double norm(double[] vector, double p) {
		int n = vector.length;
		double norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.pow(Math.abs(vector[i]), p);
		}
		return Math.pow(norm, 1.0 / p);
	}
	
	public static double squaredNorm(double[] vector) {
		int n = vector.length;
		double squaredNorm = 0;
		for (int i = 0; i < n; i++) {
			squaredNorm += vector[i] * vector[i];
		}
		return squaredNorm;
	}
	
	public static double norm(double[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static double twoNorm(double[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static double oneNorm(double[] vector) {
		int n = vector.length;
		double norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.abs(vector[i]);
		}
		return norm;
	}
	
	public static double infinityNorm(double[] vector) {
		return max(abs(vector));
	}
	
	public static double innerProduct(double[] vector1, double[] vector2) {
		int n = vector1.length;
		double innerProduct = 0;
		for (int i = 0; i < n; i++) {
			innerProduct += vector1[i] * vector2[i];
		}
		return innerProduct;
	}
}
