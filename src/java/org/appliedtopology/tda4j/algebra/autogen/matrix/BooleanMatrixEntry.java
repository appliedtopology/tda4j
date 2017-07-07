package org.appliedtopology.tda4j.algebra.autogen.matrix;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;


/**
 * This class implements a pair (a, b, c), where a and b are ints and
 * is of type boolean. Note that any instance of this class is immutable, and
 * implements value semantics. Note that the value of the entry is always set 
 * to true, and thus is only suitable for situations where only the true elements
 * are stored.
 * 
 * @author autogen
 *
 */
public class BooleanMatrixEntry {
	private final int row;
	private final int col;
	
	/**
	 * Constructor which initializes the entry.
	 * 
	 * @param row the row of the entry
	 * @param col the column of the entry
	 * @param value the value at the entry
	 */
	public BooleanMatrixEntry(int row, int col) {
		this.row = row;
		this.col = col;
	}
	
	/**
	 * Gets the row of the entry.
	 * 
	 * @return the row
	 */
	public int getRow() {
		return this.row;
	}
	
	/**
	 * Gets the column of the entry.
	 * 
	 * @return the column
	 */
	public int getCol() {
		return this.col;
	}
	
	/**
	 * Gets the value in the matrix
	 * 
	 * @return the value of the entry
	 */
	public boolean getValue() {
		return true;
	}
	
	@Override
	public String toString() {
		return ("(" + row + ", " + col + ", " + 1 + ")");
	}
	
	@Override
	public int hashCode() {
		return new HashCodeBuilder(17, 37).append(row).append(col).toHashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final BooleanMatrixEntry other = (BooleanMatrixEntry) obj;
		return new EqualsBuilder().append(row, other.row).append(col, other.col).isEquals();

	}
};
