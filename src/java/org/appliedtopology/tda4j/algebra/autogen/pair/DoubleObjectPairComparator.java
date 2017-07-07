package org.appliedtopology.tda4j.algebra.autogen.pair;

import java.util.Comparator;


/**
 * This class implements the Comparator interface for objects of type DoubleObjectPair<U>.
 * The comparison of primitive types is based on the standard comparisons <, >, and the
 * comparison of object types is given by a supplied comparator.
 * 
 * @author autogen
 *
 * @param <double>
 * @param <U>
 */
public class DoubleObjectPairComparator<U> implements Comparator<DoubleObjectPair<U>> {
		
		private final Comparator<U> secondComparator;
		
									/**
			 * Constructor which initializes the class with a comparator for type U.
			 * @param secondComparator
			 */
			public DoubleObjectPairComparator(Comparator<U> secondComparator) {
				this.secondComparator = secondComparator;
			}
				
	public int compare(DoubleObjectPair<U> o1, DoubleObjectPair<U> o2) {
				{
			double difference = o1.getFirst() - o2.getFirst();
			if (difference > 0) {
				return 1;
			} else if (difference < 0) {
				return -1;
			}
		}
				
				
		return secondComparator.compare(o1.getSecond(), o2.getSecond());
		
			}
}
