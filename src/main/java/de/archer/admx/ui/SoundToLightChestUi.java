package de.archer.admx.ui;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AudioDspManager;
import de.archer.admx.audio.SoundAction;
import de.archer.admx.audio.SoundToLightConfig;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 54-Slot Hardware-Style Sound-to-Light Steuerpult (Chest-UI) für ADMX.
 * <p>
 * Reagiert unabhängig von BPM/Tempo rein auf die Lautstärkepegel und Transienten
 * innerhalb des vom Benutzer gewählten Frequenzbandes (&le; 4000 Hz).
 * <ul>
 *   <li>Reihe 1 (Slots 0..8): Gruppenauswahl, S2L-Modus-Schalter, Audioquelle, Zurück.</li>
 *   <li>Spalte 0 (Slots 9, 18, 27): Vertikaler Threshold-Schieberegler für die aktive Gruppe.</li>
 *   <li>Spalten 1..7 (Slots 10..16, 19..25, 28..34): Live 7-Band Balkenspektrogramm (&le; 4000 Hz).</li>
 *   <li>Spalte 8 (Slots 17, 26, 35): Live-Pegel, Trigger-Statuslampe &amp; Haltezeit (Hold Ms).</li>
 *   <li>Reihe 5 (Slots 36..44): Horizontaler Frequenz-Schieberegler (&le; 4000 Hz) &amp; Presets.</li>
 *   <li>Reihe 6 (Slots 45..53): Below-Action &amp; Above-Action Selektoren, Master-Steuerung.</li>
 * </ul>
 */
public final class SoundToLightChestUi implements Listener {

    private final AdmxPlugin plugin;
    private final Map<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();
    private BukkitTask liveVisualizerTask;

    public static final class SoundToLightHolder implements InventoryHolder {
        private final String activeGroupId;
        private final int pageOffset;
        private Inventory inventory;

        public SoundToLightHolder(String activeGroupId) {
            this(activeGroupId, 0);
        }

        public SoundToLightHolder(String activeGroupId, int pageOffset) {
            this.activeGroupId = activeGroupId;
            this.pageOffset = pageOffset;
        }

        public String activeGroupId() {
            return activeGroupId;
        }

