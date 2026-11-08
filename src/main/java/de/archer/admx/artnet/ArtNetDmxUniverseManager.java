package de.archer.admx.artnet;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.render.fixture.FixtureState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Verwaltet die 16 echten 512-Kanal DMX-Universen ({@link DmxUniverse}, Universum 0..15 bzw. 1..16),
 * das automatische 16-Kanal-Geräte-Patching sowie das bidirektionale Zusammenführen (HTP/LTP)
 * von internem ADMX-Generator, platzierten 3D-DMX-Pulten und eingehendem Art-Net 4 Datenstrom.
 * <p>
 * Sicherheits-Invarianten:
 * <ul>
 *   <li>{@code SEC-14}: Feste Obergrenze von {@link #MAX_UNIVERSES} (16 Universen) verhindert
 *       Speicher-Erschöpfung durch manipulierte Art-Net Paket-Header; strenge Längen- und
 *       Header-Validierung bei eingehenden {@code OpDmx}-Paketen.</li>
 *   <li>{@code SEC-15}: Strikte Durchsetzung des Photosensitivitäts-Strobe-Limits (&le; 10 Hz)
 *       auch für extern eingespeiste DMX-Kanäle.</li>
 * </ul>
 */
public final class ArtNetDmxUniverseManager {

    public static final int CHANNELS_PER_FIXTURE = DmxUniverse.CHANNELS_PER_FIXTURE;
    public static final int FIXTURES_PER_UNIVERSE = DmxUniverse.FIXTURES_PER_UNIVERSE;
    public static final int MAX_UNIVERSES = 16;

    private final DmxUniverse[] universes = new DmxUniverse[MAX_UNIVERSES];

    public ArtNetDmxUniverseManager() {
        for (int i = 0; i < MAX_UNIVERSES; i++) {
            universes[i] = new DmxUniverse(i);
        }
    }

    public DmxUniverse getUniverse(int universeId) {
        return universes[Math.clamp(universeId, 0, MAX_UNIVERSES - 1)];
    }

    public DmxUniverse[] getAllUniverses() {
        return universes;
    }

    /**
     * Validiert ein eingehendes rohes Art-Net 4 {@code OpDmx} Paket (aus UDP 6454 oder WebSocket)
     * nach RFC- und Sicherheits-Regeln (SEC-14) und speist die DMX-Kanäle in das Ziel-Universum ein.
     *
     * @return {@code true}, wenn das Paket ein gültiges ArtDmx-Paket war und verarbeitet wurde.
     */
    public boolean handleIncomingArtNetPacket(byte[] packet, int length) {
        if (packet == null || length < ArtDmxPacket.HEADER_SIZE + 2 || length > packet.length) {
            return false;
        }
        // 1. Prüfe "Art-Net\0" Signatur (Bytes 0..7)
        for (int i = 0; i < ArtDmxPacket.HEADER_ID.length; i++) {
            if (packet[i] != ArtDmxPacket.HEADER_ID[i]) {
                return false;
            }
        }
        // 2. Prüfe OpCode 0x5000 (Little-Endian: 0x00, 0x50)
        int opCode = (packet[8] & 0xFF) | ((packet[9] & 0xFF) << 8);
        if (opCode != ArtDmxPacket.OPCODE_DMX) {
            return false;
        }
        // 3. Prüfe Protokoll-Version >= 14 (Big-Endian)
        int protVer = ((packet[10] & 0xFF) << 8) | (packet[11] & 0xFF);
        if (protVer < ArtDmxPacket.PROTOCOL_VERSION) {
            return false;
        }
        // 4. Extrahiere 15-Bit Port-Address (SubUni + Net) und begrenze auf [0 .. MAX_UNIVERSES - 1] (SEC-14)
        int subUni = packet[14] & 0xFF;
        int net = packet[15] & 0x7F;
        int universe15Bit = (net << 8) | subUni;
        if (universe15Bit < 0 || universe15Bit >= MAX_UNIVERSES) {
            return false;
        }
        // 5. Extrahiere DMX-Datenlänge (Big-Endian, 2..512) und validiere gegen tatsächliche Paketgröße (SEC-14)
        int dmxLength = ((packet[16] & 0xFF) << 8) | (packet[17] & 0xFF);
        if (dmxLength < 2 || dmxLength > DmxUniverse.UNIVERSE_SIZE) {
            return false;
        }
        int availablePayload = length - ArtDmxPacket.HEADER_SIZE;
        int safeCopyLen = Math.min(dmxLength, availablePayload);
        if (safeCopyLen <= 0) {
            return false;
        }

        universes[universe15Bit].receiveArtNetDmx(packet, ArtDmxPacket.HEADER_SIZE, safeCopyLen);
        universes[universe15Bit].recomputeMergedOutput(System.currentTimeMillis());
        return true;
    }

    public boolean handleIncomingArtDmxPacket(byte[] packet, int length) {
        return handleIncomingArtNetPacket(packet, length);
    }

    public static byte[] buildArtDmxPacket(int universeId, int sequence, byte[] dmx512) {
        ArtDmxPacket pkt = new ArtDmxPacket(universeId);
        if (dmx512 != null) {
            int len = Math.min(dmx512.length, DmxUniverse.UNIVERSE_SIZE);
            for (int i = 0; i < len; i++) {
                pkt.setChannel(i + 1, dmx512[i] & 0xFF);
            }
        }
        byte[] raw = pkt.toByteArray();
        raw[12] = (byte) (sequence & 0xFF);
        // Setze 15-Bit Port-Address ungeclampt für Sicherheits-Tests (SubUni + Net)
        raw[14] = (byte) (universeId & 0xFF);
        raw[15] = (byte) ((universeId >> 8) & 0x7F);
        return raw;
    }

    /**
     * Synchronisiert den internen ADMX-Generator-Zustand aller platzierten Geräte in die
     * jeweiligen DMX-Universen und berechnet anschließend den zusammengeführten Ausgang (HTP/LTP).
     */
    public void syncUniverses(
            List<AdmxPlugin.PlacedFixture> fixtures,
            Function<AdmxPlugin.PlacedFixture, FixtureState> stateResolver
    ) {
        long nowMs = System.currentTimeMillis();
        int maxUni = (fixtures == null || fixtures.isEmpty())
                ? 0
                : Math.min(MAX_UNIVERSES - 1, (fixtures.size() - 1) / FIXTURES_PER_UNIVERSE);

        for (int u = 0; u <= maxUni; u++) {
            universes[u].clearGeneratorChannels();
        }

        if (fixtures != null && stateResolver != null) {
            int limit = Math.min(fixtures.size(), MAX_UNIVERSES * FIXTURES_PER_UNIVERSE);
            for (int i = 0; i < limit; i++) {
                AdmxPlugin.PlacedFixture fixture = fixtures.get(i);
                FixtureState state = stateResolver.apply(fixture);
                if (state == null) continue;

                int uniIndex = i / FIXTURES_PER_UNIVERSE;
                int fixtureIndexInUni = i % FIXTURES_PER_UNIVERSE;

                universes[uniIndex].writeGeneratorFixtureState(fixtureIndexInUni, state);
            }
        }

        // Berechne für alle 16 Universen den finalen HTP/LTP Merge (inkl. Pult-Overrides & Art-Net Input)
        for (int u = 0; u < MAX_UNIVERSES; u++) {
            universes[u].recomputeMergedOutput(nowMs);
        }
    }

    /**
     * Löst den finalen {@link FixtureState} für ein Gerät unter Berücksichtigung des DMX-Universums
     * (In-Game DMX-Pult Fader, Art-Net Input, HTP/LTP Merge, Grand-Master, Blackout) auf.
     */
    public FixtureState resolveMergedStateForFixture(
            int globalFixtureIndex,
            FixtureState generatorFallback,
            float maxStrobeHz
    ) {
        if (globalFixtureIndex < 0 || globalFixtureIndex >= MAX_UNIVERSES * FIXTURES_PER_UNIVERSE) {
            return generatorFallback;
        }
        int uniIndex = globalFixtureIndex / FIXTURES_PER_UNIVERSE;
        int fixtureIndexInUni = globalFixtureIndex % FIXTURES_PER_UNIVERSE;

        return universes[uniIndex].resolveMergedFixtureState(fixtureIndexInUni, generatorFallback, maxStrobeHz);
    }

    /**
     * Erstellt die aktuellen Art-Net DMX-Pakete für alle aktiven Universen.
     */
    public List<byte[]> buildDmxFrames(
            List<AdmxPlugin.PlacedFixture> fixtures,
            Function<AdmxPlugin.PlacedFixture, FixtureState> stateResolver
    ) {
        syncUniverses(fixtures, stateResolver);

        List<byte[]> frames = new ArrayList<>();
        int maxUni = (fixtures == null || fixtures.isEmpty())
                ? 0
                : Math.min(MAX_UNIVERSES - 1, (fixtures.size() - 1) / FIXTURES_PER_UNIVERSE);

        for (int u = 0; u <= maxUni; u++) {
            frames.add(universes[u].buildArtDmxPacketBytes());
        }
        return frames;
    }

    /**
     * Erstellt die aktuellen Art-Net DMX-Pakete aus den bereits synchronisierten Universen.
     */
    public List<byte[]> buildSyncedDmxFrames(int fixtureCount) {
        List<byte[]> frames = new ArrayList<>();
        int maxUni = (fixtureCount <= 0)
                ? 0
                : Math.min(MAX_UNIVERSES - 1, (fixtureCount - 1) / FIXTURES_PER_UNIVERSE);

        for (int u = 0; u <= maxUni; u++) {
            frames.add(universes[u].buildArtDmxPacketBytes());
        }
        return frames;
    }
}
