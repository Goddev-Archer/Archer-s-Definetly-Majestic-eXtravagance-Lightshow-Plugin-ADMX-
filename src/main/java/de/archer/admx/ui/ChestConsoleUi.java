package de.archer.admx.ui;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.SceneTimelineManager;
import de.archer.admx.control.UiPreference;
import de.archer.admx.placement.FixturePlacementManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Großes 54-Slot Doppelkisten-Steuerpult (Chest-UI) für ADMX:
 * <ul>
 *   <li>Reihe 1 (Slots 0–5 + Slot 6 Seitenblättern): Auswahl der Gerätegruppen + Anzeige der eindeutig zugewiesenen Geräte.</li>
 *   <li>Reihe 2–4: Live-DMX-Fader & Parameter (Modus, Dimmer, Farbe/Hue/Sättigung, Pan, Tilt, Zoom,
 *       Strobe, Bewegungsmuster, Speed, Amplitude und Phasen-Fanning für Einzelgeräte-Chases).</li>
 *   <li>Reihe 6 (Slots 45–53): Szenen-Programmier-Ebene (Cues 1–4 laden/aufzeichnen, Sequencer Play/Stop,
 *       Master-Blackout und Wechsel zum 3D-Holo-UI).</li>
 * </ul>
 */
public final class ChestConsoleUi implements Listener {

    private final AdmxPlugin plugin;

    private static final class ConsoleHolder implements InventoryHolder {
        private final String activeGroupId;
        private final int pageOffset;
        private Inventory inventory;

        private ConsoleHolder(String activeGroupId, int pageOffset) {
            this.activeGroupId = activeGroupId;
            this.pageOffset = pageOffset;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public ChestConsoleUi(AdmxPlugin plugin) {
        this.plugin = plugin;
    }

    public void openConsole(Player player, String groupId) {
        openConsole(player, groupId, -1);
    }

    public void openConsole(Player player, String groupId, int explicitPageOffset) {
        List<FixtureGroup> allGroups = new ArrayList<>(plugin.getFixtureGroups().values());
        FixtureGroup active = plugin.getFixtureGroup(groupId);
        if (active == null && !allGroups.isEmpty()) {
            active = allGroups.getFirst();
        }
        if (active == null) {
            return;
        }

        int activeIdx = 0;
        for (int i = 0; i < allGroups.size(); i++) {
            if (allGroups.get(i).groupId().equals(active.groupId())) {
                activeIdx = i;
                break;
            }
        }
        int pageOffset = explicitPageOffset >= 0
                ? (explicitPageOffset % Math.max(1, allGroups.size()))
                : (activeIdx / 6) * 6;

        ConsoleHolder holder = new ConsoleHolder(active.groupId(), pageOffset);
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text("ADMX DMX-Pult » " + active.displayName(), NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
        );
        holder.inventory = inv;

        ItemStack bg = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, bg);
        }

