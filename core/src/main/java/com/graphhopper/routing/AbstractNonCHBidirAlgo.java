/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;

import java.util.PriorityQueue;

import static com.graphhopper.util.EdgeIterator.ANY_EDGE;
import static com.graphhopper.util.EdgeIterator.NO_EDGE;

/**
 * Common subclass for bidirectional algorithms.
 *
 * @author Peter Karich
 * @author easbar
 * @see AbstractBidirCHAlgo for bidirectional CH algorithms
 */
public abstract class AbstractNonCHBidirAlgo extends AbstractBidirAlgo implements BidirRoutingAlgorithm {
    protected final Graph graph;
    protected final Weighting weighting;
    protected final FlagEncoder flagEncoder;
    protected EdgeExplorer edgeExplorer;
    protected EdgeFilter inEdgeFilter;
    protected EdgeFilter outEdgeFilter;
    protected EdgeFilter additionalEdgeFilter;

    public AbstractNonCHBidirAlgo(Graph graph, Weighting weighting, TraversalMode tMode) {
        super(tMode);
        this.weighting = weighting;
        this.flagEncoder = weighting.getFlagEncoder();
        this.graph = graph;
        this.nodeAccess = graph.getNodeAccess();
        edgeExplorer = graph.createEdgeExplorer();
        outEdgeFilter = DefaultEdgeFilter.outEdges(flagEncoder.getAccessEnc());
        inEdgeFilter = DefaultEdgeFilter.inEdges(flagEncoder.getAccessEnc());
        int size = Math.min(Math.max(200, graph.getNodes() / 10), 150_000);
        initCollections(size);
    }

    /**
     * Creates a new entry of the shortest path tree (a {@link SPTEntry} or one of its subclasses) during a dijkstra
     * expansion.
     *
     * @param edge    the edge that is currently processed for the expansion
     * @param incEdge the id of the edge that is incoming to the node the edge is pointed at. usually this is the same as
     *                edge.getEdge(), but for edge-based CH and in case edge is a shortcut incEdge is the original edge
     *                that is incoming to the node
     * @param weight  the weight the shortest path three entry should carry
     * @param parent  the parent entry of in the shortest path tree
     * @param reverse true if we are currently looking at the backward search, false otherwise
     */
    protected abstract SPTEntry createEntry(EdgeIteratorState edge, int incEdge, double weight, SPTEntry parent, boolean reverse);

    protected BidirPathExtractor createPathExtractor(Graph graph, Weighting weighting) {
        return new BidirPathExtractor(graph, weighting);
    }

    protected void postInitFrom() {
        if (fromOutEdge == ANY_EDGE) {
            fillEdgesFromUsingFilter(additionalEdgeFilter);
        } else {
            // need to use a local reference here, because additionalEdgeFilter is modified when calling fillEdgesFromUsingFilter
            final EdgeFilter tmpFilter = additionalEdgeFilter;
            fillEdgesFromUsingFilter(new EdgeFilter() {
                @Override
                public boolean accept(EdgeIteratorState edgeState) {
                    return (tmpFilter == null || tmpFilter.accept(edgeState)) && edgeState.getOrigEdgeFirst() == fromOutEdge;
                }
            });
        }
    }

    protected void postInitTo() {
        if (toInEdge == ANY_EDGE) {
            fillEdgesToUsingFilter(additionalEdgeFilter);
        } else {
            final EdgeFilter tmpFilter = additionalEdgeFilter;
            fillEdgesToUsingFilter(new EdgeFilter() {
                @Override
                public boolean accept(EdgeIteratorState edgeState) {
                    return (tmpFilter == null || tmpFilter.accept(edgeState)) && edgeState.getOrigEdgeLast() == toInEdge;
                }
            });
        }
    }

    /**
     * @param edgeFilter edge filter used to fill edges. the {@link #additionalEdgeFilter} reference will be set to
     *                   edgeFilter by this method, so make sure edgeFilter does not use it directly.
     */
    protected void fillEdgesFromUsingFilter(EdgeFilter edgeFilter) {
        // we temporarily ignore the additionalEdgeFilter
        EdgeFilter tmpFilter = additionalEdgeFilter;
        additionalEdgeFilter = edgeFilter;
        finishedFrom = !fillEdgesFrom();
        additionalEdgeFilter = tmpFilter;
    }

    /**
     * @see #fillEdgesFromUsingFilter(EdgeFilter)
     */
    protected void fillEdgesToUsingFilter(EdgeFilter edgeFilter) {
        // we temporarily ignore the additionalEdgeFilter
        EdgeFilter tmpFilter = additionalEdgeFilter;
        additionalEdgeFilter = edgeFilter;
        finishedTo = !fillEdgesTo();
        additionalEdgeFilter = tmpFilter;
    }

