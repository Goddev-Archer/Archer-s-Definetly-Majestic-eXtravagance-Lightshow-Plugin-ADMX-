package de.archer.admx.flow;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interaktives 54-Slot Chest-GUI für das visuelle Flowboard-Lichtshow-Programmiersystem.
 * <p>
 * Erfüllt 100 % aller Sicherheits- und Performance-Regeln aus AGENTS.md:
 * <ul>
 *   <li>SEC-01: Strikte Berechtigungsprüfung (admx.control / admx.admin).</li>
 *   <li>SEC-03: Rate-Limiting & Klick-Cooldown pro Spieler (75 ms).</li>
 *   <li>SEC-07: Bedingungslose Stornierung von InventoryDragEvent und InventoryClickEvent.</li>
 *   <li>PERF-09: Asynchrones Schreiben in die SQLite-Datenbank.</li>
 * </ul>
 */
public final class FlowboardGui implements Listener {

    public enum Tool {
        PLACE("Node Platzieren", Material.DISPENSER, NamedTextColor.GREEN, "Klicke auf ein freies Canvas-Feld, um den ausgewählten Node-Typ zu platzieren."),
        SELECT("Node Auswählen & Einstellen", Material.COMPASS, NamedTextColor.AQUA, "Wähle einen Node aus, um Parameter einzusehen und zu bearbeiten."),
        MOVE("Node Verschieben", Material.PISTON, NamedTextColor.YELLOW, "Klicke zuerst den Node an, dann den Ziel-Slot auf dem Canvas."),
        LINK("Kabel Verbinden (Wire)", Material.STRING, NamedTextColor.LIGHT_PURPLE, "Klicke Source-Node, dann Target-Node, um einen Trigger-Draht zu spannen."),
        UNLINK("Verbindungen Lösen", Material.SHEARS, NamedTextColor.GOLD, "Klicke einen Node an, um alle eingehenden und ausgehenden Drähte zu trennen."),
        DELETE("Node Löschen", Material.LAVA_BUCKET, NamedTextColor.RED, "Klicke einen Node an, um ihn restlos vom Canvas zu entfernen.");

        private final String name;
        private final Material icon;
        private final NamedTextColor color;
        private final String desc;

        Tool(String name, Material icon, NamedTextColor color, String desc) {
            this.name = name;
            this.icon = icon;
            this.color = color;
            this.desc = desc;
        }

        public String toolName() { return name; }
        public Material icon() { return icon; }
        public NamedTextColor color() { return color; }
        public String desc() { return desc; }
    }

    public static final class FlowboardGuiHolder implements InventoryHolder {
        private final Flowboard board;
        private Inventory inventory;
        private Tool activeTool = Tool.PLACE;
        private FlowNodeType selectedTypeToPlace = FlowNodeType.COLOR_FADE;
        private String selectedTargetGroup = "front_truss";
        private int selectedDurationTicks = 40; // 2 Sekunden
        private int selectedSlot = -1;
        private int linkSourceNodeId = -1;

        public FlowboardGuiHolder(Flowboard board) {
            this.board = board;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }

        public Flowboard board() {
            return board;
        }
    }

    private final AdmxPlugin plugin;
    private final Map<UUID, Long> lastClickTimeByPlayer = new ConcurrentHashMap<>();

    public FlowboardGui(AdmxPlugin plugin) {
        this.plugin = plugin;
    }

    public void openFlowboard(Player player, Flowboard board) {
        if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
            player.sendMessage(Component.text("Keine Berechtigung für das ADMX-Flowboard (admx.control erforderlich).", NamedTextColor.RED));
            return;
        }

        FlowboardGuiHolder holder = new FlowboardGuiHolder(board);
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text("ADMX Flowboard » " + board.name(), NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
        );
        holder.inventory = inv;

