package org.appliedtopology.tda4j.metric.landmark;

import org.appliedtopology.tda4j.metric.interfaces.AbstractSearchableMetricSpace;

public class ExplicitLandmarkSelector<T> extends LandmarkSelector<T> {
	public ExplicitLandmarkSelector(AbstractSearchableMetricSpace<T> metricSpace, int[] indices) {
		super(metricSpace, indices);
	}

	@Override
	protected int[] computeLandmarkSet() {
		return null;
	}
}
