package de.archer.admx.fx;

import de.archer.admx.AdmxPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handheld Cryo-Bazooka Manager:
 * Ermöglicht DJs und Performern das Abfeuern eines gerichteten 50-Bar CO2-Hochdruckblasts
 * mit Rückstoß, zischendem Cryo-Sound, Nozzle-LEDs und physikalischem Crowd-Air-Blast.
 */
public final class CryoBazookaManager implements Listener {

    public enum LedColor {
        ICE_WHITE("Kaltweiß (Ice White)", Color.fromRGB(240, 248, 255), NamedTextColor.WHITE),
        CRYO_CYAN("Cryo-Cyan (Electric)", Color.fromRGB(0, 255, 255), NamedTextColor.AQUA),
        NEON_MAGENTA("Neon-Magenta (Laser)", Color.fromRGB(255, 20, 147), NamedTextColor.LIGHT_PURPLE),
        TOXIC_GREEN("Toxic-Green (Acid)", Color.fromRGB(0, 255, 102), NamedTextColor.GREEN),
        UV_VIOLET("UV-Violett (Blacklight)", Color.fromRGB(148, 0, 211), NamedTextColor.DARK_PURPLE);

        public final String displayName;
        public final Color bukkitColor;
        public final NamedTextColor textColor;

        LedColor(String displayName, Color bukkitColor, NamedTextColor textColor) {
            this.displayName = displayName;
            this.bukkitColor = bukkitColor;
            this.textColor = textColor;
        }

        public static LedColor fromIndex(int idx) {
            LedColor[] vals = values();
            return vals[Math.floorMod(idx, vals.length)];
        }
    }

    private static final long BLAST_COOLDOWN_MS = 140L;

    private final AdmxPlugin plugin;
    private final NamespacedKey bazookaKey;
    private final NamespacedKey ledColorKey;
    private final Map<UUID, Long> lastBlastTimeMs = new ConcurrentHashMap<>();

    public CryoBazookaManager(AdmxPlugin plugin) {
        this.plugin = plugin;
        this.bazookaKey = new NamespacedKey(plugin, "cryo_bazooka");
        this.ledColorKey = new NamespacedKey(plugin, "cryo_led_color");
    }

    /**
     * Erstellt das Handheld Cryo-Bazooka Item für DJs und Performer.
     */
    public ItemStack createCryoBazooka() {
        ItemStack item = new ItemStack(Material.WARPED_FUNGUS_ON_A_STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✦ ADMX Handheld Cryo-Bazooka ✦", NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.empty(),
                    Component.text("💨 50-Bar High-Pressure CO2 Cannon", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Rechtsklick:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text(" » Feuert einen massiven 14-Block Cryo-Blast ab!", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Sneak + Rechtsklick:", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                    Component.text(" » Wechselt die Nozzle-LED Farbe (Weiß, Cyan, Magenta, Toxic, UV)", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Reichweite: ~14 Blöcke | Rückstoß & Air-Blast aktiv", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(bazookaKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(ledColorKey, PersistentDataType.INTEGER, 0);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Prüft, ob ein gegebener ItemStack eine autorisierte Cryo-Bazooka ist.
     */
    public boolean isCryoBazooka(ItemStack item) {
        if (item == null || item.getType() != Material.WARPED_FUNGUS_ON_A_STICK) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bazookaKey, PersistentDataType.BYTE);
    }

    public LedColor getLedColor(ItemStack item) {
        if (item == null) return LedColor.ICE_WHITE;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return LedColor.ICE_WHITE;
        int idx = meta.getPersistentDataContainer().getOrDefault(ledColorKey, PersistentDataType.INTEGER, 0);
        return LedColor.fromIndex(idx);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!isCryoBazooka(item)) return;

        Player player = event.getPlayer();

        // Strikte Permissions-Prüfung (SEC-01)
        if (!player.hasPermission("admx.fx.co2") && !player.hasPermission("admx.admin")) {
            event.setCancelled(true);
            player.sendMessage(Component.text("✘ Du hast keine Berechtigung (admx.fx.co2 / admx.admin), die Cryo-Bazooka abzufeuern.", NamedTextColor.RED));
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);

        // Sneak + Rechtsklick: Nozzle-LED Farbe umschalten
        if (player.isSneaking()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                int curColor = meta.getPersistentDataContainer().getOrDefault(ledColorKey, PersistentDataType.INTEGER, 0);
                int nextColor = (curColor + 1) % LedColor.values().length;
                meta.getPersistentDataContainer().set(ledColorKey, PersistentDataType.INTEGER, nextColor);
                item.setItemMeta(meta);

                LedColor led = LedColor.fromIndex(nextColor);
                player.sendActionBar(Component.text("💨 Nozzle-LED: ", NamedTextColor.GRAY)
                        .append(Component.text(led.displayName, led.textColor)));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.8f);
            }
            return;
        }

        // Rate-Limiting gegen Packet-/Partikel-Flooding (SEC-03)
        long now = System.currentTimeMillis();
        Long last = lastBlastTimeMs.get(player.getUniqueId());
        if (last != null && (now - last) < BLAST_COOLDOWN_MS) {
            return;
        }
        lastBlastTimeMs.put(player.getUniqueId(), now);

        fireCryoBlast(player, item);
    }

    /**
     * Feuert die konische Hochdruck-Cryo-Säule in Blickrichtung des Spielers ab.
     */
    public void fireCryoBlast(Player player, ItemStack item) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();
        LedColor led = getLedColor(item);
        Particle.DustOptions dust = new Particle.DustOptions(led.bukkitColor, 1.8f);

        // 1. Authentisches Sound-Layering (Hochdruck-Zischen + Sub-Air-Puff)
        world.playSound(eye, Sound.BLOCK_FIRE_EXTINGUISH, 1.35f, 0.72f);
        world.playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.38f, 1.90f);
        world.playSound(eye, Sound.ITEM_BUCKET_EMPTY_LAVA, 0.65f, 1.50f);

