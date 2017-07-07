package org.appliedtopology.tda4j.algebra.generation;

import java.util.Collection;
import java.util.Hashtable;
import java.util.Vector;

/**
 * This class contains utility functions to assist with the automated generation of 
 * java code. An instance of this class is passed to the velocity template renderer.
 * This class is designed to be a stateless singleton. 
 * 
 * A java class is specified by a set of "template" types and a set of "generic" types.
 * The generic types are just regular java generic type templateTypes. The template types
 * are designed to mimic genericTypes, but are allowed to be primitive.
 * 
 * For example, suppose that one is designing a hash-table that maps one type to another.
 * We do not want to place restrictions on whether the key or value should be of primitive
 * or object type. To do this, we have to generate the hash-table class for each pair of
 * primitive an non-primitive types. We would have in this case:
 * templateTypes = {int, int} (class becomes IntIntHashTable)
 * templateTypes = {int, double} (class becomes IntDoubleHashTable)
 * ...
 * templateTypes = {int, T} (class becomes IntGenericHashTable<T>)
 * 
 * @author Andrew Tausz
 *
 */
public class JavaGeneratorUtility {
	private static String objectLabel = "Object";
	private static JavaGeneratorUtility instance = new JavaGeneratorUtility();
	
	private final Vector<String> primitiveTypes = new Vector<String>();
	private final Vector<String> numericTypes = new Vector<String>();
	private final Hashtable<String, String> boxedNames = new Hashtable<String, String>();
	private final Hashtable<String, String> defaultValues = new Hashtable<String, String>();
	
	private final Vector<String> comparisonOperators = new Vector<String>();
	private final Hashtable<String, String> comparisonNames = new Hashtable<String, String>();
	
	private JavaGeneratorUtility() {
		primitiveTypes.add("byte");
		primitiveTypes.add("short");
		primitiveTypes.add("int");
		primitiveTypes.add("long");
		primitiveTypes.add("float");
		primitiveTypes.add("double");
		primitiveTypes.add("boolean");
		primitiveTypes.add("char");
		
		numericTypes.add("int");
		numericTypes.add("long");
		numericTypes.add("float");
		numericTypes.add("double");
		
		boxedNames.put("byte", "Byte");
		boxedNames.put("short", "Short");
		boxedNames.put("int", "Integer");
		boxedNames.put("long", "Long");
		boxedNames.put("float", "Float");
		boxedNames.put("double", "Double");
		boxedNames.put("boolean", "Boolean");
		boxedNames.put("char", "Character");
		
		defaultValues.put("byte", "0");
		defaultValues.put("short", "0");
		defaultValues.put("int", "0");
		defaultValues.put("long", "0L");
		defaultValues.put("float", "0.0f");
		defaultValues.put("double", "0.0d");
		defaultValues.put("boolean", "false");
		defaultValues.put("char", "\\u0000");
		defaultValues.put(objectLabel, "null");
		
		comparisonOperators.add("==");
		comparisonOperators.add("!=");
		comparisonOperators.add("<");
		comparisonOperators.add(">");
		comparisonOperators.add("<=");
		comparisonOperators.add(">=");
		
		comparisonNames.put("==", "Equal");
		comparisonNames.put("!=", "NotEqual");
		comparisonNames.put("<", "LessThan");
		comparisonNames.put(">", "GreaterThan");
		comparisonNames.put("<=", "LessThanOrEqual");
		comparisonNames.put(">=", "GreaterThanOrEqual");
	}
	
	public static JavaGeneratorUtility getInstance() {
		return instance;
	}
	
	/**
	 * This function returns the full class name of a class defined by its basic name (tag), its template types,
	 * and its generic types. For example, suppose that the tag is "Foo", the template types are {int, T, double},
	 * and the generic types are {U, W}, then this function returns IntObjectDoubleFoo<T, U, V>.
	 * 
	 * @param tag the basic name of the class
	 * @param templateTypes the set of template types to parameterize the class (may be primitive or generic)
	 * @param genericTypes the set of object types to use as generic parameters
	 * 
	 * @return the full name of the class with generic annotations
	 */
	public String getAnnotatedClassName(String tag, Collection<String> templateTypes, Collection<String> genericTypes) {
		return getClassName(tag, templateTypes) + getGenericAnnotation(templateTypes, genericTypes);
	}
	
