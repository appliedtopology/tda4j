package org.appliedtopology.tda4j.unit_tests;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.api.FilteredStreamInterface;
import org.appliedtopology.tda4j.api.PersistenceAlgorithmInterface;
import org.appliedtopology.tda4j.examples.CellStreamExamples;
import org.appliedtopology.tda4j.examples.PointCloudExamples;
import org.appliedtopology.tda4j.examples.SimplexStreamExamples;
import org.appliedtopology.tda4j.homology.PersistenceAlgorithmTester;
import org.appliedtopology.tda4j.homology.chain_basis.Cell;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.interfaces.AbstractPersistenceAlgorithm;
import org.appliedtopology.tda4j.metric.impl.EuclideanMetricSpace;
import org.appliedtopology.tda4j.metric.landmark.LandmarkSelector;
import org.appliedtopology.tda4j.metric.landmark.RandomLandmarkSelector;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;
import org.appliedtopology.tda4j.utility.RandomUtility;

/**
 * This class contains test for verifying that the different persistence algorithms produce the
 * same results.
 * 
 * @author Andrew Tausz
 *
 */
public class PersistenceAlgorithmEqualityTest {
	
	@Before
	public void setUp() {
		RandomUtility.initializeWithSeed(0);
	}

	@After
	public void tearDown() {}

	/**
	 * This function tests various small examples of filtered simplicial complexes.
	 */
	@Test
	public void testSmallSimplexStreams() {
		int maxDimension = 4;
		
		List<AbstractFilteredStream<Simplex>> streams = new ArrayList<AbstractFilteredStream<Simplex>>();
		
		streams.add(SimplexStreamExamples.getZomorodianCarlssonExample());
		streams.add(SimplexStreamExamples.getFilteredTriangle());
		streams.add(SimplexStreamExamples.getTriangle());
		streams.add(SimplexStreamExamples.getTetrahedron());
		streams.add(SimplexStreamExamples.getTorus());
		streams.add(SimplexStreamExamples.getCircle(7));
		streams.add(SimplexStreamExamples.getOctahedron());
		streams.add(SimplexStreamExamples.getIcosahedron());
		streams.add(SimplexStreamExamples.getAnnulus(4, 10));
		
		List<AbstractPersistenceAlgorithm<Simplex>> algorithms = PersistenceAlgorithmInterface.getAllSimplicialAbsoluteHomologyAlgorithms(maxDimension);
		PersistenceAlgorithmTester.verifyEquality(algorithms, streams);
	}
	
	/**
	 * This function tests various small examples of filtered cell complexes. Note that we only test
	 * the orientable examples, due to differing results due to torsion.
	 */
	@Test
	public void testSmallCellStreams() {
		int maxDimension = 4;
		
		List<AbstractFilteredStream<Cell>> streams = new ArrayList<AbstractFilteredStream<Cell>>();
		
		streams.add(CellStreamExamples.getMorozovJohanssonExample());
		streams.add(CellStreamExamples.getCellularSphere(maxDimension - 1));
		streams.add(CellStreamExamples.getCellularTorus());
		
		List<AbstractPersistenceAlgorithm<Cell>> algorithms = PersistenceAlgorithmInterface.getAllCellularAbsoluteHomologyAlgorithms(maxDimension);
		PersistenceAlgorithmTester.verifyEquality(algorithms, streams);
	}
	
	/**
	 * This function tests the algorithms on Vietoris-Rips complexes generated from point clouds.
	 */
	@Test
	public void testVietorisRipsPointClouds() {
		final int n = 120;
		final int maxDimension = 5;
		final double maxFiltrationValue = 0.5;
		final int numDivisions = 10;
		
		List<double[][]> pointClouds = new ArrayList<double[][]>();
		pointClouds.add(PointCloudExamples.getEquispacedCirclePoints(n));
		pointClouds.add(PointCloudExamples.getGaussianPoints(n, maxDimension));
		pointClouds.add(PointCloudExamples.getRandomFigure8Points(n));
		pointClouds.add(PointCloudExamples.getRandomSpherePoints(maxDimension * n, maxDimension - 1));
		
		List<AbstractFilteredStream<Simplex>> streams = new ArrayList<AbstractFilteredStream<Simplex>>();
		
		for (double[][] pointCloud: pointClouds) {
			streams.add(FilteredStreamInterface.createPlex4VietorisRipsStream(pointCloud, maxDimension + 1, maxFiltrationValue, numDivisions));
		}
		
		List<AbstractPersistenceAlgorithm<Simplex>> algorithms = PersistenceAlgorithmInterface.getAllSimplicialAbsoluteHomologyAlgorithms(maxDimension - 1);

		PersistenceAlgorithmTester.verifyEquality(algorithms, streams);
	}
	
