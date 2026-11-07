package de.archer.admx.render.fixture;

import de.archer.admx.render.beam.BeamMode;
import de.archer.admx.render.beam.HybridBeamRenderer;
import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.entity.RenderEntity;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.fog.FogRenderer;
import de.archer.admx.render.fog.LightScatteringField;
import de.archer.admx.render.light.FakeLightManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Hybrider Fixture-Renderer (Option C):
 * Unterstützt hochauflösende Blockbench-Item-Modelle (Moving Heads: Spot/Beam/Wash/Laser,
 * statische Show-Laser, Blinder, Strobe-Bar, Raumnebelmaschine, Kaltnebelmaschine und
 * Flammenprojektor) inklusive Decken-/Bodenmontage und Vanilla-Fallback.
 */
public final class MovingHeadRenderer {

    private static final float FIXTURE_BODY_VIEW_RANGE = 0.65f;

    private final HybridBeamRenderer beamRenderer;
    private final FakeLightManager fakeLightManager;
    private final FogRenderer fogRenderer;
    private final LightScatteringField scatteringField;
    private final MovingHeadProfile defaultProfile;

    public MovingHeadRenderer(
            HybridBeamRenderer beamRenderer,
            FakeLightManager fakeLightManager,
            FogRenderer fogRenderer,
            LightScatteringField scatteringField,
            MovingHeadProfile defaultProfile
    ) {
        this.beamRenderer = beamRenderer;
        this.fakeLightManager = fakeLightManager;
        this.fogRenderer = fogRenderer;
        this.scatteringField = scatteringField;
        this.defaultProfile = defaultProfile;
    }

    public MovingHeadRenderer(HybridBeamRenderer beamRenderer, FakeLightManager fakeLightManager, MovingHeadProfile defaultProfile) {
        this(beamRenderer, fakeLightManager, new FogRenderer(), new LightScatteringField(), defaultProfile);
    }

    public MovingHeadRenderer(HybridBeamRenderer beamRenderer, MovingHeadProfile defaultProfile) {
        this(beamRenderer, new FakeLightManager(), new FogRenderer(), new LightScatteringField(), defaultProfile);
    }

    public MovingHeadRenderer() {
        this(new HybridBeamRenderer(), new FakeLightManager(), new FogRenderer(), new LightScatteringField(), MovingHeadProfile.defaultBlockbenchModel(true));
    }

    public FogRenderer fogRenderer() {
        return fogRenderer;
    }

    public LightScatteringField scatteringField() {
        return scatteringField;
    }

    public void render(
            UUID fixtureId,
            Location mountLocation,
            FixtureState state,
            BeamMode mode,
            float maxReach
    ) {
        render(fixtureId, mountLocation, 0f, false, FixtureType.MOVING_HEAD_SPOT, state, mode, maxReach, defaultProfile, 0L);
    }

    public void render(
            UUID fixtureId,
            Location mountLocation,
            FixtureState state,
            BeamMode mode,
            float maxReach,
            MovingHeadProfile profile
    ) {
        FixtureType inferredType = switch (mode) {
            case SPOT_CONE -> FixtureType.MOVING_HEAD_SPOT;
            case LASER_FAN -> FixtureType.LASER_FAN_PROJECTOR;
            case LASER_NEEDLE -> FixtureType.LASER_BEAM_PROJECTOR;
        };
        render(fixtureId, mountLocation, 0f, false, inferredType, state, mode, maxReach, profile, 0L);
    }

    public void render(
            UUID fixtureId,
            Location mountLocation,
            float mountYawDegrees,
            boolean ceilingMounted,
            FixtureType fixtureType,
            FixtureState state,
            BeamMode mode,
            float maxReach,
            MovingHeadProfile profile
    ) {
        render(fixtureId, mountLocation, mountYawDegrees, ceilingMounted, fixtureType, state, mode, maxReach, profile, 0L);
    }

