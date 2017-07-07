package org.appliedtopology.tda4j.algebra.autogen.pair;

import java.util.Comparator;


/**
 * This class implements the Comparator interface for objects of type ObjectDoublePair<T>.
 * The comparison of primitive types is based on the standard comparisons <, >, and the
 * comparison of object types is given by a supplied comparator.
 * 
 * @author autogen
 *
 * @param <T>
 * @param <double>
 */
public class ObjectDoublePairComparator<T> implements Comparator<ObjectDoublePair<T>> {
		private final Comparator<T> firstComparator;
		
		
									/**
			 * Constructor which initializes the class with a comparator for type T.
			 * @param firstComparator
			 */
			public ObjectDoublePairComparator(Comparator<T> firstComparator) {
				this.firstComparator = firstComparator;
			}
				
	public int compare(ObjectDoublePair<T> o1, ObjectDoublePair<T> o2) {
				{
			int difference = firstComparator.compare(o1.getFirst(), o2.getFirst());
			
			if (difference != 0) {
				return difference;
			}
		}
		
				
				{
			double difference = o1.getSecond() - o2.getSecond();
			if (difference > 0) {
				return 1;
			} else if (difference < 0) {
				return -1;
			}
			return 0;
		}
		
			}
}
