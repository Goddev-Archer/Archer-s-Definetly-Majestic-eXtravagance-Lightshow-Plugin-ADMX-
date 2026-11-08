package de.archer.admx.placement;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.UiPreference;
import de.archer.admx.dmx.ConsoleModelType;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.dmx.PlacedDmxConsole;
import de.archer.admx.render.fixture.FixtureType;
import de.archer.admx.render.fixture.MovingHeadProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 * Verwaltet das in-game Platzierungs-Werkzeug (ADMX Fixture Placer & Console Wand),
 * die Vorab-Gruppenzuweisung vor dem Spawnen von Geräten, das Platzieren der 5 physischen
 * 3D-DMX-Steuerpulte ({@link ConsoleModelType}) inkl. Universums-Verlinkung sowie die Auswahl
 * zwischen Chest-UI, 3D-Hologramm-Steuerpult und Pro DMX512 Workstation.
 * <p>
 * Enthält strikte Berechtigungsprüfungen (SEC-01), Klick-Rate-Limiting (SEC-03),
 * InventoryDrag-Schutz (SEC-07) und automatische Session-Bereinigung bei Disconnect (PERF-14).
 */
public final class FixturePlacementManager implements Listener {

    private final AdmxPlugin plugin;
    private final NamespacedKey wandKey;

    private final Map<UUID, PendingPlacement> pendingPlacements = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerUseCmdPref = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedGroupByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedUniverseByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UiPreference> uiPreferenceByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClickTimeMs = new ConcurrentHashMap<>();

    public record PendingPlacement(Location location, float mountYaw, boolean ceilingMounted) {
    }