    public void render(
            UUID fixtureId,
            Location mountLocation,
            float mountYawDegrees,
            boolean ceilingMounted,
            FixtureType fixtureType,
            FixtureState state,
            BeamMode mode,
            float maxReach,
            MovingHeadProfile profile,
            long tickCounter
    ) {
        RenderGroup fixtureGroup = new RenderGroup();

        // 1. Montage-Orientierung (Boden vs. Decke + Ausrichtungs-Yaw)
        Quaternionf qMount = new Quaternionf()
                .rotationY((float) Math.toRadians(-mountYawDegrees));
        if (ceilingMounted) {
            qMount.rotateZ((float) Math.PI);
        }

        // 2. Kinematik: Pan & Tilt im lokalen Gerät-Koordinatensystem
        Quaternionf qPan = new Quaternionf().rotationY(state.panRadians());
        Quaternionf qTilt = new Quaternionf().rotationX(state.tiltRadians());
        Quaternionf qMountPan = new Quaternionf(qMount).mul(qPan);
        Quaternionf qMountHead = new Quaternionf(qMountPan).mul(qTilt);

        Vector3f worldLensOffset;
        Vector3f forwardAxis = profile.useCustomModelData() ? profile.forwardAxis() : new Vector3f(0f, 0f, -1f);

        if (fixtureType.isMovingHead()) {
            // 3-teiliger Moving Head (Base, Yoke, Head: Spot, Beam, Wash oder Moving Laser Head)
            Vector3f localLensOffset = getMovingHeadLensOffset(profile, fixtureType);
            if (profile.useCustomModelData()) {
                renderCustomMovingHead(fixtureGroup, mountLocation, qMount, qMountPan, qMountHead, profile, fixtureType);
                Vector3f rotatedHeadPivot = new Vector3f(profile.headPivot()).rotate(qMount);
                Vector3f rotatedLensRel = new Vector3f(localLensOffset).rotate(qMountHead);
                worldLensOffset = rotatedHeadPivot.add(rotatedLensRel);
            } else {
                renderVanillaMovingHead(fixtureGroup, mountLocation, qMount, qMountHead);
                Vector3f rotatedHeadPivot = new Vector3f(0f, 0.45f, 0f).rotate(qMount);
                Vector3f rotatedLensRel = new Vector3f(0f, 0f, -0.25f).rotate(qMountHead);
                worldLensOffset = rotatedHeadPivot.add(rotatedLensRel);
            }
        } else {
            // Statische Gehäuse (Laserprojektor, Blinder, Strobe-Bar, Nebelmaschinen, Flammenprojektor)
            Vector3f localLensOffset = getStaticFixtureLensOffset(profile, fixtureType);
            if (profile.useCustomModelData()) {
                renderCustomStaticFixture(fixtureGroup, mountLocation, qMount, profile, fixtureType);
                worldLensOffset = new Vector3f(localLensOffset).rotate(qMount);
            } else {
                renderVanillaStaticFixture(fixtureGroup, mountLocation, qMount, fixtureType);
                worldLensOffset = new Vector3f(localLensOffset).rotate(qMount);
            }
        }

        // 3. Spezifisches Effekt-Rendering je nach Gerätetyp
        if (fixtureType.isFogMachine()) {
            if (state.isIlluminating() && state.dimmer() > 0.01f) {
                Location nozzleLoc = mountLocation.clone().add(worldLensOffset.x, worldLensOffset.y, worldLensOffset.z);
                Vector3f worldForward = new Vector3f(forwardAxis).rotate(qMount).normalize();

                RenderGroup fogVolumeGroup = fogRenderer.buildFogRenderGroup(
                        fixtureId,
                        nozzleLoc,
                        worldForward,
                        fixtureType.fogType(),
                        state.dimmer(),
                        tickCounter,
                        scatteringField,
                        fakeLightManager
                );
                fixtureGroup.set("fog_volume", fogVolumeGroup);
            } else {
                fogRenderer.deactivateInstance(fixtureId);
            }
            fixtureGroup.submit(fixtureId);
            return;
        }

        if (fixtureType.isFlameProjector()) {
            renderStageFlameEffect(fixtureId, mountLocation, worldLensOffset, qMount, state);
            fixtureGroup.submit(fixtureId);
            return;
        }

        // 4. Lichtstrahl / Laser an der berechneten Linsenposition (statisch an mountLocation verankert!)
        HybridBeamRenderer.BeamResult beamResult = beamRenderer.calculateBeam(
                mountLocation,
                qMount,
                worldLensOffset,
                forwardAxis,
                state,
                mode,
                maxReach
        );
        fixtureGroup.set("beam", beamResult.renderItem());

        // Strahl im Lichtbrechungs-Feld registrieren, damit Nebel das Licht physikalisch bricht
        if (state.isIlluminating() && scatteringField != null) {
            Location lens = beamResult.lensLocation();
            Vector3f originVec = new Vector3f((float) lens.getX(), (float) lens.getY(), (float) lens.getZ());
            scatteringField.registerBeam(
                    originVec,
                    beamResult.forward(),
                    state.color(),
                    state.zoomAngleDegrees(),
                    state.dimmer(),
                    Math.min(beamResult.hit().distance(), maxReach)
            );
        }

        // 5. Virtuelles Fake-Licht am Auftreffpunkt & gedrosselte Shader-Partikel (PERF-13)
        if (fakeLightManager != null && mountLocation.getWorld() != null) {
            World world = mountLocation.getWorld();
            var audience = world.getPlayers();
            if (state.isIlluminating() && beamResult.hit().isHit()) {
                Vector3f hp = beamResult.hit().position();
                Location hitLoc = new Location(world, hp.x, hp.y, hp.z);
                fakeLightManager.updateFixtureLight(
                        fixtureId,
                        hitLoc,
                        beamResult.hit().normal(),
                        state.dimmer(),
                        audience
                );
                if (tickCounter % 2 == 0) {
                    spawnBeamParticles(world, beamResult, state, maxReach);
                }
            } else {
                fakeLightManager.clearFixtureLight(fixtureId, world, audience);
                if (state.isIlluminating() && tickCounter % 2 == 0) {
                    spawnBeamParticles(world, beamResult, state, maxReach);
                }
            }
        }

        // Alles gemeinsam im EntityTracker registrieren
        fixtureGroup.submit(fixtureId);
    }

