package de.archer.admx.ui;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.artnet.ArtNetDmxUniverseManager;
import de.archer.admx.dmx.ConsoleModelType;
import de.archer.admx.dmx.DmxConsoleRenderer;
import de.archer.admx.dmx.DmxMergeMode;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.dmx.PlacedDmxConsole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-Game Pro DMX512 Workstation & Hardware-Pult-Oberfläche (54-Slot Inventar-Konsole).
 * <p>
 * Verbindet platzierte 3D-DMX-Pulte ({@link PlacedDmxConsole}) direkt mit einem der 16
 * DMX512-Universen ({@link DmxUniverse}) sowie der nativen Art-Net 4 Schnittstelle.
 * <p>
 * Sicherheits-Invarianten:
 * <ul>
 *     <li><b>SEC-01 / SEC-02:</b> Strikte Berechtigungsprüfung ({@code admx.console}, {@code admx.control}, {@code admx.admin}).</li>
 *     <li><b>SEC-03:</b> Klick-Rate-Limiting über {@code checkClickCooldown}.</li>
 *     <li><b>SEC-07:</b> Vollständiges Canceln von {@link InventoryClickEvent} und {@link InventoryDragEvent}.</li>
 *     <li><b>PERF-09:</b> Asynchrone SQLite-Persistierung bei Universums- oder Bank-Wechseln.</li>
 *     <li><b>PERF-14:</b> Bereinigung aller Spieler-Sessions bei {@link PlayerQuitEvent}.</li>
 * </ul>
 */
public final class DmxWorkstationUi implements Listener {

    private static final String[] CHANNEL_LABELS_16CH = {
            "Pan Coarse (X)",
            "Pan Fine (16-Bit)",
            "Tilt Coarse (Y)",
            "Tilt Fine (16-Bit)",
            "Master Dimmer",
            "Strobe / Shutter",
            "Red (R)",
            "Green (G)",
            "Blue (B)",
            "White (W)",
            "Zoom / Beam Angle",
            "Color Macro",
            "Gobo / Pattern",
            "Movement Speed",
            "FX / Prism",
            "Control / Reset"
    };

    private final AdmxPlugin plugin;
    private final NamespacedKey consoleIdKey;
    private final Map<UUID, WorkstationSession> sessionsByPlayer = new ConcurrentHashMap<>();

    public record WorkstationSession(
            UUID consoleId,
            int universeId,
            int faderBank
    ) {
        public WorkstationSession withUniverse(int newUniverse) {
            return new WorkstationSession(consoleId, Math.clamp(newUniverse, 0, DmxUniverse.MAX_UNIVERSES - 1), faderBank);
        }

        public WorkstationSession withBank(int newBank) {
            return new WorkstationSession(consoleId, universeId, Math.clamp(newBank, 0, 63));
        }
    }

    private static final class WorkstationHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public DmxWorkstationUi(AdmxPlugin plugin) {
        this.plugin = plugin;
        this.consoleIdKey = new NamespacedKey(plugin, DmxConsoleRenderer.PDC_CONSOLE_ID_KEY);
    }

