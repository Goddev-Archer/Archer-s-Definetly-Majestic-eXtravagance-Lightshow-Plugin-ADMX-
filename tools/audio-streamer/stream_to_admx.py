#!/usr/bin/env python3
"""
ADMX Loopback Audio Streamer (Option B)
--------------------------------------
Sendet Live-Audio (Windows Lautsprecher-Loopback, Stereo-Mix, Virtual Audio Cable, Spotify, DJ-Software)
oder eine .wav-Audiodatei per UDP an das ADMX-Plugin (127.0.0.1:54321).

Installation der Voraussetzungen:
    1. Für direkten Windows Lautsprecher-Loopback (WASAPI - fängt YouTube/Spotify/DJ-Sets direkt ab):
       pip install pyaudiowpatch numpy
    
    ODER 2. Standard mit sounddevice (Mikrofon, Stereo Mix oder Virtual Audio Cable):
       pip install sounddevice numpy

Verwendung:
    1. System-Audio (YouTube / DJ-Set) direkt streamen:
       python stream_to_admx.py --loopback

    2. Standard-Mikrofon / Stereo-Mix streamen:
       python stream_to_admx.py

    3. Alle verfügbaren Audiogeräte auflisten:
       python stream_to_admx.py --list-devices

    4. Bestimmtes Eingabegerät nach Index wählen:
       python stream_to_admx.py --device 2

    5. Eine WAV-Datei streamen:
       python stream_to_admx.py --file track.wav
"""

import sys
import time
import socket
import argparse
import numpy as np

TARGET_HOST = "127.0.0.1"
TARGET_PORT = 54321
SAMPLE_RATE = 44100
BLOCK_SIZE = 512

def run_wasapi_loopback():
    """Streamt direkt den Sound der Standard-Lautsprecher unter Windows (WASAPI Loopback)."""
    try:
        import pyaudiowpatch as pyaudio
    except ImportError:
        print("[ADMX] HINWEIS: 'pyaudiowpatch' ist nicht installiert.")
        print("[ADMX] Installiere es mit: pip install pyaudiowpatch numpy")
        print("[ADMX] Wechsle auf 'sounddevice'...")
        return False

    p = pyaudio.PyAudio()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    try:
        wasapi_info = p.get_host_api_info_by_type(pyaudio.paWASAPI)
    except OSError:
        print("[ADMX] WASAPI wird auf diesem System nicht unterstützt.")
        p.terminate()
        return False

    default_speakers = p.get_device_info_by_index(wasapi_info["defaultOutputDevice"])
    print(f"[ADMX] Standard-Ausgabegerät: {default_speakers['name']}")

    loopback_dev = None
    for loopback in p.get_loopback_device_info_generator():
        if default_speakers["name"] in loopback["name"]:
            loopback_dev = loopback
            break

    if not loopback_dev:
        # Fallback: Erstes verfügbares Loopback-Gerät
        for loopback in p.get_loopback_device_info_generator():
            loopback_dev = loopback
            break

    if not loopback_dev:
        print("[ADMX] Kein WASAPI Loopback-Gerät gefunden.")
        p.terminate()
        return False

    print(f"[ADMX] WASAPI Loopback aktiv auf: {loopback_dev['name']}")
    print(f"[ADMX] Streame Live-Systemaudio nach {TARGET_HOST}:{TARGET_PORT}...")

    channels = min(2, loopback_dev["maxInputChannels"])
    native_rate = int(loopback_dev["defaultSampleRate"])

    def callback(in_data, frame_count, time_info, status):
        audio_data = np.frombuffer(in_data, dtype=np.int16)
        if channels > 1:
            audio_data = audio_data.reshape(-1, channels).mean(axis=1).astype(np.int16)
        
        # Auf 44.1 kHz resamplen, falls Native Rate abweicht (z. B. 48 kHz)
        if native_rate != SAMPLE_RATE and len(audio_data) > 0:
            target_len = int(len(audio_data) * (SAMPLE_RATE / native_rate))
            if target_len > 0:
                indices = np.linspace(0, len(audio_data) - 1, target_len)
                audio_data = np.interp(indices, np.arange(len(audio_data)), audio_data).astype(np.int16)

        sock.sendto(audio_data.tobytes(), (TARGET_HOST, TARGET_PORT))
        return (None, pyaudio.paContinue)

    stream = p.open(
        format=pyaudio.paInt16,
        channels=channels,
        rate=native_rate,
        input=True,
        input_device_index=loopback_dev["index"],
        frames_per_buffer=BLOCK_SIZE,
        stream_callback=callback
    )

    stream.start_stream()
    print("[ADMX] Streaming aktiv! Drücke Strg+C zum Beenden.")
    try:
        while stream.is_active():
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[ADMX] Stream gestoppt.")
    finally:
        stream.stop_stream()
        stream.close()
        p.terminate()
        sock.close()
    return True

