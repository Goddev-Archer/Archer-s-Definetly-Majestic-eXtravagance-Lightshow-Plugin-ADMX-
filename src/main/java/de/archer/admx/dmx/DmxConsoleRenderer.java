package de.archer.admx.dmx;

import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.entity.RenderEntity;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.fixture.MovingHeadRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Rendert platzierte 3D-DMX-Steuerpulte ({@link PlacedDmxConsole}) in der Welt mittels
 * {@link EntityTracker}:
 * <ul>
 *   <li>3D-Blockbench-Modell via {@link ItemDisplay} (oder Vanilla-{@link BlockDisplay}-Fallback)</li>
 *   <li>Schwebendes Echtzeit-Status-Display ({@link TextDisplay}) mit verlinktem Universum & Merge-Status</li>
 *   <li>Einzelne, ortsfeste {@link Interaction}-Hitbox exakt am Pult-Ursprung (SEC-04, PERF-15)</li>
 * </ul>
 */
public final class DmxConsoleRenderer {

    public static final String PDC_CONSOLE_ID_KEY = "dmx_console_id";
    private static final float CONSOLE_VIEW_RANGE = 0.65f;

    private NamespacedKey consoleIdKey;

    public DmxConsoleRenderer() {
        this.consoleIdKey = null;
    }

    public DmxConsoleRenderer(NamespacedKey consoleIdKey) {
        this.consoleIdKey = consoleIdKey;
    }

    public NamespacedKey consoleIdKey() {
        return consoleIdKey;
    }

    public void render(PlacedDmxConsole console, DmxUniverse linkedUniverse, org.bukkit.plugin.Plugin plugin) {
        if (this.consoleIdKey == null && plugin != null) {
            this.consoleIdKey = new NamespacedKey(plugin, PDC_CONSOLE_ID_KEY);
        }
        render(console, linkedUniverse);
    }

    public void render(PlacedDmxConsole console, DmxUniverse linkedUniverse) {
        Location loc = console.location();
        if (loc == null || !loc.isWorldLoaded() || loc.getWorld() == null || !loc.isChunkLoaded()) {
            return;
        }

        RenderGroup group = new RenderGroup();

        Quaternionf qMount = new Quaternionf()
                .rotationY((float) Math.toRadians(-console.mountYaw()));

        ConsoleModelType type = console.modelType();

        // 1. 3D-Gehäuse (Resource-Pack Blockbench-Modell oder Vanilla-Block-Fallback)
        if (console.useCustomModelData()) {
            Matrix4f modelMat = new Matrix4f()
                    .rotate(qMount)
                    .rotateY((float) Math.PI);

            group.set("console_body", new RenderEntity<>(
                    ItemDisplay.class,
                    loc,
                    display -> {
                        display.setItemStack(MovingHeadRenderer.createItemModel(
                                Material.FEATHER,
                                type.modelKey(),
                                type.customModelData()
                        ));
                        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        display.setViewRange(CONSOLE_VIEW_RANGE);
                        display.setTransformationMatrix(modelMat);
                        display.setInterpolationDuration(1);
                        display.setInterpolationDelay(0);
                    },
                    display -> EntityTracker.updateDisplayTransform(display, modelMat, 1)
            ));
        } else {
            Matrix4f fallbackMat = new Matrix4f()
                    .rotate(qMount)
                    .translate(-0.45f, 0.0f, -0.32f)
                    .scale(0.90f, 0.38f, 0.64f);

            group.set("console_fallback", new RenderEntity<>(
                    BlockDisplay.class,
                    loc,
                    display -> {
                        display.setBlock(type.vanillaFallbackBlock().createBlockData());
                        display.setViewRange(CONSOLE_VIEW_RANGE);
                        display.setTransformationMatrix(fallbackMat);
                        display.setInterpolationDuration(1);
                        display.setInterpolationDelay(0);
                    },
                    display -> EntityTracker.updateDisplayTransform(display, fallbackMat, 1)
            ));
        }

        // 2. Schwebendes Status-Badge über dem Pult
        Matrix4f labelMat = new Matrix4f()
                .translate(0.0f, 0.82f, 0.0f)
                .scale(0.52f, 0.52f, 0.52f);

        Component labelText = buildStatusBadge(console, linkedUniverse);
        group.set("console_badge", new RenderEntity<>(
                TextDisplay.class,
                loc,
                display -> {
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setBackgroundColor(Color.fromARGB(170, 10, 14, 24));
                    display.setBrightness(new Display.Brightness(15, 15));
                    display.setShadowed(true);
                    display.setSeeThrough(false);
                    display.setViewRange(0.45f);
                    display.setTransformationMatrix(labelMat);
                    display.text(labelText);
                },
                display -> {
                    EntityTracker.updateDisplayTransform(display, labelMat, 1);
                    display.text(labelText);
                }
        ));

        // 3. Ortsfeste Interaction-Hitbox am Pult-Ursprung (SEC-04 / PERF-15: Niemals auf Spielern!)
        String idStr = console.id().toString();
        group.set("console_hitbox", new RenderEntity<>(
                Interaction.class,
                loc,
                interaction -> {
                    interaction.setInteractionWidth(1.15f);
                    interaction.setInteractionHeight(0.85f);
                    interaction.setResponsive(true);
                    if (consoleIdKey != null) {
                        interaction.getPersistentDataContainer().set(consoleIdKey, PersistentDataType.STRING, idStr);
                    }
                },
                interaction -> {
                    if (consoleIdKey != null && !interaction.getPersistentDataContainer().has(consoleIdKey, PersistentDataType.STRING)) {
                        interaction.getPersistentDataContainer().set(consoleIdKey, PersistentDataType.STRING, idStr);
                    }
                }
        ));

        group.submit(console.id());
    }

    private Component buildStatusBadge(PlacedDmxConsole console, DmxUniverse uni) {
        int uniNum = console.universeId() + 1;
        String modeName = (uni != null) ? uni.mergeMode().name() : "HTP";
        boolean blackout = (uni != null && uni.isBlackout());
        int gmPct = (uni != null) ? Math.round((uni.grandMaster() / 255.0f) * 100.0f) : 100;
        boolean artNetIn = (uni != null && uni.isArtNetInputActive(System.currentTimeMillis()));
        int overrides = (uni != null) ? uni.activeConsoleOverrideCount() : 0;

        Component line1 = Component.text("✦ " + console.modelType().displayName() + " ✦", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component line2 = Component.text("Universum " + uniNum, NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(" [" + modeName + "] ", NamedTextColor.GRAY))
                .append(blackout
                        ? Component.text("• BLACKOUT", NamedTextColor.RED, TextDecoration.BOLD)
                        : Component.text("• GM " + gmPct + "%", NamedTextColor.GREEN));

        Component line3;
        if (artNetIn) {
            line3 = Component.text("⚡ Art-Net LIVE RX", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(" | Fader: " + overrides + " aktiv", NamedTextColor.YELLOW));
        } else if (overrides > 0) {
            line3 = Component.text("🎚 Pult-Steuerung (" + overrides + " Kanäle aktiv)", NamedTextColor.YELLOW);
        } else {
            line3 = Component.text("» Rechtsklick: DMX-Workstation öffnen «", NamedTextColor.GRAY);
        }

        return line1.append(Component.newline()).append(line2).append(Component.newline()).append(line3);
    }
}