    private void renderStageFlameEffect(
            UUID fixtureId,
            Location mountLocation,
            Vector3f worldNozzleOffset,
            Quaternionf qMount,
            FixtureState state
    ) {
        World world = mountLocation.getWorld();
        if (world == null) return;

        Location burnerLoc = mountLocation.clone().add(worldNozzleOffset.x, worldNozzleOffset.y, worldNozzleOffset.z);
        Vector3f upDir = new Vector3f(0f, 1f, 0f).rotate(qMount).normalize();

        if (state.isIlluminating() && state.dimmer() > 0.2f) {
            float flameHeight = 3.2f * state.dimmer();
            for (int i = 0; i < 6; i++) {
                float h = (i / 6.0f) * flameHeight;
                double px = burnerLoc.getX() + upDir.x * h;
                double py = burnerLoc.getY() + upDir.y * h;
                double pz = burnerLoc.getZ() + upDir.z * h;
                world.spawnParticle(Particle.FLAME, px, py, pz, 2, 0.08, 0.12, 0.08, 0.02);
            }
            world.spawnParticle(Particle.LAVA, burnerLoc.getX(), burnerLoc.getY() + 0.3, burnerLoc.getZ(), 1, 0.05, 0.05, 0.05, 0.0);

            if (fakeLightManager != null) {
                fakeLightManager.updateFixtureLight(fixtureId, burnerLoc, upDir, state.dimmer(), world.getPlayers());
            }
        } else if (fakeLightManager != null) {
            fakeLightManager.clearFixtureLight(fixtureId, world, world.getPlayers());
        }
    }

    private void spawnBeamParticles(World world, HybridBeamRenderer.BeamResult beam, FixtureState state, float maxReach) {
        if (!state.isIlluminating() || state.dimmer() < 0.05f || world.getPlayers().isEmpty()) return;

        Location lens = beam.lensLocation();
        Vector3f fwd = beam.forward();
        float dist = Math.min(beam.hit().distance(), maxReach);
        if (dist <= 0.5f) return;

        org.bukkit.Color bukkitColor = state.color().toRGB();
        Particle.DustOptions dust = new Particle.DustOptions(bukkitColor, 0.75f);

        // Diskreter Haze-Kern entlang der Strahl-Mittelachse (maximal 6 Schritte -> PERF-13)
        int steps = Math.clamp((int) (dist / 4.0f), 2, 6);
        float stepSize = dist / (steps + 1);
        for (int i = 1; i <= steps; i++) {
            float d = i * stepSize;
            double px = lens.getX() + fwd.x * d;
            double py = lens.getY() + fwd.y * d;
            double pz = lens.getZ() + fwd.z * d;
            world.spawnParticle(Particle.DUST, px, py, pz, 1, 0.06, 0.06, 0.06, 0.0, dust);
        }

        if (beam.hit().isHit()) {
            Vector3f hp = beam.hit().position();
            Vector3f norm = beam.hit().normal();
            double hx = hp.x + norm.x * 0.08;
            double hy = hp.y + norm.y * 0.08;
            double hz = hp.z + norm.z * 0.08;
            world.spawnParticle(Particle.DUST, hx, hy, hz, 1, 0.10, 0.10, 0.10, 0.01, dust);
        }
    }

