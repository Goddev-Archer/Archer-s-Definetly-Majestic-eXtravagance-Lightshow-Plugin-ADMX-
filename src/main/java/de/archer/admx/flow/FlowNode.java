package de.archer.admx.flow;

import java.util.Objects;

/**
 * Repräsentiert einen einzelnen programmierbaren Baustein / Funktionsknoten auf dem Flowboard-Raster.
 */
public record FlowNode(
        int nodeId,
        int slot,                // 0..35 auf dem 9x4 Grid
        FlowNodeType type,
        String targetGroupId,    // Gruppen-ID oder "*" für alle Gruppen
        int startTick,           // Start-Verzögerung für Root-Nodes in Ticks
        int durationTicks,       // Wirkdauer in Ticks (z. B. 20 Ticks = 1 Sekunde)
        float param1,            // z. B. Ziel-Hue, Bewegungs-Speed, Strobe-Hz
        float param2,            // z. B. Amplitude, Helligkeit
        String paramString       // z. B. Muster-Name ("SWEEP_PAN") oder Farb-Preset
) {
    public static final String TARGET_ALL = "*";
    public static final int MIN_DURATION_TICKS = 5;
    public static final int MAX_DURATION_TICKS = 1200; // 60 Sekunden pro Node

    public FlowNode {
        slot = Math.clamp(slot, 0, 35);
        if (type == null) {
            type = FlowNodeType.DIMMER_PULSE;
        }
        if (targetGroupId == null || targetGroupId.isBlank()) {
            targetGroupId = TARGET_ALL;
        }
        startTick = Math.max(0, startTick);
        durationTicks = Math.clamp(durationTicks, MIN_DURATION_TICKS, MAX_DURATION_TICKS);
        if (paramString == null) {
            paramString = "";
        }
        // Fotosensibilitäts-Sicherheitsregel (SEC-09): Strobe darf NIEMALS 10 Hz überschreiten
        if (type == FlowNodeType.STROBE_BLAST) {
            param1 = Math.clamp(param1, 1.0f, 10.0f);
        }
    }

    public FlowNode withSlot(int newSlot) {
        return new FlowNode(nodeId, newSlot, type, targetGroupId, startTick, durationTicks, param1, param2, paramString);
    }

    public FlowNode withType(FlowNodeType newType) {
        return new FlowNode(nodeId, slot, newType, targetGroupId, startTick, durationTicks, param1, param2, paramString);
    }

    public FlowNode withTargetGroupId(String newTargetGroupId) {
        return new FlowNode(nodeId, slot, type, newTargetGroupId, startTick, durationTicks, param1, param2, paramString);
    }

    public FlowNode withStartTick(int newStartTick) {
        return new FlowNode(nodeId, slot, type, targetGroupId, newStartTick, durationTicks, param1, param2, paramString);
    }

    public FlowNode withDurationTicks(int newDuration) {
        return new FlowNode(nodeId, slot, type, targetGroupId, startTick, newDuration, param1, param2, paramString);
    }

    public FlowNode withParams(float p1, float p2, String ps) {
        return new FlowNode(nodeId, slot, type, targetGroupId, startTick, durationTicks, p1, p2, ps);
    }

    public boolean targetsAllGroups() {
        return TARGET_ALL.equals(targetGroupId) || "all".equalsIgnoreCase(targetGroupId);
    }
}
