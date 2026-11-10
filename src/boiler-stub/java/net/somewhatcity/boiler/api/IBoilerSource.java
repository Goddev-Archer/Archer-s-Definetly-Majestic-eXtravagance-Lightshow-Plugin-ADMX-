package net.somewhatcity.boiler.api;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;
import net.somewhatcity.boiler.api.util.DragPhase;
import org.bukkit.command.CommandSender;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;

/**
 * Compile-time Quell-Interface-Stub für interaktive Boiler-Display-Quellen.
 */
public interface IBoilerSource {
    void load(IBoilerDisplay display, JsonObject data);
    void unload();
    default void onCursorMove(CommandSender sender, int x, int y) {}
    default void onClick(CommandSender sender, int x, int y, boolean right) {}
    default void onDrag(CommandSender sender, int x, int y, boolean right, DragPhase phase) {}
    default void onScroll(CommandSender sender, int x, int y, int delta) {}
    default void onKey(CommandSender sender, String key) {}
    default void draw(Graphics2D rawGraphics, Rectangle viewport) {}
    default Map<String, CommandArgumentType> command() {
        return Map.of();
    }
}
