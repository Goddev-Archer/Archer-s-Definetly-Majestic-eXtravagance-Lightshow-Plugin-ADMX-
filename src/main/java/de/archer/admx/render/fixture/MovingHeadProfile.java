package de.archer.admx.render.fixture;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.joml.Vector3f;

/**
 * Konfigurationsprofil für das Rendern von ADMX-Geräten im hybriden Modus
 * (Modernes ItemModel / CustomModelData via ItemDisplay oder Vanilla-BlockDisplay-Fallback).
 */
public record MovingHeadProfile(
        boolean useCustomModelData,
        Material itemMaterial,
        NamespacedKey baseModelKey,
        NamespacedKey yokeModelKey,
        NamespacedKey headModelKey,
        NamespacedKey laserProjectorModelKey,
        Integer baseCmd,
        Integer yokeCmd,
        Integer headCmd,
        Integer laserProjectorCmd,
        Vector3f yokePivot,
        Vector3f headPivot,
        Vector3f lensOffset,
        Vector3f laserLensOffset,
        Vector3f forwardAxis
) {

    /**
     * Standard-Profil basierend auf den ADMX Blockbench-Modellen
     * (models/moving_head.bbmodel und models/laser_projector.bbmodel).
     */
    public static MovingHeadProfile defaultBlockbenchModel(boolean useCustomModelData) {
        return new MovingHeadProfile(
                useCustomModelData,
                Material.FEATHER,
                NamespacedKey.fromString("admx:moving_head_base"),
                NamespacedKey.fromString("admx:moving_head_yoke"),
                NamespacedKey.fromString("admx:moving_head_head"),
                NamespacedKey.fromString("admx:laser_projector"),
                10001, // moving_head_base
                10002, // moving_head_yoke
                10003, // moving_head_head
                10004, // laser_projector
                new Vector3f(0f, 3.5f / 16f, 0f),
                new Vector3f(0f, 11.5f / 16f, 0f),
                new Vector3f(0f, 0f, -5.5f / 16f),
                new Vector3f(0f, 3.5f / 16f, -5.75f / 16f),
                new Vector3f(0f, 0f, -1f)
        );
    }

    /**
     * Standard-Profil für reinen Vanilla-Block-Fallback ohne Resource-Pack.
     */
    public static MovingHeadProfile vanillaFallback() {
        return defaultBlockbenchModel(false);
    }
}
