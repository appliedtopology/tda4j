package org.appliedtopology.tda4j.algebra.autogen.formal_sum;


import java.util.Iterator;
import java.util.Map;

import gnu.trove.THashMap;



/**
 * This class is a data structure for holding a formal sum. Such an element 
 * can be thought of as being in the form r_1 m_1 + ... + r_k m_k. Only terms 
 * with non-zero coefficients are stored. Note that this class is "unaware" of 
 * the arithmetic of the coefficient type. 
 * 
 * @author autogen
 *
 * @param <R> the coefficient type
 * @param <M> the object type 
 */
public class ObjectSparseFormalSum<R, M> implements ObjectAbstractFormalSum<R, M> {
	/**
	 * The coefficient-object pairs are held in a hash map, where the
	 * key is the object), and the value is the coefficient.
	 * 
	 */
	protected final THashMap<M, R> map = new THashMap<M, R>();
	
	/**
	 * Default constructor which initializes the sum to be empty.
	 */
	public ObjectSparseFormalSum() {}
	
	/**
	 * This constructor initializes the sum to contain one object.
	 * 
	 * @param coefficient the coefficient of the initializing object
	 * @param object the object to initialize to
	 */
	public ObjectSparseFormalSum(R coefficient, M object) {
		this.put(coefficient, object);
	}
	
	/**
	 * This constructor constructs the sum from another hash map.
	 * 
	 * @param map the hash map to import from
	 */
	public ObjectSparseFormalSum(THashMap<M, R> map) {
				for (Map.Entry<M, R> entry: map.entrySet()) {
			this.map.put(entry.getKey(), entry.getValue());
		}
			}
	
	/**
	 * This constructor initializes the sum from another DoubleFormalSum.
	 * 
	 * @param formalSum the DoubleFormalSum to import from
	 */
	public ObjectSparseFormalSum(ObjectSparseFormalSum<R, M> formalSum) {
		this(formalSum.map);
	}
	
	public boolean containsObject(M object) {
		return this.map.containsKey(object);
	}

	public R getCoefficient(M object) {
		return this.map.get(object);
	}

	public boolean isEmpty() {
		return this.map.isEmpty();
	}

	public void put(R coefficient, M object) {
		this.map.put(object, coefficient);
	}
	
	public void remove(M object) {
		this.map.remove(object);
	}

	public int size() {
		return this.map.size();
	}
	
		
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		int index = 0;
		R coefficient = null;
		for (Map.Entry<M, R> entry : this.map.entrySet()) {
			if (index > 0) {
				builder.append(" + ");
			}
			coefficient = entry.getValue();
			builder.append(coefficient);
			builder.append(" ");
			builder.append(entry.getKey());
			index++;
		}
		return builder.toString();
	}
	
		
		/**
	 * This function returns an iterator for traversing the sum. 
	 * 
	 * @return an iterator for the sum
	 */
	public Iterator<Map.Entry<M, R>> iterator() {
		return this.map.entrySet().iterator();
	}
	
		
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((map == null) ? 0 : map.hashCode());
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
		ObjectSparseFormalSum<?, ?> other = (ObjectSparseFormalSum<?, ?>) obj;
		if (map == null) {
			if (other.map != null)
				return false;
		} else if (!map.equals(other.map))
			return false;
		return true;
	}
}
