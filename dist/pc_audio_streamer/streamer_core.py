#!/usr/bin/env python3
"""PC Audio Streamer Core — 音频采集 + TCP推流 + UDP自动发现"""

import socket
import struct
import threading
import queue
import array
import json
import platform
import time
import sounddevice as sd


# ---------- 常量 ----------
HOST = "0.0.0.0"
DEFAULT_PORT = 9876
DISCOVERY_PORT = 9877
TARGET_RATE = 48000
TARGET_CH = 2
BYTES_PER_SAMPLE = 3  # 24bit
FRAME_SAMPLES = 960
FRAME_BYTES = FRAME_SAMPLES * TARGET_CH * BYTES_PER_SAMPLE


class AudioStreamerCore:
    """核心推流引擎，提供回调接口给UI层"""

    def __init__(self, port: int = DEFAULT_PORT):
        self.port = port
        self._running = False
        self._audio_queue: queue.Queue[bytes] = queue.Queue(maxsize=200)

        self._dev_rate = TARGET_RATE
        self._dev_ch = 2
        self._device_index: int | None = None

        self._stream = None
        self._server_socket: socket.socket | None = None
        self._clients: list[tuple[socket.socket, tuple]] = []
        self._clients_lock = threading.Lock()

        # 回调
        self.on_log: callable = None           # (msg: str) -> None
        self.on_status: callable = None        # (status: str) -> None
        self.on_client_change: callable = None # (clients: list[str]) -> None
        self.on_error: callable = None         # (err: str) -> None
        self.on_stopped: callable = None       # () -> None

    # ---------- 日志 ----------
    def _log(self, msg: str):
        if self.on_log:
            self.on_log(msg)

    def _set_status(self, status: str):
        if self.on_status:
            self.on_status(status)

    def _notify_clients(self):
        with self._clients_lock:
            addrs = [f"{a[0]}:{a[1]}" for _, a in self._clients]
        if self.on_client_change:
            self.on_client_change(addrs)

    # ---------- 设备枚举 ----------
    @staticmethod
    def list_input_devices() -> list[dict]:
        """返回所有可用音频输入设备: [{index, name, channels, samplerate, is_virtual}]"""
        result = []
        devices = sd.query_devices()
        for i, d in enumerate(devices):
            if d["max_input_channels"] > 0:
                result.append({
                    "index": i,
                    "name": d["name"],
                    "channels": d["max_input_channels"],
                    "samplerate": int(d["default_samplerate"]),
                    "is_virtual": "virtual" in d["name"].lower()
                        or "cable" in d["name"].lower()
                        or "vb-audio" in d["name"].lower(),
                })
        return result

    @staticmethod
    def get_default_input_device_index() -> int | None:
        """获取系统默认音频输入设备索引"""
        try:
            default_info = sd.query_devices(kind='input')
            devices = sd.query_devices()
            for i, d in enumerate(devices):
                if (d['name'] == default_info['name']
                        and d['max_input_channels'] == default_info['max_input_channels']):
                    return i
        except Exception:
            pass
        devices = sd.query_devices()
        for i, d in enumerate(devices):
            if d['max_input_channels'] > 0:
                return i
        return None

    # ---------- 网络工具 ----------
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

    @staticmethod
    def _get_broadcast_targets(local_ip: str) -> list[str]:
        targets = {"255.255.255.255"}
        parts = local_ip.split(".")
        if len(parts) == 4 and all(p.isdigit() for p in parts):
            targets.add(".".join(parts[:3] + ["255"]))
        return list(targets)

    # ---------- 音频转换 ----------
    @staticmethod
    def _int32_to_24le(val: int) -> bytes:
        v = max(-8388608, min(8388607, val >> 8))
        return struct.pack("<i", v)[:3]

    def _convert_to_48k_stereo_24bit(self, data: bytes) -> bytes:
        samples = array.array("i")
        samples.frombytes(data)
        total = len(samples)
        channels = self._dev_ch
        rate = self._dev_rate
        frames = total // channels

        if channels >= 2:
            left = [samples[i * channels] for i in range(frames)]
            right = [samples[i * channels + 1] for i in range(frames)]
        else:
            left = [samples[i * channels] for i in range(frames)]
            right = left

        if rate != TARGET_RATE:
            out_len = int(frames * TARGET_RATE / rate)
            new_left, new_right = [], []
            for i in range(out_len):
                pos = i * (frames - 1) / max(out_len - 1, 1)
                idx = int(pos)
                frac = pos - idx
                idx1 = min(idx + 1, frames - 1)
                new_left.append(int(left[idx] + frac * (left[idx1] - left[idx])))
                new_right.append(int(right[idx] + frac * (right[idx1] - right[idx])))
            left, right = new_left, new_right

        out = bytearray()
        for i in range(len(left)):
            out += self._int32_to_24le(left[i])
            out += self._int32_to_24le(right[i])
        return bytes(out)

    # ---------- 音频回调 ----------
    def _audio_callback(self, indata, frames, time_info, status):
        if status:
            self._log(f"[音频] {status}")
        try:
            pcm = self._convert_to_48k_stereo_24bit(bytes(indata))
            self._audio_queue.put_nowait(pcm)
        except queue.Full:
            pass

    # ---------- UDP 发现广播 ----------
    def _discovery_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self._log("[UDP] 自动发现广播已启动")
        while self._running:
            try:
                local_ip = self.get_local_ip()
                pc_name = platform.node() or "PC"
                payload = json.dumps({
                    "name": pc_name,
                    "ip": local_ip,
                    "port": self.port,
                }).encode("utf-8")
                for target in self._get_broadcast_targets(local_ip):
                    sock.sendto(payload, (target, DISCOVERY_PORT))
            except Exception:
                pass
            time.sleep(2)
        sock.close()

    # ---------- TCP 客户端发送循环 ----------
    def _send_loop(self, conn: socket.socket, addr):
        self._log(f"[TCP] 客户端已连接: {addr[0]}:{addr[1]}")
        with self._clients_lock:
            self._clients.append((conn, addr))
        self._notify_clients()
        try:
            while self._running:
                try:
                    data = self._audio_queue.get(timeout=0.5)
                except queue.Empty:
                    try:
                        conn.sendall(struct.pack("<I", 0))
                    except Exception:
                        break
                    continue
                offset = 0
                while offset < len(data):
                    chunk = data[offset: offset + FRAME_BYTES]
                    header = struct.pack("<I", len(chunk))
                    conn.sendall(header + chunk)
                    offset += FRAME_BYTES
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
            pass
        finally:
            self._log(f"[TCP] 客户端断开: {addr[0]}:{addr[1]}")
            with self._clients_lock:
                self._clients = [(c, a) for c, a in self._clients if c is not conn]
            self._notify_clients()
            try:
                conn.close()
            except Exception:
                pass

    # ---------- TCP 服务循环 ----------
    def _server_loop(self):
        info = sd.query_devices(self._device_index)
        self._dev_rate = int(info["default_samplerate"])
        self._dev_ch = min(int(info["max_input_channels"]), 8)
        blocksize = int(self._dev_rate * 0.02)

        self._log(f"[音频] 设备: [{self._device_index}] {info['name']}")
        self._log(f"[音频] 参数: {self._dev_ch}ch, {self._dev_rate}Hz, blocksize={blocksize}")
        self._log(f"[音频] 输出: {TARGET_CH}ch, {TARGET_RATE}Hz, 24bit")

        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.settimeout(1.0)
        srv.bind((HOST, self.port))
        srv.listen(2)
        self._server_socket = srv
        self._log(f"[TCP] 监听 {HOST}:{self.port}")

        # 启动 UDP 发现
        disc = threading.Thread(target=self._discovery_loop, daemon=True)
        disc.start()

        # 打开音频流
        stream = None
        for try_ch in [self._dev_ch, 2, 1]:
            try:
                stream = sd.InputStream(
                    device=self._device_index,
                    samplerate=self._dev_rate,
                    channels=try_ch,
                    dtype="int32",
                    blocksize=blocksize,
                    callback=self._audio_callback,
                )
                self._dev_ch = try_ch
                break
            except Exception as e:
                if try_ch == 1:
                    self._log(f"[错误] 无法打开音频设备: {e}")
                    if self.on_error:
                        self.on_error(f"无法打开音频设备: {e}")
                    self._running = False
                    srv.close()
                    if self.on_stopped:
                        self.on_stopped()
                    return
                self._log(f"[音频] {try_ch}ch 打开失败，降低通道数重试...")

        stream.start()
        self._stream = stream
        self._set_status("推流中 — 等待手机连接")
        self._log("[音频] 采集已启动，等待手机连接...")

        try:
            while self._running:
                try:
                    conn, addr = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                # 清空队列
                while not self._audio_queue.empty():
                    try:
                        self._audio_queue.get_nowait()
                    except queue.Empty:
                        break
                self._set_status("推流中 — 已连接")
                t = threading.Thread(target=self._send_loop, args=(conn, addr), daemon=True)
                t.start()
        finally:
            if stream:
                stream.stop()
                stream.close()
            srv.close()
            self._server_socket = None
            self._log("[TCP] 服务器已关闭")
            self._set_status("已停止")
            if self.on_stopped:
                self.on_stopped()

    # ---------- 公共接口 ----------
    @property
    def is_running(self) -> bool:
        return self._running

    def start(self, device_index: int):
        if self._running:
            return
        self._device_index = device_index
        self._running = True
        t = threading.Thread(target=self._server_loop, daemon=True)
        t.start()

    def stop(self):
        self._running = False
        # 关闭所有客户端连接
        with self._clients_lock:
            for conn, _ in self._clients:
                try:
                    conn.close()
                except Exception:
                    pass
            self._clients.clear()
        # 关闭服务器socket
        if self._server_socket:
            try:
                self._server_socket.close()
            except Exception:
                pass
        self._notify_clients()
