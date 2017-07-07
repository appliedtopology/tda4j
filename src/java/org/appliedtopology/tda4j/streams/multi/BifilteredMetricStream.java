package org.appliedtopology.tda4j.streams.multi;

import java.util.Comparator;

import org.appliedtopology.tda4j.homology.chain_basis.PrimitiveBasisElement;
import org.appliedtopology.tda4j.streams.filter.FilterFunction;
import org.appliedtopology.tda4j.streams.interfaces.PrimitiveStream;

public class BifilteredMetricStream<T extends PrimitiveBasisElement> extends PrimitiveMultifilteredStream<T> {
	private final PrimitiveStream<T> stream;
	private final FilterFunction<T> filterFunction;
	
	public BifilteredMetricStream(PrimitiveStream<T> stream, FilterFunction<T> filterFunction) {
		this.stream = stream;
		this.filterFunction = filterFunction;
		this.construct();
	}

	private void construct() {
		for (T element: stream) {
			double filtrationValue = stream.getFiltrationValue(element);
			double filterFunctionValue = this.filterFunction.evaluate(element);
			this.addElement(element, filtrationValue, filterFunctionValue);
		}
	}

	private void addElement(T element, double filtrationValue, double filterFunctionValue) {
		this.addElement(element, new double[]{filtrationValue, filterFunctionValue});
	}
	
	public Comparator<T> getBasisComparator() {
		return this.stream.getBasisComparator();
	}
}
