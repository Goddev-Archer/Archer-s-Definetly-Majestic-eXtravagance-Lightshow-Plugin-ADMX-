package de.archer.admx.ui;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AudioDspManager;
import de.archer.admx.audio.AutoShowProfile;
import de.archer.admx.audio.AutoShowProfile.ChoreoStyle;
import de.archer.admx.audio.AutoShowProfile.SongSection;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.audio.SmartAutoDirector;
import de.archer.admx.audio.SmartAutoDirector.DirectorMode;
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

/**
 * Eigenständiges 54-Slot "Auto-Show Studio" Interface (Chest-UI) für den Multi-Pass Song-DNA Learner,
 * das Einfrieren/Speichern gelernter Auto-Shows und den 1-Klick Schnellaufruf (Quick-Recall IDs #01..#08)
 * mit 3-Stufen Live-Beat-PLL "Idiotenschutz"-Synchronisation.
 * <p>
 * Sicherheits- &amp; Performance-Invarianten:
 * <ul>
 *     <li><b>SEC-01:</b> Strikte Berechtigungsprüfung ({@code admx.control}, {@code admx.console}, {@code admx.admin}).</li>
 *     <li><b>SEC-03:</b> Klick-Rate-Limiting über {@code checkClickCooldown}.</li>
 *     <li><b>SEC-07:</b> Vollständiges Canceln von {@link InventoryClickEvent} und {@link InventoryDragEvent}.</li>
 *     <li><b>PERF-09:</b> Asynchrone SQLite-Persistierung ({@code saveAutoShowProfileAsync}).</li>
 * </ul>
 */
public final class AutoShowStudioChestUi implements Listener {

    private final AdmxPlugin plugin;
    private BukkitTask liveUpdateTask;

    public static final class StudioHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public AutoShowStudioChestUi(AdmxPlugin plugin) {
        this.plugin = plugin;
        startLiveTicker();
    }

