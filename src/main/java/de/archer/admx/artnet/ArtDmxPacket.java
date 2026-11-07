package de.archer.admx.artnet;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Erzeugt standardkonforme Art-Net 4 {@code ArtDmx}-Datenpakete (OpCode 0x5000).
 * <p>
 * Paketaufbau (530 Bytes bei 512 DMX-Kanälen):
 * <ul>
 *   <li>0..7: "Art-Net\0" (8 Bytes Protokoll-Identifikator)</li>
 *   <li>8..9: OpCode 0x5000 (OpDmx, Little-Endian)</li>
 *   <li>10..11: ProtVer 14 (Big-Endian)</li>
 *   <li>12: Sequence (1..255 zur Erkennung von Paketverlusten)</li>
 *   <li>13: Physical Input Port (0x00)</li>
 *   <li>14: SubUni (Universe 0..15 + SubNet 0..15)</li>
 *   <li>15: Net (0..127)</li>
 *   <li>16..17: Length (Anzahl Kanäle, Big-Endian, 2 bis 512, immer gerade Zahl)</li>
 *   <li>18..529: DMX512 Kanaldaten (Werte von 0 bis 255)</li>
 * </ul>
 */
public final class ArtDmxPacket {

    public static final byte[] HEADER_ID = "Art-Net\0".getBytes(StandardCharsets.US_ASCII);
    public static final int OPCODE_DMX = 0x5000;
    public static final int PROTOCOL_VERSION = 14;
    public static final int HEADER_SIZE = 18;
    public static final int MAX_CHANNELS = 512;

    private final int net;
    private final int subNet;
    private final int universe;
    private final byte[] dmxData;
    private int sequence = 1;

    public ArtDmxPacket(int net, int subNet, int universe) {
        this.net = Math.clamp(net, 0, 127);
        this.subNet = Math.clamp(subNet, 0, 15);
        this.universe = Math.clamp(universe, 0, 15);
        this.dmxData = new byte[MAX_CHANNELS];
    }

    public ArtDmxPacket(int universe15Bit) {
        this((universe15Bit >> 8) & 0x7F, (universe15Bit >> 4) & 0x0F, universe15Bit & 0x0F);
    }

    /**
     * Setzt den Wert eines DMX-Kanals (1..512) auf einen Wert von 0 bis 255.
     */
    public void setChannel(int channel1Based, int value) {
        if (channel1Based < 1 || channel1Based > MAX_CHANNELS) return;
        dmxData[channel1Based - 1] = (byte) Math.clamp(value, 0, 255);
    }

    /**
     * Schreibt ein Array von DMX-Kanälen ab einem Startkanal (1-basiert).
     */
    public void setChannels(int startChannel1Based, int[] values) {
        if (values == null) return;
        for (int i = 0; i < values.length; i++) {
            int ch = startChannel1Based + i;
            if (ch > MAX_CHANNELS) break;
            setChannel(ch, values[i]);
        }
    }

    /**
     * Setzt alle DMX-Kanäle auf 0 zurück (Blackout).
     */
    public void clear() {
        Arrays.fill(dmxData, (byte) 0);
    }

    /**
     * Serialisiert das Paket in ein sendefertiges Byte-Array gemäß Art-Net 4 Spezifikation.
     */
    public byte[] toByteArray() {
        byte[] packet = new byte[HEADER_SIZE + MAX_CHANNELS];

        // 0..7: "Art-Net\0"
        System.arraycopy(HEADER_ID, 0, packet, 0, 8);

        // 8..9: OpCode 0x5000 (Little-Endian)
        packet[8] = (byte) (OPCODE_DMX & 0xFF);
        packet[9] = (byte) ((OPCODE_DMX >> 8) & 0xFF);

        // 10..11: ProtVer 14 (Big-Endian)
        packet[10] = (byte) ((PROTOCOL_VERSION >> 8) & 0xFF);
        packet[11] = (byte) (PROTOCOL_VERSION & 0xFF);

        // 12: Sequence (1..255)
        packet[12] = (byte) (sequence & 0xFF);
        sequence = (sequence % 255) + 1;

        // 13: Physical Port
        packet[13] = 0x00;

        // 14: SubUni (Bits 0..3: Universe, Bits 4..7: SubNet)
        packet[14] = (byte) ((universe & 0x0F) | ((subNet & 0x0F) << 4));

        // 15: Net (Bits 0..6)
        packet[15] = (byte) (net & 0x7F);

        // 16..17: Length = 512 Kanäle (Big-Endian)
        packet[16] = (byte) ((MAX_CHANNELS >> 8) & 0xFF);
        packet[17] = (byte) (MAX_CHANNELS & 0xFF);

        // 18..529: DMX Data
        System.arraycopy(dmxData, 0, packet, HEADER_SIZE, MAX_CHANNELS);

        return packet;
    }

    public int get15BitUniverse() {
        return (net << 8) | (subNet << 4) | universe;
    }
}
