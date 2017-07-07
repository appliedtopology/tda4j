package org.appliedtopology.tda4j.algebra.collections.utility;

import org.appliedtopology.tda4j.algebra.autogen.array.IntArrayManipulation;
import gnu.trove.THashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This class contains various useful functions for manipulating generic collections.
 * 
 * @author Andrew Tausz
 *
 */
public class CollectionsUtility {
	
	/**
	 * This function dumps the contents of an iterable stream into a set.
	 * 
	 * @param <T>
	 * @param stream the Iterable collection to dump
	 * @return a Set<T> containing the contents of the given stream
	 */
	public static <T> Set<T> dumpIterable(final Iterable<T> stream) {
		Set<T> set = new THashSet<T>();
		
		for (T element: stream) {
			set.add(element);
		}
		
		return set;
	}
	
	/**
	 * This function converts a generic array to an ArrayList.
	 * 
	 * @param <T>
	 * @param array the array to convert
	 * @return an ArrayList containing the same contents as the array
	 */
	public static <T> List<T> toList(final T[] array) {
		List<T> list = new ArrayList<T>();
		for (int i = 0; i < array.length; i++) {
			list.add(array[i]);
		}
		return list;
	}
	
	/**
	 * This function extracts the entries of the specified array
	 * which have index in the indices array.
	 * @param <T>
	 * @param array the array of values
	 * @param indices the indices to select
	 * @return a sub-array containing those values at the specified indices
	 */
	public static <T> List<T> getArraySubset(final List<T> array, final int[] indices) {
		List<T> subArray = new ArrayList<T>();
		for (int i = 0; i < indices.length; i++) {
			subArray.add(array.get(indices[i]));
		}
		return subArray;
	}
	
	/**
	 * This function extracts the entries of the array which have index
	 * not in the indices array. In other words it performs the complement
	 * of getArraySubset.
	 * @param <T>
	 * 
	 * @param array the array of values
	 * @param indices the indices to omit
	 * @return a sub-array containing the values excluding those at the specified indices
	 */
	public static <T> List<T> getArraySubsetComplement(final List<T> array, int[] indices) {
		indices = IntArrayManipulation.sortAscending(indices);
		List<T> subArray = new ArrayList<T>();
		//int subArrayIndex = 0;
		int omissionIndex = 0;
		for (int i = 0; i < array.size(); i++) {
			if (omissionIndex < indices.length && i == indices[omissionIndex]) {
				omissionIndex++;
			} else {
				subArray.add(array.get(i));
			}
		}
		return subArray;
	}
}
