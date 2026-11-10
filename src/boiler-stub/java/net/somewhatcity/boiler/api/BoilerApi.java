package net.somewhatcity.boiler.api;

import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

/**
 * Compile-time API-Stub für das optionale Boiler-Plugin (net.somewhatcity.boiler).
 * Ermöglicht reproduzierbare Builds ohne hardcodierte lokale Dateipfade.
 */
public interface BoilerApi {
    ISourceManager sourceManager();
    IDisplayManager displayManager();

    interface ISourceManager {
        void register(String name, Class<?> sourceClass, boolean persistent);
    }

    interface IDisplayManager {
        IBoilerDisplay display(int id);
        IBoilerDisplay createDisplay(Location cornerA, Location cornerB, BlockFace facing, boolean persistent, boolean interactive);
    }
}
