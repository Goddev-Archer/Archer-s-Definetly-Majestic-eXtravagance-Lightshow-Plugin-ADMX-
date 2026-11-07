package de.archer.admx.audio;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sicherer Loopback-Audio-Empfänger (UDP-Port 127.0.0.1:54321) für Echtzeit-Audio (Option B).
 * <p>
 * Erfüllt strikte Sicherheitsanforderungen (SEC-01, SEC-03, PERF-09):
 * <ul>
 *   <li>Bindet AUSSCHLIESSLICH an 127.0.0.1 (Localhost) - keine externe Angriffsfläche.</li>
 *   <li>Verarbeitet eingehende 16-Bit PCM-Audio-Chunks vollständig asynchron im Hintergrund.</li>
 *   <li>Gedeckelte Paketgrößen (max. 4096 Bytes) gegen Buffer-Overflows und DoS-Angriffe.</li>
 * </ul>
 */
public final class LoopbackAudioReceiver implements Runnable, AutoCloseable {

    public static final int DEFAULT_PORT = 54321;
    private static final int BUFFER_SIZE = 4096;

    private final int port;
    private final java.util.function.Consumer<double[]> pcmConsumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;
    private Thread workerThread;

    public LoopbackAudioReceiver(int port, java.util.function.Consumer<double[]> pcmConsumer) {
        this.port = port;
        this.pcmConsumer = (pcmConsumer != null) ? pcmConsumer : pcm -> {};
    }

    public LoopbackAudioReceiver(int port, BeatDetector detector) {
        this(port, (detector != null) ? detector::processBlock : null);
    }

    public LoopbackAudioReceiver(BeatDetector detector) {
        this(DEFAULT_PORT, detector);
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        try {
            // Strikt an Localhost (127.0.0.1) binden, niemals an 0.0.0.0! (SEC-01 / SEC-03)
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            this.socket = new DatagramSocket(port, loopback);
            this.socket.setSoTimeout(1000);
            this.running.set(true);

            this.workerThread = new Thread(this, "ADMX-Audio-Loopback-Worker");
            this.workerThread.setDaemon(true);
            this.workerThread.start();
            Bukkit.getLogger().info("[ADMX-Audio] Loopback-Audio-Stream aktiv auf 127.0.0.1:" + port);
        } catch (IOException e) {
            Bukkit.getLogger().warning("[ADMX-Audio] Konnte Loopback-Audio-Socket nicht an Port " + port + " binden: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running.get()) {
            try {
                socket.receive(packet);
                // Nur Pakete vom Loopback akzeptieren
                if (!packet.getAddress().isLoopbackAddress()) {
                    continue;
                }

                int bytesRead = packet.getLength();
                int sampleCount = bytesRead / 2; // 16-Bit Samples = 2 Bytes
                if (sampleCount <= 0) {
                    continue;
                }

                ByteBuffer bb = ByteBuffer.wrap(packet.getData(), packet.getOffset(), bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN);

                double[] pcm = new double[sampleCount];
                for (int i = 0; i < sampleCount; i++) {
                    short raw = bb.getShort();
                    pcm[i] = raw / 32768.0; // Normalisieren auf [-1.0 .. 1.0]
                }

                // Asynchron in die Audio-Pipeline einspeisen
                pcmConsumer.accept(pcm);
            } catch (java.net.SocketTimeoutException ignored) {
                // Timeout alle 1 Sekunde zur Prüfung von running.get()
            } catch (IOException e) {
                if (running.get()) {
                    Bukkit.getLogger().warning("[ADMX-Audio] Fehler beim Empfang von Audio-Daten: " + e.getMessage());
                }
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
            socket = null;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }
}
