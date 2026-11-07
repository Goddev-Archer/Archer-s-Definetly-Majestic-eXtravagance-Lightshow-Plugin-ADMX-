package de.archer.admx.flow;

/**
 * Verbindung / Draht zwischen zwei Nodes auf dem Flowboard-Canvas.
 */
public record FlowConnection(
        int fromNodeId,
        int toNodeId,
        ConnectionType type
) {
    public enum ConnectionType {
        /**
         * Löst toNode aus, sobald fromNode seine Laufzeit beendet hat.
         */
        TRIGGER_ON_END("Trigger bei Ende", "Triggert den Folge-Node nahtlos nach Ablauf"),
        /**
         * Löst toNode synchron im exakt selben Moment wie fromNode aus.
         */
        SYNC_START("Synchron-Start", "Startet beide Nodes absolut zeitgleich");

        private final String displayName;
        private final String description;

        ConnectionType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }
    }

    public FlowConnection {
        if (fromNodeId <= 0 || toNodeId <= 0) {
            throw new IllegalArgumentException("Node-IDs müssen positiv sein: from=" + fromNodeId + ", to=" + toNodeId);
        }
        if (fromNodeId == toNodeId) {
            throw new IllegalArgumentException("Eine Node kann nicht mit sich selbst verbunden werden!");
        }
        if (type == null) {
            type = ConnectionType.TRIGGER_ON_END;
        }
    }
}
