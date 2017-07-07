package org.appliedtopology.tda4j.algebra.autogen.array;

import java.util.Arrays;


public class FloatArrayQuery {
	/**
	 * This function returns the indices of the values in the
	 * array that are equal to the value to search for.
	 * 
	 * @param array the array to search in
	 * @param value the value to search for
	 * @return the indices of those entries equal to value
	 */
	public static int[] findIndices(float[] array, float value) {
		int matches = 0;
		int n = array.length;
		for (int i = 0; i < n; i++) {
			if (array[i] == value) {
				matches++;
			}
		}
		int[] indices = new int[matches];
		int matchIndex = 0;
		for (int i = 0; i < n; i++) {
			if (array[i] == value) {
				indices[matchIndex++] = i;
			}
		}
		return indices;
	}
	
	/**
	 * This function returns true if the array contains the given value,
	 * and false otherwise. It does not assume anything about the ordering
	 * of the array, and therefore searches the entire array.
	 * 
	 * @param array the array to search
	 * @param value the value to search for
	 * @return true if the array contains the specified value and false otherwise
	 */
	public static boolean contains(float[] array, float value) {
		int n = array.length;
		for (int i = 0; i < n; i++) {
			if (array[i] == value) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * This function returns a new array with the contents of the supplied array with
	 * the specified value removed. Other than the removal of the designated value, 
	 * all of the other elements are the same and are returned in the same relative
	 * order.
	 * 
	 * @param array the array to search
	 * @param value the value to remove
	 * @return a new array with the specified value removed
	 */
	public static float[] removeElement(float[] array, float value) {
		int n = array.length;
		int occurrences = 0;
		for (int i = 0; i < n; i++) {
			if (array[i] == value) {
				occurrences++;
			}
		}
		float[] result = new float[n - occurrences];
		int j = 0;
		for (int i = 0; i < n; i++) {
			if (array[i] != value) {
				result[j] = array[i];
				j++;
			}
		}
		return result;
	}
	
	/**
	 * This function returns the indices of the k lowest entries. Note that
	 * it requires that k be less than or equal to the length of the array. It
	 * returns an array containing the indices of the k smallest elements.
	 * 
	 * @param array
	 * @param k
	 * @return
	 */
	public static int[] getMinimumIndices(float[] array, int k) {
		int n = array.length;
		int[] indices = new int[k];
		float[] minValues = new float[k];
		Arrays.fill(indices, -1);
		Arrays.fill(minValues, Float .MAX_VALUE);
		float minValue = Float .MAX_VALUE;
		int minIndex = 0;
		for (int j = 0; j < k; j++) {
			minValue = Float .MAX_VALUE;
			if (j == 0) {
				for (int i = 0; i < n; i++) {
					if (array[i] < minValue) {
						minValue = array[i];
						minIndex = i;
					}
				}
			} else {
				for (int i = 0; i < n; i++) {
					if (array[i] < minValue && (!IntArrayQuery.contains(indices, i))) {
						minValue = array[i];
						minIndex = i;
					}
				}
			}
			indices[j] = minIndex;
			minValues[j] = minValue;
		}
		return indices;
	}
	
	/**
	 * This function extracts the entries of the specified array
	 * which have index in the indices array.
	 * @param array the array of values
	 * @param indices the indices to select
	 * @return a sub-array containing those values at the specified indices
	 */
	public static float[] getArraySubset(float[] array, int[] indices) {
		float[] subArray = new float[indices.length];
		for (int i = 0; i < indices.length; i++) {
			subArray[i] = array[indices[i]];
		}
		return subArray;
	}
	
	/**
	 * This function extracts the entries of the array which have index
	 * not in the indices array. In other words it performs the complement
	 * of getArraySubset.
	 * 
	 * @param array the array of values
	 * @param indices the indices to omit
	 * @return a sub-array containing the values excluding those at the specified indices
	 */
	public static float[] getArraySubsetComplement(float[] array, int[] indices) {
		Arrays.sort(indices);
		float[] subArray = new float[array.length - indices.length];
		int subArrayIndex = 0;
		int omissionIndex = 0;
		for (int i = 0; i < array.length; i++) {
			if (omissionIndex < indices.length && i == indices[omissionIndex]) {
				omissionIndex++;
			} else {
				subArray[subArrayIndex++] = array[i];
			}
		}
		return subArray;
	}
	
	    
        
    public static float[] filterValuesEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] == value) {
    			numFound++;
    		}
    	}
    	
    	float[] result = new float[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] == value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] == value) {
    			numFound++;
    		}
    	}
    	
    	int[] indices = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] == value) {
    			indices[index] = i;
    			index++;
    		}
    	}
    	
    	return indices;
    }
    
	    
        
    public static float[] filterValuesNotEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] != value) {
    			numFound++;
    		}
    	}
    	
    	float[] result = new float[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] != value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesNotEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] != value) {
    			numFound++;
    		}
    	}
    	
    	int[] indices = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] != value) {
    			indices[index] = i;
    			index++;
    		}
    	}
    	
    	return indices;
    }
    
	    
        
    public static float[] filterValuesLessThan(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] < value) {
    			numFound++;
    		}
    	}
    	
    	float[] result = new float[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] < value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesLessThan(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] < value) {
    			numFound++;
    		}
    	}
    	
    	int[] indices = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] < value) {
    			indices[index] = i;
    			index++;
    		}
    	}
    	
    	return indices;
    }
    
	    
        
    public static float[] filterValuesGreaterThan(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] > value) {
    			numFound++;
    		}
    	}
    	
    	float[] result = new float[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] > value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesGreaterThan(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] > value) {
    			numFound++;
    		}
    	}
    	
    	int[] indices = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] > value) {
    			indices[index] = i;
    			index++;
    		}
    	}
    	
    	return indices;
    }
    
	    
        
    public static float[] filterValuesLessThanOrEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] <= value) {
    			numFound++;
    		}
    	}
    	
    	float[] result = new float[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] <= value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesLessThanOrEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] <= value) {
    			numFound++;
    		}
    	}
    	
    	int[] indices = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] <= value) {
    			indices[index] = i;
    			index++;
    		}
    	}
    	
    	return indices;
    }
    
	    
        
    public static float[] filterValuesGreaterThanOrEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] >= value) {
    			numFound++;
    		}
    	}
    	
    	float[] result = new float[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] >= value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesGreaterThanOrEqual(float[] array, float value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] >= value) {
    			numFound++;
    		}
    	}
    	
    	int[] indices = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] >= value) {
    			indices[index] = i;
    			index++;
    		}
    	}
    	
    	return indices;
    }
    
		
}
