package org.appliedtopology.tda4j.example_tests;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.api.Plex4;
import org.appliedtopology.tda4j.examples.PointCloudExamples;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.interfaces.AbstractPersistenceAlgorithm;
import org.appliedtopology.tda4j.streams.impl.VietorisRipsStream;

public class CustomFiltrationTest {

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testExample() {
	
		double[][] points = PointCloudExamples.getRandomCirclePoints(100);
		int maxDimension = 1;
		double[] filtrationValues = new double[] { 0, 0.01, 0.02, 0.05, 0.1, 0.11, 0.2, 0.3, 0.5 };
		VietorisRipsStream<double[]> stream = Plex4.createVietorisRipsStream(points, maxDimension + 1, filtrationValues);
		AbstractPersistenceAlgorithm<Simplex> algorithm = Plex4.getDefaultSimplicialAlgorithm(maxDimension + 1);
		BarcodeCollection<Double> intervals = algorithm.computeIntervals(stream);
		System.out.println(intervals);
	}
}
