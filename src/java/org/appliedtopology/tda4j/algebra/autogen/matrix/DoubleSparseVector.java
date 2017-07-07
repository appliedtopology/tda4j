package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

import gnu.trove.TIntDoubleHashMap;
import gnu.trove.TIntDoubleIterator;



/**
 * This class provides a sparse vector implementation of the interface DoubleAbstractVector.
 * It only stores non-zero entries thus is suited for applications where the dimension of the
 * vector is large, but most of the elements are zero.
 * 
 * @author autogen
 *
 * @param <double>
 */
public class DoubleSparseVector implements DoubleAbstractVector {
	/**
	 * This hash map stores the index -> value mappings. It is designed for
	 * fast lookups.
	 */
	protected final TIntDoubleHashMap map = new TIntDoubleHashMap();
	
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
	public DoubleSparseVector(int size) {
		this.size = size;
	}
	
	/**
	 * This constructor initializes the vector with the contents of the
	 * given array.
	 * 
	 * @param array the array to initialize with
	 */
	public DoubleSparseVector(double[] array) {
		this.size = array.length;
		for (int i = 0; i < size; i++) {
			if (array[i] == 0.0d) {
				continue;
			}
			this.map.put(i, array[i]);
		}
	}

	public DoubleAbstractVector like(int size) {
		return new DoubleSparseVector(size);
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
	
	public void set(int index, double value) {
		if (value == 0.0d) {
			this.map.remove(index);
		} else {
			this.map.put(index, value);
		}
	}
	
	public double get(int index) {
		return this.map.get(index);
	}
	
	public int getLength() {
		return this.size;
	}
	
		
	public DoubleSparseVector add(DoubleSparseVector other) {
		DoubleSparseVector result = new DoubleSparseVector(this.size);
		
		for (TIntDoubleIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			result.set(iterator.key(), iterator.value());
		}
		
		for (TIntDoubleIterator iterator = other.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			double new_coefficient = result.get(iterator.key()) + iterator.value();
			result.set(iterator.key(), new_coefficient);
		}
		
		return result;
	}
	
	public DoubleSparseVector subtract(DoubleSparseVector other) {
		DoubleSparseVector result = new DoubleSparseVector(this.size);
		
		for (TIntDoubleIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			result.set(iterator.key(), iterator.value());
		}
		
		for (TIntDoubleIterator iterator = other.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			double new_coefficient = result.get(iterator.key()) - iterator.value();
			result.set(iterator.key(), new_coefficient);
		}
		
		return result;
	}
	
	public DoubleSparseVector scalarMultiply(double scalar) {
		DoubleSparseVector result = new DoubleSparseVector(this.size);
		
		for (TIntDoubleIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
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
	public double innerProduct(DoubleSparseVector other) {
		double sum = 0;
		DoubleSparseVector smaller = (this.map.size() < other.map.size() ? this : other);
		DoubleSparseVector larger = (this.map.size() < other.map.size() ? other : this);
		
		for (TIntDoubleIterator iterator = smaller.map.iterator(); iterator.hasNext(); ) {
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
	public double innerProduct(double[] other) {
		double sum = 0;
		for (TIntDoubleIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			sum += iterator.value() * other[iterator.key()];
		}
		return sum;
	}

	public double innerProduct(DoubleAbstractVector other) {
		if (other instanceof DoubleSparseVector) {
			return this.innerProduct((DoubleSparseVector) other);
		}
		double sum = 0;
		for (TIntDoubleIterator iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			sum += iterator.value() * other.get(iterator.key());
		}
		return sum;
	}
		
	public Iterator<DoubleVectorEntry> iterator() {
		return new DoubleSparseVectorIterator(this);
	}

	public String toString() {
		StringBuilder builder = new StringBuilder();
		int index = 0;
		builder.append("[");
		for (DoubleVectorEntry pair: this) {
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
		for (DoubleVectorEntry pair: this) {
			if (index > 0) {
				writer.append(", ");
			}
			writer.write(pair.getIndex());
			writer.write(": ");
						writer.write(Double .toString(pair.getValue()));
						index++;
		}
		writer.write("]");
	}
	
		
	/**
	 * This function converts the vector to a (dense) array.
	 * 
	 * @return an array with the contents of the vector
	 */
	public double[] toArray() {
		double[] array = new double[this.size];
		
		for (DoubleVectorEntry pair: this) {
			array[pair.getIndex()] = pair.getValue();
		}
		
		return array;
	}
	
		
	public int[] getIndices() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		int[] indices = new int[numNonZeroEntries];
		
		int index = 0;
		for (DoubleVectorEntry pair: this) {
			indices[index++] = pair.getIndex();
		}
		
		return indices;
	}
		public double[] getValues() {
		int numNonZeroEntries = this.getNumNonzeroElements();
		double[] values = new double[numNonZeroEntries];
		
		int index = 0;
		for (DoubleVectorEntry pair: this) {
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
		DoubleSparseVector other = (DoubleSparseVector) obj;
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
