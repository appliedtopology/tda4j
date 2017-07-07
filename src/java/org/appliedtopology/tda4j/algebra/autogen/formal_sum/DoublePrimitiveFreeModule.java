package org.appliedtopology.tda4j.algebra.autogen.formal_sum;


import org.appliedtopology.tda4j.algebra.autogen.algebraic.DoubleAbstractModule;
import gnu.trove.TObjectDoubleIterator;




/**
 * This class implements the algebraic operations of a free module with generating elements of
 * type M, and over a ring with elements of type double. Elements of the free 
 * module are represented by formal sums of entries in M. The coefficient operations on 
 * the sums are simply the standard primitive operations on the type double.
 * 
 * @author autogen
 *
 * @param <double> the coefficient type
 * @param <M> the object type
 */
public class DoublePrimitiveFreeModule<M> implements DoubleAbstractModule<DoubleSparseFormalSum<M>> {
	
	public DoublePrimitiveFreeModule() {}
	
	public DoubleSparseFormalSum<M> add(DoubleSparseFormalSum<M> a, DoubleSparseFormalSum<M> b) {
		DoubleSparseFormalSum<M> result = null;
		
				
		TObjectDoubleIterator<M> iterator = null;
			
		if (a.size() > b.size()) {
			result = this.createNewSum(a);
			iterator = b.map.iterator();
		} else {
			result = this.createNewSum(b);
			iterator = a.map.iterator();
		}
		
		while(iterator.hasNext()) {
			iterator.advance();
			addObject(result, iterator.value(), iterator.key());
		}
		
				
		return result;
	}
	
	public DoubleSparseFormalSum<M> subtract(DoubleSparseFormalSum<M> a, DoubleSparseFormalSum<M> b) {
		DoubleSparseFormalSum<M> result = this.createNewSum();
		
				
		TObjectDoubleIterator<M> iterator = b.map.iterator();
		
		while(iterator.hasNext()) {
			iterator.advance();
			addObject(result, -(iterator.value()), iterator.key());
		}
		
				
		return result;
	}
	
	public DoubleSparseFormalSum<M> multiply(double r, DoubleSparseFormalSum<M> a) {
		DoubleSparseFormalSum<M> result = this.createNewSum();
		
				
		TObjectDoubleIterator<M> iterator = a.map.iterator();
		
		while(iterator.hasNext()) {
			iterator.advance();
			addObject(result, (r * iterator.value()), iterator.key());
		}
		
				
		return result;
	}
	
	public DoubleSparseFormalSum<M> negate(DoubleSparseFormalSum<M> a) {
		DoubleSparseFormalSum<M> result = this.createNewSum();
		
				
		TObjectDoubleIterator<M> iterator = a.map.iterator();
		
		while(iterator.hasNext()) {
			iterator.advance();
			addObject(result, -(iterator.value()), iterator.key());
		}
		
				
		return result;
	}
	
		public DoubleSparseFormalSum<M> multiply(int r, DoubleSparseFormalSum<M> a) {
		DoubleSparseFormalSum<M> result = this.createNewSum();
		
				
		TObjectDoubleIterator<M> iterator = a.map.iterator();
		
		while(iterator.hasNext()) {
			iterator.advance();
			addObject(result, (r * iterator.value()), iterator.key());
		}
		
				
		return result;
	}
		
	/**
	 * This function computes the inner product between two formal sums.
	 * 
	 * @param a
	 * @param b
	 * @return the inner product of a and b
	 */
	public double innerProduct(DoubleSparseFormalSum<M> a, DoubleSparseFormalSum<M> b) {
		double sum = 0.0d;
		
				
		TObjectDoubleIterator<M> iterator = null;
		DoubleSparseFormalSum<M> other = null;
		
		if (a.size() > b.size()) {
			iterator = b.map.iterator();
			other = a;
		} else {
			iterator = a.map.iterator();
			other = b;
		}
		
		while(iterator.hasNext()) {
			iterator.advance();
			sum += iterator.value() * other.getCoefficient(iterator.key());
		}
		
				
		return sum;
	}
	
