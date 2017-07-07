package org.appliedtopology.tda4j.algebra.autogen.array;

import java.util.Arrays;


public class IntArrayQuery {
	/**
	 * This function returns the indices of the values in the
	 * array that are equal to the value to search for.
	 * 
	 * @param array the array to search in
	 * @param value the value to search for
	 * @return the indices of those entries equal to value
	 */
	public static int[] findIndices(int[] array, int value) {
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
	public static boolean contains(int[] array, int value) {
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
	public static int[] removeElement(int[] array, int value) {
		int n = array.length;
		int occurrences = 0;
		for (int i = 0; i < n; i++) {
			if (array[i] == value) {
				occurrences++;
			}
		}
		int[] result = new int[n - occurrences];
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
	public static int[] getMinimumIndices(int[] array, int k) {
		int n = array.length;
		int[] indices = new int[k];
		int[] minValues = new int[k];
		Arrays.fill(indices, -1);
		Arrays.fill(minValues, Integer .MAX_VALUE);
		int minValue = Integer .MAX_VALUE;
		int minIndex = 0;
		for (int j = 0; j < k; j++) {
			minValue = Integer .MAX_VALUE;
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
	public static int[] getArraySubset(int[] array, int[] indices) {
		int[] subArray = new int[indices.length];
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
	public static int[] getArraySubsetComplement(int[] array, int[] indices) {
		Arrays.sort(indices);
		int[] subArray = new int[array.length - indices.length];
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
	
	    
        
    public static int[] filterValuesEqual(int[] array, int value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] == value) {
    			numFound++;
    		}
    	}
    	
    	int[] result = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] == value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesEqual(int[] array, int value) {
    	
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
    
	    
        
    public static int[] filterValuesNotEqual(int[] array, int value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] != value) {
    			numFound++;
    		}
    	}
    	
    	int[] result = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] != value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesNotEqual(int[] array, int value) {
    	
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
    
	    
        
    public static int[] filterValuesLessThan(int[] array, int value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] < value) {
    			numFound++;
    		}
    	}
    	
    	int[] result = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] < value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesLessThan(int[] array, int value) {
    	
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
    
	    
        
    public static int[] filterValuesGreaterThan(int[] array, int value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] > value) {
    			numFound++;
    		}
    	}
    	
    	int[] result = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] > value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesGreaterThan(int[] array, int value) {
    	
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
    
	    
        
    public static int[] filterValuesLessThanOrEqual(int[] array, int value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] <= value) {
    			numFound++;
    		}
    	}
    	
    	int[] result = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] <= value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesLessThanOrEqual(int[] array, int value) {
    	
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
    
	    
        
    public static int[] filterValuesGreaterThanOrEqual(int[] array, int value) {
    	
    	int numFound = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] >= value) {
    			numFound++;
    		}
    	}
    	
    	int[] result = new int[numFound];
    	int index = 0;
    	for (int i = 0; i < array.length; i++) {
    		if (array[i] >= value) {
    			result[index] = array[i];
    			index++;
    		}
    	}
    	
    	return result;
    }
    
    public static int[] filterIndicesGreaterThanOrEqual(int[] array, int value) {
    	
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
