package org.appliedtopology.tda4j.streams.multi;

import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;

public interface AbstractStreamFlattener<T> {
	AbstractFilteredStream<T> collapse(AbstractMultifilteredStream<T> multifilteredStream);
}
