package org.appliedtopology.tda4j.algebra.autogen.matrix;





/**
 * This class implements the functionality of an entry in a sparse matrix. 
 * It can be thought of as a pair (index, value). Note that the value is
 * always true and is therefore not stored.
 * 
 * @author autogen
 *
 * @param <boolean>
 */
public class BooleanVectorEntry {
	/**
	 * Stores the index of the entry.
	 */
	private final int index;
	
	/**
	 * Constructor which initializes the entry with the specified index.
	 * 
	 * @param index
	 */
	public BooleanVectorEntry(int index) {
		this.index = index;
	}
	
	/**
	 * Gets the index of the entry.
	 * 
	 * @return the index
	 */
	public int getIndex() {
		return index;
	}
	
	/**
	 * Gets the value of the entry.
	 * 
	 * @return the value
	 */
	public boolean getValue() {
		return true;
	}
	
	@Override
	public String toString() {
		return ("(" + index + ", " + 1 + ")");
	}
	
	@Override
	public int hashCode() {
		return index;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final BooleanVectorEntry other = (BooleanVectorEntry) obj;
		return (index == other.index);

	}
};
