package org.appliedtopology.tda4j.algebra.autogen.pair;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;


/**
 * This class implements a pair (a, b), where a is of type int and b 
 * is of type U. Note that any instance of this class is immutable, and
 * implements value semantics.
 * 
 * @author autogen
 *
 */
public class IntObjectPair<U> {
	/*
	 * Make the fields first and second final to maintain immutability.
	 */
	protected final int first;
	protected final U second;
	
	/**
	 * Constructor which initializes the pair.
	 * 
	 * @param first the value of the first component
	 * @param second the value of the second component
	 */
	public IntObjectPair(int first, U second) {
		this.first = first;
		this.second = second;
	}
	
	/**
	 * Constructor which initializes from another IntObjectPair<U>.
	 * 
	 * @param pair the IntObjectPair<U> to initialize from
	 */
	public IntObjectPair(IntObjectPair<U> pair) {
		this.first = pair.first;
		this.second = pair.second;
	}
	
	/**
	 * Get the first component.
	 * 
	 * @return the first component
	 */
	public int getFirst() {
		return this.first;
	}
	
	/**
	 * Get the second component.
	 * 
	 * @return the second component
	 */
	public U getSecond() {
		return this.second;
	}
	
	@Override
	public String toString() {
		return ("(" + first + ", " + second + ")");
	}
	
	@Override
	public int hashCode() {
		return new HashCodeBuilder(17, 37).append(first).append(second).toHashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final IntObjectPair<?> other = (IntObjectPair<?>) obj;
		return new EqualsBuilder().append(first, other.first).append(second, other.second).isEquals();
	}
}
