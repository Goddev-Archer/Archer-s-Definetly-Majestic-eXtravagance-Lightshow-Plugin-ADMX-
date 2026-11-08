package de.archer.admx.dmx;

import de.archer.admx.artnet.ArtDmxPacket;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import org.bukkit.Color;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Repräsentiert ein echtes 512-Kanal DMX512-Universum (Kanäle 1..512, Werte 0..255) als
 * zentrale Single Source of Truth (Core Bus) für In-Game DMX-Pulte, den internen ADMX-Generator
 * (Gruppen, Szenen, Sound-to-Light, Flowboard) sowie bidirektionales Art-Net 4 I/O.
 * <p>
 * Sicherheits-Invarianten:
 * <ul>
 *   <li>{@code SEC-09 / SEC-15}: Strobe-Kanäle aus DMX/Art-Net werden beim Dekodieren strikt
 *       auf {@code <= maxStrobeHz} (10.0 Hz) begrenzt, um Photosensitivitäts-Schutz garantieren zu können.</li>
 *   <li>{@code SEC-14}: Alle Kanal- und Paketzugriffe sind strikt auf {@code [1..512]} begrenzt.</li>
 *   <li>{@code SEC-17}: Thread-sichere Synchronisation zwischen Netzwerk-Threads (Art-Net RX)
 *       und dem 20-Hz-Main-Render-Thread.</li>
 * </ul>
 */
public final class DmxUniverse {

    public static final int MAX_UNIVERSES = 16;
    public static final int UNIVERSE_SIZE = 512;
    public static final int CHANNELS_PER_FIXTURE = 16;
    public static final int FIXTURES_PER_UNIVERSE = 32; // 32 * 16 = 512 Kanäle
    public static final long ARTNET_STREAM_TIMEOUT_MS = 3500L;

    private final int universeId; // 0..15 (Anzeige: Universum 1..16)
    private volatile DmxMergeMode mergeMode = DmxMergeMode.HTP;
    private volatile int grandMaster = 255; // 0..255
    private volatile boolean blackout = false;

    // 1. Interner ADMX-Generator-Bus (Gruppen, Szenen, Sound-to-Light, Flowboard)
    private final int[] generatorChannels = new int[UNIVERSE_SIZE];

    // 2. In-Game 3D-DMX-Pult-Bus (Manuelle Fader-Werte & aktive Kanal-Maske)
    private final int[] consoleChannels = new int[UNIVERSE_SIZE];
    private final boolean[] consoleOverrideMask = new boolean[UNIVERSE_SIZE];
    private volatile int activeConsoleOverrideCount = 0;
    private volatile long lastConsoleUpdateMs = 0L;

    // 3. Eingehender Art-Net 4 Netzwerk-Bus (UDP 6454 / WebSocket Client->Server)
    private final int[] artNetInputChannels = new int[UNIVERSE_SIZE];
    private volatile long lastArtNetRxMs = 0L;
    private final AtomicLong artNetRxPackets = new AtomicLong(0);
    private final AtomicLong artNetTxPackets = new AtomicLong(0);

    // 4. Final zusammengeführter Ausgangs-Bus (Merged Output nach HTP/LTP + GrandMaster + Blackout)
    private final int[] mergedChannels = new int[UNIVERSE_SIZE];
    private final ArtDmxPacket txPacket;

    public DmxUniverse(int universeId) {
        this.universeId = Math.clamp(universeId, 0, 15);
        this.txPacket = new ArtDmxPacket(this.universeId);
    }

    public int universeId() {
        return universeId;
    }

    public int displayNumber() {
        return universeId + 1;
    }

    public DmxMergeMode mergeMode() {
        return mergeMode;
    }

    public void setMergeMode(DmxMergeMode mergeMode) {
        if (mergeMode != null) {
            this.mergeMode = mergeMode;
            recomputeMergedOutput(System.currentTimeMillis());
        }
    }

    public int grandMaster() {
        return grandMaster;
    }

    public void setGrandMaster(int grandMaster) {
        this.grandMaster = Math.clamp(grandMaster, 0, 255);
        recomputeMergedOutput(System.currentTimeMillis());
    }

    public boolean isBlackout() {
        return blackout;
    }

    public void setBlackout(boolean blackout) {
        this.blackout = blackout;
        recomputeMergedOutput(System.currentTimeMillis());
    }

