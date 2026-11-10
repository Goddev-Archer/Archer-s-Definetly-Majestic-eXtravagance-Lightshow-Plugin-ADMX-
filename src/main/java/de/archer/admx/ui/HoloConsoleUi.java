package de.archer.admx.ui;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AutoShowProfile;
import de.archer.admx.audio.AutoShowProfile.SongSection;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.audio.SmartAutoDirector;
import de.archer.admx.audio.SmartAutoDirector.DirectorMode;
import de.archer.admx.audio.SoundAction;
import de.archer.admx.audio.SoundToLightConfig;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.SceneTimelineManager;
import de.archer.admx.placement.FixturePlacementManager;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.entity.RenderEntity;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.geom.TextDisplayTriangle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kompaktes, elegantes 3D-Hologramm-Steuerpult (inspiriert von {@code C:\dev\minecraft-text-display-experiments}):
 * <ul>
 *   <li>Kompakte Maße (1.28m x 0.74m) für unversperrte Sicht auf Bühne und Lichter.</li>
 *   <li>Ergonomische Platzierung auf Brust-/Arbeitshöhe vor dem Spieler oder knapp über Tischen/Blöcken.</li>
 *   <li>Sub-Pixel 3D-Raycasting für flüssige Bedienung mit Cursor & statischer Pult-Hitbox (ohne Spieler-Hitbox / Melee-Blockade -> SEC-04 & PERF-15).</li>
 *   <li>Strikte Berechtigungsprüfung für alle Klicks (SEC-02) & entkoppeltes Async-Speichern (PERF-09).</li>
 *   <li>Präzise formatierte, kollisionsfreie TextDisplays mit adaptiver Helligkeits-Kontrastierung.</li>
 * </ul>
 */
public final class HoloConsoleUi implements Listener {

    public static final String HOLO_HITBOX_TAG = "admx:holo_ui_detector";

    private static final float PANEL_WIDTH = 1.28f;
    private static final float PANEL_HEIGHT = 0.74f;
    private static final float HOLO_DISPLAY_VIEW_RANGE = 0.25f;
    private static final int HUE_STEPS = 18;
    private static final int SV_GRID = 5;
    private static final Component EMPTY_TEXT = Component.text(" ");
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    public record SpawnedHoloConsole(
            UUID ownerId,
            UUID renderHandle,
            Location origin,
            float yawDegrees,
            String selectedGroupId,
            int selectedRecordCueSlot,
            int groupPageOffset
    ) {
        public SpawnedHoloConsole(
                UUID ownerId,
                Location origin,
                float yawDegrees,
                String selectedGroupId,
                int selectedRecordCueSlot
        ) {
            this(
                    ownerId,
                    UUID.nameUUIDFromBytes(("admx:holo_console:" + ownerId).getBytes(StandardCharsets.UTF_8)),
                    origin,
                    yawDegrees,
                    selectedGroupId,
                    selectedRecordCueSlot,
                    0
            );
        }

        public SpawnedHoloConsole withGroup(String newGroupId) {
            return new SpawnedHoloConsole(ownerId, renderHandle, origin, yawDegrees, newGroupId, selectedRecordCueSlot, groupPageOffset);
        }

        public SpawnedHoloConsole withCueSlot(int newSlot) {
            return new SpawnedHoloConsole(ownerId, renderHandle, origin, yawDegrees, selectedGroupId, newSlot, groupPageOffset);
        }

        public SpawnedHoloConsole withPageOffset(int newOffset) {
            return new SpawnedHoloConsole(ownerId, renderHandle, origin, yawDegrees, selectedGroupId, selectedRecordCueSlot, newOffset);
        }
    }

    private final AdmxPlugin plugin;
    private final Map<UUID, SpawnedHoloConsole> activeConsoles = new ConcurrentHashMap<>();
    private final Set<UUID> clickedThisTick = ConcurrentHashMap.newKeySet();

    public HoloConsoleUi(AdmxPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Spawnt oder verschiebt das kompakte 3D-Hologramm-Steuerpult an die gewählte Position.
     */
    public void spawnAtWandLocation(Player player, Location targetBlockLoc, float playerYaw) {
        Location holoOrigin;
        Location blockBelow = targetBlockLoc.clone().subtract(0.0, 0.35, 0.0);
        if (blockBelow.getBlock().getType().isSolid()) {
            holoOrigin = targetBlockLoc.clone().add(0.0, 0.06, 0.0);
        } else {
            Location eye = player.getEyeLocation();
            org.bukkit.util.Vector dir = eye.getDirection().clone().setY(0).normalize();
            if (dir.lengthSquared() < 0.01) {
                dir = player.getLocation().getDirection().clone().setY(0).normalize();
            }
            holoOrigin = eye.clone().add(dir.multiply(1.85));
            holoOrigin.setY(eye.getY() - 0.46);
        }
        holoOrigin.setYaw(0f);
        holoOrigin.setPitch(0f);

        float panelYaw = playerYaw + 180f;
        String initialGroup = plugin.getPlacementManager().getSelectedGroupId(player);

        activeConsoles.put(player.getUniqueId(), new SpawnedHoloConsole(
                player.getUniqueId(),
                holoOrigin,
                panelYaw,
                initialGroup,
                0
        ));

        player.playSound(holoOrigin, Sound.BLOCK_BEACON_POWER_SELECT, 0.85f, 1.4f);
        player.sendMessage(Component.text("✦ 3D Hologramm-Steuerpult aufgebaut!", NamedTextColor.AQUA)
                .append(Component.text(" (Visiere Elemente an & klicke Links/Rechts zum Steuern)", NamedTextColor.GRAY)));
    }

    public void removeConsole(UUID playerId) {
        SpawnedHoloConsole removed = activeConsoles.remove(playerId);
        if (removed != null) {
            EntityTracker.removeByRoot(removed.renderHandle());
        }
    }

    public void removeConsolesInWorld(World world) {
        if (world == null) return;
        activeConsoles.entrySet().removeIf(entry -> {
            Location loc = entry.getValue().origin();
            if (!loc.isWorldLoaded() || world.equals(loc.getWorld())) {
                EntityTracker.removeByRoot(entry.getValue().renderHandle());
                return true;
            }
            return false;
        });
    }

    public boolean hasActiveConsole(UUID playerId) {
        return activeConsoles.containsKey(playerId);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID pid = event.getPlayer().getUniqueId();
        clickedThisTick.remove(pid);
        removeConsole(pid);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(HOLO_HITBOX_TAG)) {
            event.setCancelled(true);
            registerPlayerClickIfPermitted(event.getPlayer());
        }
    }