    public static boolean hasConsolePermission(Player player) {
        return player.hasPermission("admx.admin")
                || player.hasPermission("admx.control")
                || player.hasPermission("admx.console");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionsByPlayer.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Rechtsklick auf die statische Interaction-Hitbox eines platzierten 3D-DMX-Pults
     * öffnet sofort die verlinkte DMX512-Workstation (oder entfernt das Pult mit Sneak + Wand).
     */
    @EventHandler
    public void onInteractConsoleEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = event.getRightClicked();
        String rawId = clicked.getPersistentDataContainer().get(consoleIdKey, PersistentDataType.STRING);
        if (rawId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.getPlacementManager().checkClickCooldown(player)) return;

        UUID consoleId;
        try {
            consoleId = UUID.fromString(rawId);
        } catch (IllegalArgumentException ex) {
            return;
        }

        PlacedDmxConsole console = plugin.getPlacedConsole(consoleId);
        if (console == null) return;

        // Sneak + Rechtsklick mit dem Placement-Wand wechselt direkt das verlinkte Universum am Pult!
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (player.isSneaking() && plugin.getPlacementManager().isPlacementWand(handItem)) {
            if (!hasConsolePermission(player)) {
                player.sendMessage(Component.text("✘ Keine Berechtigung zum Verlinken von DMX-Pulten (admx.console).", NamedTextColor.RED));
                return;
            }
            int nextUni = (console.universeId() + 1) % DmxUniverse.MAX_UNIVERSES;
            console.setUniverseId(nextUni);
            plugin.getDatabase().saveConsoleAsync(console);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f);
            player.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                    .append(Component.text(console.modelType().displayName(), NamedTextColor.AQUA))
                    .append(Component.text(" ist jetzt mit ", NamedTextColor.GRAY))
                    .append(Component.text("DMX Universum " + (nextUni + 1) + " (Art-Net 0:0:" + nextUni + ")", NamedTextColor.GREEN))
                    .append(Component.text(" verlinkt!", NamedTextColor.GRAY)));
            return;
        }

        if (!hasConsolePermission(player)) {
            player.sendMessage(Component.text("✘ Du benötigst admx.console oder admx.control, um dieses DMX-Pult zu bedienen.", NamedTextColor.RED));
            return;
        }

        openForConsole(player, console);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.3f);
    }

    /**
     * Linksklick auf ein platziertes 3D-DMX-Pult öffnet ebenfalls die DMX-Workstation.
     */
    @EventHandler
    public void onDamageConsoleEntity(EntityDamageByEntityEvent event) {
        Entity target = event.getEntity();
        String rawId = target.getPersistentDataContainer().get(consoleIdKey, PersistentDataType.STRING);
        if (rawId == null) return;

        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player)) return;
        if (!plugin.getPlacementManager().checkClickCooldown(player)) return;

        if (!hasConsolePermission(player)) {
            player.sendMessage(Component.text("✘ Keine Berechtigung für dieses DMX-Pult (admx.console).", NamedTextColor.RED));
            return;
        }

        try {
            UUID consoleId = UUID.fromString(rawId);
            PlacedDmxConsole console = plugin.getPlacedConsole(consoleId);
            if (console != null) {
                openForConsole(player, console);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void openForConsole(Player player, PlacedDmxConsole console) {
        WorkstationSession session = new WorkstationSession(
                console.id(),
                console.universeId(),
                console.faderBank()
        );
        sessionsByPlayer.put(player.getUniqueId(), session);
        renderInventory(player, session);
    }

    public void openVirtualWorkstation(Player player, int universeId) {
        WorkstationSession existing = sessionsByPlayer.get(player.getUniqueId());
        int bank = (existing != null) ? existing.faderBank() : 0;
        WorkstationSession session = new WorkstationSession(
                null,
                Math.clamp(universeId, 0, DmxUniverse.MAX_UNIVERSES - 1),
                bank
        );
        sessionsByPlayer.put(player.getUniqueId(), session);
        renderInventory(player, session);
    }

    private void renderInventory(Player player, WorkstationSession session) {
        PlacedDmxConsole linkedConsole = session.consoleId() != null ? plugin.getPlacedConsole(session.consoleId()) : null;
        DmxUniverse universe = plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(session.universeId());

        String titlePrefix = (linkedConsole != null)
                ? linkedConsole.modelType().displayName()
                : "ADMX DMX512 Workstation";
        String title = titlePrefix + " » Uni " + (universe.universeId() + 1);

        WorkstationHolder holder = new WorkstationHolder();
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text(title, NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
        );
        holder.inventory = inv;

        // 1. Kopfzeile (Slots 0..8): Hardware-Status, Universum-Link, Merge-Mode & Master
        ConsoleModelType modelType = (linkedConsole != null) ? linkedConsole.modelType() : ConsoleModelType.GRAND_CONSOLE;
        inv.setItem(0, createItem(
                modelType.iconMaterial(),
                Component.text("✦ " + modelType.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text(modelType.subtitle(), NamedTextColor.GRAY),
                        Component.text("Status: ", NamedTextColor.GRAY)
                                .append(Component.text(linkedConsole != null ? "Physisches 3D-Pult in Welt" : "Virtuelle DMX-Workstation", NamedTextColor.AQUA)),
                        Component.text("Kanäle pro Bank: 8 (Gesamt: 512 DMX-Kanäle)", NamedTextColor.WHITE)
                )
        ));

        inv.setItem(1, createItem(
                Material.BEACON,
                Component.text("🔗 Verlinktes Universum: #" + (universe.universeId() + 1), NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Art-Net 4 Adresse: ", NamedTextColor.GRAY)
                                .append(Component.text("Net 0 | SubNet 0 | Universe " + universe.universeId(), NamedTextColor.YELLOW)),
                        Component.text("Alle Fader dieses Pults steuern in Echtzeit", NamedTextColor.GRAY),
                        Component.text("die 512 Kanäle dieses DMX-Universums.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Links-/Rechtsklick: Universum 1..16 wechseln", NamedTextColor.GREEN)
                )
        ));

        DmxMergeMode mergeMode = universe.mergeMode();
        inv.setItem(2, createItem(
                Material.COMPARATOR,
                Component.text("⚡ Signal-Merge: " + mergeMode.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text(mergeMode.description(), NamedTextColor.GRAY),
                        Component.text("Aktive Pult-Overrides: " + universe.activeConsoleOverrideCount() + " Kanäle", NamedTextColor.WHITE),
                        Component.text("Art-Net 4 Input aktiv: " + (universe.hasActiveArtNetInput() ? "JA (Live RX)" : "Nein (Timeout/Idle)"),
                                universe.hasActiveArtNetInput() ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten (HTP / LTP / Pult / Art-Net)", NamedTextColor.YELLOW)
                )
        ));

        var bridge = plugin.getArtNetBridgeManager();
        var udpNode = bridge.getUdpNode();
        boolean loopbackTx = udpNode != null && udpNode.isBroadcastToLocalhost();
        inv.setItem(3, createItem(
                universe.hasActiveArtNetInput() ? Material.LIME_DYE : Material.ENDER_EYE,
                Component.text("🌐 Art-Net 4 Core Interface", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("UDP Node (Port 6454): ", NamedTextColor.GRAY)
                                .append(Component.text(udpNode != null && udpNode.isRunning()
                                        ? (udpNode.isBoundToStandardPort() ? "Gebunden (RX + TX + ArtPollReply)" : "Koexistenz-Modus (QLC+ aktiv)")
                                        : "Inaktiv", NamedTextColor.AQUA)),
                        Component.text("WebSocket-Clients (Port " + bridge.getPort() + "): ", NamedTextColor.GRAY)
                                .append(Component.text(String.valueOf(bridge.getConnectedClientCount()), NamedTextColor.YELLOW)),
                        Component.text("Empfangene ArtDmx-Pakete (Uni " + (universe.universeId() + 1) + "): ", NamedTextColor.GRAY)
                                .append(Component.text(String.valueOf(universe.artNetPacketsReceived()), NamedTextColor.WHITE)),
                        Component.text("Lokaler UDP-Loopback TX: ", NamedTextColor.GRAY)
                                .append(Component.text(loopbackTx ? "AKTIV (127.0.0.1:6454)" : "PASSIV", loopbackTx ? NamedTextColor.GREEN : NamedTextColor.GRAY)),
                        Component.empty(),
                        Component.text("» Klicken: Lokalen UDP-Loopback TX umschalten", NamedTextColor.YELLOW)
                )
        ));

        int startChannel = session.faderBank() * 8 + 1;
        int endChannel = Math.min(512, startChannel + 7);
        int fixtureNumber = (session.faderBank() / 2) + 1;
        inv.setItem(4, createItem(
                Material.BOOKSHELF,
                Component.text("🎚 Fader-Bank " + (session.faderBank() + 1) + "/64 (Ch " + startChannel + "–" + endChannel + ")", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Adressiert Gerät #" + fixtureNumber + " (" + ((session.faderBank() % 2 == 0) ? "Kanäle 1–8: Pan/Tilt/Dim/RGB" : "Kanäle 9–16: Blue/White/Zoom/FX") + ")", NamedTextColor.AQUA),
                        Component.empty(),
                        Component.text("» Linksklick: Nächste Bank (+8 Ch)", NamedTextColor.GREEN),
                        Component.text("» Rechtsklick: Vorherige Bank (-8 Ch)", NamedTextColor.YELLOW),
                        Component.text("» Shift-Klick: Nächstes Gerät (+16 Ch)", NamedTextColor.GOLD)
                )
        ));

        inv.setItem(5, createItem(
                Material.HOPPER,
                Component.text("🎯 Gerätesprung (Fixture #" + fixtureNumber + ")", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Springt direkt zwischen den 16-Kanal-Blöcken", NamedTextColor.GRAY),
                        Component.text("der gepatchten Bühnengeräte (Gerät 1..32).", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Links-/Rechtsklick: Gerät #1..#32 durchschalten", NamedTextColor.YELLOW)
                )
        ));

        int gmPercent = Math.round((universe.grandMaster() / 255.0f) * 100.0f);
        inv.setItem(6, createItem(
                Material.GLOWSTONE_DUST,
                Component.text("☀ Grand Master: " + gmPercent + "% (DMX " + universe.grandMaster() + ")", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Skaliert alle Ausgangs-Intensitäten", NamedTextColor.GRAY),
                        Component.text("dieses DMX-Universums global.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Linksklick: +10% | Rechtsklick: -10%", NamedTextColor.YELLOW),
                        Component.text("» Shift-Klick: 100% (Full)", NamedTextColor.GREEN)
                )
        ));

        boolean dbo = universe.isBlackout();
        inv.setItem(7, createItem(
                dbo ? Material.REDSTONE_BLOCK : Material.COAL_BLOCK,
                Component.text(dbo ? "🚨 BLACKOUT (DBO) AKTIV" : "⬛ Blackout (DBO): AUS",
                        dbo ? NamedTextColor.RED : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Schaltet sofort alle 512 DMX-Kanäle", NamedTextColor.GRAY),
                        Component.text("dieses Universums auf 0 (Dead Blackout).", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten", dbo ? NamedTextColor.GREEN : NamedTextColor.RED)
                )
        ));

        inv.setItem(8, createItem(
                Material.BRUSH,
                Component.text("↺ Pult-Overrides freigeben (" + universe.activeConsoleOverrideCount() + ")", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Gibt alle manuell am Pult gezogenen Fader", NamedTextColor.GRAY),
                        Component.text("wieder für Auto-Show / Sound-to-Light / Art-Net frei.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Zurücksetzen aller Pult-Fader", NamedTextColor.YELLOW)
                )
        ));

        // 2. 8 Live-DMX512-Kanalzüge (Spalten 0..7 in den Reihen 2..5)
        for (int col = 0; col < 8; col++) {
            int dmxChannelZeroBased = session.faderBank() * 8 + col;
            int dmxChannelNumber = dmxChannelZeroBased + 1;
            int offsetInFixture = dmxChannelZeroBased % ArtNetDmxUniverseManager.CHANNELS_PER_FIXTURE;
            int targetFixtureIdx = (dmxChannelZeroBased / ArtNetDmxUniverseManager.CHANNELS_PER_FIXTURE) + 1;
            String funcLabel = CHANNEL_LABELS_16CH[offsetInFixture];

            int mergedVal = universe.getMergedChannel(dmxChannelZeroBased);
            int consoleVal = universe.getConsoleFader(dmxChannelZeroBased);
            int artNetVal = universe.getArtNetChannel(dmxChannelZeroBased);
            int percent = Math.round((mergedVal / 255.0f) * 100.0f);

            String sourceLabel;
            NamedTextColor sourceColor;
            if (consoleVal >= 0) {
                sourceLabel = "Pult-Fader (DMX " + consoleVal + ")";
                sourceColor = NamedTextColor.GOLD;
            } else if (universe.hasActiveArtNetInput() && artNetVal > 0) {
                sourceLabel = "Art-Net 4 RX (DMX " + artNetVal + ")";
                sourceColor = NamedTextColor.GREEN;
            } else {
                sourceLabel = "ADMX Generator";
                sourceColor = NamedTextColor.AQUA;
            }

            List<Component> chLore = List.of(
                    Component.text("Gerät #" + targetFixtureIdx + " » " + funcLabel, NamedTextColor.AQUA),
                    Component.text("Ausgangswert: ", NamedTextColor.GRAY)
                            .append(Component.text(mergedVal + " / 255 (" + percent + "%)", NamedTextColor.WHITE)),
                    Component.text("Aktive Quelle: ", NamedTextColor.GRAY)
                            .append(Component.text(sourceLabel, sourceColor)),
                    Component.empty(),
                    Component.text("» Linksklick: +16 | Rechtsklick: -16", NamedTextColor.YELLOW),
                    Component.text("» Shift+Links: 255 (FULL) | Shift+Rechts: Freigeben", NamedTextColor.GREEN)
            );

            // Reihe 2 (9 + col): Kanal-Header & Feinregler
            inv.setItem(9 + col, createItem(
                    iconForFixtureOffset(offsetInFixture),
                    Component.text("Ch " + dmxChannelNumber + ": " + funcLabel + " [" + mergedVal + "]", NamedTextColor.WHITE, TextDecoration.BOLD),
                    chLore
            ));

            // Reihe 3 (18 + col): Oberes Fader-Segment (192..255 -> Klick setzt 255 FULL / 200)
            boolean highActive = mergedVal >= 192;
            inv.setItem(18 + col, createItem(
                    highActive ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    Component.text("▲ Ch " + dmxChannelNumber + " FULL (255 / 100%)", highActive ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                    List.of(
                            Component.text("Aktuell: DMX " + mergedVal + " (" + percent + "%)", NamedTextColor.WHITE),
                            Component.text("» Linksklick: Setze auf 255 (100%)", NamedTextColor.GREEN),
                            Component.text("» Rechtsklick: Setze auf 192 (75%)", NamedTextColor.YELLOW)
                    )
            ));

            // Reihe 4 (27 + col): Mittleres Fader-Segment (96..191 -> Klick setzt 128 CENTER)
            boolean midActive = mergedVal >= 96 && mergedVal < 192;
            inv.setItem(27 + col, createItem(
                    midActive ? Material.YELLOW_STAINED_GLASS_PANE : (highActive ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE),
                    Component.text("◼ Ch " + dmxChannelNumber + " CENTER (128 / 50%)", midActive ? NamedTextColor.YELLOW : NamedTextColor.GRAY, TextDecoration.BOLD),
                    List.of(
                            Component.text("Ideal für Pan/Tilt Neutralstellung (0° = 128)", NamedTextColor.AQUA),
                            Component.text("» Linksklick: Setze auf 128 (50%)", NamedTextColor.YELLOW),
                            Component.text("» Rechtsklick: Setze auf 64 (25%)", NamedTextColor.GOLD)
                    )
            ));

            // Reihe 5 (36 + col): Unteres Fader-Segment (0..95 -> Klick setzt 0 ZERO / Rechtsklick Release)
            boolean lowActive = mergedVal > 0;
            inv.setItem(36 + col, createItem(
                    consoleVal >= 0 ? Material.ORANGE_STAINED_GLASS_PANE : (lowActive ? Material.CYAN_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE),
                    Component.text("▼ Ch " + dmxChannelNumber + " ZERO (0) / Release", consoleVal >= 0 ? NamedTextColor.GOLD : NamedTextColor.GRAY, TextDecoration.BOLD),
                    List.of(
                            Component.text("» Linksklick: Setze Kanal auf 0 (ZERO)", NamedTextColor.RED),
                            Component.text("» Rechtsklick: Pult-Override für Ch " + dmxChannelNumber + " freigeben", NamedTextColor.AQUA)
                    )
            ));
        }

        // 3. Fußzeile (Slots 45..53): Bank-Navigation, Patchbay-Übersicht & Szenen-Makros
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, filler);
        }

        inv.setItem(45, createItem(
                Material.ARROW,
                Component.text("« Vorherige Fader-Bank (-8 Kanäle)", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(Component.text("Wechselt 8 DMX-Kanäle zurück.", NamedTextColor.GRAY))
        ));

        inv.setItem(46, createItem(
                Material.ARROW,
                Component.text("Nächste Fader-Bank (+8 Kanäle) »", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("Wechselt 8 DMX-Kanäle weiter.", NamedTextColor.GRAY))
        ));

        inv.setItem(47, createItem(
                Material.JUKEBOX,
                Component.text("🧠 Auto-Show Studio (Song-DNA Learner & Quick-Recall)", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Öffnet das Auto-Show Studio zum Lernen eigener", NamedTextColor.GRAY),
                        Component.text("Song-Muster, Speichern unter ID & 1-Klick-Abruf", NamedTextColor.GRAY),
                        Component.text("mit 100% synchronem Beat-PLL Idiotenschutz!", NamedTextColor.WHITE),
                        Component.empty(),
                        Component.text("» Klicken zum Öffnen des Auto-Show Studios", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(48, createItem(
                Material.SUNFLOWER,
                Component.text("✦ Direkt-Look: Full Warm-White (Gerät #" + fixtureNumber + ")", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Setzt für Gerät #" + fixtureNumber + " sofort:", NamedTextColor.GRAY),
                        Component.text("Dimmer=255, Pan/Tilt=128 (0°), RGBW=Warmweiß.", NamedTextColor.WHITE),
                        Component.text("» Klicken zum Aktivieren", NamedTextColor.GREEN)
                )
        ));

        List<Component> patchLore = buildPatchbayLore(universe.universeId());
        inv.setItem(49, createItem(
                Material.NETHER_STAR,
                Component.text("📋 DMX512 Patchbay (Universum " + (universe.universeId() + 1) + ")", NamedTextColor.AQUA, TextDecoration.BOLD),
                patchLore
        ));

        inv.setItem(50, createItem(
                Material.AMETHYST_SHARD,
                Component.text("✦ Direkt-Look: Club Cyan-Beam (Gerät #" + fixtureNumber + ")", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Setzt für Gerät #" + fixtureNumber + " sofort:", NamedTextColor.GRAY),
                        Component.text("Dimmer=255, Cyan/Aqua, Zoom=Eng (Beam).", NamedTextColor.WHITE),
                        Component.text("» Klicken zum Aktivieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(51, createItem(
                Material.PAINTING,
                Component.text("🖥 Boiler Desktop-UI Monitor spawnen (4x2)", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Erstellt vor dir einen interaktiven Boiler-Bildschirm", NamedTextColor.GRAY),
                        Component.text("mit echtem Maus-Dragging für Fader & 2D Pan/Tilt-Pad!", NamedTextColor.WHITE),
                        Component.text("Alternativ: /boiler display source <id> admx-dmx", NamedTextColor.DARK_GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Aufstellen vor dir", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(52, createItem(
                Material.CHEST,
                Component.text("🎛 Gruppen-Steuerpult öffnen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Wechselt zum ADMX Gruppen- & Szenen-Pult.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(53, createItem(
                Material.BARRIER,
                Component.text("✖ Workstation schließen", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Schließt das DMX512-Steuerpult.", NamedTextColor.GRAY))
        ));

        player.openInventory(inv);
    }

    private List<Component> buildPatchbayLore(int universeId) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("16 Kanäle pro Gerät (max. 32 Geräte / Universum):", NamedTextColor.GRAY));
        List<AdmxPlugin.PlacedFixture> all = new ArrayList<>(plugin.getPlacedFixtures().values());
        int startIdx = universeId * ArtNetDmxUniverseManager.FIXTURES_PER_UNIVERSE;
        int endIdx = Math.min(all.size(), startIdx + ArtNetDmxUniverseManager.FIXTURES_PER_UNIVERSE);

        if (startIdx >= all.size()) {
            lines.add(Component.text("• Noch keine Geräte in diesem Universum gepatcht.", NamedTextColor.YELLOW));
        } else {
            int shown = 0;
            for (int i = startIdx; i < endIdx && shown < 8; i++, shown++) {
                AdmxPlugin.PlacedFixture f = all.get(i);
                int localNum = (i - startIdx) + 1;
                int dmxStart = (localNum - 1) * 16 + 1;
                int dmxEnd = dmxStart + 15;
                lines.add(Component.text(String.format(Locale.ROOT, " #%02d [Ch %03d–%03d]: ", localNum, dmxStart, dmxEnd), NamedTextColor.GOLD)
                        .append(Component.text(f.type().displayName() + " (" + f.groupId() + ")", NamedTextColor.WHITE)));
            }
            if (endIdx - startIdx > 8) {
                lines.add(Component.text(" ... und " + ((endIdx - startIdx) - 8) + " weitere Geräte", NamedTextColor.GRAY));
            }
        }
        return lines;
    }

    private static Material iconForFixtureOffset(int offset) {
        return switch (offset) {
            case 0, 1 -> Material.COMPASS;
            case 2, 3 -> Material.CLOCK;
            case 4 -> Material.GLOWSTONE_DUST;
            case 5 -> Material.LIGHTNING_ROD;
            case 6 -> Material.RED_DYE;
            case 7 -> Material.LIME_DYE;
            case 8 -> Material.BLUE_DYE;
            case 9 -> Material.WHITE_DYE;
            case 10 -> Material.SPYGLASS;
            default -> Material.REPEATER;
        };
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WorkstationHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WorkstationHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!hasConsolePermission(player)) {
            player.closeInventory();
            return;
        }
        if (!plugin.getPlacementManager().checkClickCooldown(player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        WorkstationSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            session = new WorkstationSession(null, 0, 0);
        }

        PlacedDmxConsole linkedConsole = session.consoleId() != null ? plugin.getPlacedConsole(session.consoleId()) : null;
        DmxUniverse universe = plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(session.universeId());

        // Kopfzeile
        if (slot == 1) {
            int delta = event.isRightClick() ? -1 : 1;
            int nextUni = (session.universeId() + delta + DmxUniverse.MAX_UNIVERSES) % DmxUniverse.MAX_UNIVERSES;
            WorkstationSession updated = session.withUniverse(nextUni);
            sessionsByPlayer.put(player.getUniqueId(), updated);
            if (linkedConsole != null) {
                linkedConsole.setUniverseId(nextUni);
                plugin.getDatabase().saveConsoleAsync(linkedConsole);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
            renderInventory(player, updated);
            return;
        }

        if (slot == 2) {
            universe.setMergeMode(universe.mergeMode().next());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
            renderInventory(player, session);
            return;
        }

        if (slot == 3) {
            var udpNode = plugin.getArtNetBridgeManager().getUdpNode();
            if (udpNode != null) {
                udpNode.setBroadcastToLocalhost(!udpNode.isBroadcastToLocalhost());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
            renderInventory(player, session);
            return;
        }

        if (slot == 4) {
            int step = event.isShiftClick() ? 2 : (event.isRightClick() ? -1 : 1);
            int nextBank = (session.faderBank() + step + 64) % 64;
            updateBank(player, session, linkedConsole, nextBank);
            return;
        }

        if (slot == 5) {
            int currentFixture = session.faderBank() / 2;
            int nextFixture = (currentFixture + (event.isRightClick() ? -1 : 1) + 32) % 32;
            updateBank(player, session, linkedConsole, nextFixture * 2);
            return;
        }

        if (slot == 6) {
            if (event.isShiftClick()) {
                universe.setGrandMaster(255);
            } else {
                int step = event.isRightClick() ? -25 : 25;
                universe.setGrandMaster(universe.grandMaster() + step);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
            renderInventory(player, session);
            return;
        }

        if (slot == 7) {
            universe.setBlackout(!universe.isBlackout());
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, universe.isBlackout() ? 0.6f : 1.4f);
            renderInventory(player, session);
            return;
        }

        if (slot == 8) {
            universe.clearAllConsoleOverrides();
            player.playSound(player.getLocation(), Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.9f, 1.3f);
            renderInventory(player, session);
            return;
        }

        // 8 Fader-Spalten (Reihen 2..5: Slots 9..44)
        if (slot >= 9 && slot <= 44) {
            int row = slot / 9; // 1, 2, 3, 4
            int col = slot % 9;
            if (col < 8) {
                int chZero = session.faderBank() * 8 + col;
                int current = universe.getMergedChannel(chZero);

                if (row == 1) {
                    if (event.isShiftClick()) {
                        if (event.isRightClick()) {
                            universe.releaseConsoleFader(chZero);
                        } else {
                            universe.setConsoleFader(chZero, 255);
                        }
                    } else {
                        int delta = event.isRightClick() ? -16 : 16;
                        universe.setConsoleFader(chZero, Math.clamp(current + delta, 0, 255));
                    }
                } else if (row == 2) {
                    universe.setConsoleFader(chZero, event.isRightClick() ? 192 : 255);
                } else if (row == 3) {
                    universe.setConsoleFader(chZero, event.isRightClick() ? 64 : 128);
                } else if (row == 4) {
                    if (event.isRightClick()) {
                        universe.releaseConsoleFader(chZero);
                    } else {
                        universe.setConsoleFader(chZero, 0);
                    }
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.4f);
                renderInventory(player, session);
            }
            return;
        }

        // Fußzeile
        if (slot == 45) {
            updateBank(player, session, linkedConsole, (session.faderBank() - 1 + 64) % 64);
            return;
        }
        if (slot == 46) {
            updateBank(player, session, linkedConsole, (session.faderBank() + 1) % 64);
            return;
        }
        if (slot == 47) {
            player.closeInventory();
            plugin.getAutoShowStudioChestUi().openStudio(player);
            return;
        }
        if (slot == 48) {
            int baseCh = (session.faderBank() / 2) * 16;
            universe.setConsoleFader(baseCh, 128);      // Pan 0°
            universe.setConsoleFader(baseCh + 2, 118);  // Tilt leicht geneigt
            universe.setConsoleFader(baseCh + 4, 255);  // Dimmer 100%
            universe.setConsoleFader(baseCh + 5, 0);    // Kein Strobe
            universe.setConsoleFader(baseCh + 6, 255);  // R
            universe.setConsoleFader(baseCh + 7, 220);  // G
            universe.setConsoleFader(baseCh + 8, 170);  // B
            universe.setConsoleFader(baseCh + 9, 170);  // W
            universe.setConsoleFader(baseCh + 10, 110); // Zoom
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.4f);
            renderInventory(player, session);
            return;
        }
        if (slot == 50) {
            int baseCh = (session.faderBank() / 2) * 16;
            universe.setConsoleFader(baseCh, 128);      // Pan 0°
            universe.setConsoleFader(baseCh + 2, 110);  // Tilt
            universe.setConsoleFader(baseCh + 4, 255);  // Dimmer 100%
            universe.setConsoleFader(baseCh + 5, 0);    // Kein Strobe
            universe.setConsoleFader(baseCh + 6, 0);    // R
            universe.setConsoleFader(baseCh + 7, 230);  // G
            universe.setConsoleFader(baseCh + 8, 255);  // B
            universe.setConsoleFader(baseCh + 9, 0);    // W
            universe.setConsoleFader(baseCh + 10, 28);  // Enger Beam-Zoom
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
            renderInventory(player, session);
            return;
        }
        if (slot == 51) {
            player.closeInventory();
            if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Boiler")) {
                player.sendMessage(Component.text("✘ Das optionale Boiler-Plugin ist auf diesem Server aktuell nicht aktiv.", NamedTextColor.RED));
                return;
            }
            int id = de.archer.admx.boiler.AdmxBoilerIntegration.spawnDesktopDisplayInFrontOfPlayer(player, 4, 2, session.universeId());
            if (id >= 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
                player.sendMessage(Component.text("🖥 Boiler Desktop-UI (Display #" + id + ") vor dir aufgestellt! Ziele auf den Screen zum Ziehen der Fader & des 2D X/Y-Pads.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("✘ Boiler ist nicht aktiv oder Display konnte nicht erstellt werden.", NamedTextColor.RED));
            }
            return;
        }
        if (slot == 52) {
            player.closeInventory();
            plugin.getChestConsoleUi().openConsole(player, plugin.getPlacementManager().getSelectedGroupId(player));
            return;
        }
        if (slot == 53) {
            player.closeInventory();
        }
    }

    private void updateBank(Player player, WorkstationSession session, PlacedDmxConsole linkedConsole, int nextBank) {
        WorkstationSession updated = session.withBank(nextBank);
        sessionsByPlayer.put(player.getUniqueId(), updated);
        if (linkedConsole != null) {
            linkedConsole.setFaderBank(nextBank);
            plugin.getDatabase().saveConsoleAsync(linkedConsole);
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.3f);
        renderInventory(player, updated);
    }

    private static ItemStack createItem(Material material, Component title, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
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
