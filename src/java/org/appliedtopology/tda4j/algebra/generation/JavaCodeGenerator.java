package org.appliedtopology.tda4j.algebra.generation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;
import java.util.Vector;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;

public class JavaCodeGenerator {
	private static JavaGeneratorUtility utility = JavaGeneratorUtility.getInstance();
	private final String basePackageName;
	private final String baseSourceDirectory;
	private final String templateDirectory;
	
	public JavaCodeGenerator(String basePackageName, String templateDirectory, String baseSourceDirectory) {
		this.basePackageName = basePackageName;
		this.templateDirectory = templateDirectory;
		this.baseSourceDirectory = baseSourceDirectory;
	}
	
	public void generateClasses(Iterable<ClassSpecifier> specifiers) {
		for (ClassSpecifier specifier: specifiers) {
			generateClass(specifier);
		}
	}
	
	public void generateClass(ClassSpecifier specifier) {
		generateClass(specifier.getPackageSpecifier(), specifier.getClassTag(), specifier.getTemplateTypes(), specifier.getGenericTypes(), specifier.getAdditionalContext());
	}
	
	public void generateClass(String packageIdentifier, String classTag) {
		generateClass(packageIdentifier, classTag, new Vector<String>(), new Vector<String>());
	}
	
	public void generateClass(String packageIdentifier, String classTag, Vector<String> templateTypes) {
		generateClass(packageIdentifier, classTag, templateTypes, new Vector<String>());
	}
	
	public void generateClass(String packageIdentifier, String classTag, Vector<String> templateTypes, Vector<String> genericTypes) {
		generateClass(packageIdentifier, classTag, templateTypes, genericTypes, new Hashtable<String, Object>());
	}
	
	public void generateClass(String packageIdentifier, String classTag, Vector<String> templateTypes, Vector<String> genericTypes, Hashtable<String, Object> additionalContext) {
		
		try {
			VelocityEngine ve = new VelocityEngine();
			ve.init();

			BufferedWriter writer = null;
			
			String packageName = basePackageName + "." + packageIdentifier;
			String directoryPath = baseSourceDirectory  + "/" + convertToPath(packageName);
			directoryPath = createDirectory(directoryPath);
			
			String className = utility.getClassName(classTag, templateTypes);
			String filePath = directoryPath + className + ".java";
			
			Template template = ve.getTemplate(templateDirectory + convertToPath(packageIdentifier) + "/" + classTag + ".vm");
			VelocityContext context = new VelocityContext();
			context.put("utility", utility);
			context.put("packageName", packageName);
			context.put("templateTypes", templateTypes);
			context.put("genericTypes", genericTypes);
			
			for (Map.Entry<String, Object> entry: additionalContext.entrySet()) {
				context.put(entry.getKey(), entry.getValue());
			}
			
			writer = new BufferedWriter(new FileWriter(filePath));
			template.merge(context, writer);
			writer.close();
			writer = null;
			
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ResourceNotFoundException e) {
			e.printStackTrace();
		} catch (ParseErrorException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private static String convertToPath(String javaPackageName) {
		return javaPackageName.replace('.', '/');
	}

	private static String createDirectory(String directoryPath) throws IOException {
		boolean directoryCreated = false;
		File directory = new File(directoryPath);
		directoryCreated = directory.isDirectory() || directory.mkdirs();
		if (!directoryCreated) {
			throw new IOException("Could not create directory: " + directoryPath);
		}
		return directory.getCanonicalPath() + System.getProperty("file.separator");
	}
}
