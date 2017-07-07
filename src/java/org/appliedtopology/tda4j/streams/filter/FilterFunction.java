package org.appliedtopology.tda4j.streams.filter;

public interface FilterFunction<T> {
	double evaluate(T point);
}
