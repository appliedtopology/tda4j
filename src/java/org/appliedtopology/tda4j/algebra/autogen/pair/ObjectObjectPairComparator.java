package org.appliedtopology.tda4j.algebra.autogen.pair;

import java.util.Comparator;


/**
 * This class implements the Comparator interface for objects of type ObjectObjectPair<T, U>.
 * The comparison of primitive types is based on the standard comparisons <, >, and the
 * comparison of object types is given by a supplied comparator.
 * 
 * @author autogen
 *
 * @param <T>
 * @param <U>
 */
public class ObjectObjectPairComparator<T, U> implements Comparator<ObjectObjectPair<T, U>> {
		private final Comparator<T> firstComparator;
		
		private final Comparator<U> secondComparator;
		
									/**
			 * Constructor which initializes the class with a comparator for type T and
			 * a comparator for type U.
			 * @param firstComparator
			 * @param secondComparator
			 */
			public ObjectObjectPairComparator(Comparator<T> firstComparator, Comparator<U> secondComparator) {
				this.firstComparator = firstComparator;
				this.secondComparator = secondComparator;
			}
				
	public int compare(ObjectObjectPair<T, U> o1, ObjectObjectPair<T, U> o2) {
				{
			int difference = firstComparator.compare(o1.getFirst(), o2.getFirst());
			
			if (difference != 0) {
				return difference;
			}
		}
		
				
				
		return secondComparator.compare(o1.getSecond(), o2.getSecond());
		
			}
}
