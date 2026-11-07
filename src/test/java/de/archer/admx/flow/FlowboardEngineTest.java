package de.archer.admx.flow;

import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.db.AdmxDatabase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowboardEngineTest {

    @TempDir
    File tempDir;

    @Test
    void testNodeGraphAndConnections() {
        Flowboard board = new Flowboard("test_board", "Test Show", 400, true);

        FlowNode n1 = board.addNode(0, FlowNodeType.COLOR_FADE, "front_truss", 40);
        FlowNode n2 = board.addNode(1, FlowNodeType.MOVEMENT_SHAPE, "stage_wash", 60);
        FlowNode n3 = board.addNode(2, FlowNodeType.STROBE_BLAST, "blinders", 20);

        assertNotNull(n1);
        assertNotNull(n2);
        assertNotNull(n3);
        assertEquals(3, board.nodes().size());

        // Verbindungen anlegen: 1 -> 2 (Trigger on End), 2 -> 3 (Sync Start)
        assertTrue(board.addConnection(n1.nodeId(), n2.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END));
        assertTrue(board.addConnection(n2.nodeId(), n3.nodeId(), FlowConnection.ConnectionType.SYNC_START));

        // Duplikate müssen abgelehnt werden
        assertFalse(board.addConnection(n1.nodeId(), n2.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END));

        // Root-Node Prüfung: n1 hat keine eingehenden Verbindungen
        assertTrue(board.isRootNode(n1.nodeId()));
        assertFalse(board.isRootNode(n2.nodeId()));
        assertFalse(board.isRootNode(n3.nodeId()));

        assertEquals(1, board.getOutgoingConnections(n1.nodeId()).size());
        assertEquals(1, board.getIncomingConnections(n2.nodeId()).size());

        // Node verschieben
        assertTrue(board.moveNode(n1.nodeId(), 15));
        assertEquals(15, board.getNode(n1.nodeId()).slot());
        assertNull(board.getNodeAtSlot(0));
        assertNotNull(board.getNodeAtSlot(15));

        // Node löschen entfernt auch alle Drähte
        assertTrue(board.removeNode(n2.nodeId()));
        assertEquals(2, board.nodes().size());
        assertTrue(board.getOutgoingConnections(n1.nodeId()).isEmpty());
        assertTrue(board.getIncomingConnections(n3.nodeId()).isEmpty());
    }

    @Test
    void testStrobePhotoSafetyCap() {
        // SEC-09: Strobe-Frequenzen müssen strikt auf <= 10 Hz gekappt werden
        FlowNode dangerousNode = new FlowNode(
                1,
                5,
                FlowNodeType.STROBE_BLAST,
                "blinders",
                0,
                30,
                25.0f, // 25 Hz angefordert
                1.0f,
                ""
        );
        assertEquals(10.0f, dangerousNode.param1(), 1e-4f, "Strobe frequency must be clamped to 10 Hz");
    }

    @Test
    void testDurationClamping() {
        Flowboard board = new Flowboard("clamp_test", "Clamping", 10, true);
        assertEquals(Flowboard.MIN_SHOW_DURATION_TICKS, board.durationTicks());

        board.setDurationTicks(100_000);
        assertEquals(Flowboard.MAX_SHOW_DURATION_TICKS, board.durationTicks());
    }

    @Test
    void testFlowboardExecutionChain() {
        Flowboard board = new Flowboard("exec_test", "Execution Test", 100, false);

        FlowNode n1 = board.addNode(0, FlowNodeType.DIMMER_PULSE, "front_truss", 10);
        FlowNode n2 = board.addNode(1, FlowNodeType.STROBE_BLAST, "front_truss", 10);
        FlowNode n3 = board.addNode(2, FlowNodeType.BLACKOUT_DROP, "front_truss", 10);

        board.putNode(n2.withParams(8.0f, 1.0f, "")); // 8 Hz Strobe

        board.addConnection(n1.nodeId(), n2.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END);
        board.addConnection(n2.nodeId(), n3.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END);

        FixtureGroup group = new FixtureGroup(
                "front_truss",
                "Front Truss",
                Material.LANTERN,
                0xFFFFAA,
                ControlMode.MANUAL_LIVE,
                0.0f,
                0.0f,
                0.0f,
                24.0f,
                0.0f,
                180.0f,
                1.0f,
                1.0f,
                MovementPattern.STATIC,
                1.0f,
                0.8f,
                0.0f
        );
        Map<String, FixtureGroup> groups = new HashMap<>();
        groups.put("front_truss", group);

        FlowboardEngine engine = new FlowboardEngine();
        engine.play(board);
        assertTrue(engine.isPlaying());
        assertTrue(engine.isNodeRunning(n1.nodeId()));

        // Tick 1..5: Node 1 (DIMMER_PULSE) läuft und erzeugt Dimmer > 0
        for (int i = 0; i < 5; i++) {
            engine.tick(groups);
        }
        assertTrue(group.dimmer() > 0.5f, "Dimmer pulse should ramp up");
        assertEquals(0.0f, group.strobeHz());

        // Bis Tick 10 laufen lassen -> Node 1 endet, triggert Node 2 (STROBE_BLAST)
        for (int i = 0; i < 6; i++) {
            engine.tick(groups);
        }
        assertTrue(engine.isNodeRunning(n2.nodeId()), "Node 2 should have triggered after Node 1 completed");
        assertEquals(8.0f, group.strobeHz(), 1e-4f, "Node 2 strobe should be active");

        // Bis Tick 20 laufen lassen -> Node 2 endet, Strobe geht aus, Node 3 (BLACKOUT) triggert
        for (int i = 0; i < 10; i++) {
            engine.tick(groups);
        }
        assertTrue(engine.isNodeRunning(n3.nodeId()), "Node 3 should trigger after Node 2");
        assertEquals(0.0f, group.strobeHz(), "Strobe must be cleaned up");
        assertEquals(0.0f, group.dimmer(), "Blackout drop must zero out dimmer");
    }

    @Test
    void testDatabaseFlowboardPersistence() {
        try (AdmxDatabase db = new AdmxDatabase(tempDir)) {
            Flowboard original = new Flowboard("fest_show", "Festival Main Show", 600, true);
            FlowNode nodeA = original.addNode(5, FlowNodeType.COLOR_RAINBOW, "*", 80);
            FlowNode nodeB = original.addNode(14, FlowNodeType.MOVEMENT_SHAPE, "lead_spots", 120);
            original.putNode(nodeB.withParams(1.5f, 0.9f, "SWEEP_PAN"));
            original.addConnection(nodeA.nodeId(), nodeB.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END);

            db.saveFlowboard(original);

            // Neu aus SQLite laden
            Map<String, Flowboard> loaded = db.loadFlowboards();
            assertTrue(loaded.containsKey("fest_show"));

            Flowboard reloaded = loaded.get("fest_show");
            assertEquals("Festival Main Show", reloaded.name());
            assertEquals(600, reloaded.durationTicks());
            assertTrue(reloaded.isLoopMode());
            assertEquals(2, reloaded.nodes().size());
            assertEquals(1, reloaded.connections().size());

            FlowConnection conn = reloaded.connections().getFirst();
            assertEquals(nodeA.nodeId(), conn.fromNodeId());
            assertEquals(nodeB.nodeId(), conn.toNodeId());
            assertEquals(FlowConnection.ConnectionType.TRIGGER_ON_END, conn.type());

            // Löschen testen
            db.deleteFlowboard("fest_show");
            Map<String, Flowboard> afterDelete = db.loadFlowboards();
            assertFalse(afterDelete.containsKey("fest_show"));
        }
    }
}
