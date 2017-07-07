package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectIterator;

/**
 * This class provides a sparse-matrix implementation of the BooleanAbstractMatrix
 * interface. It only stores non-false elements, and is therefore suited for applications
 * where most of the entries in the matrix are false.
 * 
 * @author autogen
 *
 */
public class BooleanSparseMatrix implements BooleanAbstractMatrix {
	/**
	 * We use a row-wise storage scheme. The variable map stores
	 * the rows of the matrix on an as-needed basis. Each row is a 
	 * BooleanSparseVector. This choice was made so that matrix-vector
	 * products can be computed very quickly.
	 */
	protected final TIntObjectHashMap<BooleanSparseVector> map = new TIntObjectHashMap<BooleanSparseVector>();

	/**
	 * The number of rows in the matrix.
	 */
	protected final int rows;

	/**
	 * The number of columns in the matrix.
	 */
	protected final int columns;

	/**
	 * This constructor initializes the class with the specified number
	 * of rows and columns.
	 * 
	 * @param rows the number of rows in the matrix
	 * @param columns the number of columns in the matrix
	 */
	public BooleanSparseMatrix(int rows, int columns) {
		this.rows = rows;
		this.columns = columns;
	}

	public boolean get(int row, int column) {
		if (!this.map.contains(row)) {
			return false;
		}
		return this.map.get(row).get(column);
	}

	public int getNumColumns() {
		return this.columns;
	}

	public int getNumRows() {
		return this.rows;
	}

	public Iterator<BooleanMatrixEntry> iterator() {
		return new BooleanSparseMatrixIterator(this);
	}

	public BooleanAbstractMatrix like(int rows, int columns) {
		return new BooleanSparseMatrix(rows, columns);
	}

	/**
	 * This function returns the number of non-zero elements in the matrix.
	 * 
	 * @return the number of non-zero elements
	 */
	public int getNumNonzeroElements() {
		int num = 0;
		for (TIntObjectIterator<BooleanSparseVector> iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			num += iterator.value().getNumNonzeroElements();
		}
		return num;
	}

	public BooleanAbstractVector multiply(BooleanAbstractVector vector) {
		BooleanAbstractVector result = new BooleanSparseVector(this.rows);
		boolean innerProductValue = false;
		for (TIntObjectIterator<BooleanSparseVector> iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			innerProductValue = iterator.value().innerProduct(vector);
			if (innerProductValue) {
				result.set(iterator.key(), innerProductValue);
			}
		}

		return result;
	}

	public void set(int row, int column, boolean value) {
		if (value) {
			if (!this.map.contains(row)) {
				this.map.put(row, new BooleanSparseVector(this.columns));
			}
			this.map.get(row).set(column, value);
		} else {
			if (!this.map.contains(row)) {
				return;
			} else {
				this.map.remove(column);
			}
		}
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + columns;
		result = prime * result + ((map == null) ? 0 : map.hashCode());
		result = prime * result + rows;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		BooleanSparseMatrix other = (BooleanSparseMatrix) obj;
		if (columns != other.columns)
			return false;
		if (map == null) {
			if (other.map != null)
				return false;
		} else if (!map.equals(other.map))
			return false;
		if (rows != other.rows)
			return false;
		return true;
	}
};