    @Override
    boolean fillEdgesFrom() {
        if (pqOpenSetFrom.isEmpty()) {
            return false;
        }
        currFrom = pqOpenSetFrom.poll();
        visitedCountFrom++;
        if (fromEntryCanBeSkipped()) {
            return true;
        }
        if (fwdSearchCanBeStopped()) {
            return false;
        }
        bestWeightMapOther = bestWeightMapTo;
        fillEdges(currFrom, pqOpenSetFrom, bestWeightMapFrom, false);
        return true;
    }

    @Override
    boolean fillEdgesTo() {
        if (pqOpenSetTo.isEmpty()) {
            return false;
        }
        currTo = pqOpenSetTo.poll();
        visitedCountTo++;
        if (toEntryCanBeSkipped()) {
            return true;
        }
        if (bwdSearchCanBeStopped()) {
            return false;
        }
        bestWeightMapOther = bestWeightMapFrom;
        fillEdges(currTo, pqOpenSetTo, bestWeightMapTo, true);
        return true;
    }

    private void fillEdges(SPTEntry currEdge, PriorityQueue<SPTEntry> prioQueue, IntObjectMap<SPTEntry> bestWeightMap, boolean reverse) {
        EdgeIterator iter = edgeExplorer.setBaseNode(currEdge.adjNode);
        while (iter.next()) {
            if (!accept(iter, currEdge, reverse))
                continue;

            final double weight = calcWeight(iter, currEdge, reverse);
            if (Double.isInfinite(weight)) {
                continue;
            }
            final int origEdgeId = getOrigEdgeId(iter, reverse);
            final int traversalId = getTraversalId(iter, origEdgeId, reverse);
            SPTEntry entry = bestWeightMap.get(traversalId);
            if (entry == null) {
                entry = createEntry(iter, origEdgeId, weight, currEdge, reverse);
                bestWeightMap.put(traversalId, entry);
                prioQueue.add(entry);
            } else if (entry.getWeightOfVisitedPath() > weight) {
                prioQueue.remove(entry);
                updateEntry(entry, iter, origEdgeId, weight, currEdge, reverse);
                prioQueue.add(entry);
            } else
                continue;

            if (updateBestPath) {
                // only needed for edge-based -> skip the calculation and use dummy value otherwise
                double edgeWeight = traversalMode.isEdgeBased() ? weighting.calcWeight(iter, reverse, EdgeIterator.NO_EDGE) : Double.POSITIVE_INFINITY;
                updateBestPath(edgeWeight, entry, origEdgeId, traversalId, reverse);
            }
        }
    }

    protected void updateEntry(SPTEntry entry, EdgeIteratorState edge, int edgeId, double weight, SPTEntry parent, boolean reverse) {
        entry.edge = edge.getEdge();
        entry.weight = weight;
        entry.parent = parent;
    }

    protected boolean accept(EdgeIteratorState edge, SPTEntry currEdge, boolean reverse) {
        return accept(edge, getIncomingEdge(currEdge));
    }

    protected int getOrigEdgeId(EdgeIteratorState edge, boolean reverse) {
        return edge.getEdge();
    }

    protected int getTraversalId(EdgeIteratorState edge, int origEdgeId, boolean reverse) {
        return traversalMode.createTraversalId(edge, reverse);
    }

    protected double calcWeight(EdgeIteratorState iter, SPTEntry currEdge, boolean reverse) {
        // todo: for #1776/#1835 move access flag checks into weighting
        final boolean access = reverse ? inEdgeFilter.accept(iter) : outEdgeFilter.accept(iter);
        if (!access) {
            return Double.POSITIVE_INFINITY;
        }
        return weighting.calcWeight(iter, reverse, getIncomingEdge(currEdge)) + currEdge.getWeightOfVisitedPath();
    }

    @Override
    protected double getInEdgeWeight(SPTEntry entry) {
        return weighting.calcWeight(graph.getEdgeIteratorState(getIncomingEdge(entry), entry.adjNode), false, NO_EDGE);
    }

    @Override
    protected int getOtherNode(int edge, int node) {
        return graph.getOtherNode(edge, node);
    }

    @Override
    protected Path extractPath() {
        if (finished())
            return createPathExtractor(graph, weighting).extract(bestFwdEntry, bestBwdEntry, bestWeight);

        return createEmptyPath();
    }

    public RoutingAlgorithm setEdgeFilter(EdgeFilter additionalEdgeFilter) {
        this.additionalEdgeFilter = additionalEdgeFilter;
        return this;
    }

    protected boolean accept(EdgeIteratorState iter, int prevOrNextEdgeId) {
        // for edge-based traversal we leave it for TurnWeighting to decide whether or not a u-turn is acceptable,
        // but for node-based traversal we exclude such a turn for performance reasons already here
        if (!traversalMode.isEdgeBased() && iter.getEdge() == prevOrNextEdgeId)
            return false;

        return additionalEdgeFilter == null || additionalEdgeFilter.accept(iter);
    }

    protected Path createEmptyPath() {
        return new Path(graph);
    }

    @Override
    public String toString() {
        return getName() + "|" + weighting;
    }

}