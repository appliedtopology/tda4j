package org.appliedtopology.tda4j.algebra.autogen.formal_sum;



import java.util.Iterator;

import gnu.trove.THashSet;



/**
 * This class is a data structure for holding a formal sum. Such an element 
 * can be thought of as being in the form r_1 m_1 + ... + r_k m_k. Only terms 
 * with non-zero coefficients are stored. Note that this class is "unaware" of 
 * the arithmetic of the coefficient type. 
 * 
 * @author autogen
 *
 * @param <boolean> the coefficient type
 * @param <M> the object type 
 */
public class BooleanSparseFormalSum<M> implements BooleanAbstractFormalSum<M> {
	/**
	 * Since we only store terms with coefficients equal to 1 (or "true"), 
	 * we do not need to hold the coefficients. Thus we only store the
	 * set of objects. The coefficient is determined whether the object is
	 * present or not present in the set.
	 */
	protected final THashSet<M> map = new THashSet<M>();

	/**
	 * Default constructor which initializes the sum to be empty.
	 */
	protected BooleanSparseFormalSum() {}

	/**
	 * This constructor initializes the sum to contain one object.
	 * 
	 * @param coefficient the coefficient of the initializing object
	 * @param object the object to initialize to
	 */
	protected BooleanSparseFormalSum(boolean coefficient, M object) {
		if (coefficient) {
			this.put(coefficient, object);
		}
	}

	/**
	 * This constructor constructs the sum from another hash map.
	 * 
	 * @param map the hash map to import from
	 */
	protected BooleanSparseFormalSum(THashSet<M> map) {
		this.map.addAll(map);
	}

	/**
	 * This constructor initializes the sum from another DoubleFormalSum.
	 * 
	 * @param formalSum the DoubleFormalSum to import from
	 */
	protected BooleanSparseFormalSum(BooleanSparseFormalSum<M> formalSum) {
		this(formalSum.map);
	}

	public boolean containsObject(M object) {
		return this.map.contains(object);
	}

	public boolean getCoefficient(M object) {
		return this.map.contains(object);
	}

	public boolean isEmpty() {
		return this.map.isEmpty();
	}

	public void put(boolean coefficient, M object) {
		if (coefficient) {
			this.map.add(object);
		} else {
			if (this.map.contains(object)) {
				this.map.remove(object);
			}
		}
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
		for (Iterator<M> iterator = this.map.iterator(); iterator.hasNext(); ) {
			if (index > 0) {
				builder.append(" + ");
			}
			builder.append(iterator.next().toString());
			index++;
		}
		return builder.toString();
	}
	
	/**
	 * This function returns an iterator for traversing the sum.
	 * 
	 * @return an iterator for the sum
	 */
	public Iterator<M> iterator() {
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
		BooleanSparseFormalSum<?> other = (BooleanSparseFormalSum<?>) obj;
		if (map == null) {
			if (other.map != null)
				return false;
		} else if (!map.equals(other.map))
			return false;
		return true;
	}
};