    public synchronized void setConsoleChannel(int channel1Based, int value) {
        if (channel1Based < 1 || channel1Based > UNIVERSE_SIZE) return;
        int idx = channel1Based - 1;
        consoleChannels[idx] = Math.clamp(value, 0, 255);
        if (!consoleOverrideMask[idx]) {
            consoleOverrideMask[idx] = true;
            activeConsoleOverrideCount++;
        }
        lastConsoleUpdateMs = System.currentTimeMillis();
        recomputeMergedOutput(lastConsoleUpdateMs);
    }

    public synchronized void releaseConsoleChannel(int channel1Based) {
        if (channel1Based < 1 || channel1Based > UNIVERSE_SIZE) return;
        int idx = channel1Based - 1;
        if (consoleOverrideMask[idx]) {
            consoleOverrideMask[idx] = false;
            consoleChannels[idx] = 0;
            activeConsoleOverrideCount = Math.max(0, activeConsoleOverrideCount - 1);
            recomputeMergedOutput(System.currentTimeMillis());
        }
    }

    public synchronized void clearAllConsoleOverrides() {
        Arrays.fill(consoleChannels, 0);
        Arrays.fill(consoleOverrideMask, false);
        activeConsoleOverrideCount = 0;
        recomputeMergedOutput(System.currentTimeMillis());
    }

    public synchronized boolean isConsoleChannelOverridden(int channel1Based) {
        if (channel1Based < 1 || channel1Based > UNIVERSE_SIZE) return false;
        return consoleOverrideMask[channel1Based - 1];
    }

    public int activeConsoleOverrideCount() {
        return activeConsoleOverrideCount;
    }

