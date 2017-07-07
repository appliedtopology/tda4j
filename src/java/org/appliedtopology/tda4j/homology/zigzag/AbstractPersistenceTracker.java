package org.appliedtopology.tda4j.homology.zigzag;

import java.util.Map;

import org.appliedtopology.tda4j.homology.barcodes.AnnotatedBarcodeCollection;

public interface AbstractPersistenceTracker<K, I extends Comparable<I>, G> {
	public AnnotatedBarcodeCollection<I, G> getInactiveGenerators();
	public Map<K, IntervalDescriptor<I, G>> getActiveGenerators();
}
