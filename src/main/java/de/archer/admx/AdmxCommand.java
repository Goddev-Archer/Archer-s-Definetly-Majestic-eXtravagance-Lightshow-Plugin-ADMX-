package de.archer.admx;

import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.control.UiPreference;
import de.archer.admx.dmx.ConsoleModelType;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.dmx.PlacedDmxConsole;
import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.fixture.FixtureType;
import de.archer.admx.render.fixture.MovingHeadProfile;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Moderner Paper 26.3 BasicCommand mit Brigadier Tab-Vervollständigung für
 * Geräteplatzierung, Gruppenverwaltung, UI-Auswahl (Chest-UI vs. 3D-Holo-UI),
 * Photosensitivitäts-Schutz ({@code /admx safe}) und Szenen-Programmierung.
 */
public final class AdmxCommand implements BasicCommand {

    private static final Pattern VALID_GROUP_ID = Pattern.compile("^[a-z0-9_]{1,24}$");
    private static final long CLEAR_CONFIRM_TIMEOUT_MS = 15_000L;

    private final AdmxPlugin plugin;
    private final Map<UUID, Long> pendingClearConfirmations = new ConcurrentHashMap<>();

    public AdmxCommand(AdmxPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Dieser Befehl kann nur von Spielern ausgeführt werden.", NamedTextColor.RED));
            return;
        }

