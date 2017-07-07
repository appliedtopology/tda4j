package org.appliedtopology.tda4j.api;

import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.metric.impl.EuclideanMetricSpace;
import org.appliedtopology.tda4j.metric.interfaces.AbstractSearchableMetricSpace;
import org.appliedtopology.tda4j.metric.landmark.LandmarkSelector;
import org.appliedtopology.tda4j.streams.impl.ExplicitCellStream;
import org.appliedtopology.tda4j.streams.impl.ExplicitSimplexStream;
import org.appliedtopology.tda4j.streams.impl.LazyWitnessStream;
import org.appliedtopology.tda4j.streams.impl.VietorisRipsStream;
import org.appliedtopology.tda4j.streams.impl.WitnessStream;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;

public class FilteredStreamInterface {
	public static ExplicitSimplexStream createExplicitSimplexStream() {
		return new ExplicitSimplexStream();
	}
	
	public static ExplicitSimplexStream createExplicitSimplexStream(double maxFiltrationValue) {
		return new ExplicitSimplexStream(maxFiltrationValue);
	}

	public static ExplicitCellStream createExplicitCellStream() {
		return new ExplicitCellStream();
	}
	
	public static ExplicitCellStream createExplicitCellStream(double maxFiltrationValue) {
		return new ExplicitCellStream(maxFiltrationValue);
	}

	public static VietorisRipsStream<double[]> createPlex4VietorisRipsStream(double[][] points, int maxDimension, double maxFiltrationValue, int numDivisions) {

		AbstractSearchableMetricSpace<double[]> metricSpace = new EuclideanMetricSpace(points);
		VietorisRipsStream<double[]> stream = new VietorisRipsStream<double[]>(metricSpace, maxFiltrationValue, maxDimension, numDivisions);
		stream.finalizeStream();

		return stream;
	}

	public static <T> VietorisRipsStream<T> createPlex4VietorisRipsStream(AbstractSearchableMetricSpace<T> metricSpace, int maxDimension, double maxFiltrationValue, int numDivisions) {
		VietorisRipsStream<T> stream = new VietorisRipsStream<T>(metricSpace, maxFiltrationValue, maxDimension, numDivisions);
		stream.finalizeStream();
		return stream;
	}
	
	public static VietorisRipsStream<double[]> createPlex4VietorisRipsStream(double[][] points, int maxDimension, double[] filtrationValues) {

		AbstractSearchableMetricSpace<double[]> metricSpace = new EuclideanMetricSpace(points);
		VietorisRipsStream<double[]> stream = new VietorisRipsStream<double[]>(metricSpace, filtrationValues, maxDimension);
		stream.finalizeStream();

		return stream;
	}

	public static <T> VietorisRipsStream<T> createPlex4VietorisRipsStream(AbstractSearchableMetricSpace<T> metricSpace, int maxDimension, double[] filtrationValues) {
		VietorisRipsStream<T> stream = new VietorisRipsStream<T>(metricSpace, filtrationValues, maxDimension);
		stream.finalizeStream();
		return stream;
	}

	public static LazyWitnessStream<double[]> createPlex4LazyWitnessStream(LandmarkSelector<double[]> selector, int maxDimension, double maxFiltrationValue, int numDivisions) {
		LazyWitnessStream<double[]> stream = new LazyWitnessStream<double[]>(selector.getUnderlyingMetricSpace(), selector, maxDimension, maxFiltrationValue, numDivisions);
		stream.finalizeStream();
		return stream;
	}

	public static WitnessStream<double[]> createPlex4WitnessStream(LandmarkSelector<double[]> selector, int maxDimension, double maxFiltrationValue, int numDivisions) {
		WitnessStream<double[]> stream = new WitnessStream<double[]>(selector.getUnderlyingMetricSpace(), selector, maxDimension, maxFiltrationValue, numDivisions);
		stream.finalizeStream();

		return stream;
	}

	private static int[] convertTo1Based(int[] array) {
		int[] result = new int[array.length + 1];
		result[0] = 0;
		for (int i = 0; i < array.length; i++) {
			result[i + 1] = array[i] + 1;
		}
		return result;
	}
}
