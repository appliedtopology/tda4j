package org.appliedtopology.tda4j.streams.multi;

import java.util.Iterator;

import org.appliedtopology.tda4j.homology.barcodes.Interval;
import org.appliedtopology.tda4j.homology.barcodes.PersistenceInvariantDescriptor;
import org.appliedtopology.tda4j.homology.chain_basis.PrimitiveBasisElement;
import gnu.trove.THashMap;

public abstract class PrimitiveMultifilteredStream<T extends PrimitiveBasisElement> implements AbstractMultifilteredStream<T> {

	/**
	 * This hash map contains the filtration indices of the basis elements in the complex.
	 */
	private final THashMap<T, double[]> filtrationIndices = new THashMap<T, double[]>();
	
	public void addElement(T basisElement, double[] filtrationValue) {
		this.filtrationIndices.put(basisElement, filtrationValue);
	}
	
	public Iterator<T> iterator() {
		return this.filtrationIndices.keySet().iterator();
	}

	public double[] getFiltrationValue(T basisElement) {
		return this.filtrationIndices.get(basisElement);
	}

	@SuppressWarnings("unchecked")
	public final T[] getBoundary(T basisElement) {
		return (T[]) basisElement.getBoundaryArray();
	}

	public int[] getBoundaryCoefficients(T basisElement) {
		return basisElement.getBoundaryCoefficients();
	}

	public int getDimension(T basisElement) {
		return basisElement.getDimension();
	}

	public void finalizeStream() {
	
	}

	public boolean isFinalized() {
		return true;
	}

	public int getSize() {
		return this.filtrationIndices.size();
	}

	public <G> PersistenceInvariantDescriptor<Interval<Double>, G> transform(PersistenceInvariantDescriptor<Interval<Integer>, G> barcodeCollection) {
		return null;
	}

}
