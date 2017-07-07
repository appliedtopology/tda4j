package org.appliedtopology.tda4j.example_tests;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.api.Plex4;
import org.appliedtopology.tda4j.examples.PointCloudExamples;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.interfaces.AbstractPersistenceAlgorithm;
import org.appliedtopology.tda4j.metric.impl.EuclideanMetricSpace;
import org.appliedtopology.tda4j.streams.filter.FilterFunction;
import org.appliedtopology.tda4j.streams.filter.IntFilterFunction;
import org.appliedtopology.tda4j.streams.filter.KernelDensityFilterFunction;
import org.appliedtopology.tda4j.streams.filter.MaxSimplicialFilterFunction;
import org.appliedtopology.tda4j.streams.impl.VietorisRipsStream;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;
import org.appliedtopology.tda4j.streams.multi.AbstractMultifilteredStream;
import org.appliedtopology.tda4j.streams.multi.BifilteredMetricStream;
import org.appliedtopology.tda4j.streams.multi.IncreasingOrthantFlattener;

public class MultifilteredFlatteningTest {

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void test() {
		// initialize constants
		int n = 100;
		int maxDimension = 1;
		double maxFiltrationValue = 0.4;
		double sigma = 0.4;

		// set direction of sets
		double[] principalDirection = new double[] { 0.05, 0.01 };

		// create a new metric space from random points on a circle
		double[][] points = PointCloudExamples.getRandomCirclePoints(n);
		EuclideanMetricSpace metricSpace = new EuclideanMetricSpace(points);

		// create a vietoris rips complex from the points
		VietorisRipsStream<double[]> stream = Plex4.createVietorisRipsStream(metricSpace, maxDimension + 1, maxFiltrationValue);
		stream.finalizeStream();

		// initialize the kernel density function
		IntFilterFunction intFilterFunction = new KernelDensityFilterFunction(metricSpace, sigma);
		FilterFunction<Simplex> simplexFilterFunction = new MaxSimplicialFilterFunction(intFilterFunction);

		// create the bifiltered stream
		AbstractMultifilteredStream<Simplex> multifilteredStream = new BifilteredMetricStream<Simplex>(stream, simplexFilterFunction);

		// create a "flattened" version of the stream by considering increasing
		// subsets
		IncreasingOrthantFlattener<Simplex> flattener = new IncreasingOrthantFlattener<Simplex>(principalDirection);
		AbstractFilteredStream<Simplex> flattenedStream = flattener.collapse(multifilteredStream);

		// compute the persistent homology of the flattened complex, and print
		// the result
		AbstractPersistenceAlgorithm<Simplex> persistenceAlgorithm = Plex4.getDefaultSimplicialAlgorithm(maxDimension + 1);
		BarcodeCollection<Double> barcodes = persistenceAlgorithm.computeIntervals(flattenedStream);
		System.out.println(barcodes.toString());
	}
}
