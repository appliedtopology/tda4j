clc; clear; close all;

n = 100;
p = 0.4;
q = 0.02;
c = 2;
P = diag((p - q) * ones(1, c)) + q * ones(c, c);

%%
generator = org.appliedtopology.tda4j.graph.random.StochasticBlockmodel(n, P);

graph = generator.generate();

options.dimensions = 2;

plot_graph(graph, options);