	/**
	 * This function computes the sum of two generating elements.
	 * 
	 * @param a
	 * @param b
	 * @return a + b
	 */
	public DoubleSparseFormalSum<M> add(M a, M b) {
		DoubleSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, 1, b);
		return sum;
	}
	
	/**
	 * This function computes the sum of a formal sum and a basis element.
	 * 
	 * @param a
	 * @param b
	 * @return a + b
	 */
	public DoubleSparseFormalSum<M> add(DoubleSparseFormalSum<M> a, M b) {
		DoubleSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, 1, b);
		return sum;
	}
	
	/**
	 * This function computes the difference of two generating elements.
	 * 
	 * @param a
	 * @param b
	 * @return a - b
	 */
	public DoubleSparseFormalSum<M> subtract(M a, M b) {
		DoubleSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, -1, b);
		return sum;
	}
	
	/**
	 * This function computes the difference of a formal sum and a basis element.
	 * 
	 * @param a
	 * @param b
	 * @return a - b
	 */
	public DoubleSparseFormalSum<M> subtract(DoubleSparseFormalSum<M> a, M b) {
		DoubleSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, -1, b);
		return sum;
	}
	
	/**
	 * This function performs the operation a = a + b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the module element to add
	 */
	public void accumulate(DoubleSparseFormalSum<M> a, DoubleSparseFormalSum<M> b) {
		
				
		for (TObjectDoubleIterator<M> iterator = b.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			this.addObject(a, iterator.value(), iterator.key());
		}
		
			}
	
	/**
	 * This function performs the operation a = a + r * b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the module element to add
	 * @param c the scalar multiplier
	 */
	public void accumulate(DoubleSparseFormalSum<M> a, DoubleSparseFormalSum<M> b, double r) {
		
				
		for (TObjectDoubleIterator<M> iterator = b.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			this.addObject(a, (r * iterator.value()), iterator.key());
		}
		
			}
	
	/**
	 * This function performs the operation a = a + b where b is a basis element.
	 * 
	 * @param a the value to accumulate in
	 * @param b the element to add
	 */
	public void accumulate(DoubleSparseFormalSum<M> a, M b) {
		this.addObject(a, 1, b);
	}
	
	/**
	 * This function performs the operation a = a + r * b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the basis element to add
	 * @param c the scalar multiplier
	 */
	public void accumulate(DoubleSparseFormalSum<M> a, M b, double r) {
		this.addObject(a, r, b);
	}
	
	/**
	 * This function adds the given object to the formal sum with given coefficient. In the event
	 * that the object is already present it computes the sum of the passed in and existing 
	 * coefficients and updates the coefficient with the result. In the event that the object is
	 * not present already, it adds a new object with the given coefficient.
	 * 
	 * @param formalSum the sum to add to
	 * @param coefficient the coefficient of the object to add
	 * @param object the object (basis element) to add
	 */
	private void addObject(DoubleSparseFormalSum<M> formalSum, double coefficient, M object) {
		if (coefficient == 0) {
			return;
		}
		
		if (formalSum.containsObject(object)) {
			double newCoefficient = formalSum.getCoefficient(object) + coefficient;
			if (newCoefficient == 0) {
				formalSum.remove(object);
			} else {
				formalSum.put(newCoefficient, object);
			}
		} else {
			formalSum.put(coefficient, object);
		}
	}

	public DoubleSparseFormalSum<M> getAdditiveIdentity() {
		return new DoubleSparseFormalSum<M>();
	}

	/**
	 * This function creates an empty new formal sum.
	 * 
	 * @return an empty new formal sum
	 */
	public DoubleSparseFormalSum<M> createNewSum() {
		return new DoubleSparseFormalSum<M>();
	}

	/**
	 * This function creates a new formal sum containing a single object with default
	 * coefficient equal to the multiplicative identity of the ring.
	 * 
	 * @param object the object to initialize the sum with
	 * @return a new sum containing the supplied object
	 */
	public DoubleSparseFormalSum<M> createNewSum(M object) {
		return new DoubleSparseFormalSum<M>(1, object);
	}

	/**
	 * This function creates a new formal sum containing a single object with the
	 * supplied coefficient.
	 * 
	 * @param coefficient the coefficient of the element to add
	 * @param object the object to add
	 * @return a new formal sum initializes with a coefficient and basis element
	 */
	public DoubleSparseFormalSum<M> createNewSum(double coefficient, M object) {
		return new DoubleSparseFormalSum<M>(coefficient, object);
	}

	/**
	 * This function creates a new formal sum with the contents of another sum
	 * copied in.
	 * 
	 * @param contents the formal sum to copy from
	 * @return a new formal sum with the contents of the given one copied in
	 */
	public DoubleSparseFormalSum<M> createNewSum(DoubleSparseFormalSum<M> contents) {
		return new DoubleSparseFormalSum<M>(contents);
	}
	
	/**
	 * This function creates a new formal sum initialized with the given coefficient
	 * and object arrays.
	 * 
	 * @param coefficients an array of coefficients 
	 * @param objects an array of basis elements
	 * @return a new formal sum initialized with the given coefficients and basis elements
	 */
	public DoubleSparseFormalSum<M> createNewSum(double[] coefficients, M[] objects) {
		DoubleSparseFormalSum<M> sum = new DoubleSparseFormalSum<M>();
		
		for (int i = 0; i < coefficients.length; i++) {
			addObject(sum, coefficients[i], objects[i]);
		}
		
		return sum;
	}
	
		/**
	 * This function creates a new formal sum initialized with the given coefficient
	 * and object arrays.
	 * 
	 * @param coefficients an array of coefficients 
	 * @param objects an array of basis elements
	 * @return a new formal sum initialized with the given coefficients and basis elements
	 */
	public DoubleSparseFormalSum<M> createNewSum(int[] coefficients, M[] objects) {
		DoubleSparseFormalSum<M> sum = new DoubleSparseFormalSum<M>();
		
		for (int i = 0; i < coefficients.length; i++) {
			addObject(sum, (double) coefficients[i], objects[i]);
		}
		
		return sum;
	}
	
	}
