package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntObjectIterator;

/**
 * This class implements the Iterator interface and allows for the traversal of a
 * sparse matrix. Note that it only traverses the non-false elements of the matrix, and
 * omits the false entries.
 * 
 * @author autogen
 *
 * @param <boolean>
 */
public class BooleanSparseMatrixIterator implements Iterator<BooleanMatrixEntry> {
	private final TIntObjectIterator<BooleanSparseVector> rowIterator;
	private Iterator<BooleanVectorEntry> columnIterator;
	
	/**
	 * This constructor initializes the iterator with the given parent matrix.
	 * 
	 * @param matrix the sparse matrix to initialize with
	 */
	BooleanSparseMatrixIterator(BooleanSparseMatrix matrix) {
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

	public BooleanMatrixEntry next() {
		if (!columnIterator.hasNext()) {
			// advance to the next row
			this.rowIterator.advance();
			this.columnIterator = this.rowIterator.value().iterator();
		}
		int row = rowIterator.key();
		BooleanVectorEntry columnValuePair = columnIterator.next();
		int column = columnValuePair.getIndex();
		return new BooleanMatrixEntry(row, column);
	}

	public void remove() {
		this.columnIterator.remove();
	}
};
