package org.appliedtopology.tda4j.example_tests;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.homology.chain_basis.SimplexPair;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexPairComparator;
import org.appliedtopology.tda4j.streams.impl.ExplicitSimplexStream;
import org.appliedtopology.tda4j.streams.impl.ExplicitStream;
import org.appliedtopology.tda4j.utility.RandomUtility;
import edu.stanford.math.primitivelib.algebraic.impl.ModularIntField;
import edu.stanford.math.primitivelib.autogen.algebraic.IntAbstractField;

public class VdsWitnessExample {
	IntAbstractField intField = ModularIntField.getInstance(2);
	@Before
	public void setUp() {
		RandomUtility.initializeWithSeed(0);
	}

	@After
	public void tearDown() {}

	
	public ExplicitSimplexStream getX() {
		ExplicitSimplexStream stream = new ExplicitSimplexStream();
		stream.addElement(new int[]{0});
		stream.addElement(new int[]{2});
		stream.addElement(new int[]{4});
		stream.addElement(new int[]{0, 2});
		stream.addElement(new int[]{0, 4});
		stream.addElement(new int[]{2, 4});
		return stream;
	}
	
	public ExplicitSimplexStream getY() {
		ExplicitSimplexStream stream = new ExplicitSimplexStream();
		stream.addElement(new int[]{1});
		stream.addElement(new int[]{3});
		stream.addElement(new int[]{5});
		stream.addElement(new int[]{1, 3});
		stream.addElement(new int[]{1, 5});
		stream.addElement(new int[]{3, 5});
		return stream;
	}
	
	public ExplicitStream<SimplexPair> getZ() {
		ExplicitStream<SimplexPair> subsetStream = new ExplicitStream<SimplexPair>(SimplexPairComparator.getInstance());
		
		subsetStream.addElement(new SimplexPair(new int[]{0}, new int[]{1}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{0}, new int[]{5}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{2}, new int[]{1}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{2}, new int[]{3}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{4}, new int[]{3}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{4}, new int[]{5}), 0);
		
		subsetStream.addElement(new SimplexPair(new int[]{0}, new int[]{1, 5}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{0, 2}, new int[]{1}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{2}, new int[]{1, 3}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{0, 4}, new int[]{5}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{4}, new int[]{3, 5}), 0);
		subsetStream.addElement(new SimplexPair(new int[]{2, 4}, new int[]{3}), 0);
		return subsetStream;
	}
	
	@Test
	public void test() {
		
	}
}
