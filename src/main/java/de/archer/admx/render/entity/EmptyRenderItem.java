package de.archer.admx.render.entity;

/**
 * No-Op Render-Item für unsichtbare oder inaktive Fixture-Teile.
 */
public final class EmptyRenderItem implements RenderItem {

    public static final EmptyRenderItem INSTANCE = new EmptyRenderItem();

    private EmptyRenderItem() {
    }

    @Override
    public void submit(Object handle) {
        // Keine Operation
    }
}