        // 2. Haptischer Rückstoß-Impuls auf den Schützen
        player.setVelocity(player.getVelocity().add(dir.clone().multiply(-0.16).setY(-0.02)));

        // 3. Konische Strahl-Traversierung (bis zu 14 Blöcke weit)
        int maxSteps = 16;
        double stepDist = 0.85;

        for (int step = 1; step <= maxSteps; step++) {
            double dist = step * stepDist;
            Location point = eye.clone().add(dir.clone().multiply(dist));

            // Wandkollision stoppt den Strahl und erzeugt dichte Impact-Wolke
            if (point.getBlock().getType().isSolid()) {
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, point, 8, 0.4, 0.4, 0.4, 0.04);
                world.spawnParticle(Particle.CLOUD, point, 6, 0.3, 0.3, 0.3, 0.05);
                world.spawnParticle(Particle.SNOWFLAKE, point, 5, 0.3, 0.3, 0.3, 0.08);
                break;
            }

            // Konische Aufweitung: An der Düse ca. 0.25 Blöcke, am Ende ca. 2.4 Blöcke
            double spreadRadius = 0.25 + (dist / 14.0) * 2.2;

            // Partikel-Mix: Dichter weißer Kern + expandierende Wolke + Eiskristalle + Nozzle-Farbe
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, point, 2, spreadRadius * 0.35, spreadRadius * 0.35, spreadRadius * 0.35, 0.02);
            world.spawnParticle(Particle.CLOUD, point, 3, spreadRadius * 0.55, spreadRadius * 0.55, spreadRadius * 0.55, 0.03);
            world.spawnParticle(Particle.SNOWFLAKE, point, 2, spreadRadius * 0.50, spreadRadius * 0.50, spreadRadius * 0.50, 0.05);
            world.spawnParticle(Particle.DUST, point, 3, spreadRadius * 0.40, spreadRadius * 0.40, spreadRadius * 0.40, dust);
        }

        // 4. Physischer Crowd-Air-Blast & Frost-Screen für getroffene Wesen im Strahlkegel
        Location searchCenter = eye.clone().add(dir.clone().multiply(6.5));
        Collection<Entity> nearby = world.getNearbyEntities(searchCenter, 7.5, 7.5, 7.5);

        for (Entity target : nearby) {
            if (target.equals(player)) continue;

            Vector toTarget = target.getLocation().add(0, 1.0, 0).subtract(eye).toVector();
            double distToTarget = toTarget.length();

            if (distToTarget > 0.6 && distToTarget <= 14.0) {
                double angleDeg = Math.toDegrees(toTarget.angle(dir));
                if (angleDeg < 22.0) { // Innerhalb des 22-Grad Blast-Kegels
                    double pushStrength = Math.max(0.10, 0.38 * (1.0 - distToTarget / 15.0));
                    target.setVelocity(target.getVelocity().add(dir.clone().multiply(pushStrength).setY(0.12)));

                    // Frost-Overlay (Powder Snow Vereisungseffekt)
                    target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(), target.getFreezeTicks() + 80));
                    world.spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1.0, 0), 4, 0.2, 0.4, 0.2, 0.02);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastBlastTimeMs.remove(event.getPlayer().getUniqueId());
    }
}
