package org.appliedtopology.tda4j.algebra.autogen.pair;

import java.util.Comparator;


/**
 * This class implements the Comparator interface for objects of type IntIntPair.
 * The comparison of primitive types is based on the standard comparisons <, >, and the
 * comparison of object types is given by a supplied comparator.
 * 
 * @author autogen
 *
 * @param <int>
 * @param <int>
 */
public class IntIntPairComparator implements Comparator<IntIntPair> {
		
		
									/**
			 * Default constructor.
			 */
			public IntIntPairComparator() {}
	
				
	public int compare(IntIntPair o1, IntIntPair o2) {
				{
			int difference = o1.getFirst() - o2.getFirst();
			if (difference > 0) {
				return 1;
			} else if (difference < 0) {
				return -1;
			}
		}
				
				{
			int difference = o1.getSecond() - o2.getSecond();
			if (difference > 0) {
				return 1;
			} else if (difference < 0) {
				return -1;
			}
			return 0;
		}
		
			}
}
