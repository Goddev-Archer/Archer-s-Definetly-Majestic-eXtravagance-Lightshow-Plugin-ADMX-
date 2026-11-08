package de.archer.admx.boiler;

import de.archer.admx.boiler.plasma.FlowboardStudioWindow;
import de.archer.admx.boiler.plasma.PlasmaWindow;
import de.archer.admx.boiler.plasma.PlasmaWindowManager;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class PlasmaDesktopAndFlowboardTest {

    @Test
    void testPlasmaWindowManagerAndWindowStacking() {
        PlasmaWindowManager wm = new PlasmaWindowManager();

        assertNotNull(wm.flowboardWindow());
        assertNotNull(wm.mixerWindow());
        assertNotNull(wm.autoStudioWindow());
        assertNotNull(wm.s2lWindow());

        // Flowboard sollte initial im Vordergrund sein
        assertEquals("Node Flowboard Studio", wm.flowboardWindow().title());
        assertTrue(wm.flowboardWindow().isVisible());
        assertFalse(wm.flowboardWindow().isMinimized());

        // Mixer nach vorne bringen
        wm.bringToFront(wm.mixerWindow());
        assertTrue(wm.mixerWindow().isVisible());
        assertFalse(wm.mixerWindow().isMinimized());

        // Maximieren testen
        wm.mixerWindow().toggleMaximize(PlasmaWindowManager.WORKSPACE_WIDTH, PlasmaWindowManager.WORKSPACE_HEIGHT);
        assertTrue(wm.mixerWindow().isMaximized());
        assertEquals(0, wm.mixerWindow().x());
        assertEquals(0, wm.mixerWindow().y());
        assertEquals(PlasmaWindowManager.WORKSPACE_WIDTH, wm.mixerWindow().width());
        assertEquals(PlasmaWindowManager.WORKSPACE_HEIGHT, wm.mixerWindow().height());

        // Wiederherstellen
        wm.mixerWindow().toggleMaximize(PlasmaWindowManager.WORKSPACE_WIDTH, PlasmaWindowManager.WORKSPACE_HEIGHT);
        assertFalse(wm.mixerWindow().isMaximized());

        // Minimieren
        wm.mixerWindow().setMinimized(true);
        assertTrue(wm.mixerWindow().isMinimized());
    }

    @Test
    void testFlowboardDefaultSongFlowAndPins() {
        FlowboardStudioWindow flow = new FlowboardStudioWindow(10, 10, 420, 300);

        // Standardmäßig 5 Sequenzbausteine für 146 BPM generiert
        // (Intro Stomp, Piano Rap, Snare Buildup, Brass Drop Hard Snap, Anthem Chorus)
        flow.generateDefaultSongFlow();

        // Klick in Toolbar auf '+ BRICK'
        assertTrue(flow.onContentClick(75, 10, false, null, null));

        // Klick auf '146 BPM AUTO'
        assertTrue(flow.onContentClick(150, 10, false, null, null));
    }

    @Test
    void testHardSnapStaccatoMovementPattern() {
        FixtureGroup group = new FixtureGroup(
                "moving_heads", "Moving Heads",
                Material.CYAN_STAINED_GLASS, 0x00D8FF,
                ControlMode.MANUAL_LIVE,
                1.0f, 0f, 0f, 25f, 0f, 180f, 1f, 1f,
                MovementPattern.HARD_SNAP_STACCATO, 1.5f, 1.0f, 0.5f
        );

        // Berechne Zustand für Tick 2 und Tick 3 (gleicher Staccato-Step -> Kopf steht absolut starr!)
        FixtureState s1 = group.computeMemberState(FixtureType.MOVING_HEAD_SPOT, 0, 4, 2L);
        FixtureState s2 = group.computeMemberState(FixtureType.MOVING_HEAD_SPOT, 0, 4, 3L);

        assertEquals(s1.panDegrees(), s2.panDegrees(), 1e-3f, "In Staccato freeze window, pan must not drift smoothly");
        assertEquals(s1.tiltDegrees(), s2.tiltDegrees(), 1e-3f, "In Staccato freeze window, tilt must not drift smoothly");

        // Bei einem späteren Step (Tick 20) springt der Kopf schlagartig auf den nächsten diskreten Winkel
        FixtureState s3 = group.computeMemberState(FixtureType.MOVING_HEAD_SPOT, 0, 4, 20L);
        assertNotEquals(s1.panDegrees(), s3.panDegrees(), "On subsequent beat step, pan must jump to discrete target");
    }

    @Test
    void testOffscreenJava2DRenderDesktop() {
        PlasmaWindowManager wm = new PlasmaWindowManager();
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            // Soll ohne NPE oder Rendering-Fehler zeichnen
            wm.drawDesktop(g, null, null, 150, 200);
        } finally {
            g.dispose();
        }

        // Überprüfe, dass Pixel gezeichnet wurden
        int rgb = img.getRGB(320, 180);
        assertNotEquals(0, rgb, "Desktop canvas should render non-transparent background pixels");
    }
}
