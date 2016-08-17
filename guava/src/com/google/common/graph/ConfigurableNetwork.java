/*
 * Copyright (C) 2016 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.graph;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.graph.GraphConstants.DEFAULT_EDGE_COUNT;
import static com.google.common.graph.GraphConstants.DEFAULT_NODE_COUNT;
import static com.google.common.graph.GraphConstants.EDGE_NOT_IN_GRAPH;
import static com.google.common.graph.GraphConstants.NODE_NOT_IN_GRAPH;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Configurable implementation of {@link Network} that supports the options supplied by
 * {@link NetworkBuilder}.
 *
 * <p>This class maintains a map of nodes to {@link NetworkConnections}. This class also maintains
 * a map of edges to reference nodes. The reference node is defined to be the edge's source node
 * on directed graphs, and an arbitrary endpoint of the edge on undirected graphs.
 *
 * <p>{@code Set}-returning accessors return unmodifiable views: the view returned will reflect
 * changes to the graph (if the graph is mutable) but may not be modified by the user.
 * The behavior of the returned view is undefined in the following cases:
 * <ul>
 * <li>Removing the element on which the accessor is called (e.g.:
 *     <pre>{@code
 *     Set<N> adjacentNodes = adjacentNodes(node);
 *     graph.removeNode(node);}</pre>
 *     At this point, the contents of {@code adjacentNodes} are undefined.
 * </ul>
 *
 * <p>The time complexity of all {@code Set}-returning accessors is O(1), since views are returned.
 *
 * @author James Sexton
 * @author Joshua O'Madadhain
 * @author Omar Darwish
 * @param <N> Node parameter type
 * @param <E> Edge parameter type
 */
class ConfigurableNetwork<N, E> extends AbstractNetwork<N, E> {
  private final boolean isDirected;
  private final boolean allowsParallelEdges;
  private final boolean allowsSelfLoops;
  private final ElementOrder<N> nodeOrder;
  private final ElementOrder<E> edgeOrder;

  protected final MapIteratorCache<N, NetworkConnections<N, E>> nodeConnections;

  // We could make this a Map<E, Endpoints<N>>. It would make incidentNodes(edge) slightly faster,
  // but it would also make Networks consume 5 to 20+% (increasing with average degree) more memory.
  protected final MapIteratorCache<E, N> edgeToReferenceNode; // referenceNode == source if directed

  /**
   * Constructs a graph with the properties specified in {@code builder}.
   */
  ConfigurableNetwork(NetworkBuilder<? super N, ? super E> builder) {
    this(
        builder,
        builder.nodeOrder.<N, NetworkConnections<N, E>>createMap(
            builder.expectedNodeCount.or(DEFAULT_NODE_COUNT)),
        builder.edgeOrder.<E, N>createMap(
            builder.expectedEdgeCount.or(DEFAULT_EDGE_COUNT)));
  }

  /**
   * Constructs a graph with the properties specified in {@code builder}, initialized with
   * the given node and edge maps.
   */
  ConfigurableNetwork(NetworkBuilder<? super N, ? super E> builder,
      Map<N, NetworkConnections<N, E>> nodeConnections,
      Map<E, N> edgeToReferenceNode) {
    this.isDirected = builder.directed;
    this.allowsParallelEdges = builder.allowsParallelEdges;
    this.allowsSelfLoops = builder.allowsSelfLoops;
    this.nodeOrder = builder.nodeOrder.cast();
    this.edgeOrder = builder.edgeOrder.cast();
    // Prefer the heavier "MapRetrievalCache" for nodes if lookup is expensive. This optimizes
    // methods that access the same node(s) repeatedly, such as Graphs.removeEdgesConnecting().
    this.nodeConnections = (nodeConnections instanceof TreeMap)
        ? new MapRetrievalCache<N, NetworkConnections<N, E>>(nodeConnections)
        : new MapIteratorCache<N, NetworkConnections<N, E>>(nodeConnections);
    this.edgeToReferenceNode = new MapIteratorCache<E, N>(edgeToReferenceNode);
  }

  @Override
  public Set<N> nodes() {
    return nodeConnections.unmodifiableKeySet();
  }

  @Override
  public Set<E> edges() {
    return edgeToReferenceNode.unmodifiableKeySet();
  }

  @Override
  public boolean isDirected() {
    return isDirected;
  }

  @Override
  public boolean allowsParallelEdges() {
    return allowsParallelEdges;
  }

  @Override
  public boolean allowsSelfLoops() {
    return allowsSelfLoops;
  }

  @Override
  public ElementOrder<N> nodeOrder() {
    return nodeOrder;
  }

  @Override
  public ElementOrder<E> edgeOrder() {
    return edgeOrder;
  }

  @Override
  public Set<E> incidentEdges(Object node) {
    return checkedConnections(node).incidentEdges();
  }

  @Override
  public Endpoints<N> incidentNodes(Object edge) {
    N nodeA = checkedReferenceNode(edge);
    N nodeB = nodeConnections.get(nodeA).oppositeNode(edge);
    return Endpoints.of(this, nodeA, nodeB);
  }

  @Override
  public Set<N> adjacentNodes(Object node) {
    return checkedConnections(node).adjacentNodes();
  }

  @Override
  public Set<E> edgesConnecting(Object nodeA, Object nodeB) {
    NetworkConnections<N, E> connectionsA = checkedConnections(nodeA);
    if (!allowsSelfLoops && nodeA.equals(nodeB)) {
      return ImmutableSet.of();
    }
    checkArgument(containsNode(nodeB), NODE_NOT_IN_GRAPH, nodeB);
    return connectionsA.edgesConnecting(nodeB);
  }

  @Override
  public Set<E> inEdges(Object node) {
    return checkedConnections(node).inEdges();
  }

  @Override
  public Set<E> outEdges(Object node) {
    return checkedConnections(node).outEdges();
  }

  @Override
  public Set<N> predecessors(Object node) {
    return checkedConnections(node).predecessors();
  }

  @Override
  public Set<N> successors(Object node) {
    return checkedConnections(node).successors();
  }

  protected final NetworkConnections<N, E> checkedConnections(Object node) {
    checkNotNull(node, "node");
    NetworkConnections<N, E> connections = nodeConnections.get(node);
    checkArgument(connections != null, NODE_NOT_IN_GRAPH, node);
    return connections;
  }

  protected final N checkedReferenceNode(Object edge) {
    checkNotNull(edge, "edge");
    N referenceNode = edgeToReferenceNode.get(edge);
    checkArgument(referenceNode != null, EDGE_NOT_IN_GRAPH, edge);
    return referenceNode;
  }

  protected final boolean containsNode(@Nullable Object node) {
    return nodeConnections.containsKey(node);
  }

  protected final boolean containsEdge(@Nullable Object edge) {
    return edgeToReferenceNode.containsKey(edge);
  }
}
