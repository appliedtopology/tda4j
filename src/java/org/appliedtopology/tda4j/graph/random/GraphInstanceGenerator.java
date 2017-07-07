package org.appliedtopology.tda4j.graph.random;

import java.io.Serializable;

import org.appliedtopology.tda4j.graph.AbstractUndirectedGraph;
import org.appliedtopology.tda4j.graph.UndirectedListGraph;

/**
 * This class abstracts the construction of graphs.
 * 
 * @author Andrew Tausz
 *
 */
public abstract class GraphInstanceGenerator implements Serializable {
	private static final long serialVersionUID = 6049123403450474191L;

	public enum ImplementationType {
		adjacencyList, matrix, compact
	}

	protected ImplementationType implementationType;
	protected AbstractUndirectedGraph graphImplementation;

	public GraphInstanceGenerator() {
		this.implementationType = ImplementationType.adjacencyList;
	}

	public GraphInstanceGenerator(ImplementationType implementationType) {
		this.implementationType = implementationType;
	}

	public void setImplementationType(ImplementationType implementationType) {
		this.implementationType = implementationType;
	}

	protected AbstractUndirectedGraph initializeGraph(int numNodes) {
		if (this.implementationType == ImplementationType.adjacencyList) {
			return new UndirectedListGraph(numNodes);
		} else {
			return null;
		}
	}

	public abstract AbstractUndirectedGraph generate();
}
