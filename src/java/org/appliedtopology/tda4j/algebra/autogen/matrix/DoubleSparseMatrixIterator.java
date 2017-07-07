package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntObjectIterator;



/**
 * This class implements the Iterator interface and allows for the traversal of a
 * sparse matrix. Note that it only traverses the non-zero elements of the matrix, and
 * omits the zero entries.
 * 
 * @author autogen
 *
 * @param <double>
 */
public class DoubleSparseMatrixIterator implements Iterator<DoubleMatrixEntry> {
	private final TIntObjectIterator<DoubleSparseVector> rowIterator;
	private Iterator<DoubleVectorEntry> columnIterator;
	
	/**
	 * This constructor initializes the iterator with the given parent matrix.
	 * 
	 * @param matrix the sparse matrix to initialize with
	 */
	DoubleSparseMatrixIterator(DoubleSparseMatrix matrix) {
		this.rowIterator = matrix.map.iterator();
		this.rowIterator.advance();
		this.columnIterator = this.rowIterator.value().iterator();
	}
	
	public boolean hasNext() {
		if (this.rowIterator.hasNext()) {
			return true;
		} else {
			return this.columnIterator.hasNext();
		}
	}

	public DoubleMatrixEntry next() {
		if (!columnIterator.hasNext()) {
			// advance to the next row
			this.rowIterator.advance();
			this.columnIterator = this.rowIterator.value().iterator();
			
		}
		int row = rowIterator.key();
		DoubleVectorEntry columnValuePair = columnIterator.next();
		int column = columnValuePair.getIndex();
		double value = columnValuePair.getValue();
		return new DoubleMatrixEntry(row, column, value);
	}

	public void remove() {
		this.columnIterator.remove();
	}
}
