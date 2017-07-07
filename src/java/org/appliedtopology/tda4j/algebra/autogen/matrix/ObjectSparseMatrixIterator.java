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
 * @param <R>
 */
public class ObjectSparseMatrixIterator<R> implements Iterator<ObjectMatrixEntry<R>> {
	private final TIntObjectIterator<ObjectSparseVector<R>> rowIterator;
	private Iterator<ObjectVectorEntry<R>> columnIterator;
	
	/**
	 * This constructor initializes the iterator with the given parent matrix.
	 * 
	 * @param matrix the sparse matrix to initialize with
	 */
	ObjectSparseMatrixIterator(ObjectSparseMatrix<R> matrix) {
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

	public ObjectMatrixEntry<R> next() {
		if (!columnIterator.hasNext()) {
			// advance to the next row
			this.rowIterator.advance();
			this.columnIterator = this.rowIterator.value().iterator();
			
		}
		int row = rowIterator.key();
		ObjectVectorEntry<R> columnValuePair = columnIterator.next();
		int column = columnValuePair.getIndex();
		R value = columnValuePair.getValue();
		return new ObjectMatrixEntry<R>(row, column, value);
	}

	public void remove() {
		this.columnIterator.remove();
	}
}