    private Vector3f getMovingHeadLensOffset(MovingHeadProfile profile, FixtureType fixtureType) {
        return switch (fixtureType) {
            case MOVING_HEAD_BEAM -> new Vector3f(0f, 0f, -6.65f / 16f);
            case MOVING_HEAD_WASH -> new Vector3f(0f, 0f, -5.0f / 16f);
            case MOVING_LASER_HEAD -> new Vector3f(0f, -0.5f / 16f, -5.2f / 16f);
            default -> profile.lensOffset();
        };
    }

    private Vector3f getStaticFixtureLensOffset(MovingHeadProfile profile, FixtureType fixtureType) {
        return switch (fixtureType) {
            case BLINDER_2LITE -> new Vector3f(0f, 5.5f / 16f, -4.1f / 16f);
            case STROBE_BAR -> new Vector3f(0f, 3.25f / 16f, -2.7f / 16f);
            case FOG_MACHINE_HAZER -> new Vector3f(0f, 4.0f / 16f, -8.5f / 16f);
            case FOG_MACHINE_LOW_FOG -> new Vector3f(0f, 1.5f / 16f, -8.6f / 16f);
            case STAGE_FLAME -> new Vector3f(0f, 9.25f / 16f, 0f);
            default -> profile.laserLensOffset();
        };
    }

