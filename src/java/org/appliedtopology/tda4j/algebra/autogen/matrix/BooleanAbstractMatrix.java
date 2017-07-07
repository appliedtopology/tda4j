package org.appliedtopology.tda4j.algebra.autogen.matrix;




/**
 * This interface defines the functionality of a matrix over the type boolean.
 * 
 * @author autogen
 *
 * @param <boolean>
 */
public interface BooleanAbstractMatrix extends Iterable<BooleanMatrixEntry> {
	/**
	 * This function returns a new vector of the same type as the current one with
	 * the specified size.
	 * 
	 * @param rows the number of rows in the new matrix
	 * @param columns the number of columns in the new matrix
	 * @return a new vector of the same type as the current
	 */
	public abstract BooleanAbstractMatrix like(int rows, int columns);

	/**
	 * This function returns the number of rows in the matrix.
	 * 
	 * @return the number of rows
	 */
	public abstract int getNumRows();

	/**
	 * This function returns the number of columns in the matrix.
	 * 
	 * @return the number of columns
	 */
	public abstract int getNumColumns();

	/**
	 * This function gets the element at the specified row and column.
	 * 
	 * @param row the row of the entry to get
	 * @param column the column of the entry to get
	 * @return the value at the given row and column
	 */
	public abstract boolean get(int row, int column);

	/**
	 * This function sets the element at the specified row and column
	 * 
	 * @param row the row of the entry to set
	 * @param column the column of the entry to set
	 * @param value the value at the given row and column
	 */
	public abstract void set(int row, int column, boolean value);

	/**
	 * This function computes the matrix-vector product of the current
	 * matrix with the supplied vector.
	 * 
	 * @param vector the vector to compute the matrix-vector product with
	 * @return the matrix-vector product
	 */
	public abstract BooleanAbstractVector multiply(BooleanAbstractVector vector);
};
