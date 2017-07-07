package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

import gnu.trove.TIntHashSet;
import gnu.trove.TIntIterator;

/**
 * This class provides a sparse vector implementation of the interface BooleanAbstractVector.
 * It only stores non-false entries thus is suited for applications where the dimension of the
 * vector is large, but most of the elements are false.
 * 
 * @author autogen
 *
 * @param <boolean>
 */
public class BooleanSparseVector implements BooleanAbstractVector {
	/**
	 * This hash set stores the indices of the entries in the vector which are true.
	 */
	protected final TIntHashSet map = new TIntHashSet();
	

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
	public BooleanSparseVector(int size) {
		this.size = size;
	}

	/**
	 * This constructor initializes the vector with the contents of the
	 * given array.
	 * 
	 * @param array the array to initialize with
	 */
	public BooleanSparseVector(boolean[] array) {
		this.size = array.length;
		for (int i = 0; i < size; i++) {
			if (array[i] == false) {
				continue;
			}
			this.map.add(i);
		}
	}

	public BooleanAbstractVector like(int size) {
		return new BooleanSparseVector(size);
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
	 * This function returns the density (number of non-zero entries / size) of the vector.
	 * 
	 * @return the density of the vector
	 */
	public double getDensity() {
		return ((double) this.getNumNonzeroElements()) / ((double) (size));
	}

	public void set(int index, boolean value) {
		if (value) {
			this.map.add(index);
		} else {
			this.map.remove(index);
		}
	}

	public boolean get(int index) {
		return this.map.contains(index);
	}

	public int getLength() {
		return this.size;
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
	public boolean innerProduct(BooleanSparseVector other) {
		boolean sum = false;
		BooleanSparseVector smaller = (this.map.size() < other.map.size() ? this : other);
		BooleanSparseVector larger = (this.map.size() < other.map.size() ? other : this);

		for (TIntIterator iterator = smaller.map.iterator(); iterator.hasNext(); ) {
			if (larger.map.contains(iterator.next())) {
				sum ^= true;
			}
		}

		return sum;
	}

	/**
	 * This function computes the inner product of the current vector with the given array.
	 * 
	 * @param other the array to compute the inner product with
	 * @return the inner product of this with the given array
	 */
	public boolean innerProduct(boolean[] other) {
		boolean sum = false;
		for (TIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			sum ^= other[iterator.next()];
		}
		return sum;
	}

	public boolean innerProduct(BooleanAbstractVector other) {
		if (other instanceof BooleanSparseVector) {
			return this.innerProduct((BooleanSparseVector) other);
		}
		boolean sum = false;
		for (TIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			sum ^= other.get(iterator.next());
		}
		return sum;
	}

	public Iterator<BooleanVectorEntry> iterator() {
		return new BooleanSparseVectorIterator(this);
	}

	public String toString() {
		StringBuilder builder = new StringBuilder();
		int index = 0;
		builder.append("[");
		for (TIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(iterator.next());
			builder.append(": 1");
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
		for (TIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			if (index > 0) {
				writer.write(", ");
			}
			writer.write(iterator.next());
			writer.write(": 1");
			index++;
		}
		writer.write("]");
	}


	/**
	 * This function converts the vector to a (dense) array.
	 * 
	 * @return an array with the contents of the vector
	 */
	public boolean[] toArray() {
		boolean[] array = new boolean[this.size];

		for (TIntIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			array[iterator.next()] = true;
		}

		return array;
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
		BooleanSparseVector other = (BooleanSparseVector) obj;
		if (map == null) {
			if (other.map != null)
				return false;
		} else if (!map.equals(other.map))
			return false;
		if (size != other.size)
			return false;
		return true;
	}

};