        public int pageOffset() {
            return pageOffset;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public record FreqBandDef(String name, int lowHz, int highHz) {}

    public static final FreqBandDef[] BANDS = new FreqBandDef[]{
            new FreqBandDef("Sub-Bass (35-90 Hz)", 35, 90),
            new FreqBandDef("Kick/Bass (90-200 Hz)", 90, 200),
            new FreqBandDef("Low-Mid (200-500 Hz)", 200, 500),
            new FreqBandDef("Mid-Punch (500-1000 Hz)", 500, 1000),
            new FreqBandDef("Hi-Mid (1000-2000 Hz)", 1000, 2000),
            new FreqBandDef("Presence (2000-3000 Hz)", 2000, 3000),
            new FreqBandDef("Brilliance (3000-4000 Hz)", 3000, 4000)
    };

    public SoundToLightChestUi(AdmxPlugin plugin) {
        this.plugin = plugin;
        startVisualizerTicker();
    }

    private void startVisualizerTicker() {
        // Taktet alle 3 Ticks (150ms) für flüssige Balken-Animation bei geöffnetem Inventar
        this.liveVisualizerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof SoundToLightHolder holder) {
                    refreshLiveSpectrogram(player.getOpenInventory().getTopInventory(), holder.activeGroupId());
                }
            }
        }, 5L, 3L);
    }

    public void stop() {
        if (liveVisualizerTask != null) {
            liveVisualizerTask.cancel();
            liveVisualizerTask = null;
        }
    }

    public void openUi(Player player, String targetGroupId) {
        openUi(player, targetGroupId, 0);
    }

    public void openUi(Player player, String targetGroupId, int pageOffset) {
        if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
            player.sendMessage(Component.text("Keine Berechtigung (admx.control erforderlich).", NamedTextColor.RED));
            return;
        }

        List<FixtureGroup> allGroups = new ArrayList<>(plugin.getFixtureGroups().values());
        FixtureGroup active = plugin.getFixtureGroup(targetGroupId);
        if (active == null && !allGroups.isEmpty()) {
            active = allGroups.getFirst();
        }
        if (active == null) {
            player.sendMessage(Component.text("Keine Scheinwerfergruppen vorhanden.", NamedTextColor.RED));
            return;
        }

        int clampedOffset = Math.max(0, pageOffset);
        if (clampedOffset >= allGroups.size()) {
            clampedOffset = 0;
        }

        SoundToLightHolder holder = new SoundToLightHolder(active.groupId(), clampedOffset);
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text("ADMX S2L » Sound-To-Light Hardware-Pult", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
        );
        holder.inventory = inv;

        buildInventory(inv, active, clampedOffset);
        player.openInventory(inv);
    }

    private void addTabItem(Inventory inv, int slot, FixtureGroup g, FixtureGroup active) {
        boolean isSelected = g.groupId().equals(active.groupId());
        int count = plugin.countFixturesInGroup(g.groupId());

        ItemStack tabItem = item(
                g.iconMaterial(),
                Component.text(g.displayName(), isSelected ? NamedTextColor.YELLOW : NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(
                        Component.text("Zone: ", NamedTextColor.GRAY).append(Component.text(g.stageZone().displayName(), g.stageZone().color())),
                        Component.text("Zweck: ", NamedTextColor.GRAY).append(Component.text(g.usagePurpose(), NamedTextColor.WHITE)),
                        Component.text(count + " Geräte zugewiesen", NamedTextColor.GREEN),
                        Component.text("Modus: " + g.controlMode().displayName(), isSelected ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY),
                        Component.empty(),
                        isSelected
                                ? Component.text("▶ Aktuell ausgewählt", NamedTextColor.GREEN, TextDecoration.BOLD)
                                : Component.text("Klicken zum Auswählen", NamedTextColor.YELLOW)
                )
        );
        if (isSelected) {
            tabItem.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        }
        inv.setItem(slot, tabItem);
    }

    private void buildInventory(Inventory inv, FixtureGroup active) {
        if (inv.getHolder() instanceof SoundToLightHolder holder) {
            buildInventory(inv, active, holder.pageOffset());
        } else {
            buildInventory(inv, active, 0);
        }
    }

    private void buildInventory(Inventory inv, FixtureGroup active, int pageOffset) {
        ItemStack bg = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, bg);
        }

        List<FixtureGroup> allGroups = new ArrayList<>(plugin.getFixtureGroups().values());
        SoundToLightConfig cfg = active.getSoundConfig();
        if (cfg == null) {
            cfg = SoundToLightConfig.defaultForGroup(active.groupId());
            active.setSoundConfig(cfg);
        }

        // --- Reihe 1 (Slots 0..5): Gruppenauswahl inkl. Paging für alle Kategorien ---
        if (allGroups.size() <= 6) {
            for (int i = 0; i < allGroups.size(); i++) {
                addTabItem(inv, i, allGroups.get(i), active);
            }
        } else {
            int visible = Math.min(5, allGroups.size() - pageOffset);
            for (int i = 0; i < visible; i++) {
                addTabItem(inv, i, allGroups.get(pageOffset + i), active);
            }
            int totalPages = (allGroups.size() + 4) / 5;
            int curPage = (pageOffset / 5) + 1;
            inv.setItem(5, item(
                    Material.ARROW,
                    Component.text("Kategorien Seite " + curPage + "/" + totalPages + " ▶", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    List.of(
                            Component.text("Insgesamt " + allGroups.size() + " Kategorien registriert.", NamedTextColor.GRAY),
                            Component.text("» Klicken zum Weiterblättern", NamedTextColor.GREEN)
                    )
            ));
        }

        // Slot 6: Sound-Active Schalter
        boolean isS2lActive = (active.controlMode() == ControlMode.SOUND_ACTIVE);
        inv.setItem(6, item(
                isS2lActive ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                Component.text("S2L-Modus: " + (isS2lActive ? "AKTIV" : "INAKTIV"),
                        isS2lActive ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Schaltet diese Gruppe in den Sound-Active Modus.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Klicken zum Umschalten", NamedTextColor.YELLOW)
                )
        ));

        // Slot 7: Audio-Quelle wechseln
        var dsp = plugin.getAudioDspManager();
        inv.setItem(7, item(
                Material.NOTE_BLOCK,
                Component.text("Audioquelle: " + dsp.getActiveSource().sourceName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text(dsp.getActiveSource().desc(), NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Klicken zum Wechseln der Audio-Eingabe", NamedTextColor.YELLOW)
                )
        ));

        // Slot 8: Zurück zum DMX-Pult
        inv.setItem(8, item(
                Material.CHEST,
                Component.text("Zurück zum DMX-Pult", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Öffnet das 54-Slot Hauptsteuerpult für manuelle Fader.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Klicken zum Öffnen", NamedTextColor.YELLOW)
                )
        ));

        // --- Spalte 0 (Slots 9, 18, 27): Vertikaler Threshold-Slider ---
        inv.setItem(9, item(
                Material.LIME_STAINED_GLASS_PANE,
                Component.text("Threshold [+] (+5%)", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Erhöht die Auslöseschwelle.", NamedTextColor.GRAY),
                        Component.text("Höher = Nur noch härtere Peaks/Kicks lösen aus.", NamedTextColor.DARK_GRAY)
                )
        ));

        int threshPercent = Math.round(cfg.threshold() * 100);
        inv.setItem(18, item(
                Material.COMPARATOR,
                Component.text("Threshold: " + threshPercent + "%", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Schwellenwert für Gruppe: " + active.displayName(), NamedTextColor.GRAY),
                        Component.text("Aktueller Wert: " + String.format(Locale.ROOT, "%.2f", cfg.threshold()), NamedTextColor.GOLD),
                        Component.empty(),
                        Component.text("Linksklick: +5%  |  Rechtsklick: -5%", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(27, item(
                Material.RED_STAINED_GLASS_PANE,
                Component.text("Threshold [-] (-5%)", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Verringert die Auslöseschwelle.", NamedTextColor.GRAY),
                        Component.text("Niedriger = Leisere Töne lösen bereits Aktionen aus.", NamedTextColor.DARK_GRAY)
                )
        ));

        // --- Spalte 8 (Slots 17, 26, 35): Live-Pegel, Trigger-Lampe & Haltezeit ---
        float bandLevel = plugin.getSoundToLightEngine().getGroupBandLevel(active.groupId());
        boolean isTriggered = plugin.getSoundToLightEngine().isGroupTriggered(active.groupId());

        inv.setItem(17, item(
                Material.TARGET,
                Component.text(String.format(Locale.ROOT, "Band-Pegel: %d%%", Math.round(bandLevel * 100)), NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Gemessene Energie im gewählten Bereich:", NamedTextColor.GRAY),
                        Component.text(cfg.lowHz() + " Hz - " + cfg.highHz() + " Hz", NamedTextColor.WHITE)
                )
        ));

        inv.setItem(26, item(
                isTriggered ? Material.SEA_LANTERN : Material.REDSTONE_LAMP,
                Component.text(isTriggered ? "⚡ TRIGGER: AKTIV! ⚡" : "Trigger: Ruhezustand",
                        isTriggered ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text(isTriggered
                                ? "Pegel überschreitet Schwellenwert -> Über-Threshold Aktion läuft!"
                                : "Pegel unter Schwellenwert -> Unter-Threshold Aktion läuft.", NamedTextColor.DARK_GRAY)
                )
        ));

        inv.setItem(35, item(
                Material.CLOCK,
                Component.text("Haltezeit: " + cfg.holdMs() + " ms", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Mindestdauer des Trigger-Zustands nach einem Peak.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Linksklick: +20 ms  |  Rechtsklick: -20 ms", NamedTextColor.YELLOW)
                )
        ));

        // --- Reihe 5 (Slots 36..44): Horizontaler Frequenz-Slider & Presets ---
        inv.setItem(36, item(
                Material.RED_STAINED_GLASS_PANE,
                Component.text("Low-Cut [-] (-25 Hz)", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Untere Grenzfrequenz senken", NamedTextColor.GRAY))
        ));

        inv.setItem(37, item(
                Material.LIME_STAINED_GLASS_PANE,
                Component.text("Low-Cut [+] (+25 Hz)", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("Untere Grenzfrequenz erhöhen", NamedTextColor.GRAY))
        ));

        inv.setItem(38, item(
                Material.HOPPER,
                Component.text("Von: " + cfg.lowHz() + " Hz", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Untere Grenzfrequenz des Filters.", NamedTextColor.GRAY),
                        Component.text("Linksklick: +25 Hz  |  Rechtsklick: -25 Hz", NamedTextColor.YELLOW)
                )
        ));

        // Presets in Slots 39, 40, 41
        inv.setItem(39, item(
                Material.RAW_IRON,
                Component.text("Preset: Sub-Bass & Kick", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Bereich: 35 Hz - 140 Hz", NamedTextColor.GRAY),
                        Component.text("Ideal für Strobes, Blinder & Flammen!", NamedTextColor.GREEN),
                        Component.empty(),
                        Component.text("Klicken zum Zuweisen", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(40, item(
                Material.RAW_GOLD,
                Component.text("Preset: Snare & Mids", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Bereich: 200 Hz - 900 Hz", NamedTextColor.GRAY),
                        Component.text("Ideal für Moving-Head Bewegungen & Chases!", NamedTextColor.YELLOW),
                        Component.empty(),
                        Component.text("Klicken zum Zuweisen", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(41, item(
                Material.AMETHYST_SHARD,
                Component.text("Preset: Highs & Transients", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Bereich: 1500 Hz - 4000 Hz", NamedTextColor.GRAY),
                        Component.text("Ideal für Laser-Fan-Bursts & Farbwechsel!", NamedTextColor.AQUA),
                        Component.empty(),
                        Component.text("Klicken zum Zuweisen", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(42, item(
                Material.DISPENSER,
                Component.text("Bis: " + cfg.highHz() + " Hz", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Obere Grenzfrequenz (Max. 4000 Hz).", NamedTextColor.GRAY),
                        Component.text("Linksklick: +100 Hz  |  Rechtsklick: -100 Hz", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(43, item(
                Material.RED_STAINED_GLASS_PANE,
                Component.text("High-Cut [-] (-100 Hz)", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Obere Grenzfrequenz senken", NamedTextColor.GRAY))
        ));

        inv.setItem(44, item(
                Material.LIME_STAINED_GLASS_PANE,
                Component.text("High-Cut [+] (+100 Hz)", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("Obere Grenzfrequenz erhöhen (Max 4000 Hz)", NamedTextColor.GRAY))
        ));

        // --- Reihe 6 (Slots 45..53): Aktionen & Master Controls ---
        inv.setItem(45, item(
                Material.BOOK,
                Component.text("Hardware S2L Information", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Dieses System arbeitet wie professionelle Hardware", NamedTextColor.GRAY),
                        Component.text("mit analogen/DSP Bandpassfiltern & Thresholds.", NamedTextColor.GRAY),
                        Component.text("Unabhängig von BPM, Taktart oder Pausen!", NamedTextColor.WHITE)
                )
        ));

        inv.setItem(46, item(
                Material.OBSIDIAN,
                Component.text("Unter Threshold: " + cfg.belowAction().displayName(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text(cfg.belowAction().description(), NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Linksklick: Nächste Aktion  |  Rechtsklick: Vorherige", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(48, item(
                Material.NETHER_STAR,
                Component.text("Über Threshold: " + cfg.aboveAction().displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text(cfg.aboveAction().description(), NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Linksklick: Nächste Aktion  |  Rechtsklick: Vorherige", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(50, item(
                Material.BEACON,
                Component.text("Alle Gruppen auf S2L schalten", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Aktiviert Sound-Active für ausnahmslos alle Gruppen.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Klicken zum Aktivieren", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(51, item(
                Material.ENDER_EYE,
                Component.text("3D Holo-Pult ansehen", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Öffnet oder zentriert das 3D Hologramm-Pult.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Klicken zum Spawnen", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(52, item(
                Material.JUKEBOX,
                Component.text("Tempo- & Beat-Info", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("BPM: " + String.format(Locale.ROOT, "%.1f", plugin.getAudioDspManager().getSnapshot().bpm()), NamedTextColor.WHITE),
                        Component.text("Genre: " + plugin.getAudioDspManager().getSnapshot().genre().displayName(), NamedTextColor.WHITE),
                        Component.empty(),
                        Component.text("S2L reagiert jedoch autark rein auf Pegel!", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(53, item(
                Material.BARRIER,
                Component.text("MASTER BLACKOUT", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Sofortiges Blackout für alle Scheinwerfer.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Klicken zum Umschalten", NamedTextColor.YELLOW)
                )
        ));

        // Spektrogramm-Balken initial zeichnen
        refreshLiveSpectrogram(inv, active.groupId());
    }

    /**
     * Aktualisiert die 21 Spektrogramm-Slots (Reihen 2..4, Spalten 1..7) und den Trigger-Status.
     */
    private void refreshLiveSpectrogram(Inventory inv, String groupId) {
        FixtureGroup active = plugin.getFixtureGroup(groupId);
        if (active == null) return;
        SoundToLightConfig cfg = active.getSoundConfig();
        if (cfg == null) return;

        var analyzer = plugin.getAudioDspManager().getSpectrumAnalyzer();
        float[] all24Bands = analyzer.currentBands();

        // 7 Spalten (Col 1 bis Col 7 -> Index 0 bis 6)
        for (int col = 0; col < 7; col++) {
            FreqBandDef bandDef = BANDS[col];
            float level = analyzer.getLevelForRange(bandDef.lowHz(), bandDef.highHz());

            // Prüfen, ob diese Frequenzspalte im vom Benutzer gewählten Fenster [cfg.lowHz..cfg.highHz] liegt
            boolean isInActiveBand = (bandDef.highHz() >= cfg.lowHz() && bandDef.lowHz() <= cfg.highHz());

            // 3 Höhen-Ebenen:
            // Obere Reihe (Slots 10..16): Peak Level (>= 0.70)
            // Mittlere Reihe (Slots 19..25): Mid Level (>= 0.40)
            // Untere Reihe (Slots 28..34): Base Level (>= 0.15)
            int slotTop = 10 + col;
            int slotMid = 19 + col;
            int slotBot = 28 + col;

            updateVuSlot(inv, slotTop, level >= 0.70f, Material.RED_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, bandDef, level, isInActiveBand);
            updateVuSlot(inv, slotMid, level >= 0.40f, Material.YELLOW_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, bandDef, level, isInActiveBand);
            updateVuSlot(inv, slotBot, level >= 0.15f, isInActiveBand ? Material.LIME_STAINED_GLASS_PANE : Material.CYAN_STAINED_GLASS_PANE,
                    Material.GRAY_STAINED_GLASS_PANE, bandDef, level, isInActiveBand);
        }

        // Trigger-Status & Pegelanzeige live auffrischen
        float currentBandLevel = plugin.getSoundToLightEngine().getGroupBandLevel(active.groupId());
        boolean isTriggered = plugin.getSoundToLightEngine().isGroupTriggered(active.groupId());

        inv.setItem(17, item(
                Material.TARGET,
                Component.text(String.format(Locale.ROOT, "Band-Pegel: %d%%", Math.round(currentBandLevel * 100)),
                        isTriggered ? NamedTextColor.GREEN : NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Gewählter Bereich: " + cfg.lowHz() + " Hz - " + cfg.highHz() + " Hz", NamedTextColor.WHITE),
                        Component.text("Threshold: " + Math.round(cfg.threshold() * 100) + "%", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(26, item(
                isTriggered ? Material.SEA_LANTERN : Material.REDSTONE_LAMP,
                Component.text(isTriggered ? "⚡ TRIGGER: AKTIV! ⚡" : "Trigger: Ruhezustand",
                        isTriggered ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text(isTriggered
                                ? "Pegel überschreitet Schwellenwert -> Über-Threshold Aktion läuft!"
                                : "Pegel unter Schwellenwert -> Unter-Threshold Aktion läuft.", NamedTextColor.DARK_GRAY)
                )
        ));
    }

    private void updateVuSlot(Inventory inv, int slot, boolean lit, Material onMat, Material offMat, FreqBandDef bandDef, float level, boolean inRange) {
        Material mat = lit ? onMat : offMat;
        Component title = Component.text(bandDef.name(), inRange ? NamedTextColor.YELLOW : NamedTextColor.GRAY, TextDecoration.BOLD);
        List<Component> lore = List.of(
                Component.text(String.format(Locale.ROOT, "Pegel: %d%%", Math.round(level * 100)), lit ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY),
                Component.text(inRange ? "✔ Im aktiven Gruppen-Frequenzbereich" : "✘ Außerhalb des Filter-Bereichs",
                        inRange ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY),
                Component.empty(),
                Component.text("Klicken: Frequenzbereich sofort auf dieses Band setzen", NamedTextColor.YELLOW)
        );
        inv.setItem(slot, item(mat, title, lore));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SoundToLightHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SoundToLightHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // SEC-01 Berechtigungsprüfung
        if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
            player.sendMessage(Component.text("Keine Berechtigung zur DMX-Steuerung.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        // SEC-03 Klick-Rate-Limiting (75ms Cooldown)
        long now = System.currentTimeMillis();
        long lastClick = clickCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastClick < 75) {
            return;
        }
        clickCooldowns.put(player.getUniqueId(), now);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }

        List<FixtureGroup> allGroups = new ArrayList<>(plugin.getFixtureGroups().values());
        FixtureGroup active = plugin.getFixtureGroup(holder.activeGroupId());
        if (active == null && !allGroups.isEmpty()) {
            active = allGroups.getFirst();
        }
        if (active == null) return;

        SoundToLightConfig cfg = active.getSoundConfig();
        if (cfg == null) {
            cfg = SoundToLightConfig.defaultForGroup(active.groupId());
            active.setSoundConfig(cfg);
        }

        boolean right = event.isRightClick();

        switch (slot) {
            // Gruppenauswahl (Slots 0..5 mit Paging)
            case 0, 1, 2, 3, 4 -> {
                int targetIdx = (allGroups.size() <= 6) ? slot : (holder.pageOffset() + slot);
                if (targetIdx < allGroups.size()) {
                    openUi(player, allGroups.get(targetIdx).groupId(), holder.pageOffset());
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.4f);
                    return;
                }
            }
            case 5 -> {
                if (allGroups.size() > 6) {
                    int next = (holder.pageOffset() + 5 >= allGroups.size()) ? 0 : (holder.pageOffset() + 5);
                    openUi(player, active.groupId(), next);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.4f);
                    return;
                } else if (slot < allGroups.size()) {
                    openUi(player, allGroups.get(slot).groupId(), 0);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.4f);
                    return;
                }
            }
            // S2L Toggle für Gruppe
            case 6 -> {
                if (active.controlMode() == ControlMode.SOUND_ACTIVE) {
                    active.setControlMode(ControlMode.MANUAL_LIVE);
                } else {
                    active.setControlMode(ControlMode.SOUND_ACTIVE);
                }
                plugin.getDatabase().saveGroupAsync(active);
            }
            // Audioquelle wechseln
            case 7 -> {
                var dsp = plugin.getAudioDspManager();
                var cur = dsp.getActiveSource();
                var next = switch (cur) {
                    case BOILER_MEDIA -> AudioDspManager.AudioSourceType.LOOPBACK_STREAM;
                    case LOOPBACK_STREAM -> AudioDspManager.AudioSourceType.SYNTHETIC_DEMO;
                    case SYNTHETIC_DEMO -> AudioDspManager.AudioSourceType.BOILER_MEDIA;
                };
                dsp.setActiveSource(next);
                player.sendMessage(Component.text("Audioquelle gewechselt zu: " + next.sourceName(), NamedTextColor.GREEN));
            }
            // Zurück zum ChestConsoleUi
            case 8 -> {
                plugin.getChestConsoleUi().openConsole(player, active.groupId());
                return;
            }
            // Threshold Up
            case 9 -> {
                cfg.setThreshold(cfg.threshold() + 0.05f);
                plugin.getDatabase().saveGroupAsync(active);
            }
            // Threshold Indicator
            case 18 -> {
                float step = right ? -0.05f : 0.05f;
                cfg.setThreshold(cfg.threshold() + step);
                plugin.getDatabase().saveGroupAsync(active);
            }
            // Threshold Down
            case 27 -> {
                cfg.setThreshold(cfg.threshold() - 0.05f);
                plugin.getDatabase().saveGroupAsync(active);
            }
            // Spektrogramm Spalten-Klick: Direktes Zuweisen des Bandes
            case 10, 19, 28 -> assignBandToConfig(cfg, 0, active, player);
            case 11, 20, 29 -> assignBandToConfig(cfg, 1, active, player);
            case 12, 21, 30 -> assignBandToConfig(cfg, 2, active, player);
            case 13, 22, 31 -> assignBandToConfig(cfg, 3, active, player);
            case 14, 23, 32 -> assignBandToConfig(cfg, 4, active, player);
            case 15, 24, 33 -> assignBandToConfig(cfg, 5, active, player);
            case 16, 25, 34 -> assignBandToConfig(cfg, 6, active, player);

            // Haltezeit (Hold Ms)
            case 35 -> {
                int step = right ? -20 : 20;
                cfg.setHoldMs(cfg.holdMs() + step);
                plugin.getDatabase().saveGroupAsync(active);
            }

            // Low-Cut Buttons & Indicator
            case 36 -> {
                cfg.setLowHz(cfg.lowHz() - 25);
                plugin.getDatabase().saveGroupAsync(active);
            }
            case 37 -> {
                cfg.setLowHz(cfg.lowHz() + 25);
                plugin.getDatabase().saveGroupAsync(active);
            }
            case 38 -> {
                int step = right ? -25 : 25;
                cfg.setLowHz(cfg.lowHz() + step);
                plugin.getDatabase().saveGroupAsync(active);
            }

            // Presets (Slots 39, 40, 41)
            case 39 -> {
                cfg.setLowHz(35);
                cfg.setHighHz(140);
                cfg.setBelowAction(SoundAction.BLACKOUT);
                cfg.setAboveAction(SoundAction.STROBE_10HZ);
                plugin.getDatabase().saveGroupAsync(active);
                player.sendMessage(Component.text("Preset 'Sub-Bass & Kick' für " + active.displayName() + " geladen!", NamedTextColor.GREEN));
            }
            case 40 -> {
                cfg.setLowHz(200);
                cfg.setHighHz(900);
                cfg.setBelowAction(SoundAction.DIMMED_SWEEP);
                cfg.setAboveAction(SoundAction.COLOR_CYCLE);
                plugin.getDatabase().saveGroupAsync(active);
                player.sendMessage(Component.text("Preset 'Snare & Mids' für " + active.displayName() + " geladen!", NamedTextColor.GREEN));
            }
            case 41 -> {
                cfg.setLowHz(1500);
                cfg.setHighHz(4000);
                cfg.setBelowAction(SoundAction.STATIC_COLOR);
                cfg.setAboveAction(SoundAction.LASER_FAN_BURST);
                plugin.getDatabase().saveGroupAsync(active);
                player.sendMessage(Component.text("Preset 'Highs & Transients' für " + active.displayName() + " geladen!", NamedTextColor.GREEN));
            }

            // High-Cut Buttons & Indicator
            case 42 -> {
                int step = right ? -100 : 100;
                cfg.setHighHz(cfg.highHz() + step);
                plugin.getDatabase().saveGroupAsync(active);
            }
            case 43 -> {
                cfg.setHighHz(cfg.highHz() - 100);
                plugin.getDatabase().saveGroupAsync(active);
            }
            case 44 -> {
                cfg.setHighHz(cfg.highHz() + 100);
                plugin.getDatabase().saveGroupAsync(active);
            }

            // Below Action (Slot 46)
            case 46 -> {
                SoundAction cur = cfg.belowAction();
                SoundAction next = right ? cur.prev() : cur.next();
                cfg.setBelowAction(next);
                plugin.getDatabase().saveGroupAsync(active);
            }

            // Above Action (Slot 48)
            case 48 -> {
                SoundAction cur = cfg.aboveAction();
                SoundAction next = right ? cur.prev() : cur.next();
                cfg.setAboveAction(next);
                plugin.getDatabase().saveGroupAsync(active);
            }

            // Alle Gruppen auf S2L schalten (Slot 50)
            case 50 -> {
                for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                    g.setControlMode(ControlMode.SOUND_ACTIVE);
                }
                plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                player.sendMessage(Component.text("✦ Alle Gerätegruppen auf Sound-Active (S2L) geschaltet!", NamedTextColor.GREEN));
            }

            // 3D Holo-Console (Slot 51)
            case 51 -> {
                player.closeInventory();
                plugin.getHoloConsoleUi().spawnAtWandLocation(player, player.getLocation().add(player.getLocation().getDirection().multiply(1.5)), player.getLocation().getYaw());
                return;
            }

            // Master Blackout (Slot 53)
            case 53 -> {
                boolean anyNotBlackout = plugin.getFixtureGroups().values().stream()
                        .anyMatch(g -> g.controlMode() != ControlMode.BLACKOUT);
                for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                    g.setControlMode(anyNotBlackout ? ControlMode.BLACKOUT : ControlMode.SOUND_ACTIVE);
                }
                plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
            }

            default -> {
                return;
            }
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.25f);
        buildInventory(event.getInventory(), active);
    }

    private void assignBandToConfig(SoundToLightConfig cfg, int bandIdx, FixtureGroup group, Player player) {
        FreqBandDef band = BANDS[bandIdx];
        cfg.setLowHz(band.lowHz());
        cfg.setHighHz(band.highHz());
        plugin.getDatabase().saveGroupAsync(group);
        player.sendMessage(Component.text("Frequenzbereich für " + group.displayName() + " gesetzt auf: " + band.name(), NamedTextColor.GREEN));
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
