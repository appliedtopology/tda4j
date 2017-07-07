package org.appliedtopology.tda4j.algebra.utility;

public class MathUtility {
	/**
	 * This function returns the multiplicative inverses of the integers
	 * [0, 1, ..., p-1] in mod p arithmetic. Note that the 0-th index is 
	 * simply set to 0 for convenience so that the inverse of n is in the n-th
	 * component of the returned array.
	 * 
	 * TODO:
	 * This is a somewhat inefficient way to compute the inverses, however it works
	 * fine for small p.
	 * 
	 * @param p
	 * @return an array containing the multiplicative inverses in the field Z/pZ
	 */
	public static int[] modularInverses(int p) {
		int[] inverses = new int[p];
		for (int i = 1; i < p; i++) {
			int inverse = 0;
			for (int j = 1; j < p; j++) {
				if (((j * i) % p) == 1) {
					inverse = j;
					break;
				}
			}

			if (inverse == 0) {
				throw new IllegalArgumentException(p + " is not a prime.");
			}

			inverses[i] = inverse;
		}
		
		return inverses;
	}
}
