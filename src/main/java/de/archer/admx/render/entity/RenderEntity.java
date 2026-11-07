package de.archer.admx.render.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

/**
 * Deklarative Definition einer Minecraft-Entity im Rendering-Zyklus.
 *
 * @param <T>         Entity-Typ (z. B. BlockDisplay, ItemDisplay, TextDisplay)
 * @param entityClass Klasse des Entity-Typs
 * @param location    Spawn- und Zielposition
 * @param init        Initialisierungs-Logik (nur beim ersten Spawn)
 * @param update      Aktualisierungs-Logik (in jedem Frame/Tick)
 */
public record RenderEntity<T extends Entity>(
        Class<T> entityClass,
        Location location,
        Consumer<T> init,
        Consumer<T> update
) implements RenderItem {

    public RenderEntity(Class<T> entityClass, Location location, Consumer<T> update) {
        this(entityClass, location, entity -> {}, update);
    }

    @Override
    public void submit(Object handle) {
        EntityTracker.submit(handle, this);
    }
}
