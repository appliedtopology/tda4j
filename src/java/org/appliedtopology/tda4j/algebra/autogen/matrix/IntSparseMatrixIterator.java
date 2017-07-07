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
 * @param <int>
 */
public class IntSparseMatrixIterator implements Iterator<IntMatrixEntry> {
	private final TIntObjectIterator<IntSparseVector> rowIterator;
	private Iterator<IntVectorEntry> columnIterator;
	
	/**
	 * This constructor initializes the iterator with the given parent matrix.
	 * 
	 * @param matrix the sparse matrix to initialize with
	 */
	IntSparseMatrixIterator(IntSparseMatrix matrix) {
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

	public IntMatrixEntry next() {
		if (!columnIterator.hasNext()) {
			// advance to the next row
			this.rowIterator.advance();
			this.columnIterator = this.rowIterator.value().iterator();
			
		}
		int row = rowIterator.key();
		IntVectorEntry columnValuePair = columnIterator.next();
		int column = columnValuePair.getIndex();
		int value = columnValuePair.getValue();
		return new IntMatrixEntry(row, column, value);
	}

	public void remove() {
		this.columnIterator.remove();
	}
}
