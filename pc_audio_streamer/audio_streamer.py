#!/usr/bin/env python3
"""PC Audio Streamer — 采集变声器输出，48kHz/2ch/24bit TCP 推流 + UDP 自动发现"""

import socket
import struct
import threading
import queue
import sys
import signal
import array
import json
import platform
import sounddevice as sd

HOST = "0.0.0.0"
PORT = 9876
DISCOVERY_PORT = 9877
TARGET_RATE = 48000
TARGET_CH = 2
BYTES_PER_SAMPLE = 3  # 24bit
# 20ms @ 48kHz stereo 24bit = 960 * 2 * 3 = 5760 bytes
FRAME_SAMPLES = 960
FRAME_BYTES = FRAME_SAMPLES * TARGET_CH * BYTES_PER_SAMPLE

audio_queue: queue.Queue[bytes] = queue.Queue(maxsize=200)
running = True

dev_rate = TARGET_RATE
dev_ch = 2


def get_local_ip():
    """获取本机局域网 IP"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def discovery_broadcast():
    """每 2 秒 UDP 广播自身信息到局域网"""
    local_ip = get_local_ip()
    pc_name = platform.node() or "PC"
    payload = json.dumps({
        "name": pc_name,
        "ip": local_ip,
        "port": PORT
    }).encode("utf-8")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(1.0)
    print(f"[udp] Broadcasting discovery: {pc_name} @ {local_ip}:{PORT}")

    while running:
        try:
            sock.sendto(payload, ("255.255.255.255", DISCOVERY_PORT))
        except Exception:
            pass
        try:
            sock.settimeout(2.0)
            sock.recvfrom(1)  # just sleep ~2s
        except socket.timeout:
            pass
        except Exception:
            pass
    sock.close()


def list_devices():
    print("\n可用音频输入设备:")
    print("-" * 60)
    devices = sd.query_devices()
    for i, d in enumerate(devices):
        if d["max_input_channels"] > 0:
            mark = " <<<" if d["name"].lower().find("virtual") >= 0 else ""
            sr = int(d["default_samplerate"])
            print(f"  [{i}] {d['name']} (ch={d['max_input_channels']}, {sr}Hz){mark}")
    print("-" * 60 + "\n")


def int32_to_24le(val):
    """int32 sample -> 3 bytes little-endian 24bit"""
    v = max(-8388608, min(8388607, val >> 8))
    return struct.pack("<i", v)[:3]


def convert_to_48k_stereo_24bit(data, channels, rate):
    """任意输入 → 48kHz stereo 24bit PCM bytes"""
    samples = array.array("i")
    samples.frombytes(data)
    total = len(samples)
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
        out += int32_to_24le(left[i])
        out += int32_to_24le(right[i])
    return bytes(out)


def audio_callback(indata, frames, time_info, status):
    if status:
        print(f"[audio] {status}", file=sys.stderr)
    try:
        pcm = convert_to_48k_stereo_24bit(bytes(indata), dev_ch, dev_rate)
        audio_queue.put_nowait(pcm)
    except queue.Full:
        pass


def send_loop(conn: socket.socket, addr):
    print(f"[tcp] 客户端已连接: {addr}")
    try:
        while running:
            try:
                data = audio_queue.get(timeout=0.5)
            except queue.Empty:
                conn.sendall(struct.pack("<I", 0))
                continue
            offset = 0
            while offset < len(data):
                chunk = data[offset : offset + FRAME_BYTES]
                header = struct.pack("<I", len(chunk))
                conn.sendall(header + chunk)
                offset += FRAME_BYTES
    except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
        print(f"[tcp] 客户端断开: {addr}")
    finally:
        conn.close()


def server_loop(device_index: int):
    global dev_rate, dev_ch

    info = sd.query_devices(device_index)
    dev_rate = int(info["default_samplerate"])
    dev_ch = min(int(info["max_input_channels"]), 8)
    blocksize = int(dev_rate * 0.02)  # 20ms

    print(f"[audio] 设备参数: {dev_ch}ch, {dev_rate}Hz, blocksize={blocksize}")
    print(f"[audio] 输出格式: {TARGET_CH}ch, {TARGET_RATE}Hz, 24bit")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.settimeout(1.0)
    srv.bind((HOST, PORT))
    srv.listen(1)
    print(f"[tcp] 监听 {HOST}:{PORT} ...")

    # 启动 UDP 自动发现广播
    disc_thread = threading.Thread(target=discovery_broadcast, daemon=True)
    disc_thread.start()

    stream = sd.InputStream(
        device=device_index,
        samplerate=dev_rate,
        channels=dev_ch,
        dtype="int32",
        blocksize=blocksize,
        callback=audio_callback,
    )
    stream.start()
    print("[audio] 音频采集已启动，等待手机连接...")

    try:
        while running:
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            while not audio_queue.empty():
                try:
                    audio_queue.get_nowait()
                except queue.Empty:
                    break
            t = threading.Thread(target=send_loop, args=(conn, addr), daemon=True)
            t.start()
    finally:
        stream.stop()
        stream.close()
        srv.close()
        print("[tcp] 服务器已关闭")


def main():
    global running
    list_devices()
    try:
        idx = int(input("请输入设备编号: "))
    except (ValueError, EOFError):
        print("无效输入")
        return

    def on_signal(sig, frame):
        global running
        running = False
        print("\n正在退出...")

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    server_loop(idx)


if __name__ == "__main__":
    main()