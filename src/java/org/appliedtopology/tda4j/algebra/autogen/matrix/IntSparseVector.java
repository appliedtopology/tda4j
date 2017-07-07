package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

import gnu.trove.TIntIntHashMap;
import gnu.trove.TIntIntIterator;



/**
 * This class provides a sparse vector implementation of the interface IntAbstractVector.
 * It only stores non-zero entries thus is suited for applications where the dimension of the
 * vector is large, but most of the elements are zero.
 * 
 * @author autogen
 *
 * @param <int>
 */
public class IntSparseVector implements IntAbstractVector {
	/**
	 * This hash map stores the index -> value mappings. It is designed for
	 * fast lookups.
	 */
	protected final TIntIntHashMap map = new TIntIntHashMap();
	
	/**
	 * This is the size (or dimension) of the vector. Note that this is not the
	 * actual number of entries, but is merely one past the maximum allowable index.
	 */
	protected final int size;
	
	/**
	 * This constructor initializes the vector have the specified size.
	 * 
	 * @param size the size to initialize to
	 */
	public IntSparseVector(int size) {
		this.size = size;
	}
	
	/**
	 * This constructor initializes the vector with the contents of the
	 * given array.
	 * 
	 * @param array the array to initialize with
	 */
	public IntSparseVector(int[] array) {
		this.size = array.length;
		for (int i = 0; i < size; i++) {
			if (array[i] == 0) {
				continue;
			}
			this.map.put(i, array[i]);
		}
	}

	public IntAbstractVector like(int size) {
		return new IntSparseVector(size);
	}
	
	/**
	 * This function gets the number of non-zero elements in the vector.
	 * 
	 * @return the number of non-zero entries
	 */
	public int getNumNonzeroElements() {
		return this.map.size();
	}
	
	/**
	 * This function returns true if the sum is empty (zero), and
	 * false otherwise.
	 * 
	 * @return true if the sum is empty
	 */
	public boolean isEmpty() {
		return this.map.isEmpty();
	}
	
	/**
	 * This function returns the density (number of non-zero entries / size) of the vector.
	 * 
	 * @return the density of the vector
	 */
	public double getDensity() {
		return ((double) this.getNumNonzeroElements()) / ((double) (size));
	}
	
	public void set(int index, int value) {
		if (value == 0) {
			this.map.remove(index);
		} else {
			this.map.put(index, value);
		}
	}
	
	public int get(int index) {
		return this.map.get(index);
	}
	
	public int getLength() {
		return this.size;
	}
	
		
	public IntSparseVector add(IntSparseVector other) {
		IntSparseVector result = new IntSparseVector(this.size);
		
		for (TIntIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			result.set(iterator.key(), iterator.value());
		}
		
		for (TIntIntIterator iterator = other.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			int new_coefficient = result.get(iterator.key()) + iterator.value();
			result.set(iterator.key(), new_coefficient);
		}
		
		return result;
	}
	
	public IntSparseVector subtract(IntSparseVector other) {
		IntSparseVector result = new IntSparseVector(this.size);
		
		for (TIntIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			result.set(iterator.key(), iterator.value());
		}
		
		for (TIntIntIterator iterator = other.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			int new_coefficient = result.get(iterator.key()) - iterator.value();
			result.set(iterator.key(), new_coefficient);
		}
		
		return result;
	}
	
	public IntSparseVector scalarMultiply(int scalar) {
		IntSparseVector result = new IntSparseVector(this.size);
		
		for (TIntIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			result.set(iterator.key(), iterator.value() * scalar);
		}
		
		return result;
	}
	
	/**
	 * This function compute the inner product of the current vector with the given
	 * sparse vector. The reason for the existence of this function is that it can be 
	 * performed in time on the order of the minimum of the number of non-zero entries
	 * of the two vectors.
	 * 
	 * @param other the sparse vector to compute the inner product with
	 * @return the inner product of this with the given sparse vector
	 */
	public int innerProduct(IntSparseVector other) {
		int sum = 0;
		IntSparseVector smaller = (this.map.size() < other.map.size() ? this : other);
		IntSparseVector larger = (this.map.size() < other.map.size() ? other : this);
		
		for (TIntIntIterator iterator = smaller.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			sum += iterator.value() * larger.get(iterator.key());
		}
		
		return sum;
	}
	
	/**
	 * This function computes the inner product of the current vector with the given array.
	 * 
	 * @param other the array to compute the inner product with
	 * @return the inner product of this with the given array
	 */
	public int innerProduct(int[] other) {
		int sum = 0;
		for (TIntIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			sum += iterator.value() * other[iterator.key()];
		}
		return sum;
	}

	public int innerProduct(IntAbstractVector other) {
		if (other instanceof IntSparseVector) {
			return this.innerProduct((IntSparseVector) other);
		}
		int sum = 0;
		for (TIntIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			sum += iterator.value() * other.get(iterator.key());
		}
		return sum;
	}
		
	public Iterator<IntVectorEntry> iterator() {
		return new IntSparseVectorIterator(this);
	}

	public String toString() {
		StringBuilder builder = new StringBuilder();
		int index = 0;
		builder.append("[");
		for (IntVectorEntry pair: this) {
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(pair.getIndex());
			builder.append(": ");
			builder.append(pair.getValue());
			index++;
		}
		builder.append("]");
		
		return builder.toString();
	}
	
	/**
	 * This function writes the contents of the vector to a given Writer object.
	 * 
	 * @param writer the Writer object to write to
	 * @throws IOException
	 */
	public void write(Writer writer) throws IOException {
		int index = 0;
		writer.write("[");
		for (IntVectorEntry pair: this) {
			if (index > 0) {
				writer.append(", ");
			}
			writer.write(pair.getIndex());
			writer.write(": ");
						writer.write(Integer .toString(pair.getValue()));
						index++;
		}
		writer.write("]");
	}
	
		
	/**
	 * This function converts the vector to a (dense) array.
	 * 
	 * @return an array with the contents of the vector
	 */
	public int[] toArray() {
		int[] array = new int[this.size];
		
		for (IntVectorEntry pair: this) {
			array[pair.getIndex()] = pair.getValue();
		}
		
		return array;
	}
	
		
	public int[] getIndices() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		int[] indices = new int[numNonZeroEntries];
		
		int index = 0;
		for (IntVectorEntry pair: this) {
			indices[index++] = pair.getIndex();
		}
		
		return indices;
	}
		public int[] getValues() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		int[] values = new int[numNonZeroEntries];
		
		int index = 0;
		for (IntVectorEntry pair: this) {
			values[index++] = pair.getValue();
		}
		
		return values;
	}
		
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((map == null) ? 0 : map.hashCode());
		result = prime * result + size;
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
		IntSparseVector other = (IntSparseVector) obj;
		if (map == null) {
			if (other.map != null)
				return false;
		} else if (!map.equals(other.map))
			return false;
		if (size != other.size)
			return false;
		return true;
	}
}