    private void renderCustomMovingHead(
            RenderGroup group,
            Location mountLocation,
            Quaternionf qMount,
            Quaternionf qMountPan,
            Quaternionf qMountHead,
            MovingHeadProfile profile,
            FixtureType fixtureType
    ) {
        boolean isLaserHead = (fixtureType == FixtureType.MOVING_LASER_HEAD);

        NamespacedKey baseKey = isLaserHead ? NamespacedKey.fromString("admx:laser_head_base") : profile.baseModelKey();
        Integer baseCmd = isLaserHead ? 10011 : profile.baseCmd();

        NamespacedKey yokeKey = isLaserHead ? NamespacedKey.fromString("admx:laser_head_yoke") : profile.yokeModelKey();
        Integer yokeCmd = isLaserHead ? 10012 : profile.yokeCmd();

        NamespacedKey headKey;
        Integer headCmd;
        switch (fixtureType) {
            case MOVING_HEAD_BEAM -> {
                headKey = NamespacedKey.fromString("admx:moving_head_beam_head");
                headCmd = 10005;
            }
            case MOVING_HEAD_WASH -> {
                headKey = NamespacedKey.fromString("admx:moving_head_wash_head");
                headCmd = 10006;
            }
            case MOVING_LASER_HEAD -> {
                headKey = NamespacedKey.fromString("admx:laser_head_head");
                headCmd = 10013;
            }
            default -> {
                headKey = profile.headModelKey();
                headCmd = profile.headCmd();
            }
        }

        Matrix4f baseMatrix = new Matrix4f()
                .rotate(qMount)
                .rotateY((float) Math.PI);
        group.set("base", new RenderEntity<>(
                ItemDisplay.class,
                mountLocation,
                display -> {
                    display.setItemStack(createItemModel(profile.itemMaterial(), baseKey, baseCmd));
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(baseMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, baseMatrix, 1)
        ));

        Vector3f yokePos = new Vector3f(profile.yokePivot()).rotate(qMount);
        Matrix4f yokeMatrix = new Matrix4f()
                .translate(yokePos)
                .rotate(qMountPan)
                .rotateY((float) Math.PI);
        group.set("yoke", new RenderEntity<>(
                ItemDisplay.class,
                mountLocation,
                display -> {
                    display.setItemStack(createItemModel(profile.itemMaterial(), yokeKey, yokeCmd));
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(yokeMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, yokeMatrix, 1)
        ));

        Vector3f headPos = new Vector3f(profile.headPivot()).rotate(qMount);
        Matrix4f headMatrix = new Matrix4f()
                .translate(headPos)
                .rotate(qMountHead)
                .rotateY((float) Math.PI);
        group.set("head", new RenderEntity<>(
                ItemDisplay.class,
                mountLocation,
                display -> {
                    display.setItemStack(createItemModel(profile.itemMaterial(), headKey, headCmd));
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(headMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, headMatrix, 1)
        ));
    }

    private void renderCustomStaticFixture(
            RenderGroup group,
            Location mountLocation,
            Quaternionf qMount,
            MovingHeadProfile profile,
            FixtureType fixtureType
    ) {
        NamespacedKey modelKey;
        Integer cmd;
        switch (fixtureType) {
            case BLINDER_2LITE -> {
                modelKey = NamespacedKey.fromString("admx:blinder_2lite");
                cmd = 10007;
            }
            case STROBE_BAR -> {
                modelKey = NamespacedKey.fromString("admx:strobe_bar");
                cmd = 10008;
            }
            case STAGE_FLAME -> {
                modelKey = NamespacedKey.fromString("admx:flame_projector");
                cmd = 10009;
            }
            case FOG_MACHINE_HAZER -> {
                modelKey = NamespacedKey.fromString("admx:fog_machine");
                cmd = 10010;
            }
            case FOG_MACHINE_LOW_FOG -> {
                modelKey = NamespacedKey.fromString("admx:low_fog_machine");
                cmd = 10014;
            }
            default -> {
                modelKey = profile.laserProjectorModelKey();
                cmd = profile.laserProjectorCmd();
            }
        }

        Matrix4f fixtureMatrix = new Matrix4f()
                .rotate(qMount)
                .rotateY((float) Math.PI);
        group.set("static_body", new RenderEntity<>(
                ItemDisplay.class,
                mountLocation,
                display -> {
                    display.setItemStack(createItemModel(profile.itemMaterial(), modelKey, cmd));
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(fixtureMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, fixtureMatrix, 1)
        ));
    }

    private void renderVanillaMovingHead(
            RenderGroup group,
            Location mountLocation,
            Quaternionf qMount,
            Quaternionf qMountHead
    ) {
        Matrix4f baseMatrix = new Matrix4f()
                .rotate(qMount)
                .translate(-0.25f, 0f, -0.25f)
                .scale(0.5f, 0.2f, 0.5f);

        group.set("base", new RenderEntity<>(
                BlockDisplay.class,
                mountLocation,
                display -> {
                    display.setBlock(Material.HOPPER.createBlockData());
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(baseMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, baseMatrix, 1)
        ));

        Vector3f headPivot = new Vector3f(0f, 0.45f, 0f).rotate(qMount);
        Matrix4f headMatrix = new Matrix4f()
                .translate(headPivot)
                .rotate(qMountHead)
                .translate(-0.2f, -0.2f, -0.225f)
                .scale(0.4f, 0.4f, 0.45f);

        group.set("head", new RenderEntity<>(
                BlockDisplay.class,
                mountLocation,
                display -> {
                    display.setBlock(Material.POLISHED_BLACKSTONE.createBlockData());
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(headMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, headMatrix, 1)
        ));
    }

    private void renderVanillaStaticFixture(
            RenderGroup group,
            Location mountLocation,
            Quaternionf qMount,
            FixtureType fixtureType
    ) {
        Material mat = switch (fixtureType) {
            case BLINDER_2LITE -> Material.REDSTONE_LAMP;
            case STROBE_BAR -> Material.OCHRE_FROGLIGHT;
            case FOG_MACHINE_HAZER -> Material.DISPENSER;
            case FOG_MACHINE_LOW_FOG -> Material.PACKED_ICE;
            case STAGE_FLAME -> Material.MAGMA_BLOCK;
            default -> Material.OBSERVER;
        };

        Matrix4f bodyMatrix = new Matrix4f()
                .rotate(qMount)
                .translate(-0.35f, 0f, -0.3f)
                .scale(0.7f, 0.35f, 0.6f);

        group.set("static_body", new RenderEntity<>(
                BlockDisplay.class,
                mountLocation,
                display -> {
                    display.setBlock(mat.createBlockData());
                    display.setViewRange(FIXTURE_BODY_VIEW_RANGE);
                    display.setTransformationMatrix(bodyMatrix);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> EntityTracker.updateDisplayTransform(display, bodyMatrix, 1)
        ));
    }

    @SuppressWarnings("deprecation")
    public static ItemStack createItemModel(Material material, NamespacedKey modelKey, Integer cmd) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        if (meta != null) {
            if (modelKey != null) {
                meta.setItemModel(modelKey);
            }
            if (cmd != null) {
                meta.setCustomModelData(cmd);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