        // --- Reihe 1 (Slots 0..5): Gruppen-Auswahl (inkl. Paging für > 6 Gruppen -> BUG-04) ---
        int visibleCount = Math.min(6, allGroups.size() - pageOffset);
        for (int i = 0; i < visibleCount; i++) {
            FixtureGroup g = allGroups.get(pageOffset + i);
            boolean selected = g.groupId().equals(active.groupId());
            int count = plugin.countFixturesInGroup(g.groupId());

            inv.setItem(i, item(
                    selected ? Material.BEACON : g.iconMaterial(),
                    Component.text((selected ? "✦ " : "") + g.displayName(),
                            selected ? NamedTextColor.AQUA : NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of(
                            Component.text("Gruppen-ID: ", NamedTextColor.GRAY).append(Component.text(g.groupId(), NamedTextColor.YELLOW)),
                            Component.text("Zugewiesene Geräte: ", NamedTextColor.GRAY).append(Component.text(count + " eindeutig adressiert", NamedTextColor.GREEN)),
                            Component.text("Modus: ", NamedTextColor.GRAY).append(Component.text(g.controlMode().displayName(), NamedTextColor.GOLD)),
                            Component.empty(),
                            Component.text(selected ? "✔ Aktuell ausgewählt" : "» Klicken zum Auswählen",
                                    selected ? NamedTextColor.AQUA : NamedTextColor.GREEN)
                    )
            ));
        }

        if (allGroups.size() > 6) {
            int totalPages = (allGroups.size() + 5) / 6;
            int currentPage = (pageOffset / 6) + 1;
            inv.setItem(6, item(
                    Material.ARROW,
                    Component.text("Gruppen-Seite " + currentPage + "/" + totalPages + " blättern", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    List.of(
                            Component.text("Insgesamt " + allGroups.size() + " Gruppen registriert.", NamedTextColor.GRAY),
                            Component.text("» Klicken für nächste Gruppen-Seite", NamedTextColor.GREEN)
                    )
            ));
        }

        // Slot 8: UI-Modus Umschalter (Chest-UI <-> 3D Holo-UI)
        UiPreference pref = plugin.getPlacementManager().getUiPreference(player);
        inv.setItem(8, item(
                Material.END_CRYSTAL,
                Component.text("UI-Typ: " + pref.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Wähle dein bevorzugtes Steuerpult:", NamedTextColor.GRAY),
                        Component.text("• Großes Chest-UI (54-Slot Inventar)", NamedTextColor.WHITE),
                        Component.text("• 3D Hologramm-UI (via Wand in der Welt platzierbar)", NamedTextColor.AQUA),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten auf Holo-UI", NamedTextColor.YELLOW)
                )
        ));

        // --- Reihe 3 (Slots 19..25): Kern-DMX-Fader ---
        inv.setItem(19, item(
                active.controlMode() == ControlMode.BLACKOUT ? Material.REDSTONE_BLOCK : Material.SLIME_BALL,
                Component.text("Gruppen-Modus: " + active.controlMode().displayName(), NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Steuert, wie diese Gruppe arbeitet:", NamedTextColor.GRAY),
                        Component.text("• Live-Pult (Manuelle Parameter)", NamedTextColor.WHITE),
                        Component.text("• Auto-Show (Automatische Musik/Demo-Show)", NamedTextColor.WHITE),
                        Component.text("• Szenen-Sequencer (Cues 1-4)", NamedTextColor.WHITE),
                        Component.text("• Blackout (Dunkel)", NamedTextColor.RED),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(20, item(
                Material.GLOWSTONE_DUST,
                Component.text("Dimmer (Helligkeit): " + Math.round(active.dimmer() * 100) + "%", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +10%  |  Rechtsklick: -10%", NamedTextColor.GRAY),
                        Component.text("Shift-Klick: 100% / 0% Toggle", NamedTextColor.AQUA)
                )
        ));

        inv.setItem(21, item(
                Material.CYAN_DYE,
                Component.text(String.format("Farbton (Hue): %.0f°", active.hueDegrees()), NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +20°  |  Rechtsklick: -20°", NamedTextColor.GRAY),
                        Component.text("Shift-Klick: +60° Farbsprung", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(22, item(
                Material.WHITE_DYE,
                Component.text("Farbsättigung: " + Math.round(active.saturation() * 100) + "%", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(
                        Component.text("0% = Reines Warm-/Kaltweiß, 100% = Vollfarbe", NamedTextColor.GRAY),
                        Component.text("Linksklick: +15%  |  Rechtsklick: -15%", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(23, item(
                Material.COMPASS,
                Component.text(String.format("Pan (Horizontal): %.0f°", active.panDegrees()), NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +15°  |  Rechtsklick: -15°", NamedTextColor.GRAY),
                        Component.text("Shift-Klick: Zurücksetzen auf 0° (Mitte)", NamedTextColor.AQUA)
                )
        ));

        inv.setItem(24, item(
                Material.CLOCK,
                Component.text(String.format("Tilt (Vertikal): %.0f°", active.tiltDegrees()), NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +10°  |  Rechtsklick: -10°", NamedTextColor.GRAY),
                        Component.text("Shift-Klick: Zurücksetzen auf -15°", NamedTextColor.AQUA)
                )
        ));

        inv.setItem(25, item(
                Material.SPYGLASS,
                Component.text(String.format("Zoom (Öffnungswinkel): %.0f°", active.zoomDegrees()), NamedTextColor.BLUE, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +6° (Breiter)  |  Rechtsklick: -6° (Enger)", NamedTextColor.GRAY)
                )
        ));

        // --- Reihe 4 (Slots 28..34): Bewegung, Fanning & Strobe ---
        inv.setItem(29, item(
                Material.ELYTRA,
                Component.text("Bewegungsmuster: " + active.movementPattern().displayName(), NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Form-Generator für alle Geräte der Gruppe:", NamedTextColor.GRAY),
                        Component.text("Statisch, Kreis, Pan-Sweep, Tilt-Welle, Figure-8, Fächer", NamedTextColor.WHITE),
                        Component.empty(),
                        Component.text("» Klicken für nächstes Muster", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(30, item(
                Material.FEATHER,
                Component.text(String.format("Bewegungs-Speed: %.1fx", active.movementSpeed()), NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +0.25x  |  Rechtsklick: -0.25x", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(31, item(
                Material.PISTON,
                Component.text("Bewegungs-Radius: " + Math.round(active.movementAmplitude() * 100) + "%", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Größe der Pan/Tilt-Auslenkung", NamedTextColor.GRAY),
                        Component.text("Linksklick: +15%  |  Rechtsklick: -15%", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(32, item(
                Material.PRISMARINE_SHARD,
                Component.text("Phasen-Fanning (Einzelgeräte-Chase): " + Math.round(active.phaseSpread() * 100) + "%", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Nutzt die eindeutige Datenbank-ID & Reihenfolge jedes", NamedTextColor.GRAY),
                        Component.text("Geräts in der Gruppe für Wellen- und Fächer-Chases!", NamedTextColor.GRAY),
                        Component.text("0% = Alle synchron  |  100% = Volle Welle über die Gruppe", NamedTextColor.WHITE),
                        Component.text("Linksklick: +20%  |  Rechtsklick: -20%", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(33, item(
                Material.LIGHTNING_ROD,
                Component.text(active.strobeHz() <= 0.1f ? "Strobe: AUS" : String.format("Strobe: %.0f Hz (PSE-Safe)", active.strobeHz()),
                        NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +2 Hz  |  Rechtsklick: -2 Hz (Max. 10 Hz)", NamedTextColor.GRAY),
                        Component.text("Shift-Klick: Strobe sofort ausschalten", NamedTextColor.YELLOW)
                )
        ));

        // --- Reihe 6 (Slots 45..53): Szenen-Programmier-Ebene (Cues 1..4 + Sequencer) ---
        SceneTimelineManager timeline = plugin.getSceneTimeline();
        for (int c = 0; c < SceneTimelineManager.MAX_CUES; c++) {
            boolean exists = timeline.hasCue(c);
            boolean activeCue = (timeline.activeCueIndex() == c && exists);
            inv.setItem(45 + c, item(
                    activeCue ? Material.LIME_CONCRETE : (exists ? Material.CYAN_CONCRETE : Material.GRAY_CONCRETE),
                    Component.text("Szenen-Cue " + (c + 1) + (exists ? " [Gespeichert]" : " [Leer]"),
                            activeCue ? NamedTextColor.GREEN : (exists ? NamedTextColor.AQUA : NamedTextColor.GRAY), TextDecoration.BOLD),
                    List.of(
                            Component.text("Linksklick: ", NamedTextColor.WHITE)
                                    .append(Component.text("Cue " + (c + 1) + " live aufrufen", NamedTextColor.GREEN)),
                            Component.text("Rechtsklick / Shift-Klick: ", NamedTextColor.WHITE)
                                    .append(Component.text("Aktuelles Bühnenbild in Cue " + (c + 1) + " speichern", NamedTextColor.GOLD))
                    )
            ));
        }

        boolean seqPlaying = timeline.isPlayingSequence();
        inv.setItem(50, item(
                seqPlaying ? Material.ORANGE_CONCRETE : Material.EMERALD_BLOCK,
                Component.text(seqPlaying ? "⏹ Szenen-Sequencer STOPPEN" : "▶ Szenen-Sequencer STARTEN",
                        seqPlaying ? NamedTextColor.GOLD : NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Spielt alle gespeicherten Szenen-Cues (1–4) mit", NamedTextColor.GRAY),
                        Component.text("weichen OKLab- und Pan/Tilt-Crossfades in Schleife ab!", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken für Play / Stop", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(51, item(
                Material.COMPARATOR,
                Component.text("Flowboard: Show-Programmierer", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Öffnet das interaktive Flowboard, um Nodes auf", NamedTextColor.GRAY),
                        Component.text("einer Fläche zu platzieren, mit Kabeln zu verknüpfen", NamedTextColor.GRAY),
                        Component.text("und zeitgesteuerte Lichtshows zu programmieren!", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Öffnen", NamedTextColor.YELLOW)
                )
        ));

        var beat = plugin.getAudioDspManager().getSnapshot();
        String strobeStatus = beat.strobeActive()
                ? String.format(Locale.ROOT, "Stroboskop: AKTIV (%.1f Hz | Laser & Strobes)", beat.strobeHz())
                : "Stroboskop: INAKTIV (Breakdown / Ruhephase)";
        inv.setItem(52, item(
                Material.JUKEBOX,
                Component.text("Smart Auto-Show: " + beat.genre().displayName(), beat.genre().color(), TextDecoration.BOLD),
                List.of(
                        Component.text("Quelle: " + plugin.getAudioDspManager().getActiveSource().sourceName(), NamedTextColor.GOLD),
                        Component.text(String.format(Locale.ROOT, "Tempo: %.1f BPM  |  Takt: %d/4  |  Kick: %s",
                                beat.bpm(), beat.beatInBar() + 1, beat.isKick() ? "PUNCH" : (beat.isKickDriving() ? "Treibend" : "Pause")), NamedTextColor.WHITE),
                        Component.text(strobeStatus, beat.strobeActive() ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                        Component.text("Genre: " + beat.genre().description(), NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Linksklick: Auto-Show Studio (Song-DNA Learner & Quick-Recall) öffnen", NamedTextColor.YELLOW),
                        Component.text("Rechtsklick: Audio-Eingabe wechseln (Boiler / Stream / Demo)", NamedTextColor.AQUA),
                        Component.text("Shift-Klick: Hardware Sound-To-Light (S2L) Pult öffnen", NamedTextColor.LIGHT_PURPLE)
                )
        ));

        inv.setItem(53, item(
                Material.BARRIER,
                Component.text("MASTER BLACKOUT (Alle Gruppen)", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Schaltet sofort alle Gruppen dunkel bzw.", NamedTextColor.GRAY),
                        Component.text("beim zweiten Klick wieder zurück in den Live-Modus.", NamedTextColor.GRAY)
                )
        ));

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ConsoleHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConsoleHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!FixturePlacementManager.hasPlaceOrControlPermission(player)) {
            player.closeInventory();
            return;
        }
        if (!plugin.getPlacementManager().checkClickCooldown(player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }

        List<FixtureGroup> allGroups = new ArrayList<>(plugin.getFixtureGroups().values());
        FixtureGroup active = plugin.getFixtureGroup(holder.activeGroupId);
        if (active == null) {
            player.closeInventory();
            return;
        }

        boolean right = event.isRightClick();
        boolean shift = event.isShiftClick();

        // Gruppenwechsel (Slots 0..5 auf der aktuellen Seite)
        int visibleCount = Math.min(6, allGroups.size() - holder.pageOffset);
        if (slot < visibleCount) {
            FixtureGroup target = allGroups.get(holder.pageOffset + slot);
            plugin.getPlacementManager().setSelectedGroupId(player, target.groupId());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
            openConsole(player, target.groupId(), holder.pageOffset);
            return;
        }

        if (slot == 6 && allGroups.size() > 6) {
            int nextOffset = (holder.pageOffset + 6 >= allGroups.size()) ? 0 : (holder.pageOffset + 6);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
            openConsole(player, active.groupId(), nextOffset);
            return;
        }

        if (slot == 8) {
            UiPreference next = plugin.getPlacementManager().toggleUiPreference(player);
            player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1.3f);
            player.sendMessage(Component.text("✦ Bevorzugtes Steuerpult gewechselt auf: ", NamedTextColor.AQUA)
                    .append(Component.text(next.displayName(), NamedTextColor.GOLD)));
            if (next == UiPreference.HOLO_UI) {
                player.closeInventory();
                player.sendMessage(Component.text("Tipp: Linksklicke mit dem ADMX-Wand auf einen Block (oder wähle im Wand-Menü 'Holo-UI platzieren'), um das 3D-Hologramm-Pult dort aufzustellen!", NamedTextColor.GREEN));
                return;
            }
            openConsole(player, active.groupId(), holder.pageOffset);
            return;
        }

        switch (slot) {
            case 19 -> {
                ControlMode nextMode = active.controlMode().next();
                active.setControlMode(nextMode);
                if (nextMode == ControlMode.AUTO_SHOW) {
                    plugin.getDatabase().saveGroupAsync(active);
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
                    plugin.getAutoShowStudioChestUi().openStudio(player);
                    return;
                }
            }
            case 20 -> {
                if (shift) active.setDimmer(active.dimmer() > 0.1f ? 0f : 1f);
                else active.setDimmer(active.dimmer() + (right ? -0.10f : 0.10f));
            }
            case 21 -> active.setHueDegrees(active.hueDegrees() + (shift ? 60f : (right ? -20f : 20f)));
            case 22 -> active.setSaturation(active.saturation() + (right ? -0.15f : 0.15f));
            case 23 -> {
                if (shift) active.setPanDegrees(0f);
                else active.setPanDegrees(active.panDegrees() + (right ? -15f : 15f));
            }
            case 24 -> {
                if (shift) active.setTiltDegrees(-15f);
                else active.setTiltDegrees(active.tiltDegrees() + (right ? -10f : 10f));
            }
            case 25 -> active.setZoomDegrees(active.zoomDegrees() + (right ? -6f : 6f));
            case 29 -> active.setMovementPattern(active.movementPattern().next());
            case 30 -> active.setMovementSpeed(active.movementSpeed() + (right ? -0.25f : 0.25f));
            case 31 -> active.setMovementAmplitude(active.movementAmplitude() + (right ? -0.15f : 0.15f));
            case 32 -> active.setPhaseSpread(active.phaseSpread() + (right ? -0.20f : 0.20f));
            case 33 -> {
                if (shift) active.setStrobeHz(0f);
                else active.setStrobeHz(active.strobeHz() + (right ? -2f : 2f));
            }
            case 45, 46, 47, 48 -> {
                int cueIdx = slot - 45;
                SceneTimelineManager timeline = plugin.getSceneTimeline();
                if (right || shift || !timeline.hasCue(cueIdx)) {
                    var cue = timeline.recordCue(cueIdx, "Cue " + (cueIdx + 1), plugin.getFixtureGroups().values());
                    plugin.getDatabase().saveSceneCueAsync(cue);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.7f);
                    player.sendMessage(Component.text("● Aktuelles Bühnenbild aller Gruppen in Szenen-Cue " + (cueIdx + 1) + " gespeichert!", NamedTextColor.GOLD));
                } else {
                    timeline.recallCue(cueIdx, plugin.getFixtureGroups());
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
                    player.sendMessage(Component.text("▶ Szenen-Cue " + (cueIdx + 1) + " geladen!", NamedTextColor.GREEN));
                }
            }
            case 50 -> {
                plugin.getSceneTimeline().togglePlayback(plugin.getFixtureGroups().values());
                plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
            }
            case 51 -> {
                player.closeInventory();
                var boards = plugin.getFlowboards();
                var board = boards.isEmpty()
                        ? plugin.getOrCreateFlowboard("main_show", "Intro Energy Show")
                        : boards.values().iterator().next();
                plugin.getFlowboardGui().openFlowboard(player, board);
                return;
            }
            case 52 -> {
                if (event.isShiftClick()) {
                    player.closeInventory();
                    plugin.getSoundToLightChestUi().openUi(player, active.groupId());
                    return;
                } else if (right) {
                    var dsp = plugin.getAudioDspManager();
                    var current = dsp.getActiveSource();
                    var next = switch (current) {
                        case BOILER_MEDIA -> de.archer.admx.audio.AudioDspManager.AudioSourceType.LOOPBACK_STREAM;
                        case LOOPBACK_STREAM -> de.archer.admx.audio.AudioDspManager.AudioSourceType.SYNTHETIC_DEMO;
                        case SYNTHETIC_DEMO -> de.archer.admx.audio.AudioDspManager.AudioSourceType.BOILER_MEDIA;
                    };
                    dsp.setActiveSource(next);
                    player.sendMessage(Component.text("Audio-Eingabe umgeschaltet auf: " + next.sourceName(), NamedTextColor.GREEN));
                } else {
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        g.setControlMode(ControlMode.AUTO_SHOW);
                    }
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
                    plugin.getAutoShowStudioChestUi().openStudio(player);
                    return;
                }
            }
            case 53 -> {
                boolean anyNotBlackout = plugin.getFixtureGroups().values().stream()
                        .anyMatch(g -> g.controlMode() != ControlMode.BLACKOUT);
                for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                    g.setControlMode(anyNotBlackout ? ControlMode.BLACKOUT : ControlMode.MANUAL_LIVE);
                }
                plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
            }
            default -> {
                return;
            }
        }

        plugin.getDatabase().saveGroupAsync(active);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.25f);
        openConsole(player, active.groupId(), holder.pageOffset);
    }

    private static ItemStack item(Material mat, Component title, List<Component> lore) {
        ItemStack stack = new ItemStack(mat);
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(title.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
