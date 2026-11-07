package de.archer.admx.audio;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/**
 * Direkte, latenzfreie Audio-Brücke zum Boiler-Plugin auf dem Paper-Server.
 * <p>
 * Ermöglicht es ADMX, den Sound von YouTube- und Medien-Displays direkt in-game
 * vom Server abzugreifen, ohne dass externe Python-Skripte oder Audio-Loopbacks nötig sind.
 */
public final class BoilerAudioBridge {

    private final BeatDetector detector;
    private final AudioDspManager dspManager;
    private boolean hooked = false;

    // Puffer für 512-Sample Chunks
    private final double[] chunk = new double[512];
    private int chunkIndex = 0;

    public BoilerAudioBridge(BeatDetector detector, AudioDspManager dspManager) {
        this.detector = detector;
        this.dspManager = dspManager;
    }

    public synchronized boolean tryHook() {
        if (hooked) return true;
        if (Bukkit.getPluginManager().getPlugin("Boiler") == null) {
            return false;
        }

        try {
            Class<?> clazz = Class.forName("net.somewhatcity.boiler.core.audio.BAudioPlayer");
            Field field = clazz.getField("AUDIO_TAP");

            Consumer<byte[]> tap = this::onBoilerAudio;
            field.set(null, tap);
            hooked = true;
            Bukkit.getLogger().info("[ADMX-Audio] Erfolgreich an Boiler BAudioPlayer gekoppelt! In-Game YouTube/Medien-Audio ist aktiv.");
            return true;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[ADMX-Audio] Boiler-Audio-Kopplung nicht verfügbar: " + t.getMessage());
            return false;
        }
    }

    public synchronized void unhook() {
        if (!hooked) return;
        try {
            Class<?> clazz = Class.forName("net.somewhatcity.boiler.core.audio.BAudioPlayer");
            Field field = clazz.getField("AUDIO_TAP");
            field.set(null, null);
        } catch (Throwable ignored) {
        }
        hooked = false;
    }

    private synchronized void onBoilerAudio(byte[] samples) {
        if (dspManager.getActiveSource() != AudioDspManager.AudioSourceType.BOILER_MEDIA) {
            return;
        }
        if (samples == null || samples.length < 2) {
            return;
        }

        int sampleCount = samples.length / 2;
        ByteBuffer bb = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < sampleCount; i++) {
            short raw = bb.getShort();
            chunk[chunkIndex++] = raw / 32768.0;

            if (chunkIndex >= 512) {
                dspManager.processPcmChunk(chunk);
                chunkIndex = 0;
            }
        }
    }

    public boolean isHooked() {
        return hooked;
    }
}
