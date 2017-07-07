package org.appliedtopology.tda4j.algebra.autogen.pair;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;


/**
 * This class implements a pair (a, b), where a is of type boolean and b 
 * is of type boolean. Note that any instance of this class is immutable, and
 * implements value semantics.
 * 
 * @author autogen
 *
 */
public class BooleanBooleanPair {
	/*
	 * Make the fields first and second final to maintain immutability.
	 */
	protected final boolean first;
	protected final boolean second;
	
	/**
	 * Constructor which initializes the pair.
	 * 
	 * @param first the value of the first component
	 * @param second the value of the second component
	 */
	public BooleanBooleanPair(boolean first, boolean second) {
		this.first = first;
		this.second = second;
	}
	
	/**
	 * Constructor which initializes from another BooleanBooleanPair.
	 * 
	 * @param pair the BooleanBooleanPair to initialize from
	 */
	public BooleanBooleanPair(BooleanBooleanPair pair) {
		this.first = pair.first;
		this.second = pair.second;
	}
	
	/**
	 * Get the first component.
	 * 
	 * @return the first component
	 */
	public boolean getFirst() {
		return this.first;
	}
	
	/**
	 * Get the second component.
	 * 
	 * @return the second component
	 */
	public boolean getSecond() {
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
		final BooleanBooleanPair other = (BooleanBooleanPair) obj;
		return new EqualsBuilder().append(first, other.first).append(second, other.second).isEquals();
	}
}
