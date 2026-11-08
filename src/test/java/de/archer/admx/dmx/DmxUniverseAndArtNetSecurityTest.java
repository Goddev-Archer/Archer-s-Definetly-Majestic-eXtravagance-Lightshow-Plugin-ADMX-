package de.archer.admx.dmx;

import de.archer.admx.artnet.ArtNetDmxUniverseManager;
import de.archer.admx.artnet.ArtNetUdpNode;
import de.archer.admx.db.AdmxDatabase;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DmxUniverseAndArtNetSecurityTest {

    @Test
    void testSymmetricPanTiltEncodingAndDecodingBug10() {
        DmxUniverse universe = new DmxUniverse(0);
        FixtureState neutral = new FixtureState(0f, 0f, 1.0f, Oklab.fromRGB(Color.RED), 24f, 0f);
        universe.writeGeneratorFixtureState(0, neutral);

        // 0° Pan/Tilt muss exakt in der DMX-Mitte liegen (Coarse = 128)
        assertEquals(128, universe.getMergedChannel(0), "Pan Coarse bei 0° muss 128 sein");
        assertEquals(128, universe.getMergedChannel(2), "Tilt Coarse bei 0° muss 128 sein");

        // Negativer Pan (-135°) muss < 128 (ca. 64) kodiert werden statt auf 0 zu clippen (BUG-10)
        FixtureState negativePan = new FixtureState(-135f, -67.5f, 0.8f, Oklab.fromRGB(Color.BLUE), 24f, 0f);
        universe.writeGeneratorFixtureState(0, negativePan);
        int panCoarse = universe.getMergedChannel(0);
        int tiltCoarse = universe.getMergedChannel(2);
        assertTrue(panCoarse >= 63 && panCoarse <= 65, "Pan Coarse bei -135° sollte ~64 sein, war: " + panCoarse);
        assertTrue(tiltCoarse >= 63 && tiltCoarse <= 65, "Tilt Coarse bei -67.5° sollte ~64 sein, war: " + tiltCoarse);
    }

    @Test
    void testHtpLtpConsoleAndArtNetMerging() {
        DmxUniverse universe = new DmxUniverse(1);
        FixtureState genState = new FixtureState(0f, 0f, 0.2f, Oklab.fromRGB(Color.WHITE), 20f, 0f);
        universe.writeGeneratorFixtureState(0, genState); // Ch 4 (Dimmer) = 51

        byte[] artNetDmx = new byte[512];
        artNetDmx[4] = (byte) 200; // Dimmer = 200 via Art-Net
        artNetDmx[6] = (byte) 255; // Red = 255 via Art-Net
        universe.applyArtNetInput(artNetDmx, 512);

        // HTP: max(Generator=51, ArtNet=200) = 200
        universe.setMergeMode(DmxMergeMode.HTP);
        assertEquals(200, universe.getMergedChannel(4));

        // Konsole übersteuert höher als Art-Net: 240
        universe.setConsoleFader(4, 240);
        assertEquals(240, universe.getMergedChannel(4));

        // LTP: Konsole hat Vorrang vor Art-Net, auch wenn der Pult-Wert kleiner ist (z.B. 100 < 200)
        universe.setMergeMode(DmxMergeMode.LTP);
        universe.setConsoleFader(4, 100);
        assertEquals(100, universe.getMergedChannel(4));

        // ARTNET_EXCLUSIVE: Nur Art-Net zählt (200)
        universe.setMergeMode(DmxMergeMode.ARTNET_EXCLUSIVE);
        assertEquals(200, universe.getMergedChannel(4));

        // Grand Master (50%) & Blackout (DBO)
        universe.setGrandMaster(128);
        int scaled = universe.getMergedChannel(4);
        assertTrue(scaled >= 99 && scaled <= 101);

        universe.setBlackout(true);
        assertEquals(0, universe.getMergedChannel(4));
    }

    @Test
    void testGeneric3ChRgbInQlcPlusDrivesFixtureWithoutOverrideLoss() {
        DmxUniverse universe = new DmxUniverse(0);
        FixtureState base = new FixtureState(0f, -15f, 0.0f, Oklab.fromRGB(Color.BLACK), 24f, 0f);
        universe.writeGeneratorFixtureState(0, base);

        // Benutzer hat in QLC+ ein Generic RGB auf Kanal 1..3 (Index 0..2) gepatcht und zieht R/G/B hoch:
        byte[] qlcThreeChannelRgb = new byte[512];
        qlcThreeChannelRgb[0] = (byte) 255; // Ch 1 = 255
        qlcThreeChannelRgb[1] = (byte) 0;   // Ch 2 = 0
        qlcThreeChannelRgb[2] = (byte) 128; // Ch 3 = 128
        universe.applyArtNetInput(qlcThreeChannelRgb, 512);

        FixtureState resolved = universe.resolveMergedFixtureState(0, base, 10.0f);
        assertTrue(resolved.dimmer() > 0.9f, "Generic 3-Ch RGB Input aus QLC+ muss das erste Gerät direkt zum Leuchten bringen!");
    }

    @Test
    void testPhotosensitivityCapSec15OnRawDmxStrobe() {
        DmxUniverse universe = new DmxUniverse(0);
        FixtureState base = new FixtureState(0f, 0f, 1.0f, Oklab.fromRGB(Color.WHITE), 24f, 0f);
        universe.writeGeneratorFixtureState(0, base);

        // Externe DMX-Quelle sendet maximalen Strobe-Kanalwert 255 (entspräche ungedrosselt >20 Hz)
        universe.setConsoleFader(5, 255); // Kanal 6 = Strobe

        FixtureState safeState = universe.resolveMergedFixtureState(0, base, 10.0f);
        assertTrue(safeState.strobeHz() <= 10.0f, "SEC-15: Strobe-Frequenz darf 10.0 Hz niemals überschreiten!");
        assertEquals(10.0f, safeState.strobeHz(), 0.01f);
    }

    @Test
    void testMalformedArtDmxPacketRejectionSec14() {
        ArtNetDmxUniverseManager mgr = new ArtNetDmxUniverseManager();

        // 1. Zu kurzes Paket
        assertFalse(mgr.handleIncomingArtDmxPacket(new byte[10], 10));

        // 2. Falscher Header
        byte[] badHeader = new byte[32];
        assertFalse(mgr.handleIncomingArtDmxPacket(badHeader, badHeader.length));

        // 3. Gültiger Header, aber Universe außerhalb 0..15 (z.B. Universe 99) -> SEC-14 DoS-Schutz
        byte[] outOfRangeUni = ArtNetDmxUniverseManager.buildArtDmxPacket(99, 1, new byte[512]);
        assertFalse(mgr.handleIncomingArtDmxPacket(outOfRangeUni, outOfRangeUni.length));

        // 4. Gültiges Paket für Universe 0
        byte[] valid = ArtNetDmxUniverseManager.buildArtDmxPacket(0, 1, new byte[512]);
        valid[18 + 4] = (byte) 222; // Ch 5 (Dimmer) = 222
        assertTrue(mgr.handleIncomingArtDmxPacket(valid, valid.length));
        assertEquals(222, mgr.getUniverse(0).getArtNetChannel(4));
    }

    @Test
    void testArtPollReplyPacketFormatSec12() {
        byte[] reply = ArtNetUdpNode.buildArtPollReply(new byte[]{127, 0, 0, 1}, 6454);
        assertEquals(239, reply.length, "ArtPollReply muss exakt 239 Bytes lang sein");
        String header = new String(reply, 0, 7, StandardCharsets.US_ASCII);
        assertEquals("Art-Net", header);
        assertEquals(0x00, reply[8] & 0xFF);
        assertEquals(0x21, reply[9] & 0xFF); // OpPollReply = 0x2100 LE
    }

    @Test
    void testConsoleModelsAndDatabaseCrcSec08() {
        assertEquals(5, ConsoleModelType.values().length);
        assertEquals(10015, ConsoleModelType.GRAND_CONSOLE.customModelData());
        assertEquals(10016, ConsoleModelType.STAIRVILLE_DDC6.customModelData());
        assertEquals(10017, ConsoleModelType.STAIRVILLE_DMX_MASTER.customModelData());
        assertEquals(10018, ConsoleModelType.BEHRINGER_WING.customModelData());
        assertEquals(10019, ConsoleModelType.YAMAHA_DM7.customModelData());

        UUID id = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            long crcDe = AdmxDatabase.computeConsoleCrc(id, "YAMAHA_DM7", 3, "world", 12.3456, 64.0, -98.7654);
            Locale.setDefault(Locale.US);
            long crcUs = AdmxDatabase.computeConsoleCrc(id, "YAMAHA_DM7", 3, "world", 12.3456, 64.0, -98.7654);
            assertEquals(crcDe, crcUs, "SEC-08: Console-CRC32 muss unabhängig vom System-Locale identisch sein");
        } finally {
            Locale.setDefault(prev);
        }
    }
}
