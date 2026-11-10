package de.archer.admx.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ein vollständiges Flowboard für eine programmierbare Lichtshow.
 * Verwaltet Nodes auf einem 9x4 Canvas (36 Slots), Signalverbindungen und die Show-Gesamtlaufzeit.
 */
public final class Flowboard {

    public static final int CANVAS_SLOTS = 36;
    public static final int MIN_SHOW_DURATION_TICKS = 40;   // 2 Sekunden
    public static final int MAX_SHOW_DURATION_TICKS = 72000; // 3600 Sekunden (60 Minuten)
    public static final int DEFAULT_DURATION_TICKS = 400;   // 20 Sekunden

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type NODE_LIST_TYPE = new TypeToken<List<FlowNode>>() {}.getType();
    private static final Type CONN_LIST_TYPE = new TypeToken<List<FlowConnection>>() {}.getType();

    private final String boardId;
    private String name;
    private int durationTicks;
    private boolean loopMode;

    private final Map<Integer, FlowNode> nodesById = new LinkedHashMap<>();
    private final Map<Integer, Integer> nodeIdBySlot = new LinkedHashMap<>();
    private final List<FlowConnection> connections = new ArrayList<>();
    private final AtomicInteger nextNodeIdCounter = new AtomicInteger(1);

    public Flowboard(String boardId, String name, int durationTicks, boolean loopMode) {
        this.boardId = Objects.requireNonNull(boardId, "boardId");
        this.name = (name == null || name.isBlank()) ? "Show " + boardId : name;
        this.durationTicks = Math.clamp(durationTicks, MIN_SHOW_DURATION_TICKS, MAX_SHOW_DURATION_TICKS);
        this.loopMode = loopMode;
    }

    public Flowboard(String boardId, String name) {
        this(boardId, name, DEFAULT_DURATION_TICKS, true);
    }

    public String boardId() {
        return boardId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Show " + boardId : name;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public void setDurationTicks(int ticks) {
        this.durationTicks = Math.clamp(ticks, MIN_SHOW_DURATION_TICKS, MAX_SHOW_DURATION_TICKS);
    }

    public boolean isLoopMode() {
        return loopMode;
    }

    public void setLoopMode(boolean loopMode) {
        this.loopMode = loopMode;
    }

