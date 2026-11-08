package de.archer.admx.boiler;

import com.google.gson.JsonObject;
import de.archer.admx.AdmxPlugin;
import net.somewhatcity.boiler.api.BoilerApi;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Verbindet ADMX optional (Soft-Dependency) mit der {@link BoilerApi} (`C:\dev\Boiler\api`),
 * registriert die interaktive Desktop-UI-Quelle {@code admx-dmx} ({@link AdmxBoilerDesktopSource})
 * und ermöglicht das direkte Aufstellen oder Umschalten von Boiler-Displays in der Welt.
 */
public final class AdmxBoilerIntegration {

    public static final String SOURCE_NAME = "admx-dmx";
    public static final String SOURCE_ALIAS = "admx";

    private static volatile AdmxPlugin pluginInstance;
    private static volatile boolean registered = false;

    private AdmxBoilerIntegration() {
    }

    public static AdmxPlugin getPlugin() {
        return pluginInstance;
    }

    public static boolean isBoilerAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Boiler") && getBoilerApi() != null;
    }

    private static BoilerApi getBoilerApi() {
        try {
            RegisteredServiceProvider<BoilerApi> rsp = Bukkit.getServicesManager().getRegistration(BoilerApi.class);
            return rsp != null ? rsp.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Registriert {@link AdmxBoilerDesktopSource} beim {@code ISourceManager} von Boiler.
     */
    public static void initialize(AdmxPlugin plugin) {
        pluginInstance = plugin;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Boiler")) {
                return;
            }
            BoilerApi api = getBoilerApi();
            if (api == null || api.sourceManager() == null) {
                return;
            }
            api.sourceManager().register(SOURCE_NAME, AdmxBoilerDesktopSource.class, true);
            api.sourceManager().register(SOURCE_ALIAS, AdmxBoilerDesktopSource.class, false);
            registered = true;
            plugin.getLogger().info("[ADMX-Boiler] Desktop-UI Quelle 'admx-dmx' erfolgreich in Boiler registriert!");
        } catch (Throwable t) {
            plugin.getLogger().warning("[ADMX-Boiler] Konnte Boiler-API nicht einbinden: " + t.getMessage());
        }
    }

    public static boolean isRegistered() {
        return registered && isBoilerAvailable();
    }

    /**
     * Schaltet ein bestehendes Boiler-Display (über seine ID) auf das ADMX Pro Desktop-UI um.
     */
    public static boolean activateOnDisplay(int displayId, int universeId) {
        try {
            BoilerApi api = getBoilerApi();
            if (api == null) return false;
            IBoilerDisplay display = api.displayManager().display(displayId);
            if (display == null) return false;

            JsonObject data = new JsonObject();
            data.addProperty("universeId", universeId);
            data.addProperty("faderBank", 0);
            display.source(SOURCE_NAME, data);
            display.save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Erstellt automatisch ein neues Widescreen Boiler-Display (z.B. 4x2 Blöcke) vor dem Spieler
     * und lädt sofort das interaktive ADMX Desktop-UI ({@code admx-dmx}) darauf.
     *
     * @return Die ID des erstellten Boiler-Displays oder {@code -1} bei einem Fehler.
     */
    public static int spawnDesktopDisplayInFrontOfPlayer(Player player, int widthBlocks, int heightBlocks, int universeId) {
        try {
            BoilerApi api = getBoilerApi();
            if (api == null) return -1;

            int w = Math.clamp(widthBlocks, 2, 8);
            int h = Math.clamp(heightBlocks, 2, 5);

            // Bestimme Blickrichtung des Spielers; das Display schaut den Spieler an (Opposite Face)
            BlockFace lookDir = getCardinalDirection(player.getLocation().getYaw());
            BlockFace displayFacing = lookDir.getOppositeFace();

            Location baseCenter = player.getLocation().getBlock().getRelative(lookDir, 3).getLocation();
            baseCenter.setY(Math.floor(player.getLocation().getY()) + 1);

            // Berechne die rechte Nachbar-Richtung (von Spieler-Sicht aus) für die Breite
            BlockFace rightDir = switch (lookDir) {
                case NORTH -> BlockFace.EAST;
                case SOUTH -> BlockFace.WEST;
                case EAST -> BlockFace.SOUTH;
                case WEST -> BlockFace.NORTH;
                default -> BlockFace.EAST;
            };

            int leftOffset = -(w / 2);
            int rightOffset = leftOffset + w - 1;

            Location cornerA = baseCenter.clone().add(
                    rightDir.getModX() * leftOffset,
                    0,
                    rightDir.getModZ() * leftOffset
            );
            Location cornerB = baseCenter.clone().add(
                    rightDir.getModX() * rightOffset,
                    h - 1,
                    rightDir.getModZ() * rightOffset
            );

            IBoilerDisplay created = api.displayManager().createDisplay(cornerA, cornerB, displayFacing, true, true);
            if (created == null) {
                return -1;
            }

            JsonObject data = new JsonObject();
            data.addProperty("universeId", Math.clamp(universeId, 0, 15));
            data.addProperty("faderBank", 0);
            created.source(SOURCE_NAME, data);
            created.save();
            return created.id();
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[ADMX-Boiler] Fehler beim Erstellen des Desktop-Displays: " + t.getMessage());
            return -1;
        }
    }

    private static BlockFace getCardinalDirection(float yaw) {
        float rot = (yaw % 360.0f + 360.0f) % 360.0f;
        if (rot >= 315.0f || rot < 45.0f) {
            return BlockFace.SOUTH;
        } else if (rot < 135.0f) {
            return BlockFace.WEST;
        } else if (rot < 225.0f) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }
}
