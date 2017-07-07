clc; clear; close all;

n = 100;
K = 20;

generators = cell(3, 1);

generators{1} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 1, 0.5);
generators{2} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 2, 0.5);
generators{3} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 3, 0.5);
%generators{4} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 4, 0.5);

[bottleneck_distances] = pairwise_graph_analysis(generators, K);

%%

label = sprintf('random_geometric_%d_%d_%d', size(generators, 1), n, K);

[hm_handle, mds_handle] = visualize_dissimilarity_matrix(bottleneck_distances, generators, label);
