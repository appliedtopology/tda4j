package org.appliedtopology.tda4j.algebra.generation;

import java.util.List;
import java.util.Vector;

public class GeneratorDriver {
	private static List<ClassSpecifier> classSpecifiers = new Vector<ClassSpecifier>();
	private static JavaGeneratorUtility utility = JavaGeneratorUtility.getInstance();
	
	private static String basePackageName = "org.appliedtopology.tda4j.algebra.autogen";
	private static String baseSourceDirectory = "src";
	private static String templateDirectory = "templates/";
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		addArrayClasses();
		addPairClasses();
		addMatrixClasses();
		addIoClasses();
		addAlgebraicClasses();
		addFormalSumClasses();
		addFunctionalClasses();
		
		generateClasses();
	}

	private static void addAlgebraicClasses() {
		Vector<String> types = utility.getCommonTypes();
		types.add("R");
		String[] classes = new String[]{"AbstractField", "AbstractModule", "AbstractRing"};
		String packageId = "algebraic";

		for (String type: types) {
			Vector<String> templateTypes = new Vector<String>();
			templateTypes.add(type);
			for (String classTag: classes) {
				ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
				if (classTag.equals("AbstractModule")) {
					specifier.addGenericType("M");
				}
				classSpecifiers.add(specifier);
			}
		}
	}
	
	private static void addArrayClasses() {
		Vector<String> types = utility.getNumericTypes();
		String[] classes = new String[]{"ArrayManipulation", "ArrayMath", "ArrayQuery", "ArrayUtility", "ArrayGeneration"};
		String packageId = "array";

		for (String type: types) {
			Vector<String> templateTypes = new Vector<String>();
			templateTypes.add(type);
			for (String classTag: classes) {
				ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
				specifier.addAdditionalContext("comparisons", utility.getAllComparisons());
				classSpecifiers.add(specifier);
			}
		}
	}

	private static void addFormalSumClasses() {
		Vector<String> types = utility.getCommonTypes();
		types.add("R");
		String[] classes = new String[]{"AbstractFormalSum", "AlgebraicFreeModule", "MatrixConverter",
										"PrimitiveFreeModule", "SparseFormalSum", "VectorConverter"};
		String packageId = "formal_sum";

		for (String type: types) {
			Vector<String> templateTypes = new Vector<String>();
			templateTypes.add(type);
			for (String classTag: classes) {
				if (classTag.equals("AlgebraicFreeModule") && (type.equals("boolean"))) {
					continue;
				}
				
				if (classTag.equals("PrimitiveFreeModule") && (type.equals("R"))) {
					continue;
				}
				
				ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
				specifier.addGenericType("M");
				if (classTag.equals("MatrixConverter")) {
					specifier.addGenericType("N");
				}
				classSpecifiers.add(specifier);
			}
		}
	}
	
	private static void addPairClasses() {
		Vector<String> types1 = utility.getCommonTypes();
		Vector<String> types2 = utility.getCommonTypes();
		types1.add("T");
		types2.add("U");
		String[] classes = new String[]{"Pair", "PairComparator"};
		String packageId = "pair";

		for (String type1: types1) {
			for (String type2: types2) {
				Vector<String> templateTypes = new Vector<String>();
				templateTypes.add(type1);
				templateTypes.add(type2);
				for (String classTag: classes) {
					if (classTag.equals("PairComparator") && (type1.equals("boolean") || type2.equals("boolean"))) {
						continue;
					}
					
					ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
					classSpecifiers.add(specifier);
				}
			}
		}
	}

	private static void addMatrixClasses() {
		Vector<String> types = utility.getCommonTypes();
		types.add("R");
		String[] classes = new String[]{"AbstractMatrix", "AbstractVector", "MatrixEntry", "SparseMatrix",
										"SparseMatrixIterator", "SparseVector", "SparseVectorIterator", "VectorEntry"};
		String packageId = "matrix";

		for (String type: types) {
			Vector<String> templateTypes = new Vector<String>();
			templateTypes.add(type);
			for (String classTag: classes) {
				ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
				classSpecifiers.add(specifier);
			}
		}
	}
	
	private static void addIoClasses() {
		Vector<String> types = utility.getCommonTypes();
		String[] classes = new String[]{"MatlabOutput"};
		String packageId = "io";

		for (String type: types) {
			Vector<String> templateTypes = new Vector<String>();
			templateTypes.add(type);
			for (String classTag: classes) {
				ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
				classSpecifiers.add(specifier);
			}
		}
	}
	
	private static void addFunctionalClasses() {
		Vector<String> types1 = utility.getCommonTypes();
		Vector<String> types2 = utility.getCommonTypes();
		types1.add("T");
		types2.add("U");
		String[] classes = new String[]{"Function"};
		String packageId = "functional";

		for (String type1: types1) {
			for (String type2: types2) {
				Vector<String> templateTypes = new Vector<String>();
				templateTypes.add(type1);
				templateTypes.add(type2);
				for (String classTag: classes) {
					ClassSpecifier specifier = new ClassSpecifier(packageId, classTag, templateTypes);
					classSpecifiers.add(specifier);
				}
			}
		}
	}
	
	private static void generateClasses() {
		JavaCodeGenerator generator = new JavaCodeGenerator(basePackageName, templateDirectory, baseSourceDirectory);
		generator.generateClasses(classSpecifiers);
	}

}
