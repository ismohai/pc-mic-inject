#!/usr/bin/env python3
"""PC Audio Streamer — 采集变声器输出音频，通过 TCP 推流到手机"""

import socket
import struct
import threading
import queue
import sys
import signal
import sounddevice as sd

HOST = "0.0.0.0"
PORT = 9876
SAMPLE_RATE = 16000
CHANNELS = 1
DTYPE = "int16"
FRAME_SAMPLES = 320          # 20ms @ 16kHz
FRAME_BYTES = FRAME_SAMPLES * 2  # 640 bytes per frame

audio_queue: queue.Queue[bytes] = queue.Queue(maxsize=200)
running = True


def list_devices():
    """列出所有可用音频设备"""
    print("\n可用音频输入设备:")
    print("-" * 60)
    devices = sd.query_devices()
    for i, d in enumerate(devices):
        if d["max_input_channels"] > 0:
            mark = " <<<" if d["name"].lower().find("virtual") >= 0 else ""
            print(f"  [{i}] {d['name']} (ch={d['max_input_channels']}){mark}")
    print("-" * 60 + "\n")


def audio_callback(indata, frames, time_info, status):
    """sounddevice 回调：将采集到的 PCM 数据放入队列"""
    if status:
        print(f"[audio] {status}", file=sys.stderr)
    try:
        audio_queue.put_nowait(bytes(indata))
    except queue.Full:
        pass


def send_loop(conn: socket.socket, addr):
    """从队列取数据，按帧协议发送给手机"""
    print(f"[tcp] 客户端已连接: {addr}")
    try:
        while running:
            try:
                data = audio_queue.get(timeout=0.5)
            except queue.Empty:
                conn.sendall(struct.pack("<I", 0))  # 心跳
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
    """TCP Server 主循环"""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.settimeout(1.0)
    srv.bind((HOST, PORT))
    srv.listen(1)
    print(f"[tcp] 监听 {HOST}:{PORT} ...")

    stream = sd.InputStream(
        device=device_index,
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype=DTYPE,
        blocksize=FRAME_SAMPLES,
        callback=audio_callback,
    )
    stream.start()
    print("[audio] 音频采集已启动")

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