	/**
	 * This function tests the algorithms on Lazy-Witness complexes generated from point clouds.
	 */
	@Test
	public void testLazyWitnessPointClouds() {
		final int n = 500;
		final int l = 50;
		final int maxDimension = 3;
		final double maxFiltrationValue = 0.3;
		final int numDivisions = 10;
		
		List<double[][]> pointClouds = new ArrayList<double[][]>();
		pointClouds.add(PointCloudExamples.getEquispacedCirclePoints(n));
		pointClouds.add(PointCloudExamples.getGaussianPoints(n, maxDimension));
		pointClouds.add(PointCloudExamples.getRandomFigure8Points(n));
		pointClouds.add(PointCloudExamples.getRandomSpherePoints(maxDimension * n, maxDimension - 1));
		
		List<AbstractFilteredStream<Simplex>> streams = new ArrayList<AbstractFilteredStream<Simplex>>();
		
		for (double[][] pointCloud: pointClouds) {
			LandmarkSelector<double[]> landmarkSet = new RandomLandmarkSelector<double[]>(new EuclideanMetricSpace(pointCloud), l);
			streams.add(FilteredStreamInterface.createPlex4LazyWitnessStream(landmarkSet, maxDimension, maxFiltrationValue, numDivisions));
		}
		
		List<AbstractPersistenceAlgorithm<Simplex>> algorithms = PersistenceAlgorithmInterface.getAllSimplicialAbsoluteHomologyAlgorithms(maxDimension - 1);
		PersistenceAlgorithmTester.verifyEquality(algorithms, streams);
	}
	
	/**
	 * This function tests a complex that contains approximately 500,000 simplices. It compares the
	 * efficiency of the different algorithms on a large complex.
	 */
	@Test
	public void testLargeFigure8Complex() {
		final int n = 220;
		final int maxDimension = 4;
		final double maxFiltrationValue = 0.5;
		final int numDivisions = 10;
		
		double[][] points = PointCloudExamples.getRandomFigure8Points(n);
		AbstractFilteredStream<Simplex> stream = FilteredStreamInterface.createPlex4VietorisRipsStream(points, maxDimension + 1, maxFiltrationValue, numDivisions);
		
		List<AbstractPersistenceAlgorithm<Simplex>> algorithms = PersistenceAlgorithmInterface.getAllSimplicialAbsoluteHomologyAlgorithms(maxDimension - 1);
		PersistenceAlgorithmTester.verifyEquality(algorithms, stream);
	}
	
	/**
	 * This function compares the algorithms on a Vietoris-Rips stream generated from sampling a 6-dimensional sphere.
	 */
	@Test
	public void testHighDimensionalSphere() {
		final int n = 48;
		final int sphereDimension = 6;
		final double maxFiltrationValue = 1.5;
		final int numDivisions = 10;
		
		double[][] points = PointCloudExamples.getRandomSpherePoints(n, sphereDimension);
		AbstractFilteredStream<Simplex> stream = FilteredStreamInterface.createPlex4VietorisRipsStream(points, sphereDimension + 1, maxFiltrationValue, numDivisions);
		
		List<AbstractPersistenceAlgorithm<Simplex>> algorithms = PersistenceAlgorithmInterface.getAllSimplicialAbsoluteHomologyAlgorithms(sphereDimension);
		PersistenceAlgorithmTester.verifyEquality(algorithms, stream);
	}
}