    private void startLiveTicker() {
        this.liveUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof StudioHolder) {
                    populateStudioInventory(player.getOpenInventory().getTopInventory());
                }
            }
        }, 4L, 4L);
    }

    public void stop() {
        if (liveUpdateTask != null) {
            liveUpdateTask.cancel();
            liveUpdateTask = null;
        }
    }

    public static boolean hasStudioPermission(Player player) {
        return player.hasPermission("admx.admin")
                || player.hasPermission("admx.control")
                || player.hasPermission("admx.console");
    }

    public void openStudio(Player player) {
        if (!hasStudioPermission(player)) {
            player.sendMessage(Component.text("✘ Keine Berechtigung für das Auto-Show Studio (admx.control).", NamedTextColor.RED));
            return;
        }

        StudioHolder holder = new StudioHolder();
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text("ADMX Auto-Show Studio » Song-DNA & Recall", NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
        );
        holder.inventory = inv;

        populateStudioInventory(inv);
        player.openInventory(inv);
    }

    private void populateStudioInventory(Inventory inv) {
        ItemStack bg = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, bg);
        }

        SmartAutoDirector director = plugin.getSmartAutoDirector();
        AudioDspManager dsp = plugin.getAudioDspManager();
        BeatSnapshot beat = dsp.getSnapshot();
        DirectorMode mode = director.directorMode();
        AutoShowProfile activeProfile = director.activeProfile();
        SongSection currentSec = director.currentSection();
        int selectedSlot = director.selectedQuickSlot();

        // --- REIHE 1 (Slots 0..8): Studio-Kopfzeile, Beat-PLL-Taktgeber & Audio-Quelle ---
        Material modeMat = switch (mode) {
            case LEARNING_PASS -> Material.REDSTONE_TORCH;
            case LOCKED_PROFILE -> Material.LODESTONE;
            case ADAPTIVE_LIVE -> Material.BEACON;
        };
        NamedTextColor modeCol = switch (mode) {
            case LEARNING_PASS -> NamedTextColor.RED;
            case LOCKED_PROFILE -> NamedTextColor.GREEN;
            case ADAPTIVE_LIVE -> NamedTextColor.AQUA;
        };

        inv.setItem(0, item(
                modeMat,
                Component.text("🧠 Auto-Director Modus: " + mode.displayName(), modeCol, TextDecoration.BOLD),
                List.of(
                        Component.text("Aktives Profil: ", NamedTextColor.GRAY)
                                .append(Component.text(activeProfile.displayName() + " [" + activeProfile.profileId() + "]", NamedTextColor.GOLD)),
                        Component.text("Choreografie-Stil: ", NamedTextColor.GRAY)
                                .append(Component.text(activeProfile.choreoStyle().displayName(), NamedTextColor.LIGHT_PURPLE)),
                        Component.text("Ziel-Speicherplatz: ", NamedTextColor.GRAY)
                                .append(Component.text("Slot #" + String.format(Locale.ROOT, "%02d", selectedSlot + 1), NamedTextColor.YELLOW))
                )
        ));

        int beatNum = beat.beatInBar() + 1;
        String beatDots = (beatNum == 1 ? "● " : "○ ")
                + (beatNum == 2 ? "● " : "○ ")
                + (beatNum == 3 ? "● " : "○ ")
                + (beatNum == 4 ? "●" : "○");
        inv.setItem(2, item(
                beat.isKick() ? Material.SEA_LANTERN : Material.CLOCK,
                Component.text(String.format(Locale.ROOT, "⏱ Live Beat-PLL: %.1f BPM [%s]", beat.bpm(), beatDots),
                        beat.isKick() ? NamedTextColor.GREEN : NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Takt #" + beat.barCount() + " | Beat " + beatNum + "/4 | Phase: " + Math.round(beat.phase() * 100) + "%", NamedTextColor.WHITE),
                        Component.text("Genre-Signatur: " + beat.genre().displayName(), beat.genre().color()),
                        Component.text("Idiotenschutz-Status: ", NamedTextColor.GRAY)
                                .append(Component.text(director.isMusicPausedStandby() ? "STANDBY (Warte auf Musik)" : "100% BEAT-LOCKED", NamedTextColor.GREEN))
                )
        ));

        inv.setItem(6, item(
                Material.NOTE_BLOCK,
                Component.text("🎵 Audio-Quelle: " + dsp.getActiveSource().sourceName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text(dsp.getActiveSource().desc(), NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten (Boiler / Stream / Demo)", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(8, item(
                Material.CHEST,
                Component.text("« Zurück zum Hauptsteuerpult", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(Component.text("Öffnet wieder das ADMX Gruppen-Steuerpult.", NamedTextColor.GRAY))
        ));

        // --- REIHE 2 (Slots 9..17): Live 4-Sektionen Song-DNA Monitor & Lern-Fortschritt ---
        SongSection[] sections = SongSection.values();
        Material[] secMats = {
                Material.LIGHT_BLUE_STAINED_GLASS,
                Material.LIME_STAINED_GLASS,
                Material.YELLOW_STAINED_GLASS,
                Material.RED_STAINED_GLASS
        };
        for (int i = 0; i < sections.length; i++) {
            SongSection sec = sections[i];
            boolean isCurrent = (sec == currentSec);
            ItemStack secItem = item(
                    isCurrent ? Material.GLOWSTONE : secMats[i],
                    Component.text((isCurrent ? "▶ AKTIV: " : "• ") + sec.displayName(),
                            isCurrent ? NamedTextColor.GOLD : NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of(
                            Component.text(sec.description(), NamedTextColor.GRAY),
                            Component.text("Zugewiesenes Muster: ", NamedTextColor.GRAY)
                                    .append(Component.text(activeProfile.patternForSection(sec).displayName(), NamedTextColor.AQUA)),
                            Component.empty(),
                            Component.text(isCurrent ? "⚡ Wird gerade live zur Musik ausgeführt!" : "Wartet auf passende Song-Energie",
                                    isCurrent ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                    )
            );
            if (isCurrent) {
                secItem.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
            }
            inv.setItem(10 + i, secItem);
        }

        int confPct = Math.round(activeProfile.confidence() * 100f);
        inv.setItem(15, item(
                mode == DirectorMode.LEARNING_PASS ? Material.RECOVERY_COMPASS : Material.BOOK,
                Component.text(String.format(Locale.ROOT, "📊 Lern-Reifegrad: %d%% (Pass #%d)", confPct, activeProfile.completedPasses() + 1),
                        confPct >= 80 ? NamedTextColor.GREEN : NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Analysierte Beats: " + activeProfile.learnedBeats() + " Beats", NamedTextColor.WHITE),
                        Component.text("Abgeschlossene Durchläufe: " + activeProfile.completedPasses(), NamedTextColor.AQUA),
                        Component.text("Erkannte Haupt-Drops: " + activeProfile.detectedDrops(), NamedTextColor.GOLD),
                        Component.text(String.format(Locale.ROOT, "Gelerntes Dynamik-Fenster: %.0f%% .. %.0f%%",
                                activeProfile.learnedFloorEnergy() * 100f, activeProfile.learnedPeakEnergy() * 100f), NamedTextColor.GRAY),
                        Component.text(String.format(Locale.ROOT, "Referenz-Tempo: %.1f BPM (Phrasen: %d Takte)",
                                activeProfile.referenceBpm(), activeProfile.phraseLengthBars()), NamedTextColor.GRAY)
                )
        ));

        inv.setItem(16, item(
                Material.SHIELD,
                Component.text("🛡 3-Stufen Idiotenschutz: AKTIV", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Garantiert 100% Musik-Synchronität auch bei", NamedTextColor.GRAY),
                        Component.text("vorgelernten & gespeicherten Auto-Shows:", NamedTextColor.GRAY),
                        Component.text("1. Live-Beat-PLL statt starrer Stoppuhr", NamedTextColor.AQUA),
                        Component.text("2. Echtzeit-Sektions-Erkennung mitten im Song", NamedTextColor.YELLOW),
                        Component.text("3. Auto-Hold Standby bei Musik-Pause", NamedTextColor.LIGHT_PURPLE)
                )
        ));

        // --- REIHE 3 (Slots 19..25): Lern-Algorithmus & Speicher-Steuerung ---
        boolean isLearning = (mode == DirectorMode.LEARNING_PASS);
        inv.setItem(19, item(
                isLearning ? Material.RED_CONCRETE : Material.EMERALD_BLOCK,
                Component.text(isLearning ? "● LERNEN LÄUFT... (Klick = Neu starten)" : "▶ 1. LERN-ALGORITHMUS STARTEN",
                        isLearning ? NamedTextColor.RED : NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Startet die Multi-Pass Song-Analyse für", NamedTextColor.GRAY),
                        Component.text("den gewählten Speicher-Slot #" + (selectedSlot + 1) + ".", NamedTextColor.GRAY),
                        Component.text("Lass das Lied 1–2 Mal laufen, bis die", NamedTextColor.WHITE),
                        Component.text("Song-DNA & Drops optimal kalibriert sind!", NamedTextColor.WHITE),
                        Component.empty(),
                        Component.text("» Klicken zum Starten der Song-Analyse", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(20, item(
                Material.NETHER_STAR,
                Component.text("💾 2. LERNEN ABSCHALTEN & SPEICHERN (Slot #" + (selectedSlot + 1) + ")", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Schaltet den Lern-Algorithmus ab (friert das", NamedTextColor.GRAY),
                        Component.text("gelernte Muster ein) und speichert die Auto-Show", NamedTextColor.GRAY),
                        Component.text("dauerhaft unter ID 'show_0" + (selectedSlot + 1) + "' in SQLite!", NamedTextColor.AQUA),
                        Component.text("Tipp: Für eigenen Namen nutze /admx auto save <Name>", NamedTextColor.DARK_GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Einfrieren & Speichern", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(21, item(
                Material.AMETHYST_CLUSTER,
                Component.text("🎲 Choreografie-Stil variieren: " + activeProfile.choreoStyle().displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Wechselt Farbpalette, Bewegungsmuster und", NamedTextColor.GRAY),
                        Component.text("Strobe-Charakteristik, behält aber die gelernten", NamedTextColor.GRAY),
                        Component.text("Audio-Schwellenwerte des Liedes bei!", NamedTextColor.WHITE),
                        Component.empty(),
                        Component.text("» Klicken für nächsten Stil", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(22, item(
                Material.COMPARATOR,
                Component.text(String.format(Locale.ROOT, "⚙ Drop-Schwelle: %d%%", Math.round(activeProfile.dropThreshold() * 100f)),
                        NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Bestimmt, ab welcher normalisierten Song-Energie", NamedTextColor.GRAY),
                        Component.text("der Haupt-Drop (Blinder, Strobe, Pyro) zündet.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Linksklick: +5% (Strenger) | Rechtsklick: -5% (Früher)", NamedTextColor.AQUA)
                )
        ));

        inv.setItem(23, item(
                mode == DirectorMode.ADAPTIVE_LIVE ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("⚡ Freier Adaptiv-Modus (Ohne festes Profil)",
                        mode == DirectorMode.ADAPTIVE_LIVE ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Schaltet zurück in den freien Echtzeit-Modus", NamedTextColor.GRAY),
                        Component.text("ohne gespeichertes Song-DNA-Profil.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Aktivieren", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(25, item(
                Material.JUKEBOX,
                Component.text("✦ Alle Gruppen auf AUTO_SHOW schalten", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Aktiviert sofort für alle Bühnengruppen", NamedTextColor.GRAY),
                        Component.text("den Auto-Show-Modus.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Aktivieren", NamedTextColor.GREEN)
                )
        ));

        // --- REIHE 5 (Slots 36..43): 8 QUICK-RECALL SLOTS (ID #01 .. #08) ---
        for (int slot = 0; slot < AutoShowProfile.MAX_QUICK_SLOTS; slot++) {
            String slotId = String.format(Locale.ROOT, "show_%02d", slot + 1);
            AutoShowProfile saved = plugin.getAutoShowProfile(slotId);
            boolean isSelectedSlot = (slot == selectedSlot);
            boolean isCurrentlyRunning = (mode == DirectorMode.LOCKED_PROFILE
                    && activeProfile != null
                    && slotId.equalsIgnoreCase(activeProfile.profileId()));

            if (saved != null) {
                Material mat = isCurrentlyRunning
                        ? Material.LIME_CONCRETE
                        : (isSelectedSlot ? Material.GOLD_BLOCK : Material.CYAN_CONCRETE);
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Auto-Show ID: ", NamedTextColor.GRAY)
                        .append(Component.text(saved.profileId() + " (Slot #" + (slot + 1) + ")", NamedTextColor.YELLOW)));
                lore.add(Component.text("Stil: ", NamedTextColor.GRAY)
                        .append(Component.text(saved.choreoStyle().displayName(), NamedTextColor.LIGHT_PURPLE)));
                lore.add(Component.text(String.format(Locale.ROOT, "Song-DNA: %.1f BPM | Drop-Thr: %d%% | Reife: %d%%",
                        saved.referenceBpm(), Math.round(saved.dropThreshold() * 100f), Math.round(saved.confidence() * 100f)), NamedTextColor.WHITE));
                lore.add(Component.text("Gelernte Beats: " + saved.learnedBeats() + " (" + saved.completedPasses() + " Passes)", NamedTextColor.GRAY));
                lore.add(Component.empty());
                lore.add(Component.text("» Linksklick: Sofort synchron abrufen (Quick-Recall)", NamedTextColor.GREEN));
                lore.add(Component.text("» Rechtsklick: Als Ziel-Slot zum Lernen wählen", NamedTextColor.YELLOW));
                lore.add(Component.text("» Shift-Klick: Aktuelle Song-DNA hier speichern", NamedTextColor.GOLD));

                ItemStack slotItem = item(
                        mat,
                        Component.text((isCurrentlyRunning ? "▶ " : "★ ") + "#" + (slot + 1) + ": " + saved.displayName(),
                                isCurrentlyRunning ? NamedTextColor.GREEN : NamedTextColor.AQUA, TextDecoration.BOLD),
                        lore
                );
                if (isCurrentlyRunning) {
                    slotItem.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
                }
                inv.setItem(36 + slot, slotItem);
            } else {
                inv.setItem(36 + slot, item(
                        isSelectedSlot ? Material.YELLOW_STAINED_GLASS : Material.GRAY_CONCRETE,
                        Component.text("○ Slot #" + (slot + 1) + " [Freier Speicherplatz]",
                                isSelectedSlot ? NamedTextColor.YELLOW : NamedTextColor.GRAY, TextDecoration.BOLD),
                        List.of(
                                Component.text("Auto-Show ID: " + slotId, NamedTextColor.DARK_GRAY),
                                Component.empty(),
                                Component.text("» Linksklick: Slot auswählen & Lernen starten", NamedTextColor.YELLOW),
                                Component.text("» Shift-Klick: Aktuelle Song-DNA hier speichern", NamedTextColor.GOLD)
                        )
                ));
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StudioHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StudioHolder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!hasStudioPermission(player)) {
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

        SmartAutoDirector director = plugin.getSmartAutoDirector();
        int selectedSlot = director.selectedQuickSlot();

        if (slot == 6) {
            var dsp = plugin.getAudioDspManager();
            var next = switch (dsp.getActiveSource()) {
                case SYNTHETIC_DEMO -> AudioDspManager.AudioSourceType.BOILER_MEDIA;
                case BOILER_MEDIA -> AudioDspManager.AudioSourceType.LOOPBACK_STREAM;
                case LOOPBACK_STREAM -> AudioDspManager.AudioSourceType.SYNTHETIC_DEMO;
            };
            dsp.setActiveSource(next);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
            populateStudioInventory(event.getInventory());
            return;
        }

        if (slot == 8) {
            player.closeInventory();
            plugin.getChestConsoleUi().openConsole(player, plugin.getPlacementManager().getSelectedGroupId(player));
            return;
        }

        // Slot 19: Lern-Algorithmus starten
        if (slot == 19) {
            enableAutoShowOnAllGroups();
            director.startLearning(selectedSlot, "Learned Show #" + (selectedSlot + 1));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.85f, 1.5f);
            player.sendMessage(Component.text("🧠 Multi-Pass Lern-Algorithmus gestartet (Ziel: Slot #" + (selectedSlot + 1) + ")! ", NamedTextColor.GREEN)
                    .append(Component.text("Lass das Lied laufen – ADMX lernt jetzt Dynamik, Buildups & Drops.", NamedTextColor.GRAY)));
            populateStudioInventory(event.getInventory());
            return;
        }

        // Slot 20: Lernen abschalten & in aktivem Slot speichern
        if (slot == 20) {
            AutoShowProfile saved = director.stopLearningAndFreeze(null, selectedSlot);
            plugin.saveAutoShowProfile(saved);
            enableAutoShowOnAllGroups();
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.75f, 1.3f);
            player.sendMessage(Component.text("💾 Lern-Algorithmus abgeschaltet & Auto-Show gespeichert unter ID ", NamedTextColor.GREEN)
                    .append(Component.text(saved.profileId() + " (" + saved.displayName() + ")", NamedTextColor.GOLD))
                    .append(Component.text("! [3-Stufen Beat-PLL Idiotenschutz aktiv]", NamedTextColor.AQUA)));
            populateStudioInventory(event.getInventory());
            return;
        }

        // Slot 21: Choreografie-Stil variieren
        if (slot == 21) {
            ChoreoStyle nextStyle = director.mutateChoreoStyle();
            if (director.directorMode() == DirectorMode.LOCKED_PROFILE) {
                plugin.saveAutoShowProfile(director.activeProfile());
            }
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.3f);
            populateStudioInventory(event.getInventory());
            return;
        }

        // Slot 22: Drop-Schwelle anpassen
        if (slot == 22) {
            float delta = event.isRightClick() ? -0.05f : 0.05f;
            director.adjustDropThreshold(delta);
            if (director.directorMode() == DirectorMode.LOCKED_PROFILE) {
                plugin.saveAutoShowProfile(director.activeProfile());
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
            populateStudioInventory(event.getInventory());
            return;
        }

        // Slot 23: Freier Adaptiv-Modus
        if (slot == 23) {
            director.setAdaptiveLiveMode();
            enableAutoShowOnAllGroups();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
            populateStudioInventory(event.getInventory());
            return;
        }

        // Slot 25: Alle Gruppen auf AUTO_SHOW
        if (slot == 25) {
            enableAutoShowOnAllGroups();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.85f, 1.4f);
            player.sendMessage(Component.text("✔ Alle Gruppen laufen jetzt im Smart Auto-Show Modus!", NamedTextColor.GREEN));
            populateStudioInventory(event.getInventory());
            return;
        }

        // Slots 36..43: Quick-Recall Slots #1..#8
        if (slot >= 36 && slot < 36 + AutoShowProfile.MAX_QUICK_SLOTS) {
            int targetIdx = slot - 36;
            String slotId = String.format(Locale.ROOT, "show_%02d", targetIdx + 1);
            AutoShowProfile existing = plugin.getAutoShowProfile(slotId);

            if (event.isShiftClick()) {
                AutoShowProfile saved = director.stopLearningAndFreeze(
                        existing != null ? existing.displayName() : ("Learned Show #" + (targetIdx + 1)),
                        targetIdx
                );
                plugin.saveAutoShowProfile(saved);
                enableAutoShowOnAllGroups();
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.5f);
                player.sendMessage(Component.text("💾 Auto-Show in Slot #" + (targetIdx + 1) + " (" + saved.profileId() + ") gespeichert!", NamedTextColor.GOLD));
            } else if (event.isRightClick()) {
                director.setSelectedQuickSlot(targetIdx);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
            } else {
                if (existing != null) {
                    director.recallProfile(existing);
                    enableAutoShowOnAllGroups();
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.85f, 1.5f);
                    player.sendMessage(Component.text("⚡ Auto-Show ", NamedTextColor.AQUA)
                            .append(Component.text(existing.displayName() + " [" + existing.profileId() + "]", NamedTextColor.GOLD))
                            .append(Component.text(" geladen! (100% Beat-PLL synchronisiert)", NamedTextColor.GREEN)));
                } else {
                    director.startLearning(targetIdx, "Learned Show #" + (targetIdx + 1));
                    enableAutoShowOnAllGroups();
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
                    player.sendMessage(Component.text("🧠 Slot #" + (targetIdx + 1) + " war leer -> Lern-Algorithmus für diesen Slot gestartet!", NamedTextColor.YELLOW));
                }
            }
            populateStudioInventory(event.getInventory());
        }
    }

    private void enableAutoShowOnAllGroups() {
        for (FixtureGroup g : plugin.getFixtureGroups().values()) {
            g.setControlMode(ControlMode.AUTO_SHOW);
        }
        plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(mat);
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