def run_sounddevice_stream(device_id=None):
    try:
        import sounddevice as sd
    except ImportError:
        print("[ADMX] FEHLER: Weder 'pyaudiowpatch' noch 'sounddevice' ist installiert.")
        print("[ADMX] Führe aus: pip install sounddevice numpy")
        sys.exit(1)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print(f"[ADMX] Starte Live-Audio Stream nach {TARGET_HOST}:{TARGET_PORT} via sounddevice...")

    def callback(indata, frames, time_info, status):
        if status:
            print(f"[ADMX] Status: {status}", file=sys.stderr)
        mono = np.mean(indata, axis=1) if indata.ndim > 1 else indata
        pcm16 = (np.clip(mono, -1.0, 1.0) * 32767.0).astype(np.int16)
        sock.sendto(pcm16.tobytes(), (TARGET_HOST, TARGET_PORT))

    try:
        with sd.InputStream(device=device_id, channels=1, samplerate=SAMPLE_RATE, blocksize=BLOCK_SIZE, callback=callback):
            print("[ADMX] Streaming aktiv! Drücke Strg+C zum Beenden.")
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        print("\n[ADMX] Stream gestoppt.")
    finally:
        sock.close()

def run_file_stream(filepath):
    import wave
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print(f"[ADMX] Streame Datei '{filepath}' nach {TARGET_HOST}:{TARGET_PORT}...")

    with wave.open(filepath, 'rb') as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        framerate = wf.getframerate()
        print(f"[ADMX] Audio-Info: {n_channels} Kanäle, {framerate} Hz, {sampwidth * 8} Bit")

        chunk_frames = 512
        sleep_sec = chunk_frames / framerate

        try:
            while True:
                data = wf.readframes(chunk_frames)
                if not data:
                    wf.rewind()
                    continue

                if sampwidth == 2:
                    raw_arr = np.frombuffer(data, dtype=np.int16)
                    if n_channels > 1:
                        raw_arr = raw_arr.reshape(-1, n_channels).mean(axis=1).astype(np.int16)
                    sock.sendto(raw_arr.tobytes(), (TARGET_HOST, TARGET_PORT))
                time.sleep(sleep_sec)
        except KeyboardInterrupt:
            print("\n[ADMX] Datei-Stream beendet.")
        finally:
            sock.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ADMX Loopback Audio Streamer")
    parser.add_argument("--loopback", action="store_true", help="Windows Lautsprecher Loopback via WASAPI (pyaudiowpatch)")
    parser.add_argument("--list-devices", action="store_true", help="Listet alle verfügbaren Audiogeräte auf")
    parser.add_argument("--device", type=int, default=None, help="Index des Audio-Eingabegeräts")
    parser.add_argument("--file", type=str, default=None, help="Pfad zu einer WAV-Datei zum Streamen")

    args = parser.parse_args()

    if args.list_devices:
        try:
            import sounddevice as sd
            print("--- sounddevice Geräte ---")
            print(sd.query_devices())
        except ImportError:
            pass
        try:
            import pyaudiowpatch as pyaudio
            p = pyaudio.PyAudio()
            print("\n--- WASAPI Loopback Geräte ---")
            for loop in p.get_loopback_device_info_generator():
                print(f"Index {loop['index']}: {loop['name']}")
            p.terminate()
        except ImportError:
            pass
    elif args.file:
        run_file_stream(args.file)
    elif args.loopback:
        if not run_wasapi_loopback():
            run_sounddevice_stream(args.device)
    else:
        # Standard: Versuche erst Loopback, falls pyaudiowpatch vorhanden, sonst sounddevice
        try:
            import pyaudiowpatch
            if not run_wasapi_loopback():
                run_sounddevice_stream(args.device)
        except ImportError:
            run_sounddevice_stream(args.device)