    @EventHandler
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity().getScoreboardTags().contains(HOLO_HITBOX_TAG)
                && event.getDamager() instanceof Player player) {
            event.setCancelled(true);
            registerPlayerClickIfPermitted(player);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                || action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            if (isNearAnyActiveConsole(player)) {
                registerPlayerClickIfPermitted(player);
            }
        }
    }

    private boolean isNearAnyActiveConsole(Player player) {
        if (activeConsoles.isEmpty()) return false;
        Location pLoc = player.getLocation();
        World pWorld = pLoc.getWorld();
        if (pWorld == null) return false;
        for (SpawnedHoloConsole c : activeConsoles.values()) {
            if (c.origin().isWorldLoaded()
                    && pWorld.equals(c.origin().getWorld())
                    && pLoc.distanceSquared(c.origin()) <= 36.0) {
                return true;
            }
        }
        return false;
    }

    private void registerPlayerClickIfPermitted(Player player) {
        // Sicherheitsprüfung (SEC-02): Nur berechtigte Spieler dürfen das 3D-Steuerpult bedienen!
        if (!FixturePlacementManager.hasPlaceOrControlPermission(player)) {
            return;
        }
        if (plugin.getPlacementManager().checkClickCooldown(player)) {
            clickedThisTick.add(player.getUniqueId());
        }
    }

    /**
     * Rendert alle aktiven 3D-Hologramm-Steuerpulte und verarbeitet Blick-Cursor & Klicks.
     */
    public void renderAllConsoles() {
        for (Map.Entry<UUID, SpawnedHoloConsole> entry : activeConsoles.entrySet()) {
            SpawnedHoloConsole console = entry.getValue();
            if (!console.origin().isWorldLoaded()) {
                continue;
            }
            World world = console.origin().getWorld();
            if (world == null || !console.origin().isChunkLoaded() || world.getPlayers().isEmpty()) {
                continue;
            }

            RenderGroup group = buildConsoleRenderGroup(console);
            group.submit(console.renderHandle());
        }
        clickedThisTick.clear();
    }

    private RenderGroup buildConsoleRenderGroup(SpawnedHoloConsole console) {
        RenderGroup group = new RenderGroup();
        Location origin = console.origin();
        if (origin == null || !origin.isWorldLoaded()) return group;
        World world = origin.getWorld();
        if (world == null) return group;

        Quaternionf qRot = new Quaternionf().rotationY((float) Math.toRadians(-console.yawDegrees()));
        HoloPlanePointDetector detector = new HoloPlanePointDetector(world.getPlayers(), origin, 6.0);

        List<FixtureGroup> allGroups = new ArrayList<>(plugin.getFixtureGroups().values());
        FixtureGroup activeGroup = plugin.getFixtureGroup(console.selectedGroupId());
        if (activeGroup == null && !allGroups.isEmpty()) {
            activeGroup = allGroups.getFirst();
        }
        if (activeGroup == null) {
            return group;
        }

        final FixtureGroup currentGroup = activeGroup;

        // 1. Hintergrundfläche (dezentes halbtransparentes Cyber-Anthrazit)
        Matrix4f bgPlane = new Matrix4f()
                .rotate(qRot)
                .translate(-PANEL_WIDTH * 0.5f, 0f, 0f)
                .scale(PANEL_WIDTH, PANEL_HEIGHT, 1f);

        addQuad(group, "panel_bg", origin, bgPlane, Color.fromARGB(185, 12, 16, 24));

        // Feiner oberer Akzentstreifen in Gruppenfarbe
        Color groupAccent = Color.fromRGB(currentGroup.accentRgb() & 0xFFFFFF);
        Matrix4f topBarPlane = new Matrix4f()
                .rotate(qRot)
                .translate(-PANEL_WIDTH * 0.5f, PANEL_HEIGHT - 0.005f, 0.002f)
                .scale(PANEL_WIDTH, 0.005f, 1f);
        addQuad(group, "top_accent_bar", origin, topBarPlane,
                Color.fromARGB(245, groupAccent.getRed(), groupAccent.getGreen(), groupAccent.getBlue()));

        // Untere dezente Kante
        Matrix4f bottomBarPlane = new Matrix4f()
                .rotate(qRot)
                .translate(-PANEL_WIDTH * 0.5f, 0f, 0.002f)
                .scale(PANEL_WIDTH, 0.003f, 1f);
        addQuad(group, "bottom_bar", origin, bottomBarPlane, Color.fromARGB(140, 50, 70, 95));

        // Statische, am Pult-Ursprung zentrierte Interaction-Hitbox (SEC-04 & PERF-15: niemals auf dem Spieler!)
        group.set("panel_static_hitbox", new RenderEntity<>(
                Interaction.class,
                origin,
                inter -> {
                    inter.setInteractionWidth(PANEL_WIDTH);
                    inter.setInteractionHeight(PANEL_HEIGHT);
                    inter.addScoreboardTag(HOLO_HITBOX_TAG);
                },
                inter -> {
                }
        ));

        HoloPlanePointDetector.PlaneHit mainHit = detector.detectHit(bgPlane);

        // 2. Kopfzeile: Schlanke, kompakte Statuszeile (Y = 0.702f)
        int memberCount = plugin.countFixturesInGroup(currentGroup.groupId());
        String shortName = currentGroup.displayName().replace("Gruppe ", "G");
        Component headerText = Component.text("ADMX • ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(shortName, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" (" + memberCount + " Fix)", NamedTextColor.YELLOW))
                .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                .append(Component.text(currentGroup.controlMode().displayName(), NamedTextColor.GREEN))
                .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                .append(Component.text(currentGroup.movementPattern().displayName(), NamedTextColor.LIGHT_PURPLE));

        Matrix4f headerTransform = new Matrix4f()
                .rotate(qRot)
                .translate(0f, 0.702f, 0.010f)
                .scale(0.080f);
        addTextLabel(group, "header_label", origin, headerTransform, headerText, Color.fromARGB(0, 0, 0, 0));

        // 3. Reihe oben: Gruppen-Auswahl-Tabs (inkl. Paging für > 6 Gruppen -> BUG-04) & Schließen-Button
        int pageOffset = console.groupPageOffset() >= allGroups.size() ? 0 : console.groupPageOffset();
        int maxTabs = allGroups.size() > 6 ? 5 : Math.min(6, allGroups.size());
        int visibleTabs = Math.min(maxTabs, allGroups.size() - pageOffset);
        float tabStartX = -0.580f;
        float tabY = 0.615f;
        float tabW = 0.150f;
        float tabH = 0.060f;
        float tabGap = 0.010f;

        for (int i = 0; i < visibleTabs; i++) {
            int globalIdx = pageOffset + i;
            FixtureGroup g = allGroups.get(globalIdx);
            boolean isSelected = g.groupId().equals(currentGroup.groupId());
            float tx = tabStartX + i * (tabW + tabGap);

            Matrix4f tabPlane = new Matrix4f()
                    .rotate(qRot)
                    .translate(tx, tabY, isSelected ? 0.006f : 0.004f)
                    .scale(tabW, tabH, 1f);

            HoloPlanePointDetector.PlaneHit tabHit = detector.detectHit(tabPlane);
            boolean hovered = (tabHit != null);
            if (hovered && didClick(tabHit.player())) {
                activeConsoles.put(console.ownerId(), console.withGroup(g.groupId()));
                plugin.getPlacementManager().setSelectedGroupId(tabHit.player(), g.groupId());
                tabHit.player().playSound(origin, Sound.UI_BUTTON_CLICK, 0.8f, 1.35f);
            }

            Color acc = Color.fromRGB(g.accentRgb() & 0xFFFFFF);
            Color tabColor = isSelected
                    ? Color.fromARGB(245, acc.getRed(), acc.getGreen(), acc.getBlue())
                    : (hovered ? Color.fromARGB(210, 50, 65, 88) : Color.fromARGB(180, 24, 32, 44));

            addQuad(group, "tab_bg_" + i, origin, tabPlane, tabColor);

            NamedTextColor textColor = isSelected ? getContrastingTextColor(acc) : NamedTextColor.WHITE;
            String shortLabel = "G" + (globalIdx + 1) + " (" + plugin.countFixturesInGroup(g.groupId()) + ")";

            Matrix4f labelMat = new Matrix4f()
                    .rotate(qRot)
                    .translate(tx + tabW * 0.5f, tabY + tabH * 0.5f - 0.008f, 0.010f)
                    .scale(0.088f);
            addTextLabel(group, "tab_lbl_" + i, origin, labelMat,
                    Component.text(shortLabel, textColor, TextDecoration.BOLD),
                    Color.fromARGB(0, 0, 0, 0));
        }

        if (allGroups.size() > 6) {
            float pageX = tabStartX + 5 * (tabW + tabGap);
            renderActionButton(group, origin, qRot, detector, "btn_group_page",
                    pageX, tabY, tabW, tabH,
                    "▶ MEHR (" + allGroups.size() + ")",
                    Color.fromRGB(50, 75, 115),
                    player -> {
                        int nextOff = (pageOffset + 5 >= allGroups.size()) ? 0 : (pageOffset + 5);
                        activeConsoles.put(console.ownerId(), console.withPageOffset(nextOff));
                    });
        }

        // [X] Close Button oben rechts (X = +0.480f .. +0.580f)
        float closeX = 0.480f;
        float closeW = 0.100f;
        Matrix4f closePlane = new Matrix4f()
                .rotate(qRot)
                .translate(closeX, tabY, 0.005f)
                .scale(closeW, tabH, 1f);

        HoloPlanePointDetector.PlaneHit closeHit = detector.detectHit(closePlane);
        if (closeHit != null && didClick(closeHit.player())) {
            closeHit.player().playSound(origin, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.2f);
            removeConsole(console.ownerId());
            return group;
        }

        addQuad(group, "btn_close", origin, closePlane,
                closeHit != null ? Color.fromARGB(245, 230, 50, 55) : Color.fromARGB(210, 180, 35, 40));
        addTextLabel(group, "lbl_close", origin,
                new Matrix4f().rotate(qRot).translate(closeX + closeW * 0.5f, tabY + tabH * 0.5f - 0.008f, 0.010f).scale(0.100f),
                Component.text("✖", NamedTextColor.WHITE, TextDecoration.BOLD), Color.fromARGB(0, 0, 0, 0));

        // 4. Haupt-Arbeitsbereich: Standard DMX (Farbe/Pan/Tilt/Fader), Sound-to-Light ODER Auto-Show Studio
        if (currentGroup.controlMode() == ControlMode.SOUND_ACTIVE) {
            renderSoundToLightPanel(group, origin, qRot, detector, currentGroup);
        } else if (currentGroup.controlMode() == ControlMode.AUTO_SHOW) {
            renderAutoShowStudioPanel(group, origin, qRot, detector, currentGroup);
        } else {
            // Modul Links: Farb-Steuerung (Hue-Regenbogen-Leiste + SV-Farbfeld)
            renderHueAndSvPicker(group, origin, qRot, detector, currentGroup);

            // Modul Mitte: Pan/Tilt XY-Pad + 4 Vertikal-Fader (Dimmer, Speed, Zoom, Phase)
            renderPanTiltPadAndFaders(group, origin, qRot, detector, currentGroup);
        }

        // 5. Modul Rechts & Unten: Betriebs-Buttons & Szenen-Programmierer (Cue 1..4, Rec, Seq)
        renderModeMovementAndSceneControls(group, console, origin, qRot, detector, currentGroup);

        // 7. Kleiner, dezenter Diamant-Cursor auf der Hologramm-Ebene
        if (mainHit != null) {
            float cx = (mainHit.u() - 0.5f) * PANEL_WIDTH;
            float cy = mainHit.v() * PANEL_HEIGHT;
            float cursorSize = 0.024f;

            Matrix4f cursorMat = new Matrix4f()
                    .rotate(qRot)
                    .translate(cx, cy, 0.016f)
                    .scale(cursorSize, cursorSize, 1f)
                    .rotateZ((float) (Math.PI / 4.0))
                    .translate(-0.5f, -0.5f, 0f);
            addQuad(group, "holo_cursor", origin, cursorMat, Color.fromARGB(250, 255, 255, 255));
        }

        return group;
    }

    private void renderAutoShowStudioPanel(
            RenderGroup group,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            FixtureGroup activeGroup
    ) {
        SmartAutoDirector director = plugin.getSmartAutoDirector();
        BeatSnapshot beat = plugin.getAudioDspManager().getSnapshot();
        DirectorMode mode = director.directorMode();
        AutoShowProfile profile = director.activeProfile();
        SongSection activeSec = director.currentSection();
        int selectedSlot = director.selectedQuickSlot();

        addTextLabel(group, "lbl_auto_studio_hdr", origin,
                new Matrix4f().rotate(qRot).translate(-0.120f, 0.568f, 0.010f).scale(0.080f),
                Component.text("AUTO-SHOW STUDIO • " + mode.displayName() + " • PLL: " + Math.round(beat.bpm()) + " BPM",
                        NamedTextColor.AQUA, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        // 1. Live 4-Sektionen Song-DNA Anzeige (X = -0.580f .. +0.120f, Y = 0.455f, H = 0.080f)
        SongSection[] secs = SongSection.values();
        String[] shortNames = {"BREAK", "VERSE", "BUILD", "DROP"};
        Color[] secCols = {
                Color.fromRGB(35, 165, 220),
                Color.fromRGB(45, 200, 110),
                Color.fromRGB(240, 175, 35),
                Color.fromRGB(235, 55, 75)
        };
        float secStartX = -0.580f;
        float secW = 0.165f;
        float secGap = 0.012f;
        for (int i = 0; i < secs.length; i++) {
            boolean isCurrent = (secs[i] == activeSec);
            float sx = secStartX + i * (secW + secGap);
            Color c = secCols[i];
            Color bg = isCurrent
                    ? Color.fromARGB(245, c.getRed(), c.getGreen(), c.getBlue())
                    : Color.fromARGB(170, 22, 28, 42);
            Matrix4f secPlane = new Matrix4f()
                    .rotate(qRot)
                    .translate(sx, 0.455f, 0.005f)
                    .scale(secW, 0.080f, 1f);
            addQuad(group, "auto_sec_bg_" + i, origin, secPlane, bg);
            addTextLabel(group, "auto_sec_lbl_" + i, origin,
                    new Matrix4f().rotate(qRot).translate(sx + secW * 0.5f, 0.455f + 0.030f, 0.012f).scale(0.080f),
                    Component.text((isCurrent ? "⚡ " : "") + shortNames[i],
                            isCurrent ? NamedTextColor.WHITE : NamedTextColor.GRAY, TextDecoration.BOLD),
                    Color.fromARGB(0, 0, 0, 0));
        }

        // 2. Multi-Pass Lern-Fortschrittsbalken (Y = 0.365f)
        float barX = -0.580f;
        float barW = 0.696f;
        float barH = 0.068f;
        int confPct = Math.round(profile.confidence() * 100f);
        addQuad(group, "auto_conf_bg", origin,
                new Matrix4f().rotate(qRot).translate(barX, 0.365f, 0.004f).scale(barW, barH, 1f),
                Color.fromARGB(215, 16, 22, 32));
        float fillW = Math.max(0.015f, profile.confidence() * barW);
        addQuad(group, "auto_conf_fill", origin,
                new Matrix4f().rotate(qRot).translate(barX, 0.365f, 0.007f).scale(fillW, barH, 1f),
                mode == DirectorMode.LEARNING_PASS ? Color.fromARGB(235, 245, 165, 30) : Color.fromARGB(235, 40, 205, 110));
        addTextLabel(group, "auto_conf_lbl", origin,
                new Matrix4f().rotate(qRot).translate(barX + barW * 0.5f, 0.365f + 0.024f, 0.012f).scale(0.076f),
                Component.text("DNA REIFE: " + confPct + "% (Pass #" + (profile.completedPasses() + 1)
                        + " | " + profile.learnedBeats() + " Beats | Stil: " + profile.choreoStyle().displayName() + ")",
                        NamedTextColor.WHITE, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        // 3. Lern- & Speicher-Steuerung (Y = 0.255f)
        float ctrlW = 0.224f;
        float ctrlGap = 0.012f;
        renderActionButton(group, origin, qRot, detector, "btn_holo_learn",
                barX, 0.255f, ctrlW, 0.085f,
                mode == DirectorMode.LEARNING_PASS ? "● LEARN AKTIV" : "▶ LEARN START",
                mode == DirectorMode.LEARNING_PASS ? Color.fromRGB(215, 45, 55) : Color.fromRGB(30, 145, 85),
                player -> {
                    director.startLearning(selectedSlot, "Learned Show #" + (selectedSlot + 1));
                    player.sendMessage(Component.text("🧠 Multi-Pass Lern-Algorithmus auf 3D-Holo gestartet!", NamedTextColor.GREEN));
                });

        renderActionButton(group, origin, qRot, detector, "btn_holo_save",
                barX + (ctrlW + ctrlGap), 0.255f, ctrlW, 0.085f,
                "💾 SAVE #" + (selectedSlot + 1),
                Color.fromRGB(205, 130, 25),
                player -> {
                    AutoShowProfile saved = director.stopLearningAndFreeze(null, selectedSlot);
                    plugin.saveAutoShowProfile(saved);
                    player.sendMessage(Component.text("💾 Auto-Show gespeichert unter ID " + saved.profileId() + "!", NamedTextColor.GOLD));
                });

        renderActionButton(group, origin, qRot, detector, "btn_holo_style",
                barX + (ctrlW + ctrlGap) * 2, 0.255f, ctrlW, 0.085f,
                "🎲 STIL WECHSELN",
                Color.fromRGB(115, 55, 175),
                player -> {
                    director.mutateChoreoStyle();
                    if (director.directorMode() == DirectorMode.LOCKED_PROFILE) {
                        plugin.saveAutoShowProfile(director.activeProfile());
                    }
                });

        // 4. Quick-Recall Slots #1..#4 direkt auf dem 3D-Holo-Panel (Y = 0.130f)
        for (int s = 0; s < 4; s++) {
            final int slotIdx = s;
            String slotId = String.format(java.util.Locale.ROOT, "show_%02d", s + 1);
            AutoShowProfile saved = plugin.getAutoShowProfile(slotId);
            boolean isRunning = (mode == DirectorMode.LOCKED_PROFILE && slotId.equalsIgnoreCase(profile.profileId()));
            float qx = secStartX + s * (secW + secGap);
            String label = saved != null ? ("#" + (s + 1) + " " + saved.displayName().substring(0, Math.min(8, saved.displayName().length()))) : ("#" + (s + 1) + " LEER");
            Color qCol = isRunning ? Color.fromRGB(35, 190, 95) : (s == selectedSlot ? Color.fromRGB(185, 135, 30) : Color.fromRGB(40, 62, 95));

            renderActionButton(group, origin, qRot, detector, "btn_holo_qslot_" + s,
                    qx, 0.130f, secW, 0.095f,
                    label,
                    qCol,
                    player -> {
                        director.setSelectedQuickSlot(slotIdx);
                        if (saved != null) {
                            director.recallProfile(saved);
                            player.sendMessage(Component.text("⚡ Auto-Show " + saved.displayName() + " [" + saved.profileId() + "] synchron geladen!", NamedTextColor.AQUA));
                        } else {
                            director.startLearning(slotIdx, "Learned Show #" + (slotIdx + 1));
                        }
                    });
        }
    }

    private void renderSoundToLightPanel(
            RenderGroup group,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            FixtureGroup activeGroup
    ) {
        SoundToLightConfig cfg = activeGroup.getSoundConfig();
        if (cfg == null) {
            cfg = SoundToLightConfig.defaultForGroup(activeGroup.groupId());
            activeGroup.setSoundConfig(cfg);
        }

        final SoundToLightConfig finalCfg = cfg;

        // Header Label
        addTextLabel(group, "lbl_s2l_header", origin,
                new Matrix4f().rotate(qRot).translate(-0.160f, 0.570f, 0.010f).scale(0.082f),
                Component.text("HARDWARE SOUND-TO-LIGHT (S2L)", NamedTextColor.AQUA, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        // 1. Linker Vertikaler Threshold-Fader (X = -0.580f .. -0.535f, Y = 0.160f .. 0.540f)
        renderVerticalFader(group, origin, qRot, detector, "s2l_thresh", "THR",
                Math.round(finalCfg.threshold() * 100) + "%",
                -0.580f, 0.160f, 0.045f, 0.380f, finalCfg.threshold(), Color.fromRGB(255, 185, 20),
                val -> {
                    finalCfg.setThreshold(val);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        // 2. Zentriertes 10-Band Spektrogramm (X = -0.520f .. +0.130f, Y = 0.160f .. 0.540f)
        float specX = -0.520f;
        float specY = 0.160f;
        float specW = 0.650f;
        float specH = 0.380f;

        // Spektrogramm-Hintergrund
        addQuad(group, "s2l_spec_bg", origin,
                new Matrix4f().rotate(qRot).translate(specX, specY, 0.003f).scale(specW, specH, 1f),
                Color.fromARGB(215, 14, 18, 26));

        // 10 Frequenz-Säulen bis 4000 Hz
        int numBars = 10;
        int[][] freqRanges = new int[][]{
                {35, 75},
                {75, 140},
                {140, 260},
                {260, 450},
                {450, 750},
                {750, 1200},
                {1200, 1800},
                {1800, 2500},
                {2500, 3200},
                {3200, 4000}
        };

        float barW = 0.054f;
        float barGap = 0.010f;
        var analyzer = plugin.getAudioDspManager().getSpectrumAnalyzer();

        for (int i = 0; i < numBars; i++) {
            int fLow = freqRanges[i][0];
            int fHigh = freqRanges[i][1];
            boolean inRange = (fHigh >= finalCfg.lowHz() && fLow <= finalCfg.highHz());

            float level = analyzer.getLevelForRange(fLow, fHigh);
            float barH = Math.max(0.008f, level * (specH - 0.020f));
            float bx = specX + 0.010f + i * (barW + barGap);

            Color barColor;
            if (inRange) {
                if (level >= finalCfg.threshold()) {
                    barColor = Color.fromARGB(250, 255, 40, 60); // Peak Trigger Rot
                } else {
                    barColor = Color.fromARGB(240, 0, 235, 255); // Aktives Band Cyan
                }
            } else {
                barColor = Color.fromARGB(120, 40, 52, 70); // Außerhalb Band Steel Blue
            }

            Matrix4f barPlane = new Matrix4f()
                    .rotate(qRot)
                    .translate(bx, specY, 0.006f)
                    .scale(barW, barH, 1f);
            addQuad(group, "s2l_bar_" + i, origin, barPlane, barColor);
        }

        // Horizontale Threshold-Linie über das gesamte Spektrogramm
        float thrY = specY + finalCfg.threshold() * (specH - 0.020f);
        Matrix4f thrLineMat = new Matrix4f()
                .rotate(qRot)
                .translate(specX, thrY, 0.008f)
                .scale(specW, 0.005f, 1f);
        addQuad(group, "s2l_thr_line", origin, thrLineMat, Color.fromRGB(255, 215, 0));

        // 3. Unterer Horizontaler Frequenz-Slider (X = -0.520f .. +0.130f, Y = 0.115f, H = 0.038f)
        float fSliderY = 0.115f;
        float fSliderH = 0.038f;
        Matrix4f freqPlane = new Matrix4f()
                .rotate(qRot)
                .translate(specX, fSliderY, 0.005f)
                .scale(specW, fSliderH, 1f);

        HoloPlanePointDetector.PlaneHit freqHit = detector.detectHit(freqPlane);
        if (freqHit != null && didClick(freqHit.player())) {
            freqHit.player().playSound(origin, Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
            if (freqHit.u() < 0.5f) {
                int nextLow = switch (finalCfg.lowHz()) {
                    case 35 -> 90;
                    case 90 -> 200;
                    case 200 -> 500;
                    case 500 -> 1000;
                    default -> 35;
                };
                finalCfg.setLowHz(nextLow);
            } else {
                int nextHigh = switch (finalCfg.highHz()) {
                    case 140 -> 500;
                    case 500 -> 1200;
                    case 1200 -> 2500;
                    case 2500 -> 4000;
                    default -> 140;
                };
                finalCfg.setHighHz(nextHigh);
            }
            plugin.getDatabase().saveGroupAsync(activeGroup);
        }

        addQuad(group, "s2l_fslider_bg", origin, freqPlane, Color.fromARGB(210, 20, 28, 42));

        // Filterbereichs-Markierung auf dem Slider
        float normLow = Math.clamp((finalCfg.lowHz() - 30f) / 3970f, 0f, 1f);
        float normHigh = Math.clamp((finalCfg.highHz() - 30f) / 3970f, 0f, 1f);
        float markX = specX + normLow * specW;
        float markW = Math.max(0.015f, (normHigh - normLow) * specW);
        addQuad(group, "s2l_fslider_mark", origin,
                new Matrix4f().rotate(qRot).translate(markX, fSliderY, 0.007f).scale(markW, fSliderH, 1f),
                Color.fromARGB(240, 0, 200, 230));

        addTextLabel(group, "lbl_fslider", origin,
                new Matrix4f().rotate(qRot).translate(specX + specW * 0.5f, fSliderY + fSliderH * 0.5f - 0.008f, 0.012f).scale(0.076f),
                Component.text("FILTER: " + finalCfg.lowHz() + " Hz - " + finalCfg.highHz() + " Hz (Klick = Ändern)", NamedTextColor.WHITE, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        // 4. Rechte Aktions-Sektion (X = 0.145f .. 0.335f)
        float actX = 0.145f;
        float actW = 0.190f;
        float actH = 0.080f;

        // Trigger-Status-Lampe
        boolean isTriggered = plugin.getSoundToLightEngine().isGroupTriggered(activeGroup.groupId());
        Matrix4f trigPlane = new Matrix4f()
                .rotate(qRot)
                .translate(actX, 0.460f, 0.005f)
                .scale(actW, actH, 1f);
        Color trigColor = isTriggered ? Color.fromARGB(245, 30, 230, 80) : Color.fromARGB(180, 50, 20, 25);
        addQuad(group, "s2l_trig_bulb", origin, trigPlane, trigColor);
        addTextLabel(group, "lbl_s2l_trig", origin,
                new Matrix4f().rotate(qRot).translate(actX + actW * 0.5f, 0.460f + actH * 0.5f - 0.009f, 0.012f).scale(0.080f),
                Component.text(isTriggered ? "⚡ TRIGGER! ⚡" : "RUHEZUSTAND", isTriggered ? NamedTextColor.WHITE : NamedTextColor.GRAY, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        // Below-Action Button
        renderActionButton(group, origin, qRot, detector, "btn_below_act",
                actX, 0.360f, actW, actH,
                "VOR: " + finalCfg.belowAction().name(),
                Color.fromRGB(40, 60, 90),
                player -> {
                    finalCfg.setBelowAction(finalCfg.belowAction().next());
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        // Above-Action Button
        renderActionButton(group, origin, qRot, detector, "btn_above_act",
                actX, 0.260f, actW, actH,
                "HIT: " + finalCfg.aboveAction().name(),
                Color.fromRGB(180, 90, 20),
                player -> {
                    finalCfg.setAboveAction(finalCfg.aboveAction().next());
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        // Preset Button
        String presetName = (finalCfg.highHz() <= 200) ? "SUB/KICK" : ((finalCfg.lowHz() >= 1000) ? "HIGHS" : "MIDS");
        renderActionButton(group, origin, qRot, detector, "btn_s2l_preset",
                actX, 0.160f, actW, actH,
                "PRESET: " + presetName,
                Color.fromRGB(75, 45, 120),
                player -> {
                    if (finalCfg.highHz() <= 200) {
                        finalCfg.setLowHz(200);
                        finalCfg.setHighHz(900);
                        finalCfg.setBelowAction(SoundAction.DIMMED_SWEEP);
                        finalCfg.setAboveAction(SoundAction.COLOR_CYCLE);
                    } else if (finalCfg.lowHz() < 1000) {
                        finalCfg.setLowHz(1500);
                        finalCfg.setHighHz(4000);
                        finalCfg.setBelowAction(SoundAction.STATIC_COLOR);
                        finalCfg.setAboveAction(SoundAction.LASER_FAN_BURST);
                    } else {
                        finalCfg.setLowHz(35);
                        finalCfg.setHighHz(140);
                        finalCfg.setBelowAction(SoundAction.BLACKOUT);
                        finalCfg.setAboveAction(SoundAction.STROBE_10HZ);
                    }
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });
    }

    private void renderHueAndSvPicker(
            RenderGroup group,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            FixtureGroup activeGroup
    ) {
        float hueX = -0.580f;
        float hueY = 0.115f;
        float hueW = 0.045f;
        float hueH = 0.435f;
        float stepH = hueH / HUE_STEPS;

        Matrix4f fullHueHitPlane = new Matrix4f()
                .rotate(qRot)
                .translate(hueX, hueY, 0.004f)
                .scale(hueW, hueH, 1f);
        HoloPlanePointDetector.PlaneHit hueHit = detector.detectHit(fullHueHitPlane);
        Float hoveredHue = (hueHit != null) ? (hueHit.v() * 360f) : null;

        if (hueHit != null && didClick(hueHit.player())) {
            activeGroup.setHueDegrees(hueHit.v() * 360f);
            plugin.getDatabase().saveGroupAsync(activeGroup);
            hueHit.player().playSound(origin, Sound.BLOCK_DISPENSER_FAIL, 0.7f, 1.9f);
        }

        for (int i = 0; i < HUE_STEPS; i++) {
            float segHue = (i + 0.5f) * (360f / HUE_STEPS);
            float closeness = 0f;
            if (hoveredHue != null) {
                float t = Math.clamp(1f - Math.abs(segHue - hoveredHue) / 45f, 0f, 1f);
                closeness = t * t * (3f - 2f * t);
            }
            float wScaled = hueW * (1f + closeness * 0.25f);

            Matrix4f segMat = new Matrix4f()
                    .rotate(qRot)
                    .translate(hueX, hueY + i * stepH, 0.004f)
                    .scale(wScaled, stepH, 1f);

            int rgb = Oklab.hsbToRgbInt(segHue / 360f, 1.0f, 1.0f);
            addQuad(group, "hue_seg_" + i, origin, segMat, Color.fromARGB(240, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
        }

        float selHueY = hueY + (activeGroup.hueDegrees() / 360f) * hueH;
        Matrix4f hueCursorMat = new Matrix4f()
                .rotate(qRot)
                .translate(hueX - 0.005f, selHueY - 0.006f, 0.008f)
                .scale(hueW + 0.010f, 0.012f, 1f);
        addQuad(group, "hue_sel_cursor", origin, hueCursorMat, Color.WHITE);

        float svX = -0.520f;
        float svY = 0.115f;
        float svW = 0.260f;
        float svH = 0.435f;
        float cellW = svW / SV_GRID;
        float cellH = svH / SV_GRID;

        Matrix4f svPlane = new Matrix4f()
                .rotate(qRot)
                .translate(svX, svY, 0.004f)
                .scale(svW, svH, 1f);
        HoloPlanePointDetector.PlaneHit svHit = detector.detectHit(svPlane);
        if (svHit != null && didClick(svHit.player())) {
            activeGroup.setSaturation(Math.clamp(svHit.u(), 0.05f, 1.0f));
            activeGroup.setBrightness(Math.clamp(svHit.v(), 0.15f, 1.0f));
            plugin.getDatabase().saveGroupAsync(activeGroup);
            svHit.player().playSound(origin, Sound.BLOCK_DISPENSER_FAIL, 0.7f, 1.7f);
        }

        for (int sx = 0; sx < SV_GRID; sx++) {
            for (int sy = 0; sy < SV_GRID; sy++) {
                float sVal = (sx + 0.5f) / SV_GRID;
                float vVal = (sy + 0.5f) / SV_GRID;
                int rgb = Oklab.hsbToRgbInt(activeGroup.hueDegrees() / 360f, sVal, vVal);
                Matrix4f cellMat = new Matrix4f()
                        .rotate(qRot)
                        .translate(svX + sx * cellW, svY + sy * cellH, 0.004f)
                        .scale(cellW, cellH, 1f);
                addQuad(group, "sv_" + sx + "_" + sy, origin, cellMat,
                        Color.fromARGB(235, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
            }
        }

        float curSvX = svX + activeGroup.saturation() * svW;
        float curSvY = svY + activeGroup.brightness() * svH;
        Matrix4f svCursor = new Matrix4f()
                .rotate(qRot)
                .translate(curSvX, curSvY, 0.009f)
                .scale(0.030f, 0.030f, 1f)
                .rotateZ((float) (Math.PI / 4.0))
                .translate(-0.5f, -0.5f, 0f);
        addQuad(group, "sv_cursor", origin, svCursor, Color.WHITE);

        addTextLabel(group, "lbl_color_sec", origin,
                new Matrix4f().rotate(qRot).translate(-0.420f, 0.570f, 0.010f).scale(0.085f),
                Component.text("FARBE (HUE/SV)", NamedTextColor.AQUA, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        int curRgb = Oklab.hsbToRgbInt(
                activeGroup.hueDegrees() / 360f,
                activeGroup.saturation(),
                activeGroup.brightness()
        );
        Matrix4f previewMat = new Matrix4f()
                .rotate(qRot)
                .translate(-0.285f, 0.565f, 0.008f)
                .scale(0.025f, 0.025f, 1f);
        addQuad(group, "color_preview_swatch", origin, previewMat,
                Color.fromRGB((curRgb >> 16) & 0xFF, (curRgb >> 8) & 0xFF, curRgb & 0xFF));
    }

    private void renderPanTiltPadAndFaders(
            RenderGroup group,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            FixtureGroup activeGroup
    ) {
        float padX = -0.230f;
        float padY = 0.115f;
        float padW = 0.300f;
        float padH = 0.435f;

        Matrix4f padPlane = new Matrix4f()
                .rotate(qRot)
                .translate(padX, padY, 0.004f)
                .scale(padW, padH, 1f);
        HoloPlanePointDetector.PlaneHit padHit = detector.detectHit(padPlane);
        if (padHit != null && didClick(padHit.player())) {
            float newPan = (padHit.u() - 0.5f) * 300f;
            float newTilt = (padHit.v() - 0.5f) * 140f;
            activeGroup.setPanDegrees(newPan);
            activeGroup.setTiltDegrees(newTilt);
            plugin.getDatabase().saveGroupAsync(activeGroup);
            padHit.player().playSound(origin, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.6f);
        }

        addQuad(group, "xy_pad_bg", origin, padPlane, Color.fromARGB(210, 18, 24, 36));

        addQuad(group, "xy_cross_h", origin,
                new Matrix4f().rotate(qRot).translate(padX, padY + padH * 0.5f - 0.002f, 0.006f).scale(padW, 0.004f, 1f),
                Color.fromARGB(120, 80, 105, 140));
        addQuad(group, "xy_cross_v", origin,
                new Matrix4f().rotate(qRot).translate(padX + padW * 0.5f - 0.002f, padY, 0.006f).scale(0.004f, padH, 1f),
                Color.fromARGB(120, 80, 105, 140));

        float normPan = Math.clamp((activeGroup.panDegrees() / 300f) + 0.5f, 0f, 1f);
        float normTilt = Math.clamp((activeGroup.tiltDegrees() / 140f) + 0.5f, 0f, 1f);
        Matrix4f ptDot = new Matrix4f()
                .rotate(qRot)
                .translate(padX + normPan * padW, padY + normTilt * padH, 0.010f)
                .scale(0.035f, 0.035f, 1f)
                .rotateZ((float) (Math.PI / 4.0))
                .translate(-0.5f, -0.5f, 0f);
        addQuad(group, "xy_pad_dot", origin, ptDot, Color.fromRGB(0, 235, 255));

        addTextLabel(group, "lbl_xy_pad", origin,
                new Matrix4f().rotate(qRot).translate(-0.080f, 0.570f, 0.010f).scale(0.082f),
                Component.text(String.format("PAN/TILT (%.0f° / %.0f°)", activeGroup.panDegrees(), activeGroup.tiltDegrees()),
                        NamedTextColor.YELLOW, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        float faderStartX = 0.100f;
        float faderGap = 0.015f;
        float faderW = 0.042f;
        float faderH = 0.390f;
        float faderY = 0.145f;

        renderVerticalFader(group, origin, qRot, detector, "dimmer", "DIM",
                Math.round(activeGroup.dimmer() * 100) + "%",
                faderStartX, faderY, faderW, faderH, activeGroup.dimmer(), Color.fromRGB(45, 215, 115),
                val -> {
                    activeGroup.setDimmer(val);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        renderVerticalFader(group, origin, qRot, detector, "speed", "SPD",
                String.format("%.1fx", activeGroup.movementSpeed()),
                faderStartX + (faderW + faderGap), faderY, faderW, faderH, (activeGroup.movementSpeed() - 0.1f) / 3.9f, Color.fromRGB(255, 175, 40),
                val -> {
                    activeGroup.setMovementSpeed(0.1f + val * 3.9f);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        renderVerticalFader(group, origin, qRot, detector, "zoom", "ZOM",
                Math.round(activeGroup.zoomDegrees()) + "°",
                faderStartX + (faderW + faderGap) * 2, faderY, faderW, faderH, (activeGroup.zoomDegrees() - 4f) / 66f, Color.fromRGB(60, 180, 255),
                val -> {
                    activeGroup.setZoomDegrees(4f + val * 66f);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        renderVerticalFader(group, origin, qRot, detector, "phase", "FAN",
                Math.round(activeGroup.phaseSpread() * 100) + "%",
                faderStartX + (faderW + faderGap) * 3, faderY, faderW, faderH, activeGroup.phaseSpread(), Color.fromRGB(215, 85, 255),
                val -> {
                    activeGroup.setPhaseSpread(val);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });
    }

    private void renderVerticalFader(
            RenderGroup group,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            String id,
            String shortTitle,
            String valueText,
            float x,
            float y,
            float w,
            float h,
            float normalizedValue,
            Color barColor,
            java.util.function.Consumer<Float> onValueChange
    ) {
        float clamped = Math.clamp(normalizedValue, 0f, 1f);
        Matrix4f trackPlane = new Matrix4f()
                .rotate(qRot)
                .translate(x, y, 0.004f)
                .scale(w, h, 1f);

        HoloPlanePointDetector.PlaneHit hit = detector.detectHit(trackPlane);
        if (hit != null && didClick(hit.player())) {
            onValueChange.accept(Math.clamp(hit.v(), 0f, 1f));
            hit.player().playSound(origin, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f + hit.v() * 1.1f);
        }

        addQuad(group, "fader_bg_" + id, origin, trackPlane, Color.fromARGB(210, 22, 28, 38));

        float fillH = Math.max(0.015f, clamped * h);
        Matrix4f fillPlane = new Matrix4f()
                .rotate(qRot)
                .translate(x, y, 0.007f)
                .scale(w, fillH, 1f);
        addQuad(group, "fader_fill_" + id, origin, fillPlane,
                Color.fromARGB(235, barColor.getRed(), barColor.getGreen(), barColor.getBlue()));

        addTextLabel(group, "fader_lbl_" + id, origin,
                new Matrix4f().rotate(qRot).translate(x + w * 0.5f, y + h + 0.018f, 0.010f).scale(0.078f),
                Component.text(shortTitle, NamedTextColor.WHITE, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));

        addTextLabel(group, "fader_val_" + id, origin,
                new Matrix4f().rotate(qRot).translate(x + w * 0.5f, y - 0.022f, 0.010f).scale(0.070f),
                Component.text(valueText, NamedTextColor.GRAY),
                Color.fromARGB(0, 0, 0, 0));
    }

    private void renderModeMovementAndSceneControls(
            RenderGroup group,
            SpawnedHoloConsole console,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            FixtureGroup activeGroup
    ) {
        float btnX = 0.355f;
        float btnW = 0.225f;
        float btnH = 0.080f;

        renderActionButton(group, origin, qRot, detector, "btn_mode",
                btnX, 0.455f, btnW, btnH,
                "MODUS: " + activeGroup.controlMode().displayName(),
                activeGroup.controlMode() == ControlMode.BLACKOUT ? Color.fromRGB(180, 35, 35) : Color.fromRGB(30, 120, 100),
                player -> {
                    activeGroup.setControlMode(activeGroup.controlMode().next());
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        renderActionButton(group, origin, qRot, detector, "btn_pattern",
                btnX, 0.357f, btnW, btnH,
                "FX: " + activeGroup.movementPattern().displayName(),
                Color.fromRGB(45, 95, 170),
                player -> {
                    activeGroup.setMovementPattern(activeGroup.movementPattern().next());
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        String strobeText = activeGroup.strobeHz() <= 0.1f ? "STROBE: AUS" : String.format("STROBE: %.0f Hz", activeGroup.strobeHz());
        renderActionButton(group, origin, qRot, detector, "btn_strobe",
                btnX, 0.259f, btnW, btnH,
                strobeText,
                activeGroup.strobeHz() > 0.1f ? Color.fromRGB(225, 140, 20) : Color.fromRGB(50, 58, 72),
                player -> {
                    float nextStrobe = switch (Math.round(activeGroup.strobeHz())) {
                        case 0 -> 3f;
                        case 3 -> 6f;
                        case 6 -> 10f;
                        default -> 0f;
                    };
                    activeGroup.setStrobeHz(nextStrobe);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        renderActionButton(group, origin, qRot, detector, "btn_amp",
                btnX, 0.161f, btnW, btnH,
                "RADIUS: " + Math.round(activeGroup.movementAmplitude() * 100) + "%",
                Color.fromRGB(90, 60, 150),
                player -> {
                    float nextAmp = activeGroup.movementAmplitude() + 0.25f;
                    if (nextAmp > 1.05f) nextAmp = 0.0f;
                    activeGroup.setMovementAmplitude(nextAmp);
                    plugin.getDatabase().saveGroupAsync(activeGroup);
                });

        SceneTimelineManager timeline = plugin.getSceneTimeline();
        float cueStartX = -0.580f;
        float cueY = 0.020f;
        float cueW = 0.150f;
        float cueH = 0.065f;
        float cueGap = 0.010f;

        for (int c = 0; c < SceneTimelineManager.MAX_CUES; c++) {
            final int cueIdx = c;
            boolean hasSaved = timeline.hasCue(cueIdx);
            boolean isCurrent = (timeline.activeCueIndex() == cueIdx && hasSaved);
            float cx = cueStartX + c * (cueW + cueGap);

            Color cCol = isCurrent
                    ? Color.fromRGB(35, 185, 95)
                    : (hasSaved ? Color.fromRGB(40, 95, 160) : Color.fromRGB(40, 46, 58));

            renderActionButton(group, origin, qRot, detector, "btn_cue_" + c,
                    cx, cueY, cueW, cueH,
                    (hasSaved ? "★ CUE " : "○ CUE ") + (c + 1),
                    cCol,
                    player -> {
                        activeConsoles.put(console.ownerId(), console.withCueSlot(cueIdx));
                        if (timeline.hasCue(cueIdx)) {
                            timeline.recallCue(cueIdx, plugin.getFixtureGroups());
                            plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                            player.sendMessage(Component.text("▶ Szenen-Cue " + (cueIdx + 1) + " geladen!", NamedTextColor.GREEN));
                        } else {
                            var saved = timeline.recordCue(cueIdx, "Cue " + (cueIdx + 1), plugin.getFixtureGroups().values());
                            plugin.getDatabase().saveSceneCueAsync(saved);
                            player.sendMessage(Component.text("● Szenen-Cue " + (cueIdx + 1) + " aufgezeichnet!", NamedTextColor.GOLD));
                        }
                    });
        }

        int targetSlot = console.selectedRecordCueSlot();
        renderActionButton(group, origin, qRot, detector, "btn_rec_cue",
                0.075f, cueY, 0.240f, cueH,
                "● REC CUE " + (targetSlot + 1),
                Color.fromRGB(195, 40, 40),
                player -> {
                    var cue = timeline.recordCue(targetSlot, "Cue " + (targetSlot + 1), plugin.getFixtureGroups().values());
                    plugin.getDatabase().saveSceneCueAsync(cue);
                    player.playSound(origin, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.8f);
                    player.sendMessage(Component.text("● Live-Zustand aller Gruppen in Cue " + (targetSlot + 1) + " gespeichert!", NamedTextColor.GOLD));
                });

        boolean playing = timeline.isPlayingSequence();
        renderActionButton(group, origin, qRot, detector, "btn_seq_play",
                0.335f, cueY, 0.245f, cueH,
                playing ? "⏹ STOP SEQ" : "▶ PLAY SEQ",
                playing ? Color.fromRGB(225, 135, 20) : Color.fromRGB(35, 160, 80),
                player -> {
                    timeline.togglePlayback(plugin.getFixtureGroups().values());
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                });
    }

    private void renderActionButton(
            RenderGroup group,
            Location origin,
            Quaternionf qRot,
            HoloPlanePointDetector detector,
            String key,
            float x,
            float y,
            float w,
            float h,
            String title,
            Color baseColor,
            java.util.function.Consumer<Player> onClick
    ) {
        Matrix4f plane = new Matrix4f()
                .rotate(qRot)
                .translate(x, y, 0.005f)
                .scale(w, h, 1f);
        HoloPlanePointDetector.PlaneHit hit = detector.detectHit(plane);
        boolean hovered = (hit != null);
        if (hovered && didClick(hit.player())) {
            hit.player().playSound(origin, Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
            onClick.accept(hit.player());
        }

        Color col = hovered
                ? Color.fromARGB(245,
                        Math.min(255, baseColor.getRed() + 45),
                        Math.min(255, baseColor.getGreen() + 45),
                        Math.min(255, baseColor.getBlue() + 45))
                : Color.fromARGB(215, baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue());

        addQuad(group, key + "_bg", origin, plane, col);
        addTextLabel(group, key + "_lbl", origin,
                new Matrix4f().rotate(qRot).translate(x + w * 0.5f, y + h * 0.5f - 0.010f, 0.012f).scale(0.082f),
                Component.text(title, NamedTextColor.WHITE, TextDecoration.BOLD),
                Color.fromARGB(0, 0, 0, 0));
    }

    private boolean didClick(Player player) {
        return clickedThisTick.remove(player.getUniqueId());
    }

    private static NamedTextColor getContrastingTextColor(Color background) {
        double luminance = (0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue());
        return luminance > 135 ? NamedTextColor.BLACK : NamedTextColor.WHITE;
    }

    private void addQuad(RenderGroup group, String key, Location origin, Matrix4f unitPlaneTransform, Color argb) {
        Matrix4f finalMat = new Matrix4f(unitPlaneTransform).mul(TextDisplayTriangle.TEXT_DISPLAY_UNIT_SQUARE);
        group.set(key, new RenderEntity<>(
                TextDisplay.class,
                origin,
                display -> {
                    display.text(EMPTY_TEXT);
                    display.setBrightness(FULL_BRIGHTNESS);
                    display.setBackgroundColor(argb);
                    display.setShadowed(false);
                    display.setSeeThrough(false);
                    display.setViewRange(HOLO_DISPLAY_VIEW_RANGE);
                    display.setTransformationMatrix(finalMat);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> {
                    if (!argb.equals(display.getBackgroundColor())) {
                        display.setBackgroundColor(argb);
                    }
                    EntityTracker.updateDisplayTransform(display, finalMat, 1);
                }
        ));
    }

    private void addTextLabel(RenderGroup group, String key, Location origin, Matrix4f textTransform, Component text, Color bg) {
        group.set(key, new RenderEntity<>(
                TextDisplay.class,
                origin,
                display -> {
                    display.text(text);
                    display.setBrightness(FULL_BRIGHTNESS);
                    display.setBackgroundColor(bg);
                    display.setShadowed(true);
                    display.setSeeThrough(false);
                    display.setAlignment(TextDisplay.TextAlignment.CENTER);
                    display.setLineWidth(1200);
                    display.setViewRange(HOLO_DISPLAY_VIEW_RANGE);
                    display.setTransformationMatrix(textTransform);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                },
                display -> {
                    if (!text.equals(display.text())) {
                        display.text(text);
                    }
                    if (!bg.equals(display.getBackgroundColor())) {
                        display.setBackgroundColor(bg);
                    }
                    EntityTracker.updateDisplayTransform(display, textTransform, 1);
                }
        ));
    }
}