    /**
     * Nimmt ein eingehendes Art-Net DMX-Datenfeld (OpDmx 0x5000) sicher entgegen (SEC-14, SEC-17).
     */
    public synchronized void receiveArtNetDmx(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length <= 0 || offset + length > data.length) {
            return;
        }
        int copyLen = Math.min(length, UNIVERSE_SIZE);
        for (int i = 0; i < copyLen; i++) {
            artNetInputChannels[i] = data[offset + i] & 0xFF;
        }
        lastArtNetRxMs = System.currentTimeMillis();
        artNetRxPackets.incrementAndGet();
    }

    public boolean isArtNetInputActive(long nowMs) {
        long last = lastArtNetRxMs;
        return last > 0L && (nowMs - last) <= ARTNET_STREAM_TIMEOUT_MS;
    }

    public long artNetRxPacketCount() {
        return artNetRxPackets.get();
    }

    public long artNetPacketsReceived() {
        return artNetRxPackets.get();
    }

    public boolean hasActiveArtNetInput() {
        return isArtNetInputActive(System.currentTimeMillis());
    }

    public synchronized void setConsoleFader(int channelZeroBased, int value) {
        setConsoleChannel(channelZeroBased + 1, value);
    }

    public synchronized void releaseConsoleFader(int channelZeroBased) {
        releaseConsoleChannel(channelZeroBased + 1);
    }

    public synchronized int getConsoleFader(int channelZeroBased) {
        if (channelZeroBased < 0 || channelZeroBased >= UNIVERSE_SIZE) return -1;
        return consoleOverrideMask[channelZeroBased] ? consoleChannels[channelZeroBased] : -1;
    }

    public synchronized int getArtNetChannel(int channelZeroBased) {
        return getArtNetInputChannel(channelZeroBased + 1);
    }

    public synchronized void applyArtNetInput(byte[] dmxPayload, int length) {
        receiveArtNetDmx(dmxPayload, 0, length);
        recomputeMergedOutput(System.currentTimeMillis());
    }

    public long artNetTxPacketCount() {
        return artNetTxPackets.get();
    }

    public synchronized int getMergedChannel(int channelZeroBased) {
        if (channelZeroBased < 0 || channelZeroBased >= UNIVERSE_SIZE) return 0;
        return mergedChannels[channelZeroBased];
    }

    public synchronized int getConsoleChannel(int channel1Based) {
        if (channel1Based < 1 || channel1Based > UNIVERSE_SIZE) return 0;
        return consoleChannels[channel1Based - 1];
    }

    public synchronized int getArtNetInputChannel(int channel1Based) {
        if (channel1Based < 1 || channel1Based > UNIVERSE_SIZE) return 0;
        return artNetInputChannels[channel1Based - 1];
    }

    public synchronized int getGeneratorChannel(int channel1Based) {
        if (channel1Based < 1 || channel1Based > UNIVERSE_SIZE) return 0;
        return generatorChannels[channel1Based - 1];
    }

    public synchronized void clearGeneratorChannels() {
        Arrays.fill(generatorChannels, 0);
    }

    /**
     * Kodiert den berechneten Zustand eines Bühnengeräts in die 16 DMX-Kanäle des Generator-Busses.
     * Pan (-270..+270 Grad) und Tilt (-135..+135 Grad) werden symmetrisch um DMX-Mitte 32768 (Coarse 128)
     * in 16-Bit (Coarse + Fine) kodiert.
     */
    public synchronized void writeGeneratorFixtureState(int fixtureIndexInUniverse, FixtureState state) {
        int startChannel1Based = (fixtureIndexInUniverse * CHANNELS_PER_FIXTURE) + 1;
        writeGeneratorFixtureStateAtChannel(startChannel1Based, state);
    }

    public synchronized void writeGeneratorFixtureStateAtChannel(int startChannel1Based, FixtureState state) {
        if (state == null || startChannel1Based < 1 || startChannel1Based + CHANNELS_PER_FIXTURE - 1 > UNIVERSE_SIZE) {
            return;
        }
        int base = startChannel1Based - 1;

        // 1. Pan (-270..+270 Grad -> 0..65535, 0 Grad = 32768 / Coarse 128)
        float normPan = Math.clamp((state.panDegrees() + 270.0f) / 540.0f, 0.0f, 1.0f);
        int pan16 = Math.round(normPan * 65535.0f);
        generatorChannels[base] = (pan16 >> 8) & 0xFF;
        generatorChannels[base + 1] = pan16 & 0xFF;

        // 2. Tilt (-135..+135 Grad -> 0..65535, 0 Grad = 32768 / Coarse 128)
        float normTilt = Math.clamp((state.tiltDegrees() + 135.0f) / 270.0f, 0.0f, 1.0f);
        int tilt16 = Math.round(normTilt * 65535.0f);
        generatorChannels[base + 2] = (tilt16 >> 8) & 0xFF;
        generatorChannels[base + 3] = tilt16 & 0xFF;

        // 3. Master Dimmer (0.0..1.0 -> 0..255)
        generatorChannels[base + 4] = Math.round(Math.clamp(state.dimmer(), 0.0f, 1.0f) * 255.0f);

        // 4. Strobe (0 = Offen, 1..255 = 0.1..10.0 Hz)
        int strobe = (state.strobeHz() <= 0.05f)
                ? 0
                : Math.round(Math.clamp(state.strobeHz() / 10.0f * 255.0f, 1.0f, 255.0f));
        generatorChannels[base + 5] = strobe;

        // 5. RGB + White
        Color rgb = state.color().toRGB();
        int r = rgb.getRed();
        int g = rgb.getGreen();
        int b = rgb.getBlue();
        int w = (r > 230 && g > 230 && b > 230) ? 255 : 0;
        generatorChannels[base + 6] = r;
        generatorChannels[base + 7] = g;
        generatorChannels[base + 8] = b;
        generatorChannels[base + 9] = w;

        // 6. Zoom (2.0..45.0 Grad -> 0..255)
        int zoom = Math.round(Math.clamp((state.zoomAngleDegrees() - 2.0f) / 43.0f, 0.0f, 1.0f) * 255.0f);
        generatorChannels[base + 10] = zoom;
        for (int i = 11; i < CHANNELS_PER_FIXTURE; i++) {
            generatorChannels[base + i] = 0;
        }
        recomputeMergedOutput(System.currentTimeMillis());
    }

    /**
     * Führt alle drei Busse (Generator, In-Game-Pult, Art-Net Input) gemäß dem gewählten
     * {@link DmxMergeMode}, Grand-Master und Blackout im Ausgangs-Puffer zusammen.
     */
    public synchronized void recomputeMergedOutput(long nowMs) {
        if (blackout || grandMaster <= 0) {
            Arrays.fill(mergedChannels, 0);
            return;
        }

        boolean artNetActive = isArtNetInputActive(nowMs);
        DmxMergeMode mode = this.mergeMode;
        float gmScale = grandMaster / 255.0f;

        for (int i = 0; i < UNIVERSE_SIZE; i++) {
            int genVal = generatorChannels[i];
            int conVal = consoleOverrideMask[i] ? consoleChannels[i] : 0;
            int netVal = artNetActive ? artNetInputChannels[i] : 0;

            int raw = switch (mode) {
                case HTP -> Math.max(genVal, Math.max(conVal, netVal));
                case LTP -> {
                    if (artNetActive && lastArtNetRxMs >= lastConsoleUpdateMs) {
                        yield consoleOverrideMask[i] ? conVal : netVal;
                    } else if (consoleOverrideMask[i]) {
                        yield conVal;
                    } else if (artNetActive) {
                        yield netVal;
                    } else {
                        yield genVal;
                    }
                }
                case CONSOLE_EXCLUSIVE -> conVal;
                case ARTNET_EXCLUSIVE -> netVal;
            };

            mergedChannels[i] = Math.clamp(Math.round(raw * gmScale), 0, 255);
        }
    }

    /**
     * Liest den zusammengeführten 16-Kanal-DMX-Block eines Geräts zurück und dekodiert ihn in einen
     * {@link FixtureState} für die 3D-Render-Pipeline in Minecraft.
     * <p>
     * Wenn im HTP/LTP-Modus weder Art-Net Input noch ein Pult-Override noch Grand-Master/Blackout
     * auf die 16 Kanäle dieses Geräts einwirken, wird der hochpräzise {@code fallbackGeneratorState}
     * unverändert durchgereicht, um volle Fließkomma-Präzision bei Kurvenfahrten zu erhalten.
     */
    public synchronized FixtureState resolveMergedFixtureState(
            int fixtureIndexInUniverse,
            FixtureState fallbackGeneratorState,
            float maxStrobeHz
    ) {
        int startChannel1Based = (fixtureIndexInUniverse * CHANNELS_PER_FIXTURE) + 1;
        return resolveMergedFixtureStateAtChannel(startChannel1Based, fallbackGeneratorState, maxStrobeHz);
    }

    public synchronized FixtureState resolveMergedFixtureStateAtChannel(
            int startChannel1Based,
            FixtureState fallbackGeneratorState,
            float maxStrobeHz
    ) {
        if (blackout || grandMaster <= 0) {
            return new FixtureState(
                    fallbackGeneratorState != null ? fallbackGeneratorState.panDegrees() : 0f,
                    fallbackGeneratorState != null ? fallbackGeneratorState.tiltDegrees() : 0f,
                    0.0f,
                    fallbackGeneratorState != null ? fallbackGeneratorState.color() : Oklab.fromRGB(Color.WHITE),
                    fallbackGeneratorState != null ? fallbackGeneratorState.zoomAngleDegrees() : 18.0f,
                    0.0f
            );
        }

        if (startChannel1Based < 1 || startChannel1Based + CHANNELS_PER_FIXTURE - 1 > UNIVERSE_SIZE) {
            return fallbackGeneratorState;
        }

        int base = startChannel1Based - 1;
        boolean anyConsoleOverrideOnFixture = false;
        for (int i = 0; i < CHANNELS_PER_FIXTURE; i++) {
            if (consoleOverrideMask[base + i]) {
                anyConsoleOverrideOnFixture = true;
                break;
            }
        }

        boolean artNetActive = isArtNetInputActive(System.currentTimeMillis());
        boolean isExclusive = (mergeMode == DmxMergeMode.CONSOLE_EXCLUSIVE || mergeMode == DmxMergeMode.ARTNET_EXCLUSIVE);

        // Falls keine externe oder manuelle DMX-Manipulation vorliegt, volle Float-Präzision erhalten
        if (!isExclusive && !artNetActive && !anyConsoleOverrideOnFixture && grandMaster == 255 && fallbackGeneratorState != null) {
            return fallbackGeneratorState;
        }

        // Dekodiere die 16 Kanäle aus dem zusammengeführten DMX512-Puffer (oder Einzelkanal-Override)
        int panCoarse = mergedChannels[base];
        int panFine = mergedChannels[base + 1];
        int tiltCoarse = mergedChannels[base + 2];
        int tiltFine = mergedChannels[base + 3];
        int dimmerDmx = mergedChannels[base + 4];
        int strobeDmx = mergedChannels[base + 5];
        int rDmx = mergedChannels[base + 6];
        int gDmx = mergedChannels[base + 7];
        int bDmx = mergedChannels[base + 8];
        int wDmx = mergedChannels[base + 9];
        int zoomDmx = mergedChannels[base + 10];

        // Komfort-Erkennung für QLC+ "Generic RGB" (3-Kanal R/G/B auf Kanälen 1..3), falls Ch 5..10 auf 0 stehen:
        boolean generic3ChRgbActive = artNetActive
                && artNetInputChannels[base + 4] == 0
                && artNetInputChannels[base + 6] == 0
                && artNetInputChannels[base + 7] == 0
                && artNetInputChannels[base + 8] == 0
                && (artNetInputChannels[base] > 0 || artNetInputChannels[base + 1] > 0 || artNetInputChannels[base + 2] > 0);
        if (generic3ChRgbActive) {
            rDmx = artNetInputChannels[base];
            gDmx = artNetInputChannels[base + 1];
            bDmx = artNetInputChannels[base + 2];
            dimmerDmx = Math.max(rDmx, Math.max(gDmx, bDmx));
            panCoarse = 128;
            panFine = 0;
            tiltCoarse = 115;
            tiltFine = 0;
        }

        float panDeg;
        if (!isExclusive && !artNetActive && !consoleOverrideMask[base] && !consoleOverrideMask[base + 1] && fallbackGeneratorState != null) {
            panDeg = fallbackGeneratorState.panDegrees();
        } else {
            int pan16 = (panCoarse << 8) | panFine;
            panDeg = (pan16 / 65535.0f) * 540.0f - 270.0f;
        }

        float tiltDeg;
        if (!isExclusive && !artNetActive && !consoleOverrideMask[base + 2] && !consoleOverrideMask[base + 3] && fallbackGeneratorState != null) {
            tiltDeg = fallbackGeneratorState.tiltDegrees();
        } else {
            int tilt16 = (tiltCoarse << 8) | tiltFine;
            tiltDeg = (tilt16 / 65535.0f) * 270.0f - 135.0f;
        }

        float dimmer = Math.clamp(dimmerDmx / 255.0f, 0.0f, 1.0f);

        // SEC-09 / SEC-15: Strobe-Frequenz strikt auf <= maxStrobeHz (max. 10.0 Hz) deckeln!
        float safeCap = Math.clamp(maxStrobeHz, 1.0f, 10.0f);
        float strobeHz = (strobeDmx <= 2) ? 0.0f : Math.clamp((strobeDmx / 255.0f) * safeCap, 0.5f, safeCap);

        // RGBW Mischung
        int r = Math.min(255, rDmx + wDmx);
        int g = Math.min(255, gDmx + wDmx);
        int b = Math.min(255, bDmx + wDmx);
        Oklab color;
        if (r == 0 && g == 0 && b == 0 && !isExclusive && !artNetActive
                && !consoleOverrideMask[base + 6] && !consoleOverrideMask[base + 7]
                && !consoleOverrideMask[base + 8] && !consoleOverrideMask[base + 9]
                && fallbackGeneratorState != null) {
            color = fallbackGeneratorState.color();
        } else {
            color = Oklab.fromRGB(Color.fromRGB(r, g, b));
        }

        float zoomDeg;
        if (!isExclusive && !artNetActive && !consoleOverrideMask[base + 10] && fallbackGeneratorState != null) {
            zoomDeg = fallbackGeneratorState.zoomAngleDegrees();
        } else {
            zoomDeg = 2.0f + (zoomDmx / 255.0f) * 43.0f;
        }

        return new FixtureState(panDeg, tiltDeg, dimmer, color, zoomDeg, strobeHz);
    }

    /**
     * Erzeugt das aktuelle 530-Byte {@link ArtDmxPacket} aus dem zusammengeführten Ausgangs-Puffer.
     */
    public synchronized byte[] buildArtDmxPacketBytes() {
        for (int i = 0; i < UNIVERSE_SIZE; i++) {
            txPacket.setChannel(i + 1, mergedChannels[i]);
        }
        artNetTxPackets.incrementAndGet();
        return txPacket.toByteArray();
    }

    /**
     * Liefert die sprechende Rollen-Bezeichnung eines DMX-Kanals innerhalb des 16-Kanal-Standardprofils.
     */
    public static String describeChannelRole(int channel1Based) {
        int offset = (Math.max(1, channel1Based) - 1) % CHANNELS_PER_FIXTURE;
        return switch (offset) {
            case 0 -> "Pan Coarse (Drehung Grob)";
            case 1 -> "Pan Fine (Drehung Fein)";
            case 2 -> "Tilt Coarse (Neigung Grob)";
            case 3 -> "Tilt Fine (Neigung Fein)";
            case 4 -> "Master Dimmer (Helligkeit)";
            case 5 -> "Strobe / Shutter (<= 10 Hz)";
            case 6 -> "Farbe: Rot (R)";
            case 7 -> "Farbe: Grün (G)";
            case 8 -> "Farbe: Blau (B)";
            case 9 -> "Farbe: Weiß (W)";
            case 10 -> "Zoom / Abstrahlwinkel";
            case 11 -> "Bewegungs-Makro / Speed";
            default -> "Reserviert / Aux (" + (offset + 1) + ")";
        };
    }
}
