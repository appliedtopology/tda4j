package org.appliedtopology.tda4j.algebra.autogen.matrix;



/**
 * This interface defines the functionality of a matrix over the type R.
 * 
 * @author autogen
 *
 * @param <R>
 */
public interface ObjectAbstractMatrix<R> extends Iterable<ObjectMatrixEntry<R>> {
	/**
	 * This function returns a new vector of the same type as the current one with
	 * the specified size.
	 * 
	 * @param rows the number of rows in the new matrix
	 * @param columns the number of columns in the new matrix
	 * @return a new vector of the same type as the current
	 */
	public abstract ObjectAbstractMatrix<R> like(int rows, int columns);
	
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
	public abstract R get(int row, int column);
	
	/**
	 * This function sets the element at the specified row and column
	 * 
	 * @param row the row of the entry to set
	 * @param column the column of the entry to set
	 * @param value the value at the given row and column
	 */
	public abstract void set(int row, int column, R value);
	
	}
