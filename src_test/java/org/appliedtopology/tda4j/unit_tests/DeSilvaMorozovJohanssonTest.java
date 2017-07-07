package org.appliedtopology.tda4j.unit_tests;

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.autogen.homology.ObjectAbsoluteHomology;
import org.appliedtopology.tda4j.autogen.homology.ObjectRelativeHomology;
import org.appliedtopology.tda4j.examples.DeSilvaMorozovJohanssonExample;
import org.appliedtopology.tda4j.homology.barcodes.AnnotatedBarcodeCollection;
import org.appliedtopology.tda4j.homology.chain_basis.Cell;
import org.appliedtopology.tda4j.homology.chain_basis.CellComparator;
import org.appliedtopology.tda4j.homology.interfaces.AbstractPersistenceBasisAlgorithm;
import edu.stanford.math.primitivelib.algebraic.impl.ModularIntegerField;
import edu.stanford.math.primitivelib.autogen.algebraic.ObjectAbstractField;
import edu.stanford.math.primitivelib.autogen.formal_sum.ObjectSparseFormalSum;

public class DeSilvaMorozovJohanssonTest {
	private final ObjectAbstractField<Integer> field = ModularIntegerField.getInstance(13);
	private final DeSilvaMorozovJohanssonExample<Integer> example = new DeSilvaMorozovJohanssonExample<Integer>(field);
	
	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testAbsoluteHomology() {
		AbstractPersistenceBasisAlgorithm<Cell, ObjectSparseFormalSum<Integer, Cell>> persistenceAlgorithm = new ObjectAbsoluteHomology<Integer, Cell>(field, CellComparator.getInstance(), 0, 3);
		AnnotatedBarcodeCollection<Integer, ObjectSparseFormalSum<Integer, Cell>> collection = persistenceAlgorithm.computeAnnotatedIndexIntervals(example.getCellComplex());
		assertTrue(collection.equals(example.getAbsoluteHomologyBarcodes()));
	}
	
	@Test
	public void testRelativeHomology() {
		AbstractPersistenceBasisAlgorithm<Cell, ObjectSparseFormalSum<Integer, Cell>> persistenceAlgorithm = new ObjectRelativeHomology<Integer, Cell>(field, CellComparator.getInstance(), 0, 3);
		AnnotatedBarcodeCollection<Integer, ObjectSparseFormalSum<Integer, Cell>> collection = persistenceAlgorithm.computeAnnotatedIndexIntervals(example.getCellComplex());
		assertTrue(collection.equals(example.getRelativeHomologyBarcodes()));
	}
}
