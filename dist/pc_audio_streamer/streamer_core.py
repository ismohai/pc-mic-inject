#!/usr/bin/env python3
"""PC Audio Streamer Core — captures audio and sends raw 16-bit PCM to phone daemon."""

import socket
import struct
import threading
import array
import time
import sounddevice as sd

TARGET_RATE = 48000
TARGET_CH = 2


class AudioStreamerCore:
    """Captures PC audio and streams raw 16-bit PCM to phone daemon via TCP."""

    def __init__(self):
        self._running = False
        self._device_index: int | None = None
        self._dev_rate = TARGET_RATE
        self._dev_ch = 2
        self._stream = None
        self._socket: socket.socket | None = None
        self._sock_lock = threading.Lock()

        self.on_log: callable = None
        self.on_status: callable = None
        self.on_error: callable = None
        self.on_stopped: callable = None

    def _log(self, msg: str):
        if self.on_log:
            self.on_log(msg)

    def _set_status(self, s: str):
        if self.on_status:
            self.on_status(s)

    @staticmethod
    def list_input_devices() -> list[dict]:
        result = []
        for i, d in enumerate(sd.query_devices()):
            if d["max_input_channels"] > 0:
                result.append({
                    "index": i,
                    "name": d["name"],
                    "channels": d["max_input_channels"],
                    "samplerate": int(d["default_samplerate"]),
                    "is_virtual": any(k in d["name"].lower() for k in
                                      ["virtual", "cable", "vb-audio", "stereo mix",
                                       "loopback", "wasapi"]),
                })
        return result

    @staticmethod
    def get_default_input_device_index() -> int | None:
        try:
            info = sd.query_devices(kind='input')
            for i, d in enumerate(sd.query_devices()):
                if d['name'] == info['name'] and d['max_input_channels'] == info['max_input_channels']:
                    return i
        except Exception:
            pass
        for i, d in enumerate(sd.query_devices()):
            if d['max_input_channels'] > 0:
                return i
        return None

    @staticmethod
    def get_local_ip() -> str:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def _convert_to_16bit_stereo_48k(self, data: bytes) -> bytes:
        """Convert int32 input to 16-bit signed LE stereo 48kHz PCM."""
        samples = array.array("i")
        samples.frombytes(data)
        total = len(samples)
        ch = self._dev_ch
        frames = total // ch

        if ch >= 2:
            left = [samples[i * ch] >> 16 for i in range(frames)]
            right = [samples[i * ch + 1] >> 16 for i in range(frames)]
        else:
            mono = [samples[i] >> 16 for i in range(frames)]
            left = right = mono

        # Resample if needed
        if self._dev_rate != TARGET_RATE and frames > 1:
            out_len = int(frames * TARGET_RATE / self._dev_rate)
            nl, nr = [], []
            for i in range(out_len):
                pos = i * (frames - 1) / max(out_len - 1, 1)
                idx = int(pos)
                frac = pos - idx
                idx1 = min(idx + 1, frames - 1)
                nl.append(int(left[idx] + frac * (left[idx1] - left[idx])))
                nr.append(int(right[idx] + frac * (right[idx1] - right[idx])))
            left, right = nl, nr

        # Interleave L/R as 16-bit signed LE
        out = bytearray(len(left) * 4)
        for i in range(len(left)):
            struct.pack_into("<hh", out, i * 4,
                             max(-32768, min(32767, left[i])),
                             max(-32768, min(32767, right[i])))
        return bytes(out)

    def _audio_callback(self, indata, frames, time_info, status):
        if status:
            self._log(f"[Audio] {status}")
        try:
            pcm = self._convert_to_16bit_stereo_48k(bytes(indata))
            with self._sock_lock:
                if self._socket:
                    self._socket.sendall(pcm)
        except (BrokenPipeError, ConnectionResetError, OSError):
            self._log("[TCP] Connection lost")
            self._running = False
        except Exception:
            pass

    def _stream_loop(self, phone_ip: str, phone_port: int):
        info = sd.query_devices(self._device_index)
        self._dev_rate = int(info["default_samplerate"])
        self._dev_ch = min(int(info["max_input_channels"]), 8)
        blocksize = int(self._dev_rate * 0.02)

        self._log(f"[Audio] Device: [{self._device_index}] {info['name']}")
        self._log(f"[Audio] Input: {self._dev_ch}ch {self._dev_rate}Hz")
        self._log(f"[Audio] Output: 2ch 48000Hz 16bit PCM")
        self._set_status("Connecting...")
        self._log(f"[TCP] Connecting to {phone_ip}:{phone_port}...")

        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5.0)
            sock.connect((phone_ip, phone_port))
            sock.settimeout(None)
            with self._sock_lock:
                self._socket = sock
            self._log(f"[TCP] Connected to phone!")
        except Exception as e:
            self._log(f"[Error] Cannot connect: {e}")
            if self.on_error:
                self.on_error(f"Cannot connect to {phone_ip}:{phone_port}: {e}")
            self._running = False
            if self.on_stopped:
                self.on_stopped()
            return

        # Open audio stream
        stream = None
        for try_ch in [self._dev_ch, 2, 1]:
            try:
                stream = sd.InputStream(
                    device=self._device_index, samplerate=self._dev_rate,
                    channels=try_ch, dtype="int32", blocksize=blocksize,
                    callback=self._audio_callback)
                self._dev_ch = try_ch
                break
            except Exception as e:
                if try_ch == 1:
                    self._log(f"[Error] Cannot open audio: {e}")
                    self._running = False
                    sock.close()
                    if self.on_stopped:
                        self.on_stopped()
                    return

        stream.start()
        self._stream = stream
        self._set_status("Streaming")
        self._log("[Audio] Streaming to phone...")

        try:
            while self._running:
                time.sleep(0.5)
        finally:
            stream.stop()
            stream.close()
            with self._sock_lock:
                if self._socket:
                    try:
                        self._socket.close()
                    except Exception:
                        pass
                    self._socket = None
            self._log("[TCP] Disconnected")
            self._set_status("Stopped")
            if self.on_stopped:
                self.on_stopped()

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self, device_index: int, phone_ip: str, phone_port: int = 9876):
        if self._running:
            return
        self._device_index = device_index
        self._running = True
        threading.Thread(target=self._stream_loop, args=(phone_ip, phone_port), daemon=True).start()

    def stop(self):
        self._running = False
        with self._sock_lock:
            if self._socket:
                try:
                    self._socket.close()
                except Exception:
                    pass
                self._socket = None