    private static final class PlacementGuiHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public FixturePlacementManager(AdmxPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "fixture_placer");
    }

    public boolean checkClickCooldown(Player player) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.getUiClickCooldownMs();
        Long prev = lastClickTimeMs.get(player.getUniqueId());
        if (prev != null && (now - prev) < cooldown) {
            return false;
        }
        lastClickTimeMs.put(player.getUniqueId(), now);
        return true;
    }

    public static boolean hasPlaceOrControlPermission(Player player) {
        return player.hasPermission("admx.admin")
                || player.hasPermission("admx.place")
                || player.hasPermission("admx.control")
                || player.hasPermission("admx.console");
    }

    public String getSelectedGroupId(Player player) {
        String id = selectedGroupByPlayer.get(player.getUniqueId());
        if (id != null && plugin.getFixtureGroup(id) != null) {
            return id;
        }
        var groups = plugin.getFixtureGroups();
        if (!groups.isEmpty()) {
            String first = groups.keySet().iterator().next();
            selectedGroupByPlayer.put(player.getUniqueId(), first);
            return first;
        }
        return "front_truss";
    }

    public void setSelectedGroupId(Player player, String groupId) {
        if (plugin.getFixtureGroup(groupId) != null) {
            selectedGroupByPlayer.put(player.getUniqueId(), groupId);
        }
    }

    public String cycleSelectedGroup(Player player, boolean backwards) {
        List<String> keys = new ArrayList<>(plugin.getFixtureGroups().keySet());
        if (keys.isEmpty()) {
            return "front_truss";
        }
        String current = getSelectedGroupId(player);
        int idx = keys.indexOf(current);
        int nextIdx = backwards
                ? (idx - 1 + keys.size()) % keys.size()
                : (idx + 1) % keys.size();
        String next = keys.get(nextIdx);
        selectedGroupByPlayer.put(player.getUniqueId(), next);
        return next;
    }

    public int getSelectedUniverseId(Player player) {
        return selectedUniverseByPlayer.getOrDefault(player.getUniqueId(), 0);
    }

    public int cycleSelectedUniverse(Player player, boolean backwards) {
        int cur = getSelectedUniverseId(player);
        int next = backwards
                ? (cur - 1 + DmxUniverse.MAX_UNIVERSES) % DmxUniverse.MAX_UNIVERSES
                : (cur + 1) % DmxUniverse.MAX_UNIVERSES;
        selectedUniverseByPlayer.put(player.getUniqueId(), next);
        return next;
    }

    public UiPreference getUiPreference(Player player) {
        return uiPreferenceByPlayer.getOrDefault(player.getUniqueId(), UiPreference.CHEST_UI);
    }

    public void setUiPreference(Player player, UiPreference pref) {
        uiPreferenceByPlayer.put(player.getUniqueId(), pref);
    }

    public UiPreference toggleUiPreference(Player player) {
        UiPreference next = getUiPreference(player).toggle();
        uiPreferenceByPlayer.put(player.getUniqueId(), next);
        return next;
    }

    /**
     * Erstellt das spawn- und klickbare Platzierungs-Item (ADMX Fixture Placer & Console Wand).
     */
    public ItemStack createPlacementWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✦ ADMX Fixture Placer & Console Wand ✦", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true));
            meta.lore(List.of(
                    Component.empty(),
                    Component.text("Rechtsklick auf Block:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text(" » Öffnet Geräte-, 3D-DMX-Pult- & Gruppen-Auswahlmenü", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Sneak + Rechtsklick auf Block / 3D-Pult:", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                    Component.text(" » Pixelgenaue Platzierung bzw. Universum am Pult wechseln", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Linksklick auf Block:", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                    Component.text(" » Öffnet Chest-UI oder spawnt 3D-Holo-UI am Block!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isPlacementWand(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        var meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID pid = event.getPlayer().getUniqueId();
        pendingPlacements.remove(pid);
        playerUseCmdPref.remove(pid);
        selectedGroupByPlayer.remove(pid);
        selectedUniverseByPlayer.remove(pid);
        uiPreferenceByPlayer.remove(pid);
        lastClickTimeMs.remove(pid);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!isPlacementWand(item)) return;

        Player player = event.getPlayer();
        // Sicherheitsprüfung (SEC-01): Gestohlene oder gedroppte Wands dürfen ohne Berechtigung nicht funktionieren!
        if (!hasPlaceOrControlPermission(player)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("✘ Du hast keine Berechtigung (admx.place / admx.admin), das ADMX-Werkzeug zu verwenden.", NamedTextColor.RED));
            return;
        }

        Action action = event.getAction();

        // Linksklick mit dem Wand: Direkt gewähltes UI öffnen bzw. 3D-Holo-UI an der angeklickten Blockposition spawnen!
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            event.setCancelled(true);
            if (!checkClickCooldown(player)) return;

            UiPreference pref = getUiPreference(player);
            if (pref == UiPreference.HOLO_UI) {
                if (!plugin.getHoloConsoleUi().hasActiveConsole(player.getUniqueId()) || player.isSneaking()) {
                    Block clicked = event.getClickedBlock();
                    Location spawnLoc = (clicked != null)
                            ? clicked.getLocation().add(0.5, 1.0, 0.5)
                            : player.getEyeLocation().add(player.getEyeLocation().getDirection().clone().setY(0).normalize().multiply(1.85));
                    plugin.getHoloConsoleUi().spawnAtWandLocation(player, spawnLoc, player.getLocation().getYaw());
                }
            } else {
                plugin.getChestConsoleUi().openConsole(player, getSelectedGroupId(player));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
            }
            return;
        }

        if (action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        if (!checkClickCooldown(player)) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        BlockFace face = event.getBlockFace();
        boolean ceilingMounted = (face == BlockFace.DOWN);

        Location targetLoc;
        Location interactionPoint = event.getInteractionPoint();

        if (player.isSneaking() && interactionPoint != null) {
            targetLoc = interactionPoint.clone();
        } else {
            if (face == BlockFace.UP) {
                targetLoc = clickedBlock.getLocation().add(0.5, 1.0, 0.5);
            } else if (face == BlockFace.DOWN) {
                targetLoc = clickedBlock.getLocation().add(0.5, 0.0, 0.5);
            } else {
                Block adjacent = clickedBlock.getRelative(face);
                targetLoc = adjacent.getLocation().add(0.5, 0.0, 0.5);
            }
        }
        targetLoc.setYaw(0f);
        targetLoc.setPitch(0f);

        float rawYaw = player.getLocation().getYaw();
        float mountYaw = player.isSneaking()
                ? rawYaw
                : Math.round(rawYaw / 45f) * 45f;

        pendingPlacements.put(player.getUniqueId(), new PendingPlacement(targetLoc, mountYaw, ceilingMounted));
        openSelectionGui(player);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
    }

    public void openSelectionGui(Player player) {
        boolean useCmd = playerUseCmdPref.getOrDefault(player.getUniqueId(), true);
        PendingPlacement pending = pendingPlacements.get(player.getUniqueId());
        String targetGroupId = getSelectedGroupId(player);
        FixtureGroup targetGroup = plugin.getFixtureGroup(targetGroupId);
        String groupName = (targetGroup != null) ? targetGroup.displayName() : targetGroupId;
        int groupMembers = plugin.countFixturesInGroup(targetGroupId);
        int targetUniverse = getSelectedUniverseId(player);
        UiPreference uiPref = getUiPreference(player);

        PlacementGuiHolder holder = new PlacementGuiHolder();
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text("ADMX Gerät & 3D-Pult platzieren » " + groupName, NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
        );
        holder.inventory = inv;

        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }

        String mountInfo = (pending != null && pending.ceilingMounted()) ? "Deckenmontage (Hängend)" : "Boden-/Stativmontage";
        String yawInfo = (pending != null) ? String.format(Locale.ROOT, "%.0f°", pending.mountYaw()) : "0°";
        Component groupInfoLine = Component.text("Ziel-Gruppe: ", NamedTextColor.GRAY)
                .append(Component.text(groupName + " (#" + (groupMembers + 1) + ")", NamedTextColor.AQUA));
        Component universeInfoLine = Component.text("Ziel-Universum: ", NamedTextColor.GRAY)
                .append(Component.text("Universum " + (targetUniverse + 1) + " (Art-Net 0:0:" + targetUniverse + ")", NamedTextColor.GREEN));

        // --- Reihe 1 & 2: Bühnengeräte (Fixtures) ---
        inv.setItem(10, createGuiItem(
                FixtureType.MOVING_HEAD_SPOT.iconMaterial(),
                Component.text("Moving Head Spotlight", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("3-Part Moving Head (Spot-Optik)", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Volumetrischer 3D-Lichtkegel (Spot Cone)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(11, createGuiItem(
                FixtureType.MOVING_HEAD_BEAM.iconMaterial(),
                Component.text("Moving Head Beam (Sharpie)", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("3-Part Moving Head (Konvexe Beam-Linse)", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Enger, messerscharfer Nadelstrahl (Beam)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(12, createGuiItem(
                FixtureType.MOVING_HEAD_WASH.iconMaterial(),
                Component.text("Moving Head Wash", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("3-Part Moving Head (Waben-LED-Cluster)", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Breiter, weicher Farbkegel (Washlight)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(13, createGuiItem(
                FixtureType.LASER_FAN_PROJECTOR.iconMaterial(),
                Component.text("Show-Laser (Scanning Fan)", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("Statischer Club-Laserprojektor", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Breiter 2D-Laserfächer (Audience Scan)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(14, createGuiItem(
                FixtureType.LASER_BEAM_PROJECTOR.iconMaterial(),
                Component.text("Show-Laser (Needle Beam)", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("Statischer Club-Laserprojektor", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Gebündelter 3D-Nadelstrahl (Single Beam)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(15, createGuiItem(
                FixtureType.MOVING_LASER_HEAD.iconMaterial(),
                Component.text("Moving Laser Head", NamedTextColor.DARK_AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("3-Part Moving Laser Head (Base/Yoke/Head)", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Rotierender 360°-Laserfächer", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(16, createGuiItem(
                FixtureType.BLINDER_2LITE.iconMaterial(),
                Component.text("Audience Blinder (2-Lite)", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("2-Fach PAR36 Warmweiß-Fluter", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Volumetrischer Warmweiß-Fluter (Breit)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(19, createGuiItem(
                FixtureType.STROBE_BAR.iconMaterial(),
                Component.text("LED Strobe / Sunstrip", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("Mehrsegment-LED-Leiste (1m)", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Ultra-helle Strobe-Flashes", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(21, createGuiItem(
                FixtureType.FOG_MACHINE_HAZER.iconMaterial(),
                Component.text("Raumnebelmaschine (Stage Hazer)", NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("DMX Nebelmaschine mit Tank & Heizblock", NamedTextColor.WHITE)),
                        Component.text("Physik: ", NamedTextColor.GRAY).append(Component.text("3D-Raum-Flood-Fill (max. 30 Blöcke)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(22, createGuiItem(
                FixtureType.FOG_MACHINE_LOW_FOG.iconMaterial(),
                Component.text("Kaltnebelmaschine (Bodennebel)", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("Cryo-/Ultraschall-Kaltnebelmaschine", NamedTextColor.WHITE)),
                        Component.text("Physik: ", NamedTextColor.GRAY).append(Component.text("Schwerkraft-Boden-Flood-Fill (max. 30 Blöcke)", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(24, createGuiItem(
                FixtureType.STAGE_FLAME.iconMaterial(),
                Component.text("DMX Flammenprojektor", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text("Bühnen-Flammenprojektor mit Schutzkäfig", NamedTextColor.WHITE)),
                        Component.text("Optik: ", NamedTextColor.GRAY).append(Component.text("Vertikale Pyro-Flammenstöße + Echtzeit-Licht", NamedTextColor.WHITE)),
                        groupInfoLine,
                        Component.text("Montage: " + mountInfo + " (Yaw: " + yawInfo + ")", NamedTextColor.DARK_GRAY),
                        Component.text("» Klicken zum Platzieren", NamedTextColor.GREEN)
                )
        ));

        // --- Reihe 4 (Slots 28..32 + 34): Physische 3D-DMX-Steuerpulte (Universe-Linkable) ---
        ConsoleModelType[] consoleTypes = ConsoleModelType.values();
        for (int i = 0; i < consoleTypes.length && i < 5; i++) {
            ConsoleModelType ct = consoleTypes[i];
            inv.setItem(28 + i, createGuiItem(
                    ct.iconMaterial(),
                    Component.text("🎛 3D-Pult: " + ct.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                    List.of(
                            Component.text("Modell: ", NamedTextColor.GRAY).append(Component.text(ct.subtitle(), NamedTextColor.WHITE)),
                            Component.text("Funktion: ", NamedTextColor.GRAY).append(Component.text("Interaktives 3D-DMX512-Pult in der Welt", NamedTextColor.AQUA)),
                            universeInfoLine,
                            Component.text("Rechtsklick auf das platzierte Pult öffnet die", NamedTextColor.GRAY),
                            Component.text("512-Kanal DMX-Workstation & Art-Net Steuerung!", NamedTextColor.GRAY),
                            Component.text("» Klicken zum Platzieren (Yaw: " + yawInfo + ")", NamedTextColor.GREEN)
                    )
            ));
        }

        inv.setItem(34, createGuiItem(
                Material.BEACON,
                Component.text("🔗 Pult-Universum: Universum " + (targetUniverse + 1), NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text("Bestimmt, mit welchem DMX512-Universum (1..16)", NamedTextColor.GRAY),
                        Component.text("ein neu platziertes 3D-DMX-Pult verknüpft wird.", NamedTextColor.GRAY),
                        Component.text("Art-Net 4 Port-Address: 0:0:" + targetUniverse, NamedTextColor.AQUA),
                        Component.empty(),
                        Component.text("» Links-/Rechtsklick zum Wechseln (1..16)", NamedTextColor.YELLOW)
                )
        ));

        // --- Reihe 5 (Slots 36..44): Gruppen-, UI- & Lösch-Werkzeuge ---
        inv.setItem(36, createGuiItem(
                targetGroup != null ? targetGroup.iconMaterial() : Material.CYAN_STAINED_GLASS,
                Component.text("Ziel-Gruppe: " + groupName, NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Weist das nächste gespawnte Gerät vorab", NamedTextColor.GRAY),
                        Component.text("dieser SQLite-Gerätegruppe zu!", NamedTextColor.GRAY),
                        Component.text("Aktuell in Gruppe: " + groupMembers + " Geräte", NamedTextColor.WHITE),
                        Component.empty(),
                        Component.text("» Links-/Rechtsklick zum Wechseln der Gruppe", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(38, createGuiItem(
                uiPref == UiPreference.HOLO_UI ? Material.END_CRYSTAL : Material.CHEST,
                Component.text("Steuerpult-UI: " + uiPref.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                        Component.text("Wähle dein bevorzugtes DMX-Steuerpult:", NamedTextColor.GRAY),
                        Component.text("• Großes Chest-UI (54-Slot Inventar)", NamedTextColor.WHITE),
                        Component.text("• 3D Hologramm-UI (via Wand frei platzierbar)", NamedTextColor.AQUA),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(39, createGuiItem(
                useCmd ? Material.NETHER_STAR : Material.IRON_BLOCK,
                Component.text("Modell-Modus: " + (useCmd ? "Resource-Pack (3D-Modelle)" : "Vanilla-Blöcke"),
                        useCmd ? NamedTextColor.GOLD : NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Bestimmt, ob die eigenen ADMX Blockbench-Modelle", NamedTextColor.GRAY),
                        Component.text("oder Vanilla-BlockDisplays verwendet werden.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Umschalten", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(40, createGuiItem(
                Material.COMPARATOR,
                Component.text(uiPref == UiPreference.HOLO_UI
                                ? "✦ 3D Hologramm-UI hier aufstellen"
                                : "✦ Großes Chest-Steuerpult öffnen",
                        NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text(uiPref == UiPreference.HOLO_UI
                                ? "Spawnt das interaktive 3D-Hologramm-Steuerpult"
                                : "Öffnet das 54-Slot DMX-Steuerpult für", NamedTextColor.GRAY),
                        Component.text(uiPref == UiPreference.HOLO_UI
                                ? "exakt an der angeklickten Wand-Position!"
                                : "Gruppen-Ansteuerung & Szenen-Programmierung!", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Ausführen", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(41, createGuiItem(
                Material.REPEATER,
                Component.text("🎚 Pro DMX512 Workstation öffnen (Uni " + (targetUniverse + 1) + ")", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Öffnet die 512-Kanal DMX-Anwendung mit", NamedTextColor.GRAY),
                        Component.text("Live-Fadern, HTP/LTP-Merger & Art-Net 4 Status.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Öffnen", NamedTextColor.GREEN)
                )
        ));

        inv.setItem(43, createGuiItem(
                Material.BARRIER,
                Component.text("Nächstes Gerät / 3D-Pult entfernen", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Entfernt das nächstgelegene ADMX-Gerät oder", NamedTextColor.GRAY),
                        Component.text("3D-DMX-Pult im Umkreis von 3 Blöcken.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Löschen", NamedTextColor.RED)
                )
        ));

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PlacementGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PlacementGuiHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!hasPlaceOrControlPermission(player)) {
            player.closeInventory();
            return;
        }
        if (!checkClickCooldown(player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        UUID playerId = player.getUniqueId();
        boolean useCmd = playerUseCmdPref.getOrDefault(playerId, true);

        if (slot == 34) {
            cycleSelectedUniverse(player, event.isRightClick());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
            openSelectionGui(player);
            return;
        }

        if (slot == 36) {
            cycleSelectedGroup(player, event.isRightClick());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
            openSelectionGui(player);
            return;
        }

        if (slot == 38) {
            toggleUiPreference(player);
            player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1.3f);
            openSelectionGui(player);
            return;
        }

        if (slot == 39) {
            playerUseCmdPref.put(playerId, !useCmd);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
            openSelectionGui(player);
            return;
        }

        if (slot == 41) {
            player.closeInventory();
            plugin.getDmxWorkstationUi().openVirtualWorkstation(player, getSelectedUniverseId(player));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.3f);
            return;
        }

        PendingPlacement pending = pendingPlacements.get(playerId);
        if (pending == null) {
            player.closeInventory();
            return;
        }

        if (slot == 40) {
            player.closeInventory();
            UiPreference pref = getUiPreference(player);
            if (pref == UiPreference.HOLO_UI) {
                plugin.getHoloConsoleUi().spawnAtWandLocation(player, pending.location(), player.getLocation().getYaw());
            } else {
                plugin.getChestConsoleUi().openConsole(player, getSelectedGroupId(player));
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
            }
            return;
        }

        if (slot == 43) {
            if (!player.hasPermission("admx.remove") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung zum Entfernen von Geräten (admx.remove).", NamedTextColor.RED));
                return;
            }
            boolean removedConsole = plugin.removeNearestConsole(pending.location(), 3.0);
            boolean removedFixture = !removedConsole && plugin.removeNearestFixture(pending.location(), 3.0);
            player.closeInventory();
            if (removedConsole || removedFixture) {
                player.sendMessage(Component.text("✔ Das ADMX-" + (removedConsole ? "3D-DMX-Pult" : "Gerät")
                        + " am Zielpunkt wurde aus Welt und Datenbank entfernt.", NamedTextColor.GREEN));
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.7f, 1.2f);
            } else {
                player.sendMessage(Component.text("✘ Kein ADMX-Gerät oder 3D-Pult im Umkreis von 3 Blöcken gefunden.", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            }
            return;
        }

        // Platzierung eines 3D-DMX-Steuerpults (Slots 28..32)
        if (slot >= 28 && slot <= 32) {
            if (!player.hasPermission("admx.place") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung zum Platzieren von DMX-Pulten (admx.place).", NamedTextColor.RED));
                return;
            }
            ConsoleModelType[] types = ConsoleModelType.values();
            int idx = slot - 28;
            if (idx >= 0 && idx < types.length) {
                ConsoleModelType selectedConsole = types[idx];
                String limitErr = plugin.checkConsolePlacementLimits(pending.location().getWorld());
                if (limitErr != null) {
                    player.closeInventory();
                    player.sendMessage(Component.text("✘ " + limitErr, NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                    return;
                }

                int targetUni = getSelectedUniverseId(player);
                PlacedDmxConsole created = plugin.addPlacedConsole(
                        UUID.randomUUID(),
                        selectedConsole,
                        targetUni,
                        pending.location(),
                        pending.mountYaw(),
                        useCmd,
                        player.getUniqueId()
                );
                player.closeInventory();
                player.playSound(pending.location(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
                player.sendMessage(Component.text("✔ 3D-DMX-Pult ", NamedTextColor.GREEN)
                        .append(Component.text(created.modelType().displayName(), NamedTextColor.GOLD))
                        .append(Component.text(" platziert & mit ", NamedTextColor.GREEN))
                        .append(Component.text("Universum " + (created.universeId() + 1) + " (Art-Net 0:0:" + created.universeId() + ")", NamedTextColor.AQUA))
                        .append(Component.text(" verlinkt! (Rechtsklick auf Pult = DMX-Workstation öffnen)", NamedTextColor.GRAY)));
            }
            return;
        }

        FixtureType selectedType = switch (slot) {
            case 10 -> FixtureType.MOVING_HEAD_SPOT;
            case 11 -> FixtureType.MOVING_HEAD_BEAM;
            case 12 -> FixtureType.MOVING_HEAD_WASH;
            case 13 -> FixtureType.LASER_FAN_PROJECTOR;
            case 14 -> FixtureType.LASER_BEAM_PROJECTOR;
            case 15 -> FixtureType.MOVING_LASER_HEAD;
            case 16 -> FixtureType.BLINDER_2LITE;
            case 19 -> FixtureType.STROBE_BAR;
            case 21 -> FixtureType.FOG_MACHINE_HAZER;
            case 22 -> FixtureType.FOG_MACHINE_LOW_FOG;
            case 24 -> FixtureType.STAGE_FLAME;
            default -> null;
        };

        if (selectedType != null) {
            if (!player.hasPermission("admx.place") && !player.hasPermission("admx.admin")) {
                player.sendMessage(Component.text("✘ Keine Berechtigung zum Platzieren von Geräten (admx.place).", NamedTextColor.RED));
                return;
            }
            String limitError = plugin.checkPlacementLimits(pending.location().getWorld(), selectedType);
            if (limitError != null) {
                player.closeInventory();
                player.sendMessage(Component.text("✘ " + limitError, NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                return;
            }

            MovingHeadProfile profile = MovingHeadProfile.defaultBlockbenchModel(useCmd);
            UUID fixtureId = UUID.randomUUID();
            String assignedGroupId = getSelectedGroupId(player);
            FixtureGroup group = plugin.getFixtureGroup(assignedGroupId);
            String groupLabel = (group != null) ? group.displayName() : assignedGroupId;

            AdmxPlugin.PlacedFixture created = plugin.addPlacedFixtureWithGroup(
                    fixtureId,
                    assignedGroupId,
                    pending.location(),
                    pending.mountYaw(),
                    pending.ceilingMounted(),
                    selectedType,
                    profile
            );
            player.closeInventory();
            player.playSound(pending.location(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
            player.sendMessage(Component.text("✔ ", NamedTextColor.GREEN)
                    .append(Component.text(selectedType.displayName(), NamedTextColor.GOLD))
                    .append(Component.text(" in ", NamedTextColor.GREEN))
                    .append(Component.text(groupLabel + " [#" + (created.fixtureOrder() + 1) + "]", NamedTextColor.AQUA))
                    .append(Component.text(" platziert!", NamedTextColor.GREEN)));
        }
    }

    private static ItemStack createGuiItem(Material material, Component title, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(title.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
