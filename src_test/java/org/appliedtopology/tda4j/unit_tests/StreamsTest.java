package org.appliedtopology.tda4j.unit_tests;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.examples.PointCloudExamples;
import org.appliedtopology.tda4j.homology.StreamTester;
import org.appliedtopology.tda4j.metric.impl.EuclideanMetricSpace;
import org.appliedtopology.tda4j.metric.landmark.LandmarkSelector;
import org.appliedtopology.tda4j.metric.landmark.RandomLandmarkSelector;
import org.appliedtopology.tda4j.utility.RandomUtility;

/**
 * This class tests the equality of streams produced by plex 3 and plex 4.
 * 
 * @author Andrew Tausz
 *
 */
public class StreamsTest {
	private final List<double[][]> pointClouds = new ArrayList<double[][]>();
	private final int n = 60;
	private final int l = n/2;
	private final int d = 4;
	private final int maxDimension = 4;
	private final double maxFiltrationValue = 0.3;
	private final int numDivisions = 20;
	
	@Before
	public void setUp() {
		RandomUtility.initializeWithSeed(4);
		
		pointClouds.add(PointCloudExamples.getEquispacedCirclePoints(n));
		pointClouds.add(PointCloudExamples.getGaussianPoints(n, d));
		pointClouds.add(PointCloudExamples.getRandomFigure8Points(n));
		pointClouds.add(PointCloudExamples.getRandomSpherePoints(n, d - 1));
	}

	@After
	public void tearDown() {}
	
	@Test
	public void testVietorisRips() {
		for (double[][] pointCloud: pointClouds) {
			StreamTester.compareVietorisRipsStreams(pointCloud, maxDimension, maxFiltrationValue, numDivisions);
		}
	}
	
	@Test
	public void testLazyWitness() {
		for (double[][] pointCloud: pointClouds) {
			LandmarkSelector<double[]> landmarkSet = new RandomLandmarkSelector<double[]>(new EuclideanMetricSpace(pointCloud), l);
			StreamTester.compareLazyWitnessStreams(landmarkSet, maxDimension, maxFiltrationValue, numDivisions);
		}
	}
	
	@Test
	public void testWitness() {
		for (double[][] pointCloud: pointClouds) {
			LandmarkSelector<double[]> landmarkSet = new RandomLandmarkSelector<double[]>(new EuclideanMetricSpace(pointCloud), l);
			StreamTester.compareWitnessStreams(landmarkSet, maxDimension, maxFiltrationValue, numDivisions);
		}
	}
}
