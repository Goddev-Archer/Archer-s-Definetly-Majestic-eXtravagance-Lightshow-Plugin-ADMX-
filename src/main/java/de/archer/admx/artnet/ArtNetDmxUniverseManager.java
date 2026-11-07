package de.archer.admx.artnet;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.bukkit.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet das DMX-Universum-Patching für alle in ADMX platzierten Geräte
 * und generiert fertige {@link ArtDmxPacket}s für die Art-Net WebSocket-Bridge.
 * <p>
 * Standard-Patching (16 Kanäle pro Gerät im Moving-Head Profil):
 * <ul>
 *   <li>Kanal 1: Pan Coarse (0..255)</li>
 *   <li>Kanal 2: Pan Fine (0..255)</li>
 *   <li>Kanal 3: Tilt Coarse (0..255)</li>
 *   <li>Kanal 4: Tilt Fine (0..255)</li>
 *   <li>Kanal 5: Master Dimmer (0..255)</li>
 *   <li>Kanal 6: Strobe Frequenz (0 = Offen, 1..255 = Strobe-Geschwindigkeit)</li>
 *   <li>Kanal 7: Rot (0..255)</li>
 *   <li>Kanal 8: Grün (0..255)</li>
 *   <li>Kanal 9: Blau (0..255)</li>
 *   <li>Kanal 10: Weiß (0..255)</li>
 *   <li>Kanal 11: Zoom-Winkel (0..255)</li>
 *   <li>Kanal 12..16: Makro / Speed / Reserved</li>
 * </ul>
 * Bis zu 32 Geräte pro Universum (32 * 16 = 512 Kanäle). Bei mehr als 32 Geräten
 * wird automatisch Universum 1, 2 etc. adressiert.
 */
public final class ArtNetDmxUniverseManager {

    public static final int CHANNELS_PER_FIXTURE = 16;
    public static final int FIXTURES_PER_UNIVERSE = 32;

    private final Map<Integer, ArtDmxPacket> activeUniverses = new ConcurrentHashMap<>();

    public ArtNetDmxUniverseManager() {
        activeUniverses.put(0, new ArtDmxPacket(0));
    }

    /**
     * Erstellt die aktuellen Art-Net DMX-Pakete für alle platzierten Geräte.
     */
    public List<byte[]> buildDmxFrames(List<AdmxPlugin.PlacedFixture> fixtures, java.util.function.Function<AdmxPlugin.PlacedFixture, FixtureState> stateResolver) {
        List<byte[]> frames = new ArrayList<>();
        if (fixtures == null || fixtures.isEmpty()) {
            ArtDmxPacket u0 = activeUniverses.computeIfAbsent(0, ArtDmxPacket::new);
            u0.clear();
            frames.add(u0.toByteArray());
            return frames;
        }

        int maxUni = (fixtures.size() - 1) / FIXTURES_PER_UNIVERSE;
        for (int u = 0; u <= maxUni; u++) {
            ArtDmxPacket packet = activeUniverses.computeIfAbsent(u, ArtDmxPacket::new);
            packet.clear();
        }

        for (int i = 0; i < fixtures.size(); i++) {
            AdmxPlugin.PlacedFixture fixture = fixtures.get(i);
            FixtureState state = stateResolver.apply(fixture);
            if (state == null) continue;

            int uniIndex = i / FIXTURES_PER_UNIVERSE;
            int fixtureIndexInUni = i % FIXTURES_PER_UNIVERSE;
            int startCh = (fixtureIndexInUni * CHANNELS_PER_FIXTURE) + 1;

            ArtDmxPacket packet = activeUniverses.get(uniIndex);
            if (packet != null) {
                mapFixtureToChannels(packet, startCh, fixture.type(), state);
            }
        }

        for (int u = 0; u <= maxUni; u++) {
            ArtDmxPacket p = activeUniverses.get(u);
            if (p != null) {
                frames.add(p.toByteArray());
            }
        }

        return frames;
    }

    private void mapFixtureToChannels(ArtDmxPacket packet, int startCh, FixtureType type, FixtureState state) {
        // 1. Pan (0..540 Grad) auf 16-Bit Auflösung (Coarse + Fine)
        float normPan = Math.clamp(state.panDegrees() / 540.0f, 0.0f, 1.0f);
        int pan16 = Math.round(normPan * 65535.0f);
        int panCoarse = (pan16 >> 8) & 0xFF;
        int panFine = pan16 & 0xFF;

        // 2. Tilt (0..270 Grad) auf 16-Bit Auflösung
        float normTilt = Math.clamp(state.tiltDegrees() / 270.0f, 0.0f, 1.0f);
        int tilt16 = Math.round(normTilt * 65535.0f);
        int tiltCoarse = (tilt16 >> 8) & 0xFF;
        int tiltFine = tilt16 & 0xFF;

        // 3. Dimmer (0.0 .. 1.0) -> 0..255
        int dimmer = Math.round(state.dimmer() * 255.0f);

        // 4. Strobe (0 = Offen/Dauerlicht, >0 = Strobe 1..255)
        int strobe = (state.strobeHz() <= 0.05f) ? 0 : Math.round(Math.clamp(state.strobeHz() / 10.0f * 255.0f, 1.0f, 255.0f));

        // 5. Farbe (RGB aus Oklab berechnen)
        Color bukkitColor = state.color().toRGB();
        int r = bukkitColor.getRed();
        int g = bukkitColor.getGreen();
        int b = bukkitColor.getBlue();
        int w = (r > 230 && g > 230 && b > 230) ? 255 : 0;

        // 6. Zoom (2.0 .. 45.0 Grad) -> 0..255
        int zoom = Math.round(Math.clamp((state.zoomAngleDegrees() - 2.0f) / 43.0f, 0.0f, 1.0f) * 255.0f);

        packet.setChannel(startCh, panCoarse);
        packet.setChannel(startCh + 1, panFine);
        packet.setChannel(startCh + 2, tiltCoarse);
        packet.setChannel(startCh + 3, tiltFine);
        packet.setChannel(startCh + 4, dimmer);
        packet.setChannel(startCh + 5, strobe);
        packet.setChannel(startCh + 6, r);
        packet.setChannel(startCh + 7, g);
        packet.setChannel(startCh + 8, b);
        packet.setChannel(startCh + 9, w);
        packet.setChannel(startCh + 10, zoom);
        packet.setChannel(startCh + 11, 0); // Speed / Macro
    }
}