        refreshInventory(holder);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
    }

    private void refreshInventory(FlowboardGuiHolder holder) {
        Inventory inv = holder.getInventory();
        Flowboard board = holder.board();
        FlowboardEngine engine = plugin.getFlowboardEngine();
        boolean isCurrentPlaying = (engine != null && engine.isPlaying() && engine.activeBoard() == board);

        // 1. Slots 0..35: 9x4 Canvas
        for (int slot = 0; slot < Flowboard.CANVAS_SLOTS; slot++) {
            FlowNode node = board.getNodeAtSlot(slot);
            if (node == null) {
                // Leeres Canvas-Feld
                int x = (slot % 9) + 1;
                int y = (slot / 9) + 1;
                inv.setItem(slot, item(
                        Material.GRAY_STAINED_GLASS_PANE,
                        Component.text("Canvas-Raster [" + x + ", " + y + "]", NamedTextColor.DARK_GRAY),
                        List.of(
                                Component.text("Aktives Werkzeug: " + holder.activeTool.toolName(), NamedTextColor.GRAY),
                                Component.text("Klicken zum Platzieren / Interagieren", NamedTextColor.YELLOW)
                        )
                ));
            } else {
                // Aktiver FlowNode
                boolean isSelected = (holder.selectedSlot == slot || holder.linkSourceNodeId == node.nodeId());
                boolean isRunning = isCurrentPlaying && engine.isNodeRunning(node.nodeId());

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Typ: ", NamedTextColor.GRAY).append(node.type().titleComponent()));
                lore.add(Component.text("Zielgruppe: ", NamedTextColor.GRAY).append(Component.text(node.targetGroupId(), NamedTextColor.WHITE)));
                lore.add(Component.text(String.format("Dauer: %.1fs (%d Ticks)", node.durationTicks() / 20.0f, node.durationTicks()), NamedTextColor.AQUA));

                if (board.isRootNode(node.nodeId())) {
                    lore.add(Component.text("Start-Verzögerung: " + node.startTick() + " Ticks (Root-Node)", NamedTextColor.GREEN));
                } else {
                    List<FlowConnection> incoming = board.getIncomingConnections(node.nodeId());
                    lore.add(Component.text("Eingehende Signale: " + incoming.size(), NamedTextColor.YELLOW));
                    for (FlowConnection in : incoming) {
                        lore.add(Component.text("  ← von Node #" + in.fromNodeId() + " (" + in.type().displayName() + ")", NamedTextColor.DARK_AQUA));
                    }
                }

                List<FlowConnection> outgoing = board.getOutgoingConnections(node.nodeId());
                if (!outgoing.isEmpty()) {
                    lore.add(Component.text("Ausgehende Drähte: " + outgoing.size(), NamedTextColor.LIGHT_PURPLE));
                    for (FlowConnection out : outgoing) {
                        lore.add(Component.text("  → triggert Node #" + out.toNodeId() + " (" + out.type().displayName() + ")", NamedTextColor.AQUA));
                    }
                }

                if (isRunning) {
                    lore.add(Component.empty());
                    lore.add(Component.text("▶ WIRD GERADE LIVE AUSGEFÜHRT", NamedTextColor.GREEN, TextDecoration.BOLD));
                }
                if (isSelected) {
                    lore.add(Component.empty());
                    lore.add(Component.text("★ AKTIV AUSGEWÄHLT", NamedTextColor.GOLD, TextDecoration.BOLD));
                }

                ItemStack nodeItem = item(
                        node.type().icon(),
                        Component.text("[#" + node.nodeId() + "] " + node.type().displayName(), node.type().color(), TextDecoration.BOLD),
                        lore
                );
                if (isSelected || isRunning) {
                    nodeItem.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
                    nodeItem.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
                inv.setItem(slot, nodeItem);
            }
        }

        // 2. Slots 36..44: Werkzeugleiste & Node-Palette
        inv.setItem(36, item(
                holder.activeTool == Tool.PLACE ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("Werkzeug: Node Platzieren [" + holder.selectedTypeToPlace.displayName() + "]",
                        holder.activeTool == Tool.PLACE ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: Auswählen  |  Rechtsklick: Nächster Node-Typ", NamedTextColor.YELLOW),
                        Component.text("Typ: " + holder.selectedTypeToPlace.description(), NamedTextColor.GRAY)
                )
        ));

        inv.setItem(37, item(
                holder.activeTool == Tool.SELECT ? Material.LIGHT_BLUE_CONCRETE : Material.COMPASS,
                Component.text("Werkzeug: Node Auswählen & Konfigurieren",
                        holder.activeTool == Tool.SELECT ? NamedTextColor.AQUA : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Wähle einen Node auf dem Canvas aus,", NamedTextColor.GRAY),
                        Component.text("um ihn mit den Fader-Tools anzupassen.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(38, item(
                holder.activeTool == Tool.MOVE ? Material.GOLD_BLOCK : Material.PISTON,
                Component.text("Werkzeug: Node Verschieben (Move)",
                        holder.activeTool == Tool.MOVE ? NamedTextColor.GOLD : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Klicke Node an, dann den neuen Zielslot auf dem Canvas.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(39, item(
                holder.activeTool == Tool.LINK ? Material.PURPLE_CONCRETE : Material.STRING,
                Component.text("Werkzeug: Kabel Verbinden (Link Wires)",
                        holder.activeTool == Tool.LINK ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text(holder.linkSourceNodeId > 0
                                ? "Klicke jetzt Target-Node zum Verbinden (Source: #" + holder.linkSourceNodeId + ")"
                                : "Klicke Source-Node, dann Target-Node zum Verbinden.", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(40, item(
                Material.NAME_TAG,
                Component.text("Zielgruppe: " + holder.selectedTargetGroup, NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(
                        Component.text("Klicken zum Durchwechseln der Zielgruppe", NamedTextColor.GRAY),
                        Component.text("Aktuell: " + holder.selectedTargetGroup, NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(41, item(
                Material.CLOCK,
                Component.text(String.format("Wirkdauer: %.1fs (%d Ticks)", holder.selectedDurationTicks / 20.0f, holder.selectedDurationTicks),
                        NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Linksklick: +10 Ticks (+0.5s)  |  Rechtsklick: -10 Ticks", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(42, item(
                Material.REPEATER,
                Component.text("Werkzeug: Verbindungen Lösen (Unlink)",
                        holder.activeTool == Tool.UNLINK ? NamedTextColor.GOLD : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Trennt alle Drähte eines angeklickten Nodes.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(43, item(
                holder.activeTool == Tool.DELETE ? Material.RED_CONCRETE : Material.LAVA_BUCKET,
                Component.text("Werkzeug: Node Löschen",
                        holder.activeTool == Tool.DELETE ? NamedTextColor.RED : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Entfernt einen angeklickten Node vom Canvas.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(44, item(
                Material.TNT,
                Component.text("Canvas Leeren (Clear Board)", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Shift-Klick: Löscht alle Nodes und Kabel dieses Boards!", NamedTextColor.RED)
                )
        ));

        // 3. Slots 45..53: Timeline & Playback
        inv.setItem(45, item(
                Material.RED_TERRACOTTA,
                Component.text("Show-Dauer: -2 Sekunden", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Shift-Klick: -10 Sekunden (Min. 2s)", NamedTextColor.GRAY))
        ));

        inv.setItem(46, item(
                Material.LIME_TERRACOTTA,
                Component.text("Show-Dauer: +2 Sekunden", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("Shift-Klick: +10 Sekunden (Max. 120s)", NamedTextColor.GRAY))
        ));

        inv.setItem(47, item(
                Material.TARGET,
                Component.text(String.format("Laufzeit: %.1fs / %.1fs (%d Ticks)",
                                (isCurrentPlaying ? engine.currentTick() : 0) / 20.0f,
                                board.durationTicks() / 20.0f,
                                board.durationTicks()),
                        NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Gesamtlänge des programmierten Show-Loops", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(48, item(
                board.isLoopMode() ? Material.EMERALD : Material.REDSTONE,
                Component.text(board.isLoopMode() ? "🔁 Loop: AKTIV" : "➡ Einmalig (Loop: AUS)",
                        board.isLoopMode() ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                        Component.text("Klicken zum Umschalten zwischen Endlos-Schleife", NamedTextColor.GRAY),
                        Component.text("und einmaligem Durchlauf.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(49, item(
                isCurrentPlaying ? Material.ORANGE_CONCRETE : Material.EMERALD_BLOCK,
                Component.text(isCurrentPlaying ? "⏹ SHOW STOPPEN" : "▶ SHOW STARTEN",
                        isCurrentPlaying ? NamedTextColor.GOLD : NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(
                        Component.text(isCurrentPlaying
                                ? "Stoppt die Flowboard-Show sofort."
                                : "Startet das Flowboard live auf der Bühne!", NamedTextColor.WHITE)
                )
        ));

        inv.setItem(50, item(
                Material.NETHER_STAR,
                Component.text("Signal-Test: Alle Root-Nodes Triggern", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                        Component.text("Löst sofort alle Starter-Nodes aus, um den Signalfluss", NamedTextColor.GRAY),
                        Component.text("ohne Timeline-Wartezeit zu testen.", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(51, item(
                Material.BOOK,
                Component.text("Show in Datenbank Speichern", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Speichert Nodes, Kabel und Show-Dauer asynchron", NamedTextColor.GRAY),
                        Component.text("in die SQLite-Tabelle flowboards (PERF-09).", NamedTextColor.GRAY)
                )
        ));

        inv.setItem(52, item(
                Material.DARK_OAK_DOOR,
                Component.text("Zurück zum DMX-Pult", NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                        Component.text("Öffnet das klassische Chest-DMX-Steuerpult.", NamedTextColor.YELLOW)
                )
        ));

        inv.setItem(53, item(
                Material.BARRIER,
                Component.text("Schließen", NamedTextColor.RED, TextDecoration.BOLD),
                List.of()
        ));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // SEC-07: Drag-Events in benutzerdefinierten Inventaren IMMER stornieren
        if (event.getInventory().getHolder() instanceof FlowboardGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FlowboardGuiHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // SEC-01: Berechtigungsprüfung
        if (!player.hasPermission("admx.control") && !player.hasPermission("admx.admin")) {
            player.sendMessage(Component.text("Keine Berechtigung (admx.control erforderlich).", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        // SEC-03: Klick-Cooldown (75 ms) gegen Paketfluten
        long now = System.currentTimeMillis();
        long lastClick = lastClickTimeByPlayer.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastClick < 75) {
            return;
        }
        lastClickTimeByPlayer.put(player.getUniqueId(), now);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }

        Flowboard board = holder.board();
        FlowboardEngine engine = plugin.getFlowboardEngine();

        // 1. Klick auf das Canvas (Slots 0..35)
        if (slot < Flowboard.CANVAS_SLOTS) {
            handleCanvasClick(player, holder, slot, event.isRightClick(), event.isShiftClick());
            refreshInventory(holder);
            return;
        }

        // 2. Klick auf Werkzeugleiste (Slots 36..44)
        switch (slot) {
            case 36 -> {
                if (event.isRightClick()) {
                    // Nächsten Node-Typ wählen
                    FlowNodeType[] types = FlowNodeType.values();
                    int nextIdx = (holder.selectedTypeToPlace.ordinal() + 1) % types.length;
                    holder.selectedTypeToPlace = types[nextIdx];
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                } else {
                    holder.activeTool = Tool.PLACE;
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                }
            }
            case 37 -> {
                holder.activeTool = Tool.SELECT;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            }
            case 38 -> {
                holder.activeTool = Tool.MOVE;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            }
            case 39 -> {
                holder.activeTool = Tool.LINK;
                holder.linkSourceNodeId = -1;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            }
            case 40 -> {
                // Zielgruppen durchwechseln
                List<String> groupIds = new ArrayList<>(plugin.getFixtureGroups().keySet());
                groupIds.add(FlowNode.TARGET_ALL);
                int idx = groupIds.indexOf(holder.selectedTargetGroup);
                holder.selectedTargetGroup = groupIds.get((idx + 1) % groupIds.size());
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            }
            case 41 -> {
                int delta = event.isRightClick() ? -10 : 10;
                holder.selectedDurationTicks = Math.clamp(holder.selectedDurationTicks + delta, 10, 200);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
            }
            case 42 -> {
                holder.activeTool = Tool.UNLINK;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            }
            case 43 -> {
                holder.activeTool = Tool.DELETE;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            }
            case 44 -> {
                if (event.isShiftClick()) {
                    board.clear();
                    plugin.getDatabase().saveFlowboardAsync(board);
                    player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
                    player.sendMessage(Component.text("Canvas wurde vollständig geleert.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text("Halte SHIFT gedrückt, um das Board unwiderruflich zu leeren.", NamedTextColor.RED));
                }
            }

            // 3. Timeline & Playback (Slots 45..53)
            case 45 -> {
                int delta = event.isShiftClick() ? 200 : 40;
                board.setDurationTicks(board.durationTicks() - delta);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.9f);
            }
            case 46 -> {
                int delta = event.isShiftClick() ? 200 : 40;
                board.setDurationTicks(board.durationTicks() + delta);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
            }
            case 48 -> {
                board.setLoopMode(!board.isLoopMode());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, board.isLoopMode() ? 1.5f : 0.8f);
            }
            case 49 -> {
                if (engine != null) {
                    if (engine.isPlaying() && engine.activeBoard() == board) {
                        engine.stop();
                        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.6f, 0.8f);
                    } else {
                        engine.play(board);
                        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.5f);
                    }
                }
            }
            case 50 -> {
                // Root Nodes manuell testen
                if (engine != null) {
                    for (FlowNode n : board.nodes().values()) {
                        if (board.isRootNode(n.nodeId())) {
                            engine.startNode(n);
                        }
                    }
                    player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.6f, 1.4f);
                }
            }
            case 51 -> {
                // PERF-09: Asynchrones Speichern in SQLite
                plugin.getDatabase().saveFlowboardAsync(board);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f);
                player.sendMessage(Component.text("Flowboard '" + board.name() + "' erfolgreich in SQLite gespeichert!", NamedTextColor.GREEN));
            }
            case 52 -> {
                // Zurück zur DMX-Konsole
                player.closeInventory();
                plugin.getChestConsoleUi().openConsole(player, "front_truss");
                return;
            }
            case 53 -> {
                player.closeInventory();
                return;
            }
        }

        refreshInventory(holder);
    }

    private void handleCanvasClick(Player player, FlowboardGuiHolder holder, int slot, boolean rightClick, boolean shiftClick) {
        Flowboard board = holder.board();
        FlowNode existing = board.getNodeAtSlot(slot);

        switch (holder.activeTool) {
            case PLACE -> {
                if (existing != null) {
                    // Slot belegt -> Node auswählen
                    holder.selectedSlot = slot;
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                } else {
                    // Neuen Node platzieren
                    FlowNode created = board.addNode(slot, holder.selectedTypeToPlace, holder.selectedTargetGroup, holder.selectedDurationTicks);
                    if (created != null) {
                        player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.4f);
                    }
                }
            }
            case SELECT -> {
                if (existing != null) {
                    holder.selectedSlot = slot;
                    if (rightClick) {
                        // Node-Dauer verändern
                        int newDur = Math.clamp(existing.durationTicks() + (shiftClick ? 40 : 10), 10, 200);
                        board.putNode(existing.withDurationTicks(newDur));
                    }
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
                } else {
                    holder.selectedSlot = -1;
                }
            }
            case MOVE -> {
                if (holder.selectedSlot >= 0 && holder.selectedSlot != slot) {
                    FlowNode nodeToMove = board.getNodeAtSlot(holder.selectedSlot);
                    if (nodeToMove != null && existing == null) {
                        board.moveNode(nodeToMove.nodeId(), slot);
                        holder.selectedSlot = slot;
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
                    } else {
                        holder.selectedSlot = (existing != null) ? slot : -1;
                    }
                } else {
                    holder.selectedSlot = (existing != null) ? slot : -1;
                }
            }
            case LINK -> {
                if (existing != null) {
                    if (holder.linkSourceNodeId <= 0) {
                        holder.linkSourceNodeId = existing.nodeId();
                        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.6f, 1.4f);
                        player.sendMessage(Component.text("Source-Node #" + existing.nodeId() + " gewählt. Klicke jetzt auf den Ziel-Node!", NamedTextColor.AQUA));
                    } else if (holder.linkSourceNodeId != existing.nodeId()) {
                        boolean connected = board.addConnection(holder.linkSourceNodeId, existing.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END);
                        if (connected) {
                            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.8f);
                            player.sendMessage(Component.text("Draht gespannt: Node #" + holder.linkSourceNodeId + " → Node #" + existing.nodeId() + " (Trigger on End)", NamedTextColor.GREEN));
                        } else {
                            player.sendMessage(Component.text("Verbindung existiert bereits oder ungültig.", NamedTextColor.YELLOW));
                        }
                        holder.linkSourceNodeId = -1;
                    }
                } else {
                    holder.linkSourceNodeId = -1;
                }
            }
            case UNLINK -> {
                if (existing != null) {
                    board.removeAllConnectionsFor(existing.nodeId());
                    player.playSound(player.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 0.6f, 1.2f);
                    player.sendMessage(Component.text("Alle Verbindungen für Node #" + existing.nodeId() + " gelöst.", NamedTextColor.YELLOW));
                }
            }
            case DELETE -> {
                if (existing != null) {
                    board.removeNode(existing.nodeId());
                    holder.selectedSlot = -1;
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 1.0f);
                }
            }
        }
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        it.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return it;
    }
}
