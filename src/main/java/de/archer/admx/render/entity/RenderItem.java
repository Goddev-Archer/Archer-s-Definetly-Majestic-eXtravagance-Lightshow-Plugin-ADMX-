package de.archer.admx.render.entity;

/**
 * Basis-Interface für das deklarative Entity-Rendering-System in ADMX.
 * Erlaubt das Einreichen von Entities oder Baumstrukturen pro Tick,
 * die automatisch wiederverwendet oder bei Nichtbenutzung entfernt werden.
 */
public sealed interface RenderItem permits RenderEntity, RenderGroup, EmptyRenderItem {

    /**
     * Reicht dieses Element unter einem eindeutigen Handle (z. B. Fixture-ID + Subteil) ein.
     *
     * @param handle Eindeutiger Schlüssel für das Lifecycle-Tracking
     */
    void submit(Object handle);
}