	/**
	 * This function returns the class name of a class along with appropriate wildcard generic parameters. For example,
	 * suppse that the tag is "Foo", the template types are {int, T, double}, and the generic types are {U, W}, then 
	 * this function returns IntObjectDoubleFoo<?, ?, ?>.
	 * 
	 * @param tag the basic name of the class
	 * @param templateTypes the set of template types to parameterize the class (may be primitive or generic)
	 * @param genericTypes the set of object types to use as generic parameters
	 * 
	 * @return the name of the class with wildcard generic annotations
	 */
	public String getWildcardClassName(String tag, Collection<String> templateTypes, Collection<String> genericTypes) {
		return getClassName(tag, templateTypes) + getGenericWildcardAnnotation(templateTypes, genericTypes);
	}
	
	/**
	 * This function returns the class name of a class defined by its basic name (tag), its template types,
	 * and its generic types. For example, suppose that the tag is "Foo", the template types are {int, T, double},
	 * and the generic types are {U, W}, then this function returns IntObjectDoubleFoo.
	 * 
	 * @param tag the basic name of the class
	 * @param templateTypes the set of template types to parameterize the class (may be primitive or generic)
	 * @param genericTypes the set of object types to use as generic parameters
	 * 
	 * @return the name of the class without generic annotations
	 */
	public String getClassName(String tag, Collection<String> templateTypes, Collection<String> genericTypes) {
		return getClassName(tag, templateTypes);
	}
	/**
	 * This function returns the class name of a class defined by its basic name (tag), its template types. 
	 * For example, suppose that the tag is "Foo", the template types are {int, T, double}, then this function 
	 * returns IntObjectDoubleFoo.
	 * 
	 * @param tag the basic name of the class
	 * @param templateTypes the set of template types to parameterize the class (may be primitive or generic)
	 * 
	 * @return the  name of the class without generic annotations
	 */
	public String getClassName(String tag, Collection<String> templateTypes) {
		StringBuilder builder = new StringBuilder();
		
		for (String type: templateTypes) {
			builder.append(getClassNameModifier(type));
		}
		
		builder.append(tag);
		
		return builder.toString();
	}
	
	public String getGenericAnnotation(String type1, String type2) {
		Vector<String> types = new Vector<String>();
		types.add(type1);
		types.add(type2);
		return getGenericAnnotation(types, new Vector<String>());
	}
	
	public String getGenericAnnotation(Collection<String> templateTypes) {
		return getGenericAnnotation(templateTypes, new Vector<String>(), false);
	}
	
	
	public String getGenericAnnotation(Collection<String> templateTypes, Collection<String> genericTypes) {
		return getGenericAnnotation(templateTypes, genericTypes, false);
	}
	
	public String getGenericWildcardAnnotation(Collection<String> templateTypes, Collection<String> genericTypes) {
		return getGenericAnnotation(templateTypes, genericTypes, true);
	}
	
	public String getGenericAnnotation(Collection<String> templateTypes, Collection<String> genericTypes, boolean wildcard) {
		boolean containsNonPrimitive = false;
		if (!genericTypes.isEmpty()) {
			containsNonPrimitive = true;
		} else {
			for (String type: templateTypes) {
				if (!isPrimitive(type)) {
					containsNonPrimitive = true;
					break;
				}
			}
		}
		
		if (!containsNonPrimitive) {
			return "";
		}
		
		// at this point we have at least 1 non-primitive type parameter
		
		StringBuilder builder = new StringBuilder();
		int count = 0;
		
		builder.append("<");
		
		for (String type: templateTypes) {
			if (!isPrimitive(type)) {
				if (count > 0) {
					builder.append(", ");
				}
				if (wildcard) {
					builder.append("?");
				} else {
					builder.append(type);
				}
				count++;
			}
		}
		
		// add all generic types
		for (String type: genericTypes) {
			if (count > 0) {
				builder.append(", ");
			}
			if (wildcard) {
				builder.append("?");
			} else {
				builder.append(type);
			}
			count++;
		}
		
		builder.append(">");
		
		return builder.toString();
	}
	
