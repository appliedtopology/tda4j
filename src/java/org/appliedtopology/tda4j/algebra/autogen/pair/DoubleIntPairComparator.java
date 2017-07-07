package org.appliedtopology.tda4j.algebra.autogen.pair;

import java.util.Comparator;


/**
 * This class implements the Comparator interface for objects of type DoubleIntPair.
 * The comparison of primitive types is based on the standard comparisons <, >, and the
 * comparison of object types is given by a supplied comparator.
 * 
 * @author autogen
 *
 * @param <double>
 * @param <int>
 */
public class DoubleIntPairComparator implements Comparator<DoubleIntPair> {
		
		
									/**
			 * Default constructor.
			 */
			public DoubleIntPairComparator() {}
	
				
	public int compare(DoubleIntPair o1, DoubleIntPair o2) {
				{
			double difference = o1.getFirst() - o2.getFirst();
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
