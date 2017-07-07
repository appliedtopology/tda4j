package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectIterator;




/**
 * This class provides a sparse-matrix implementation of the DoubleAbstractMatrix
 * interface. It only stores non-zero elements, and is therefore suited for applications
 * where most of the entries in the matrix are zero.
 * 
 * @author autogen
 *
 */
public class DoubleSparseMatrix implements DoubleAbstractMatrix {
	/**
	 * We use a row-wise storage scheme. The variable map stores
	 * the rows of the matrix on an as-needed basis. Each row is a 
	 * DoubleSparseVector. This choice was made so that matrix-vector
	 * products can be computed very quickly.
	 */
	protected final TIntObjectHashMap<DoubleSparseVector> map = new TIntObjectHashMap<DoubleSparseVector>();

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
	public DoubleSparseMatrix(int rows, int columns) {
		this.rows = rows;
		this.columns = columns;
	}
	
	public double get(int row, int column) {
		if (!this.map.contains(row)) {
			return 0.0d;
		}
		return this.map.get(row).get(column);
	}

	public int getNumColumns() {
		return this.columns;
	}

	public int getNumRows() {
		return this.rows;
	}

	public Iterator<DoubleMatrixEntry> iterator() {
		return new DoubleSparseMatrixIterator(this);
	}

	public DoubleAbstractMatrix like(int rows, int columns) {
		return new DoubleSparseMatrix(rows, columns);
	}
	
	/**
	 * This function returns the number of non-zero elements in the matrix.
	 * 
	 * @return the number of non-zero elements
	 */
	public int getNumNonzeroElements() {
		int num = 0;
		for (TIntObjectIterator<DoubleSparseVector> iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			num += iterator.value().getNumNonzeroElements();
		}
		return num;
	}

		public DoubleSparseVector multiply(DoubleAbstractVector vector) {
		DoubleSparseVector result = new DoubleSparseVector(this.rows);
		double innerProductValue = 0;
		for (TIntObjectIterator<DoubleSparseVector> iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			innerProductValue = iterator.value().innerProduct(vector);
			if (innerProductValue != 0) {
				result.set(iterator.key(), innerProductValue);
			}
		}
		
		return result;
	}
	
	public void set(int row, int column, double value) {
		if (value == 0.0d) {
			if (!this.map.contains(row)) {
				return;
			} else {
				this.map.remove(column);
			}
		} else {
			if (!this.map.contains(row)) {
				this.map.put(row, new DoubleSparseVector(this.columns));
			}
			this.map.get(row).set(column, value);
		}
	}
	
	/**
	 * This function computes the transpose of the matrix.
	 * 
	 * @return the transpose of the matrix
	 */
	public DoubleSparseMatrix transpose() {
		DoubleSparseMatrix result = new DoubleSparseMatrix(columns, rows);
		
		for (DoubleMatrixEntry entry: this) {
			result.set(entry.getCol(), entry.getRow(), entry.getValue());
		}
		
		return result;
	}
	
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("[");
		int count = 0;
		for (DoubleMatrixEntry entry: this) {
			if (count > 0) {
				builder.append(", ");
			}
			builder.append("(" + entry.getRow() + ", " + entry.getCol() + "): " + entry.getValue());
			count++;
		}
		builder.append("]");
		return builder.toString();
	}
	
	public int[] getRows() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		int[] rows = new int[numNonZeroEntries];
		
		int index = 0;
		for (DoubleMatrixEntry entry: this) {
			rows[index++] = entry.getRow();
		}
		
		return rows;
	}
	
	public int[] getColumns() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		int[] cols = new int[numNonZeroEntries];
		
		int index = 0;
		for (DoubleMatrixEntry entry: this) {
			cols[index++] = entry.getCol();
		}
		
		return cols;
	}
	
		public double[] getValues() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		double[] values = new double[numNonZeroEntries];
		
		int index = 0;
		for (DoubleMatrixEntry entry: this) {
			values[index++] = entry.getValue();
		}
		
		return values;
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
		DoubleSparseMatrix other = (DoubleSparseMatrix) obj;
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
}
