package org.appliedtopology.tda4j.algebra.autogen.matrix;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;



/**
 * This class implements the functionality of an entry in a sparse vector. 
 * It can be thought of as a pair (index, value).
 * 
 * @author autogen
 *
 * @param <R>
 */
public class ObjectVectorEntry<R> {
	/**
	 * Stores the index of the entry.
	 */
	private final int index;
	
	/**
	 * Stores the value.
	 */
	private final R value;
	
	/**
	 * Constructor which initializes the entry with the specified index and value.
	 * 
	 * @param index
	 * @param value
	 */
	public ObjectVectorEntry(int index, R value) {
		this.index = index;
		this.value = value;
	}
	
	/**
	 * Gets the index of the entry.
	 * 
	 * @return the index
	 */
	public int getIndex() {
		return this.index;
	}

	/**
	 * Gets the value of the entry.
	 * 
	 * @return the value
	 */
	public R getValue() {
		return this.value;
	}
	
	@Override
	public String toString() {
		return ("(" + index + ", " + value + ")");
	}
	
	@Override
	public int hashCode() {
		return new HashCodeBuilder(17, 37).append(index).append(value).toHashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final ObjectVectorEntry<?> other = (ObjectVectorEntry<?>) obj;
		return new EqualsBuilder().append(index, other.index).append(value, other.value).isEquals();

	}
}
