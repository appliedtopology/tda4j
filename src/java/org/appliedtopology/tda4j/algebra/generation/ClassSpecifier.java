package org.appliedtopology.tda4j.algebra.generation;

import java.util.Hashtable;
import java.util.Vector;

/**
 * This class contains fields that are used to render a java class from a template.
 * 
 * @author Andrew Tausz
 *
 */
public class ClassSpecifier {
	private final String packageSpecifier;
	private final String classTag;
	private final Vector<String> templateTypes;
	private final Vector<String> genericTypes;
	private final Hashtable<String, Object> additionalContext = new Hashtable<String, Object>();
	
	public ClassSpecifier(String packageSpecifier, String classTag) {
		super();
		this.packageSpecifier = packageSpecifier;
		this.classTag = classTag;
		this.templateTypes = new Vector<String>();
		this.genericTypes = new Vector<String>();
	}
	
	public ClassSpecifier(String packageSpecifier, String classTag, Vector<String> templateTypes) {
		super();
		this.packageSpecifier = packageSpecifier;
		this.classTag = classTag;
		this.templateTypes = templateTypes;
		this.genericTypes = new Vector<String>();
	}
	
	public ClassSpecifier(String packageSpecifier, String classTag, Vector<String> templateTypes, Vector<String> genericTypes) {
		super();
		this.packageSpecifier = packageSpecifier;
		this.classTag = classTag;
		this.templateTypes = templateTypes;
		this.genericTypes = genericTypes;
	}
	
	/**
	 * @return the packageSpecifier
	 */
	public String getPackageSpecifier() {
		return packageSpecifier;
	}

	/**
	 * @return the classTag
	 */
	public String getClassTag() {
		return classTag;
	}

	/**
	 * @return the templateTypes
	 */
	public Vector<String> getTemplateTypes() {
		return templateTypes;
	}

	/**
	 * @return the genericTypes
	 */
	public Vector<String> getGenericTypes() {
		return genericTypes;
	}

	/**
	 * @return the additionalContext
	 */
	public Hashtable<String, Object> getAdditionalContext() {
		return additionalContext;
	}
	
	/**
	 * Adds a "template" type parameter to the class. This may be either
	 * primitive, or might be something like "T" in which case it is 
	 * interpreted as an object type.
	 * 
	 * @param type
	 */
	public void addTemplateType(String type) {
		this.templateTypes.add(type);
	}
	
	/**
	 * Adds a generic type parameter to the class. The type must
	 * not be primitive or an existing class name. It should be something
	 * like "T" or "Type1". 
	 * 
	 * @param type the generic type parameter to add
	 */
	public void addGenericType(String type) {
		this.genericTypes.add(type);
	}
	
	/**
	 * Adds additional context available to the template renderer.
	 * 
	 * @param key the context variable name
	 * @param value the value of the context data
	 */
	public void addAdditionalContext(String key, Object value) {
		this.additionalContext.put(key, value);
	}
}