	private String firstToUpperCase(String s) {
		if (s.length() == 0) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
	}
	
	public String getClassNameModifier(String type) {
		if (isPrimitive(type)) {
			return this.firstToUpperCase(type);
		} else {
			return objectLabel;
		}
	}
	
	public String toUnderscoreFormat(String javaName) {
		int underscoreCount = 0;
		char[] characters = javaName.toCharArray();
		
		for (int i = 0; i < characters.length - 1; i++) {
			if (isLower(characters[i]) && !isLower(characters[i + 1])) {
				underscoreCount++;
			}
		}
		
		char[] newCharacters = new char[characters.length + underscoreCount];
		int targetIndex = 0;
		
		for (int i = 0; i < characters.length; i++) {
			newCharacters[targetIndex] = characters[i];
			targetIndex++;
			if (i < characters.length - 1 && isLower(characters[i]) && !isLower(characters[i + 1])) {
				newCharacters[targetIndex] = '_';
				targetIndex++;
			}
		}
		
		return new String(newCharacters).toLowerCase();
	}
	
	private boolean isLower(char c) {
		return (c >= 'a' && c <= 'z');
	}
	
	public String getTroveNameModifier(String type) {
		if (isPrimitive(type)) {
			return this.firstToUpperCase(type);
		} else {
			return "Object";
		}
	}
	
	public String getMapType(String keyType, String valueType) {
		if (!isPrimitive(keyType) && !isPrimitive(valueType)) {
			return "THashMap";
		}
		// eg. TIntDoubleHashMap
		return "T" + getTroveNameModifier(keyType) + getTroveNameModifier(valueType) + "HashMap";
	}
	
	public String getMapIteratorType(String keyType, String valueType) {
		// eg. TIntDoubleIterator
		return "T" + getTroveNameModifier(keyType) + getTroveNameModifier(valueType) + "Iterator";
	}
	
	public String beginMapIteration(String keyType, String valueType, String mapName, String keyVariable, String valueVariable) {
		String iteratorType = getMapIteratorType(keyType, valueType);
		
		StringBuilder builder = new StringBuilder();
		
		if (!isPrimitive(keyType) && !isPrimitive(valueType)) {
			// we have a THashMap - we have to use the standard Iterable 
			builder.append("for (Map.Entry<" + keyType + ", " + valueType + "> __entry : " + mapName + ".entrySet()) {");
			builder.append(keyType + " " + keyVariable + " = " + "__entry.getKey();");
			builder.append(valueType + " " + valueVariable + " = " + "__entry.getValue();");
		} else {
			// we use Trove-style iteration
			builder.append("for (" + iteratorType + " __iterator = " + mapName + ".iterator(); __iterator.hasNext(); ) {");
			builder.append("__iterator.advance();");
			builder.append(keyType + " " + keyVariable + " = " + "__iterator.key();");
			builder.append(valueType + " " + valueVariable + " = " + "__iterator.value();");
		}
		
		return builder.toString();
	}
	
	public String endMapIteration() {
		return "}";
	}
	
	public String beginMapIteration(String keyType, String valueType, String mapName) {
		return beginMapIteration(keyType, valueType, mapName, "__key", "__value");
	}
	
	public String getDefaultElement(String type) {
		if (isPrimitive(type)) {
			return this.defaultValues.get(type);
		} else {
			return "null";
		}
	}
	
	public boolean isPrimitive(String type) {
		return this.primitiveTypes.contains(type);
	}
	
	public boolean isNumeric(String type) {
		return this.numericTypes.contains(type);
	}
	
	public Vector<String> getPrimitiveTypes() {
		return this.primitiveTypes;
	}
	
	public Vector<String> getNumericTypes() {
		return this.numericTypes;
	}
	
	public Vector<String> getCommonTypes() {
		Vector<String> types = new Vector<String>();
		types.add("int");
		types.add("double");
		types.add("boolean");
		return types;
	}
	
	public String getBoxedName(String primitiveName) {
		return this.boxedNames.get(primitiveName);
	}
	
	public String getComparisonName(String comparison) {
		return this.comparisonNames.get(comparison);
	}
	
	public Vector<String> getAllComparisons() { 
		return this.comparisonOperators;
	}
}