    public synchronized Map<Integer, FlowNode> nodes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(nodesById));
    }

    public synchronized List<FlowConnection> connections() {
        return Collections.unmodifiableList(new ArrayList<>(connections));
    }

    public synchronized FlowNode getNode(int nodeId) {
        return nodesById.get(nodeId);
    }

    public synchronized FlowNode getNodeAtSlot(int slot) {
        Integer id = nodeIdBySlot.get(slot);
        return id != null ? nodesById.get(id) : null;
    }

    public synchronized FlowNode addNode(int slot, FlowNodeType type, String targetGroup, int durationTicks) {
        if (slot < 0 || slot >= CANVAS_SLOTS) {
            return null;
        }
        if (nodeIdBySlot.containsKey(slot)) {
            // Slot bereits belegt
            return null;
        }
        int id = nextNodeIdCounter.getAndIncrement();
        FlowNode node = new FlowNode(id, slot, type, targetGroup, 0, durationTicks, 180f, 1.0f, "");
        nodesById.put(id, node);
        nodeIdBySlot.put(slot, id);
        return node;
    }

    public synchronized boolean putNode(FlowNode node) {
        if (node.slot() < 0) {
            return false;
        }
        if (node.slot() < CANVAS_SLOTS) {
            Integer existingAtSlot = nodeIdBySlot.get(node.slot());
            if (existingAtSlot != null && existingAtSlot != node.nodeId()) {
                return false;
            }
        }

        FlowNode old = nodesById.put(node.nodeId(), node);
        if (old != null && old.slot() != node.slot()) {
            nodeIdBySlot.remove(old.slot());
        }
        if (node.slot() < CANVAS_SLOTS) {
            nodeIdBySlot.put(node.slot(), node.nodeId());
        }
        if (node.nodeId() >= nextNodeIdCounter.get()) {
            nextNodeIdCounter.set(node.nodeId() + 1);
        }
        return true;
    }

    public synchronized boolean moveNode(int nodeId, int newSlot) {
        if (newSlot < 0 || newSlot >= CANVAS_SLOTS) {
            return false;
        }
        if (nodeIdBySlot.containsKey(newSlot)) {
            return false;
        }
        FlowNode existing = nodesById.get(nodeId);
        if (existing == null) {
            return false;
        }
        nodeIdBySlot.remove(existing.slot());
        FlowNode updated = existing.withSlot(newSlot);
        nodesById.put(nodeId, updated);
        nodeIdBySlot.put(newSlot, nodeId);
        return true;
    }

    public synchronized boolean removeNode(int nodeId) {
        FlowNode removed = nodesById.remove(nodeId);
        if (removed == null) {
            return false;
        }
        nodeIdBySlot.remove(removed.slot());
        connections.removeIf(c -> c.fromNodeId() == nodeId || c.toNodeId() == nodeId);
        return true;
    }

    public synchronized boolean addConnection(int fromNodeId, int toNodeId, FlowConnection.ConnectionType type) {
        if (fromNodeId <= 0 || toNodeId <= 0 || fromNodeId == toNodeId) {
            return false;
        }
        if (!nodesById.containsKey(fromNodeId) || !nodesById.containsKey(toNodeId)) {
            return false;
        }
        for (FlowConnection c : connections) {
            if (c.fromNodeId() == fromNodeId && c.toNodeId() == toNodeId) {
                return false; // Bereits verbunden
            }
        }
        connections.add(new FlowConnection(fromNodeId, toNodeId, type));
        return true;
    }

    public synchronized boolean removeConnection(int fromNodeId, int toNodeId) {
        return connections.removeIf(c -> c.fromNodeId() == fromNodeId && c.toNodeId() == toNodeId);
    }

    public synchronized void removeAllConnectionsFor(int nodeId) {
        connections.removeIf(c -> c.fromNodeId() == nodeId || c.toNodeId() == nodeId);
    }

    public synchronized List<FlowConnection> getOutgoingConnections(int fromNodeId) {
        List<FlowConnection> out = new ArrayList<>();
        for (FlowConnection c : connections) {
            if (c.fromNodeId() == fromNodeId) {
                out.add(c);
            }
        }
        return out;
    }

    public synchronized List<FlowConnection> getIncomingConnections(int toNodeId) {
        List<FlowConnection> in = new ArrayList<>();
        for (FlowConnection c : connections) {
            if (c.toNodeId() == toNodeId) {
                in.add(c);
            }
        }
        return in;
    }

    public synchronized boolean isRootNode(int nodeId) {
        for (FlowConnection c : connections) {
            if (c.toNodeId() == nodeId) {
                return false;
            }
        }
        return true;
    }

    public synchronized void clear() {
        nodesById.clear();
        nodeIdBySlot.clear();
        connections.clear();
        nextNodeIdCounter.set(1);
    }

    public synchronized String serializeNodesJson() {
        return GSON.toJson(new ArrayList<>(nodesById.values()), NODE_LIST_TYPE);
    }

    public synchronized String serializeConnectionsJson() {
        return GSON.toJson(connections, CONN_LIST_TYPE);
    }

    public synchronized void populateFromJson(String nodesJson, String connectionsJson) {
        clear();
        if (nodesJson != null && !nodesJson.isBlank()) {
            List<FlowNode> loadedNodes = GSON.fromJson(nodesJson, NODE_LIST_TYPE);
            if (loadedNodes != null) {
                for (FlowNode n : loadedNodes) {
                    putNode(n);
                }
            }
        }
        if (connectionsJson != null && !connectionsJson.isBlank()) {
            List<FlowConnection> loadedConns = GSON.fromJson(connectionsJson, CONN_LIST_TYPE);
            if (loadedConns != null) {
                for (FlowConnection c : loadedConns) {
                    if (nodesById.containsKey(c.fromNodeId()) && nodesById.containsKey(c.toNodeId())) {
                        connections.add(c);
                    }
                }
            }
        }
    }
}
