package de.archer.admx.dmx;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Repräsentiert ein physisch in der Minecraft-Welt platziertes 3D-DMX-Steuerpult,
 * das fest oder dynamisch mit einem der 16 DMX512-Universen (0..15) verlinkt ist.
 */
public final class PlacedDmxConsole {

    private final UUID id;
    private final ConsoleModelType modelType;
    private volatile int universeId; // 0..15 (Universum 1..16)
    private volatile int faderBank;  // 0..63 (je 8 Kanäle pro Bank)
    private final Location location;
    private final float mountYaw;
    private final boolean useCustomModelData;
    private final UUID ownerId;

    public PlacedDmxConsole(
            UUID id,
            ConsoleModelType modelType,
            int universeId,
            int faderBank,
            Location location,
            float mountYaw,
            boolean useCustomModelData,
            UUID ownerId
    ) {
        this.id = id;
        this.modelType = modelType != null ? modelType : ConsoleModelType.GRAND_CONSOLE;
        this.universeId = Math.clamp(universeId, 0, 15);
        this.faderBank = Math.clamp(faderBank, 0, 63);
        this.location = location;
        this.mountYaw = mountYaw;
        this.useCustomModelData = useCustomModelData;
        this.ownerId = ownerId;
    }

    public UUID id() {
        return id;
    }

    public ConsoleModelType modelType() {
        return modelType;
    }

    public int universeId() {
        return universeId;
    }

    public void setUniverseId(int universeId) {
        this.universeId = Math.clamp(universeId, 0, 15);
    }

    public int faderBank() {
        return faderBank;
    }

    public void setFaderBank(int faderBank) {
        this.faderBank = Math.clamp(faderBank, 0, 63);
    }

    public Location location() {
        return location;
    }

    public float mountYaw() {
        return mountYaw;
    }

    public boolean useCustomModelData() {
        return useCustomModelData;
    }

    public UUID ownerId() {
        return ownerId;
    }
}
