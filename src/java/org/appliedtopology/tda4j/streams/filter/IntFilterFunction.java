package org.appliedtopology.tda4j.streams.filter;

public interface IntFilterFunction {
	double evaluate(int point);
	
	double getMaxValue();
	double getMinValue();
	
	double[] getValues();
}
