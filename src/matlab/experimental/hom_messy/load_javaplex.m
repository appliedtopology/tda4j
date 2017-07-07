% This script prepares the javaplex library for use

clc; clear all; close all;
clear import;

javaaddpath('./../javaplex.jar');
javaaddpath('./../plex-viewer.jar');
javaaddpath('./../jogl.jar');

import org.appliedtopology.tda4j.*;
import edu.stanford.math.plex_viewer.*;
