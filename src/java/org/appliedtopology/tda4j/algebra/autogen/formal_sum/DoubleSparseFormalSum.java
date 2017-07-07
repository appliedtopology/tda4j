package org.appliedtopology.tda4j.algebra.autogen.formal_sum;


import gnu.trove.TObjectDoubleHashMap;
import gnu.trove.TObjectDoubleIterator;



/**
 * This class is a data structure for holding a formal sum. Such an element 
 * can be thought of as being in the form r_1 m_1 + ... + r_k m_k. Only terms 
 * with non-zero coefficients are stored. Note that this class is "unaware" of 
 * the arithmetic of the coefficient type. 
 * 
 * @author autogen
 *
 * @param <double> the coefficient type
 * @param <M> the object type 
 */
public class DoubleSparseFormalSum<M> implements DoubleAbstractFormalSum<M> {
	/**
	 * The coefficient-object pairs are held in a hash map, where the
	 * key is the object), and the value is the coefficient.
	 * 
	 */
	protected final TObjectDoubleHashMap<M> map = new TObjectDoubleHashMap<M>();
	
	/**
	 * Default constructor which initializes the sum to be empty.
	 */
	public DoubleSparseFormalSum() {}
	
	/**
	 * This constructor initializes the sum to contain one object.
	 * 
	 * @param coefficient the coefficient of the initializing object
	 * @param object the object to initialize to
	 */
	public DoubleSparseFormalSum(double coefficient, M object) {
		this.put(coefficient, object);
	}
	
	/**
	 * This constructor constructs the sum from another hash map.
	 * 
	 * @param map the hash map to import from
	 */
	public DoubleSparseFormalSum(TObjectDoubleHashMap<M> map) {
				this.map.putAll(map);
			}
	
	/**
	 * This constructor initializes the sum from another DoubleFormalSum.
	 * 
	 * @param formalSum the DoubleFormalSum to import from
	 */
	public DoubleSparseFormalSum(DoubleSparseFormalSum<M> formalSum) {
		this(formalSum.map);
	}
	
	public boolean containsObject(M object) {
		return this.map.containsKey(object);
	}

	public double getCoefficient(M object) {
		return this.map.get(object);
	}

	public boolean isEmpty() {
		return this.map.isEmpty();
	}

	public void put(double coefficient, M object) {
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
		for (TObjectDoubleIterator<M> iterator = this.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			if (index > 0) {
				builder.append(" + ");
			}
			if (iterator.value() == -1) {
				builder.append('-');
			} else if (iterator.value() != 1) {
				builder.append(iterator.value());
			}
			builder.append(iterator.key().toString());
			index++;
		}
		return builder.toString();
	}
	
		
		/**
	 * This function returns an iterator for traversing the sum. Note that for primitive
	 * coefficient types, this will not be an implementor of the Iterable<> interface.
	 * 
	 * @return an iterator for the sum
	 */
	public TObjectDoubleIterator<M> iterator() {
		return this.map.iterator();
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
		DoubleSparseFormalSum<?> other = (DoubleSparseFormalSum<?>) obj;
		if (map == null) {
			if (other.map != null)
				return false;
		} else if (!map.equals(other.map))
			return false;
		return true;
	}
}
