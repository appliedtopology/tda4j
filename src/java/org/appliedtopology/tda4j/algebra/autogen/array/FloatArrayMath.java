package org.appliedtopology.tda4j.algebra.autogen.array;


public class FloatArrayMath {
	/*
	 * Vector operations
	 */
	
	public static float[] negate(float[] vector) {
		int n = vector.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = -vector[i];
		}
		return result;
	}
	
	public static float[] add(float[] vector1, float[] vector2) {
		int n = vector1.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] + vector2[i];
		}
		return result;
	}
	
	public static float[] subtract(float[] vector1, float[] vector2) {
		int n = vector1.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] - vector2[i];
		}
		return result;
	}
	
	public static float[] componentwiseMultiply(float[] vector1, float[] vector2) {
		int n = vector1.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector1[i] * vector2[i];
		}
		return result;
	}
	
	public static float[] scalarAdd(float[] vector, float scalar) {
		int n = vector.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] + scalar;
		}
		return result;
	}
	
	public static float[] scalarMultiply(float[] vector, float scalar) {
		int n = vector.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = vector[i] * scalar;
		}
		return result;
	}
	
	public static void accumulate(float[] vector1, float[] vector2) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += vector2[i];
		}
	}
	
	public static void accumulate(float[] vector1, float[] vector2, float coefficient) {
		int n = vector1.length;
		for (int i = 0; i < n; i++) {
			vector1[i] += coefficient * vector2[i];
		}
	}
	
	public static void inPlaceMultiply(float[] vector, float scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= scalar;
		}
	}
	
	public static void inPlaceAdd(float[] vector, float scalar) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] += scalar;
		}
	}
	
	public static void inPlaceNegate(float[] vector) {
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			vector[i] *= -1;
		}
	}
	
	/*
	 * Matrix Operations
	 */
	
	public static float[][] add(float[][] matrix1, float[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		float[][] result = new float[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] + matrix2[i][j];
			}
		}
		return result;
	}
	
	public static float[][] subtract(float[][] matrix1, float[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		float[][] result = new float[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] - matrix2[i][j];
			}
		}
		return result;
	}
	
	public static float[][] componentwiseMultiply(float[][] matrix1, float[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		float[][] result = new float[m][n];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i][j] = matrix1[i][j] * matrix2[i][j];
			}
		}
		return result;
	}
	
	public static float[][] multiply(float[][] matrix1, float[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		int p = matrix2[0].length;
		
		float[][] result = new float[m][p];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				for (int k = 0; k < n; k++) {
					result[i][j] += matrix1[i][k] * matrix2[k][j];
				}
			}
		}
		return result;
	}
	
	public static float[] multiply(float[][] matrix, float[] vector) {
		int m = matrix.length;
		int n = matrix[0].length;
		float[] result = new float[m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[i] += matrix[i][j] * vector[j];
			}
		}
		return result;
	}
	
	public static float[] multiply(float[] vector, float[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		float[] result = new float[n];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < m; i++) {
				result[j] += vector[i] * matrix[i][j];
			}
		}
		return result;
	}
	
	public static float[][] transpose(float[][] matrix) {
		int m = matrix.length;
		int n = matrix[0].length;
		float[][] result = new float[n][m];
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				result[j][i] = matrix[i][j];
			}
		}
		return result; 
	}
	
	public static double frobeniusNorm(float[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		float sum = 0;
		float entry = 0;
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
	
	public static float infinityNorm(float[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		float max = 0;
		float sum = 0;
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
	
	public static float oneNorm(float[][] matrix) {
		return infinityNorm(transpose(matrix));
	}
	
	public static float innerProduct(float[][] matrix1, float[][] matrix2) {
		int m = matrix1.length;
		int n = matrix1[0].length;
		float sum = 0;
		for (int i = 0; i < m; i++) {
			for (int j = 0; j < n; j++) {
				sum += matrix1[i][j] * matrix2[i][j];
			}
		}
		return sum;
	}
	
	public static float trace(float[][] matrix) {
		if (matrix.length == 0) {
			return 0;
		}
		float sum = 0;
		int m = Math.min(matrix.length, matrix[0].length);
		for (int i = 0; i < m; i++) {
			sum += matrix[i][i];
		}
		return sum;
	}
	
	public static void normalizeRows(float[][] matrix, float p) {
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
	
	public static void normalizeRows(float[][] matrix) {
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
	
	public static float[] abs(float[] vector) {
		int n = vector.length;
		float[] result = new float[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.abs(vector[i]);
		}
		return result;
	}
	
	public static double[] sqrt(float[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.sqrt(vector[i]);
		}
		return result;
	}
	
	public static double[] log(float[] vector) {
		int n = vector.length;
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = Math.log(vector[i]);
		}
		return result;
	}
	
	public static double[] reciprocal(float[] vector) {
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
	
	public static float max(float[] vector) {
		float max = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] > max) { 
				max = vector[i];
			}
		}
		return max;
	}
	
	public static float min(float[] vector) {
		float min = vector[0];
		int n = vector.length;
		for (int i = 0; i < n; i++) {
			if (vector[i] < min) { 
				min = vector[i];
			}
		}
		return min;
	}
	
	public static float sum(float[] vector) {
		float sum = 0;
		for (int i = 0; i < vector.length; i++) {
			sum += vector[i];
		}
		return sum;
	}
	
	public static float product(float[] vector) {
		float product = 1;
		for (int i = 0; i < vector.length; i++) {
			product *= vector[i];
		}
		return product;
	}
	
	
	public static double mean(float[] vector) {
		if (vector.length == 0) {
			return 0;
		}
		return sum(vector) / (double) vector.length;
	}

	public static double standardDeviation(float[] array) {
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
	
	public static float[] firstDifferences(float[] vector) {
		float[] result = new float[vector.length - 1];
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
	public static double distance(float[] x1, float[] x2, float p) {
		double dist = 0;
		for (int i = 0; i < x1.length; i++) {
			dist += Math.pow(Math.abs(x1[i] - x2[i]), p);
		}
		dist = Math.pow(dist, 1.0 / p);
		return dist;
	}
	
	public static double distance(float[] x1, float[] x2) {
		return Math.sqrt(squaredDistance(x1, x2));
	}
	
	public static float squaredDistance(float[] point1, float[] point2) {
		float squaredDistance = 0;
		float difference = 0;
		for (int i = 0; i < point1.length; i++) {
			difference = (point1[i] - point2[i]);
			squaredDistance += difference * difference;
		}
		return squaredDistance;
	}

	public static float squaredDistance(float point1, float point2) {
		float difference = (point1 - point2);
		return difference * difference;
	}
	
	public static double norm(float[] vector, float p) {
		int n = vector.length;
		float norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.pow(Math.abs(vector[i]), p);
		}
		return Math.pow(norm, 1.0 / p);
	}
	
	public static float squaredNorm(float[] vector) {
		int n = vector.length;
		float squaredNorm = 0;
		for (int i = 0; i < n; i++) {
			squaredNorm += vector[i] * vector[i];
		}
		return squaredNorm;
	}
	
	public static double norm(float[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static double twoNorm(float[] vector) {
		return Math.sqrt(squaredNorm(vector));
	}
	
	public static float oneNorm(float[] vector) {
		int n = vector.length;
		float norm = 0;
		for (int i = 0; i < n; i++) {
			norm += Math.abs(vector[i]);
		}
		return norm;
	}
	
	public static float infinityNorm(float[] vector) {
		return max(abs(vector));
	}
	
	public static float innerProduct(float[] vector1, float[] vector2) {
		int n = vector1.length;
		float innerProduct = 0;
		for (int i = 0; i < n; i++) {
			innerProduct += vector1[i] * vector2[i];
		}
		return innerProduct;
	}
}