        // /admx safe ist für jeden Spieler mit admx.safe (Default: true) zugänglich (SEC-09)
        if (args.length > 0 && args[0].equalsIgnoreCase("safe")) {
            if (!player.hasPermission("admx.safe") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für /admx safe.", NamedTextColor.RED));
                return;
            }
            boolean enabled = EntityTracker.toggleSafeMode(plugin, player);
            if (enabled) {
                plugin.getHoloConsoleUi().removeConsole(player.getUniqueId());
                player.sendMessage(Component.text("🛡 Photosensitivitäts-Schutzmodus (Safe-Mode) AKTIVIERT: Alle ADMX-Lichtkegel, Stroboskope und Nebel-Effekte sind für deinen Client ausgeblendet.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("✦ Photosensitivitäts-Schutzmodus DEAKTIVIERT: ADMX-Lichteffekte sind wieder sichtbar.", NamedTextColor.YELLOW));
            }
            return;
        }

        // Alle administrativen Unterbefehle prüfen
        if (!player.hasPermission("admx.admin")
                && !player.hasPermission("admx.place")
                && !player.hasPermission("admx.control")
                && !player.hasPermission("admx.console")) {
            player.sendMessage(Component.text("✘ Du benötigst admx.admin (oder nutze /admx safe für den Photosensitivitäts-Schutzmodus).", NamedTextColor.RED));
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage(Component.text("--- ADMX Stage Lighting & Console Befehle ---", NamedTextColor.GOLD));
            player.sendMessage(Component.text("/admx wand - Gibt dir den ADMX Fixture Placer & Console Wand", NamedTextColor.AQUA));
            player.sendMessage(Component.text("/admx co2 - Gibt dir die Handheld Cryo-Bazooka (50-Bar CO2-Cannon)", NamedTextColor.AQUA));
            player.sendMessage(Component.text("/admx desktop [spawn|set] [uni|displayId] - Spawnt das Boiler Desktop-UI (Maus/Drag/Scroll)", NamedTextColor.GREEN));
            player.sendMessage(Component.text("/admx dmx [1-16] - Öffnet die 512-Kanal Pro DMX Workstation (Chest-UI)", NamedTextColor.GOLD));
            player.sendMessage(Component.text("/admx s2l - Öffnet das Hardware-Style Sound-to-Light Pult (Spektrogramm & Filter)", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("/admx artnet [status|start|stop|token|loopback] - Art-Net 4 UDP & WebSocket-Bridge", NamedTextColor.GOLD));
            player.sendMessage(Component.text("/admx ui [chest|holo|open|close] - Wählt/öffnet das Chest-UI oder 3D-Hologramm-UI", NamedTextColor.LIGHT_PURPLE));
            player.sendMessage(Component.text("/admx flow [boardId] - Öffnet das visuelle Flowboard-Programmier-Pult", NamedTextColor.LIGHT_PURPLE));
            player.sendMessage(Component.text("/admx auto [studio|learn|save|load|list|on|off|s2l|source|genre|bpm] - Auto-Show Studio & Song-DNA", NamedTextColor.AQUA));
            player.sendMessage(Component.text("/admx group [list|select|create|delete] <id> [Name] - Verwaltet SQLite-Gerätegruppen", NamedTextColor.GREEN));
            player.sendMessage(Component.text("/admx scene [play|stop|rec|load] [1-4] - Szenen-Programmierung & Sequencer", NamedTextColor.GOLD));
            player.sendMessage(Component.text("/admx spawn [spot|beam|wash|...|grand_console|ddc6|dmx_master|wing|dm7]", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("/admx safe - Schaltet den persönlichen Photosensitivitäts-Schutzmodus um", NamedTextColor.AQUA));
            player.sendMessage(Component.text("/admx purge - Bereinigt verwaiste Display-Entities", NamedTextColor.RED));
            player.sendMessage(Component.text("/admx clear confirm - Entfernt alle platzierten ADMX-Geräte (mit Bestätigung)", NamedTextColor.YELLOW));
            return;
        }

        if (args[0].equalsIgnoreCase("desktop") || args[0].equalsIgnoreCase("boiler")) {
            if (!player.hasPermission("admx.console") && !player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für das Boiler Desktop-UI (admx.console).", NamedTextColor.RED));
                return;
            }
            if (!de.archer.admx.boiler.AdmxBoilerIntegration.isBoilerAvailable()) {
                player.sendMessage(Component.text("✘ Das Boiler-Plugin ist auf diesem Server aktuell nicht aktiv.", NamedTextColor.RED));
                return;
            }
            String sub = args.length > 1 ? args[1].toLowerCase() : "spawn";
            if (sub.equals("set") || sub.equals("display")) {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Verwendung: /admx desktop set <boilerDisplayId> [universum 1-16]", NamedTextColor.YELLOW));
                    return;
                }
                try {
                    int dispId = Integer.parseInt(args[2]);
                    int uni = (args.length > 3) ? Math.clamp(Integer.parseInt(args[3]) - 1, 0, 15) : 0;
                    if (de.archer.admx.boiler.AdmxBoilerIntegration.activateOnDisplay(dispId, uni)) {
                        player.sendMessage(Component.text("✔ Boiler-Display #" + dispId + " zeigt jetzt das interaktive ADMX Pro Stage OS (Universum " + (uni + 1) + ")!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("✘ Boiler-Display #" + dispId + " wurde nicht gefunden.", NamedTextColor.RED));
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.text("✘ Ungültige Display-ID.", NamedTextColor.RED));
                }
                return;
            }

            int uni = 0;
            if (args.length > 2) {
                try {
                    uni = Math.clamp(Integer.parseInt(args[2]) - 1, 0, 15);
                } catch (NumberFormatException ignored) {
                }
            }
            int createdId = de.archer.admx.boiler.AdmxBoilerIntegration.spawnDesktopDisplayInFrontOfPlayer(player, 4, 2, uni);
            if (createdId >= 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
                player.sendMessage(Component.text("🖥 ADMX Pro Stage OS Desktop-Display (Boiler #" + createdId + ", 4x2 Widescreen) vor dir aufgestellt!", NamedTextColor.GREEN));
                player.sendMessage(Component.text(" » Ziele auf den Bildschirm, um Fader & das 2D Pan/Tilt-Pad per Maus zu ziehen oder mit dem Mausrad zu drehen!", NamedTextColor.AQUA));
            } else {
                player.sendMessage(Component.text("✘ Konnte kein Boiler-Display erstellen (Alternativ: /boiler display source <id> admx-dmx).", NamedTextColor.RED));
            }
            return;
        }

        if (args[0].equalsIgnoreCase("dmx") || args[0].equalsIgnoreCase("workstation")) {
            if (!player.hasPermission("admx.console") && !player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für die DMX512-Workstation (admx.console).", NamedTextColor.RED));
                return;
            }
            int uni = 0;
            if (args.length > 1) {
                try {
                    uni = Math.clamp(Integer.parseInt(args[1]) - 1, 0, DmxUniverse.MAX_UNIVERSES - 1);
                } catch (NumberFormatException ignored) {
                }
            }
            plugin.getDmxWorkstationUi().openVirtualWorkstation(player, uni);
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.3f);
            return;
        }

        if (args[0].equalsIgnoreCase("wand") || args[0].equalsIgnoreCase("placer") || args[0].equalsIgnoreCase("item") || args[0].equalsIgnoreCase("tool")) {
            ItemStack wand = plugin.getPlacementManager().createPlacementWand();
            player.getInventory().addItem(wand);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
            player.sendMessage(Component.text("✦ Du hast den ", NamedTextColor.GREEN)
                    .append(Component.text("ADMX Fixture Placer & Console Wand", NamedTextColor.GOLD))
                    .append(Component.text(" erhalten! Rechtsklick = Geräte/Gruppen-Menü, Linksklick = Steuerpult (Chest/3D-Holo)!", NamedTextColor.GREEN)));
            return;
        }

        if (args[0].equalsIgnoreCase("co2") || args[0].equalsIgnoreCase("bazooka") || args[0].equalsIgnoreCase("cryo")) {
            if (!player.hasPermission("admx.fx.co2") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für die Cryo-Bazooka (admx.fx.co2 / admx.admin erforderlich).", NamedTextColor.RED));
                return;
            }
            ItemStack bazooka = plugin.getCryoBazookaManager().createCryoBazooka();
            player.getInventory().addItem(bazooka);
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 1.2f);
            player.sendMessage(Component.text("💨 Du hast die ", NamedTextColor.GREEN)
                    .append(Component.text("ADMX Handheld Cryo-Bazooka", NamedTextColor.AQUA))
                    .append(Component.text(" erhalten! (Rechtsklick: 50-Bar Blast | Sneak+Rechtsklick: Nozzle-Farbe)", NamedTextColor.GRAY)));
            return;
        }

        if (args[0].equalsIgnoreCase("flow") || args[0].equalsIgnoreCase("flowboard")) {
            if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für das Flowboard (admx.control erforderlich).", NamedTextColor.RED));
                return;
            }
            String boardId = (args.length > 1 && !args[1].isBlank()) ? args[1].toLowerCase() : "main_show";
            var board = plugin.getOrCreateFlowboard(boardId, "Show " + boardId);
            plugin.getFlowboardGui().openFlowboard(player, board);
            return;
        }

        if (args[0].equalsIgnoreCase("artnet") || args[0].equalsIgnoreCase("bridge")) {
            if (!player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Nur Administratoren (admx.admin) können die Art-Net Bridge verwalten.", NamedTextColor.RED));
                return;
            }

            var bridge = plugin.getArtNetBridgeManager();
            String sub = (args.length > 1) ? args[1].toLowerCase() : "status";

            switch (sub) {
                case "start" -> {
                    bridge.start();
                    player.sendMessage(Component.text("✔ Art-Net WebSocket-Bridge gestartet (" + bridge.getBindHost() + ":" + bridge.getPort() + ").", NamedTextColor.GREEN));
                }
                case "stop" -> {
                    bridge.stop();
                    player.sendMessage(Component.text("✔ Art-Net WebSocket-Bridge gestoppt.", NamedTextColor.YELLOW));
                }
                case "token" -> {
                    if (args.length > 2 && args[2].equalsIgnoreCase("regenerate")) {
                        bridge.regenerateToken();
                        player.sendMessage(Component.text("✔ Neuer Art-Net Authentifizierungs-Token generiert und gespeichert!", NamedTextColor.GREEN));
                    }
                    player.sendMessage(Component.text("--- ADMX Art-Net Authentifizierungs-Daten ---", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("Geheimer Token: ", NamedTextColor.GRAY)
                            .append(Component.text(bridge.getAuthToken(), NamedTextColor.YELLOW)));
                    player.sendMessage(Component.text("WebSocket URL: ", NamedTextColor.GRAY)
                            .append(Component.text("ws://" + (bridge.getBindHost().equals("0.0.0.0") ? "<deine-server-ip>" : bridge.getBindHost())
                                    + ":" + bridge.getPort() + "/artnet?token=" + bridge.getAuthToken(), NamedTextColor.AQUA)));
                    player.sendMessage(Component.text("Tipp: Nutze '/admx artnet token regenerate' um den Token sofort zu erneuern.", NamedTextColor.DARK_GRAY));
                }
                case "loopback" -> {
                    var udp = bridge.getUdpNode();
                    if (udp != null) {
                        udp.setBroadcastToLocalhost(!udp.isBroadcastToLocalhost());
                        player.sendMessage(Component.text("✔ Lokaler Art-Net UDP-Loopback TX ist jetzt: "
                                + (udp.isBroadcastToLocalhost() ? "AKTIV (127.0.0.1:6454)" : "PASSIV"), NamedTextColor.GREEN));
                    }
                }
                default -> {
                    var udp = bridge.getUdpNode();
                    DmxUniverse u0 = bridge.getUniverseManager().getUniverse(0);
                    player.sendMessage(Component.text("--- ADMX Art-Net 4 Core & WebSocket-Bridge Status ---", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("WebSocket-Bridge: ", NamedTextColor.GRAY)
                            .append(Component.text(bridge.isRunning() ? "ONLINE (" + bridge.getBindHost() + ":" + bridge.getPort() + ")" : "OFFLINE",
                                    bridge.isRunning() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                    player.sendMessage(Component.text("Nativer Art-Net 4 UDP Node (Port 6454): ", NamedTextColor.GRAY)
                            .append(Component.text(udp != null && udp.isRunning()
                                    ? (udp.isBoundToStandardPort() ? "AKTIV (ArtPollReply + OpDmx RX/TX)" : "KOEXISTENZ-MODUS (QLC+ gebunden)")
                                    : "INAKTIV", NamedTextColor.AQUA)));
                    player.sendMessage(Component.text("Verbundene WebSocket-Clients: " + bridge.getConnectedClientCount(), NamedTextColor.AQUA));
                    player.sendMessage(Component.text("Universum 1 (Art-Net 0:0:0): Merge=" + u0.mergeMode().displayName()
                            + " | RX-Pakete=" + u0.artNetPacketsReceived(), NamedTextColor.WHITE));
                    player.sendMessage(Component.text("Befehle: /admx artnet [status|start|stop|token|loopback] | /admx dmx", NamedTextColor.GRAY));
                }
            }
            return;
        }

        if (args[0].equalsIgnoreCase("s2l") || args[0].equalsIgnoreCase("soundtolight")) {
            if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für Sound-to-Light (admx.control erforderlich).", NamedTextColor.RED));
                return;
            }
            plugin.getSoundToLightChestUi().openUi(player, plugin.getPlacementManager().getSelectedGroupId(player));
            return;
        }

        if (args[0].equalsIgnoreCase("auto") || args[0].equalsIgnoreCase("autoshow")) {
            if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin") && !player.hasPermission("admx.console")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung für Smart Auto-Show (admx.control erforderlich).", NamedTextColor.RED));
                return;
            }
            var dsp = plugin.getAudioDspManager();
            var director = plugin.getSmartAutoDirector();
            String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "studio";

            switch (sub) {
                case "studio", "ui", "gui" -> {
                    plugin.getAutoShowStudioChestUi().openStudio(player);
                }
                case "learn" -> {
                    int slotIdx = director.selectedQuickSlot();
                    String initName = null;
                    if (args.length > 2) {
                        try {
                            slotIdx = Math.clamp(Integer.parseInt(args[2]) - 1, 0, de.archer.admx.audio.AutoShowProfile.MAX_QUICK_SLOTS - 1);
                            if (args.length > 3) {
                                initName = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                            }
                        } catch (NumberFormatException ex) {
                            initName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                        }
                    }
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        g.setControlMode(ControlMode.AUTO_SHOW);
                    }
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    director.startLearning(slotIdx, initName != null ? initName : ("Learned Show #" + (slotIdx + 1)));
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.85f, 1.5f);
                    player.sendMessage(Component.text("🧠 Multi-Pass Lern-Algorithmus gestartet (Ziel: Slot #" + (slotIdx + 1) + ")! ", NamedTextColor.GREEN)
                            .append(Component.text("Nutze '/admx auto save <Name>', sobald du mit der gelernten Show zufrieden bist.", NamedTextColor.GRAY)));
                }
                case "save", "freeze" -> {
                    int slotIdx = director.selectedQuickSlot();
                    String customName = (args.length > 2)
                            ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                            : null;
                    var saved = director.stopLearningAndFreeze(customName, slotIdx);
                    plugin.saveAutoShowProfile(saved);
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        g.setControlMode(ControlMode.AUTO_SHOW);
                    }
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.75f, 1.3f);
                    player.sendMessage(Component.text("💾 Lern-Algorithmus abgeschaltet & Auto-Show gespeichert unter ID ", NamedTextColor.GREEN)
                            .append(Component.text(saved.profileId() + " (" + saved.displayName() + ")", NamedTextColor.GOLD))
                            .append(Component.text("! [3-Stufen Beat-PLL Idiotenschutz aktiv]", NamedTextColor.AQUA)));
                }
                case "load", "recall" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Verwendung: /admx auto load <show_01..show_08 | 1..8>", NamedTextColor.YELLOW));
                        return;
                    }
                    String target = args[2].toLowerCase(Locale.ROOT);
                    if (target.matches("^[1-8]$")) {
                        target = "show_0" + target;
                    }
                    var profile = plugin.getAutoShowProfile(target);
                    if (profile == null) {
                        player.sendMessage(Component.text("✘ Auto-Show '" + target + "' wurde nicht gefunden. Nutze /admx auto list.", NamedTextColor.RED));
                        return;
                    }
                    director.recallProfile(profile);
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        g.setControlMode(ControlMode.AUTO_SHOW);
                    }
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.85f, 1.5f);
                    player.sendMessage(Component.text("⚡ Auto-Show ", NamedTextColor.AQUA)
                            .append(Component.text(profile.displayName() + " [" + profile.profileId() + "]", NamedTextColor.GOLD))
                            .append(Component.text(" geladen! (100% Beat-PLL synchronisiert)", NamedTextColor.GREEN)));
                }
                case "list" -> {
                    player.sendMessage(Component.text("--- Gespeicherte ADMX Auto-Show Profile (Song-DNA) ---", NamedTextColor.GOLD));
                    for (var p : plugin.getAutoShowProfiles().values()) {
                        player.sendMessage(Component.text(" • [" + p.profileId() + "] ", NamedTextColor.YELLOW)
                                .append(Component.text(p.displayName(), NamedTextColor.AQUA))
                                .append(Component.text(String.format(Locale.ROOT, " (Slot #%d | %.1f BPM | Stil: %s | Reife: %d%%)",
                                        p.slotIndex() + 1, p.referenceBpm(), p.choreoStyle().displayName(), Math.round(p.confidence() * 100f)), NamedTextColor.GRAY)));
                    }
                }
                case "s2l", "sound", "soundtolight" -> {
                    plugin.getSoundToLightChestUi().openUi(player, plugin.getPlacementManager().getSelectedGroupId(player));
                }
                case "on", "start" -> {
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        g.setControlMode(ControlMode.AUTO_SHOW);
                    }
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    player.sendMessage(Component.text("✔ Smart Auto-Show für alle Gruppen aktiviert!", NamedTextColor.GREEN));
                }
                case "off", "stop" -> {
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        g.setControlMode(ControlMode.MANUAL_LIVE);
                    }
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    player.sendMessage(Component.text("✔ Auto-Show beendet (Gruppen im Live-Modus).", NamedTextColor.YELLOW));
                }
                case "source" -> {
                    if (args.length > 2) {
                        String s = args[2].toLowerCase();
                        if (s.equals("boiler") || s.equals("video") || s.equals("youtube") || s.equals("media")) {
                            dsp.setActiveSource(de.archer.admx.audio.AudioDspManager.AudioSourceType.BOILER_MEDIA);
                            player.sendMessage(Component.text("✔ Audio-Quelle auf In-Game Boiler Display / YouTube gesetzt.", NamedTextColor.GREEN));
                        } else if (s.equals("demo") || s.equals("synth")) {
                            dsp.setActiveSource(de.archer.admx.audio.AudioDspManager.AudioSourceType.SYNTHETIC_DEMO);
                            player.sendMessage(Component.text("✔ Audio-Quelle auf Synthetischen Beat-Generator gesetzt.", NamedTextColor.GREEN));
                        } else {
                            dsp.setActiveSource(de.archer.admx.audio.AudioDspManager.AudioSourceType.LOOPBACK_STREAM);
                            player.sendMessage(Component.text("✔ Audio-Quelle auf Loopback-Stream (127.0.0.1:54321) gesetzt.", NamedTextColor.GREEN));
                        }
                    } else {
                        player.sendMessage(Component.text("Aktuelle Audio-Quelle: " + dsp.getActiveSource().sourceName(), NamedTextColor.AQUA));
                        player.sendMessage(Component.text("Beschreibung: " + dsp.getActiveSource().desc(), NamedTextColor.GRAY));
                        player.sendMessage(Component.text("Umschalten: /admx auto source <boiler|stream|demo>", NamedTextColor.YELLOW));
                    }
                }
                case "genre" -> {
                    if (args.length > 2) {
                        try {
                            var g = de.archer.admx.audio.MusicGenre.valueOf(args[2].toUpperCase());
                            dsp.setActiveSource(de.archer.admx.audio.AudioDspManager.AudioSourceType.SYNTHETIC_DEMO);
                            dsp.setSyntheticParameters(g.minBpm() + (g.maxBpm() - g.minBpm()) * 0.5, g);
                            player.sendMessage(Component.text("✔ Demo-Genre auf " + g.displayName() + " gesetzt.", NamedTextColor.GREEN));
                        } catch (IllegalArgumentException e) {
                            player.sendMessage(Component.text("✘ Unbekanntes Genre. Verfügbar: techno, rap_hiphop, drum_and_bass, house_club, ambient", NamedTextColor.RED));
                        }
                    } else {
                        player.sendMessage(Component.text("Aktuelles Genre: " + dsp.getSnapshot().genre().displayName(), NamedTextColor.AQUA));
                    }
                }
                case "bpm" -> {
                    if (args.length > 2) {
                        try {
                            double val = Double.parseDouble(args[2]);
                            dsp.getBeatDetector().setManualBpm(val);
                            player.sendMessage(Component.text("✔ Manuelles Tempo auf " + val + " BPM gesetzt.", NamedTextColor.GREEN));
                        } catch (NumberFormatException e) {
                            player.sendMessage(Component.text("✘ Ungültiger BPM-Wert.", NamedTextColor.RED));
                        }
                    } else {
                        player.sendMessage(Component.text(String.format(Locale.ROOT, "Aktuelles Tempo: %.1f BPM", dsp.getSnapshot().bpm()), NamedTextColor.AQUA));
                    }
                }
                default -> {
                    var s = dsp.getSnapshot();
                    player.sendMessage(Component.text("--- ADMX Smart Auto-Show Status ---", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("Quelle: " + dsp.getActiveSource().sourceName(), NamedTextColor.YELLOW));
                    player.sendMessage(Component.text(String.format(Locale.ROOT, "Tempo: %.1f BPM (Konfidenz: %.0f%%)", s.bpm(), s.confidence() * 100f), NamedTextColor.AQUA));
                    player.sendMessage(Component.text("Genre: " + s.genre().displayName(), s.genre().color()));
                    player.sendMessage(Component.text("Stil: " + s.genre().description(), NamedTextColor.GRAY));
                    player.sendMessage(Component.text("Takt: Bar " + s.barCount() + ", Beat " + (s.beatInBar() + 1) + "/4 (Phase: " + Math.round(s.phase() * 100) + "%)", NamedTextColor.WHITE));
                    if (s.strobeActive()) {
                        player.sendMessage(Component.text(String.format(Locale.ROOT, "Stroboskop: AKTIV (%.1f Hz) - Treibende Kicks (Laser/Strobes aktiv)", s.strobeHz()), NamedTextColor.RED));
                    } else {
                        player.sendMessage(Component.text("Stroboskop: INAKTIV - Ruhige Phase / Breakdown (Kein Laser-Strobe)", NamedTextColor.GRAY));
                    }
                }
            }
            return;
        }

        if (args[0].equalsIgnoreCase("ui") || args[0].equalsIgnoreCase("console")) {
            String sub = args.length > 1 ? args[1].toLowerCase() : "open";
            switch (sub) {
                case "chest" -> {
                    plugin.getPlacementManager().setUiPreference(player, UiPreference.CHEST_UI);
                    plugin.getHoloConsoleUi().removeConsole(player.getUniqueId());
                    plugin.getChestConsoleUi().openConsole(player, plugin.getPlacementManager().getSelectedGroupId(player));
                    player.sendMessage(Component.text("✦ UI-Modus auf Großes Chest-UI (54 Slots) gesetzt.", NamedTextColor.GREEN));
                }
                case "holo", "hologram" -> {
                    plugin.getPlacementManager().setUiPreference(player, UiPreference.HOLO_UI);
                    Location spawnLoc = player.getEyeLocation().add(player.getEyeLocation().getDirection().clone().setY(0).normalize().multiply(1.85));
                    plugin.getHoloConsoleUi().spawnAtWandLocation(player, spawnLoc, player.getLocation().getYaw());
                }
                case "close" -> {
                    plugin.getHoloConsoleUi().removeConsole(player.getUniqueId());
                    player.sendMessage(Component.text("✔ 3D Hologramm-Steuerpult geschlossen.", NamedTextColor.YELLOW));
                }
                default -> {
                    UiPreference pref = plugin.getPlacementManager().getUiPreference(player);
                    if (pref == UiPreference.HOLO_UI) {
                        Location spawnLoc = player.getEyeLocation().add(player.getEyeLocation().getDirection().clone().setY(0).normalize().multiply(1.85));
                        plugin.getHoloConsoleUi().spawnAtWandLocation(player, spawnLoc, player.getLocation().getYaw());
                    } else {
                        plugin.getChestConsoleUi().openConsole(player, plugin.getPlacementManager().getSelectedGroupId(player));
                    }
                }
            }
            return;
        }

        if (args[0].equalsIgnoreCase("group")) {
            String sub = args.length > 1 ? args[1].toLowerCase() : "list";
            switch (sub) {
                case "select" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Verwendung: /admx group select <group_id>", NamedTextColor.RED));
                        return;
                    }
                    String gid = args[2];
                    FixtureGroup g = plugin.getFixtureGroup(gid);
                    if (g == null) {
                        player.sendMessage(Component.text("✘ Gruppe '" + gid + "' existiert nicht.", NamedTextColor.RED));
                        return;
                    }
                    plugin.getPlacementManager().setSelectedGroupId(player, gid);
                    player.sendMessage(Component.text("✔ Ziel-Gruppe für neue Geräte gesetzt auf: ", NamedTextColor.GREEN)
                            .append(Component.text(g.displayName(), NamedTextColor.AQUA)));
                }
                case "create" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Verwendung: /admx group create <group_id> [Anzeigename]", NamedTextColor.RED));
                        return;
                    }
                    String gid = args[2].toLowerCase();
                    if (!VALID_GROUP_ID.matcher(gid).matches()) {
                        player.sendMessage(Component.text("✘ Ungültige Gruppen-ID '" + gid + "'. Erlaubt sind nur a-z, 0-9 und _ (max. 24 Zeichen).", NamedTextColor.RED));
                        return;
                    }
                    if (plugin.getFixtureGroup(gid) != null) {
                        player.sendMessage(Component.text("✘ Eine Gruppe mit der ID '" + gid + "' existiert bereits!", NamedTextColor.RED));
                        return;
                    }
                    if (plugin.getFixtureGroups().size() >= 24) {
                        player.sendMessage(Component.text("✘ Maximale Anzahl von 24 Gerätegruppen erreicht.", NamedTextColor.RED));
                        return;
                    }
                    String rawName = args.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : ("Gruppe: " + gid);
                    String name = rawName.length() > 36 ? rawName.substring(0, 36) : rawName;
                    FixtureGroup created = new FixtureGroup(
                            gid, name, Material.BLUE_STAINED_GLASS, 0x33AAFF,
                            ControlMode.MANUAL_LIVE, 0.9f, 0f, -15f, 24f, 0f, 200f, 0.85f, 1.0f,
                            MovementPattern.PAN_SWEEP, 1.0f, 0.6f, 0.5f
                    );
                    plugin.getFixtureGroups().put(gid, created);
                    plugin.getDatabase().saveGroupAsync(created);
                    plugin.getPlacementManager().setSelectedGroupId(player, gid);
                    player.sendMessage(Component.text("✔ Neue SQLite-Gerätegruppe '" + name + "' (" + gid + ") erstellt und ausgewählt!", NamedTextColor.GREEN));
                }
                case "delete", "remove" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Verwendung: /admx group delete <group_id>", NamedTextColor.RED));
                        return;
                    }
                    String gid = args[2].toLowerCase();
                    if (plugin.getFixtureGroup(gid) == null) {
                        player.sendMessage(Component.text("✘ Gruppe '" + gid + "' existiert nicht.", NamedTextColor.RED));
                        return;
                    }
                    if (plugin.getFixtureGroups().size() <= 1) {
                        player.sendMessage(Component.text("✘ Die letzte verbleibende Gruppe kann nicht gelöscht werden.", NamedTextColor.RED));
                        return;
                    }
                    int count = plugin.countFixturesInGroup(gid);
                    if (count > 0) {
                        player.sendMessage(Component.text("✘ Gruppe '" + gid + "' enthält noch " + count + " platzierte Geräte und kann wegen der Datenbank-Integrität (FOREIGN KEY RESTRICT) erst geleert gelöscht werden.", NamedTextColor.RED));
                        return;
                    }
                    plugin.getFixtureGroups().remove(gid);
                    plugin.getDatabase().deleteGroup(gid);
                    player.sendMessage(Component.text("✔ Leere Gerätegruppe '" + gid + "' wurde gelöscht.", NamedTextColor.GREEN));
                }
                default -> {
                    String current = plugin.getPlacementManager().getSelectedGroupId(player);
                    player.sendMessage(Component.text("--- ADMX SQLite-Gerätegruppen ---", NamedTextColor.GOLD));
                    for (FixtureGroup g : plugin.getFixtureGroups().values()) {
                        int count = plugin.countFixturesInGroup(g.groupId());
                        boolean sel = g.groupId().equals(current);
                        player.sendMessage(Component.text((sel ? " » " : " • ") + g.displayName() + " [" + g.groupId() + "]: ",
                                sel ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                                .append(Component.text(count + " Geräte (" + g.controlMode().displayName() + ")", NamedTextColor.WHITE)));
                    }
                }
            }
            return;
        }

        if (args[0].equalsIgnoreCase("scene")) {
            String sub = args.length > 1 ? args[1].toLowerCase() : "play";
            int cueSlot = 0;
            if (args.length > 2) {
                try {
                    cueSlot = Math.clamp(Integer.parseInt(args[2]) - 1, 0, 3);
                } catch (NumberFormatException ignored) {
                }
            }
            switch (sub) {
                case "rec", "record", "save" -> {
                    var cue = plugin.getSceneTimeline().recordCue(cueSlot, "Cue " + (cueSlot + 1), plugin.getFixtureGroups().values());
                    plugin.getDatabase().saveSceneCueAsync(cue);
                    player.sendMessage(Component.text("● Live-Zustand aller Gruppen in Szenen-Cue " + (cueSlot + 1) + " gespeichert!", NamedTextColor.GOLD));
                }
                case "load", "recall" -> {
                    if (plugin.getSceneTimeline().recallCue(cueSlot, plugin.getFixtureGroups())) {
                        plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                        player.sendMessage(Component.text("▶ Szenen-Cue " + (cueSlot + 1) + " geladen!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("✘ Szenen-Cue " + (cueSlot + 1) + " ist noch leer.", NamedTextColor.RED));
                    }
                }
                default -> {
                    plugin.getSceneTimeline().togglePlayback(plugin.getFixtureGroups().values());
                    plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
                    boolean playing = plugin.getSceneTimeline().isPlayingSequence();
                    player.sendMessage(Component.text(playing
                            ? "▶ Szenen-Sequencer gestartet!"
                            : "⏹ Szenen-Sequencer gestoppt.", playing ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                }
            }
            return;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            if (!player.hasPermission("admx.place") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung zum Spawnen von Geräten (admx.place).", NamedTextColor.RED));
                return;
            }
            FixtureType type = FixtureType.MOVING_HEAD_SPOT;
            boolean useCmd = true;
            String groupId = plugin.getPlacementManager().getSelectedGroupId(player);

            if (args.length > 1) {
                String sub = args[1].toLowerCase();
                ConsoleModelType consoleType = switch (sub) {
                    case "grand_console", "dmx_console", "grandma" -> ConsoleModelType.GRAND_CONSOLE;
                    case "ddc6", "stairville_ddc6" -> ConsoleModelType.STAIRVILLE_DDC6;
                    case "dmx_master", "stairville_dmx_master" -> ConsoleModelType.STAIRVILLE_DMX_MASTER;
                    case "wing", "behringer_wing" -> ConsoleModelType.BEHRINGER_WING;
                    case "dm7", "yamaha_dm7" -> ConsoleModelType.YAMAHA_DM7;
                    default -> null;
                };
                if (consoleType != null) {
                    String limitErr = plugin.checkConsolePlacementLimits(player.getWorld());
                    if (limitErr != null) {
                        player.sendMessage(Component.text("✘ " + limitErr, NamedTextColor.RED));
                        return;
                    }
                    int targetUni = 0;
                    if (args.length > 2) {
                        try {
                            targetUni = Math.clamp(Integer.parseInt(args[2]) - 1, 0, DmxUniverse.MAX_UNIVERSES - 1);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    Location spawnLoc = player.getLocation().add(player.getLocation().getDirection().multiply(2.5));
                    spawnLoc.setX(Math.floor(spawnLoc.getX()) + 0.5);
                    spawnLoc.setY(Math.floor(spawnLoc.getY()));
                    spawnLoc.setZ(Math.floor(spawnLoc.getZ()) + 0.5);
                    float mountYaw = Math.round(player.getLocation().getYaw() / 45f) * 45f;

                    PlacedDmxConsole created = plugin.addPlacedConsole(
                            UUID.randomUUID(), consoleType, targetUni, spawnLoc, mountYaw, true, player.getUniqueId()
                    );
                    player.sendMessage(Component.text("✔ 3D-DMX-Pult '" + created.modelType().displayName()
                            + "' gespawnt & mit Universum " + (created.universeId() + 1) + " verlinkt!", NamedTextColor.GREEN));
                    return;
                }

                type = switch (sub) {
                    case "beam" -> FixtureType.MOVING_HEAD_BEAM;
                    case "wash" -> FixtureType.MOVING_HEAD_WASH;
                    case "fan" -> FixtureType.LASER_FAN_PROJECTOR;
                    case "needle" -> FixtureType.LASER_BEAM_PROJECTOR;
                    case "laserhead" -> FixtureType.MOVING_LASER_HEAD;
                    case "blinder" -> FixtureType.BLINDER_2LITE;
                    case "strobe" -> FixtureType.STROBE_BAR;
                    case "hazer", "raumnebel", "fog" -> FixtureType.FOG_MACHINE_HAZER;
                    case "lowfog", "kaltnebel" -> FixtureType.FOG_MACHINE_LOW_FOG;
                    case "flame" -> FixtureType.STAGE_FLAME;
                    default -> FixtureType.MOVING_HEAD_SPOT;
                };
            }

            if (args.length > 2) {
                if (plugin.getFixtureGroup(args[2]) != null) {
                    groupId = args[2];
                } else {
                    useCmd = !args[2].equalsIgnoreCase("vanilla");
                }
            }

            String limitError = plugin.checkPlacementLimits(player.getWorld(), type);
            if (limitError != null) {
                player.sendMessage(Component.text("✘ " + limitError, NamedTextColor.RED));
                return;
            }

            Location spawnLoc = player.getLocation().add(player.getLocation().getDirection().multiply(3));
            spawnLoc.setX(Math.floor(spawnLoc.getX()) + 0.5);
            spawnLoc.setY(Math.floor(spawnLoc.getY()));
            spawnLoc.setZ(Math.floor(spawnLoc.getZ()) + 0.5);

            float mountYaw = Math.round(player.getLocation().getYaw() / 45f) * 45f;
            MovingHeadProfile profile = MovingHeadProfile.defaultBlockbenchModel(useCmd);

            UUID id = UUID.randomUUID();
            AdmxPlugin.PlacedFixture created = plugin.addPlacedFixtureWithGroup(id, groupId, spawnLoc, mountYaw, false, type, profile);

            player.sendMessage(Component.text("✔ " + type.displayName() + " in Gruppe '" + created.groupId()
                    + "' [#" + (created.fixtureOrder() + 1) + "] gespawnt!", NamedTextColor.GREEN));
            return;
        }

        if (args[0].equalsIgnoreCase("purge")) {
            int count = EntityTracker.purgeDanglingEntities();
            player.sendMessage(Component.text("✔ " + count + " verwaiste ADMX Display-Entities wurden aus der Welt bereinigt.", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.7f, 1.2f);
            return;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            if (!player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Nur Administratoren (admx.admin) können alle Geräte löschen.", NamedTextColor.RED));
                return;
            }
            long now = System.currentTimeMillis();
            boolean confirmedArg = args.length > 1 && args[1].equalsIgnoreCase("confirm");
            Long requestedAt = pendingClearConfirmations.get(player.getUniqueId());
            boolean validPending = requestedAt != null && (now - requestedAt) <= CLEAR_CONFIRM_TIMEOUT_MS;

            if (!confirmedArg && !validPending) {
                pendingClearConfirmations.put(player.getUniqueId(), now);
                player.sendMessage(Component.text("⚠ ACHTUNG: Dies löscht ALLE platzierten ADMX-Geräte unwiderruflich aus Welt und Datenbank!", NamedTextColor.RED));
                player.sendMessage(Component.text("Gib innerhalb von 15 Sekunden ", NamedTextColor.YELLOW)
                        .append(Component.text("/admx clear confirm", NamedTextColor.GOLD))
                        .append(Component.text(" ein, um den Vorgang zu bestätigen.", NamedTextColor.YELLOW)));
                return;
            }

            pendingClearConfirmations.remove(player.getUniqueId());
            plugin.clearDemoFixtures();
            EntityTracker.removeAll();
            player.sendMessage(Component.text("✔ Alle ADMX-Geräte wurden aus Welt und Datenbank entfernt.", NamedTextColor.GREEN));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        boolean isAdmin = sender.hasPermission("admx.admin")
                || sender.hasPermission("admx.place")
                || sender.hasPermission("admx.control");
        if (!isAdmin) {
            return (args.length <= 1) ? List.of("safe") : List.of();
        }
        if (args.length == 0 || args.length == 1) {
            return List.of("wand", "co2", "bazooka", "desktop", "dmx", "ui", "s2l", "artnet", "group", "scene", "flow", "auto", "autoshow", "spawn", "safe", "purge", "clear", "help");
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "desktop", "boiler" -> List.of("spawn", "set");
                case "dmx", "workstation" -> List.of("1", "2", "3", "4", "5", "6", "7", "8");
                case "artnet", "bridge" -> List.of("status", "start", "stop", "token", "loopback");
                case "spawn" -> List.of("spot", "beam", "wash", "fan", "needle", "laserhead", "blinder", "strobe", "hazer", "lowfog", "flame",
                        "grand_console", "ddc6", "dmx_master", "wing", "dm7");
                case "ui", "console" -> List.of("chest", "holo", "open", "close");
                case "flow", "flowboard" -> new ArrayList<>(plugin.getFlowboards().keySet());
                case "auto", "autoshow" -> List.of("studio", "learn", "save", "load", "list", "on", "off", "s2l", "status", "source", "genre", "bpm");
                case "group" -> List.of("list", "select", "create", "delete");
                case "scene" -> List.of("play", "stop", "rec", "load");
                case "clear" -> List.of("confirm");
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("artnet") && args[1].equalsIgnoreCase("token")) {
                return List.of("regenerate");
            }
            if (args[0].equalsIgnoreCase("auto") || args[0].equalsIgnoreCase("autoshow")) {
                if (args[1].equalsIgnoreCase("load") || args[1].equalsIgnoreCase("recall")) {
                    return new ArrayList<>(plugin.getAutoShowProfiles().keySet());
                }
                if (args[1].equalsIgnoreCase("learn")) {
                    return List.of("1", "2", "3", "4", "5", "6", "7", "8");
                }
                if (args[1].equalsIgnoreCase("source")) {
                    return List.of("boiler", "stream", "demo");
                }
                if (args[1].equalsIgnoreCase("genre")) {
                    return List.of("techno", "rap_hiphop", "drum_and_bass", "house_club", "ambient");
                }
            }
            if (args[0].equalsIgnoreCase("spawn")
                    || (args[0].equalsIgnoreCase("group") && (args[1].equalsIgnoreCase("select") || args[1].equalsIgnoreCase("delete")))) {
                return new ArrayList<>(plugin.getFixtureGroups().keySet());
            }
            if (args[0].equalsIgnoreCase("scene")) {
                return List.of("1", "2", "3", "4");
            }
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "admx.safe";
    }
}
