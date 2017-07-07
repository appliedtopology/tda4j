package org.appliedtopology.tda4j.algebra.collections.utility;

import java.util.Comparator;

/**
 * This Comparator simply produces the reverse comparison as the reference
 * comparator.
 * 
 * @author Andrew Tausz
 *
 * @param <T>
 */
public class ReversedComparator<T> implements Comparator<T> {
	/**
	 * This is the original comparator.
	 */
	private final Comparator<T> forwardComparator;
	
	/**
	 * This constructor initializes the class with the forward comparator.
	 * 
	 * @param forwardComparator the forward comparator
	 */
	public ReversedComparator(final Comparator<T> forwardComparator) {
		this.forwardComparator = forwardComparator;
	}
	
	public int compare(T arg0, T arg1) {
		return -this.forwardComparator.compare(arg0, arg1);
	}
}
