#!/usr/bin/env python3
"""PC Mic Inject — 入口文件
默认启动GUI；带 --headless 参数则进入命令行模式
"""

import sys
import signal


def run_headless():
    """命令行模式（向后兼容）"""
    from streamer_core import AudioStreamerCore

    core = AudioStreamerCore()
    core.on_log = lambda msg: print(msg)
    core.on_status = lambda s: print(f"[状态] {s}")
    core.on_client_change = lambda c: print(f"[客户端] {', '.join(c) if c else '无'}")
    core.on_error = lambda e: print(f"[错误] {e}", file=sys.stderr)

    running = True

    def on_signal(sig, frame):
        nonlocal running
        running = False
        print("\n正在退出...")
        core.stop()

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    # 设备选择
    idx = None
    if "--device" in sys.argv:
        try:
            pos = sys.argv.index("--device")
            idx = int(sys.argv[pos + 1])
        except (IndexError, ValueError):
            print("用法: --device <设备编号>")
            return

    if "--list" in sys.argv:
        devices = core.list_input_devices()
        print("\n可用音频输入设备:")
        print("-" * 60)
        for d in devices:
            mark = " <<<" if d["is_virtual"] else ""
            print(f"  [{d['index']}] {d['name']} (ch={d['channels']}, {d['samplerate']}Hz){mark}")
        print("-" * 60)
        return

    if idx is None:
        idx = core.get_default_input_device_index()
        if idx is None:
            print("[错误] 未找到任何可用的音频输入设备")
            return

    core.on_stopped = lambda: None
    core.start(idx)

    # 等待直到退出
    import time
    try:
        while running and core.is_running:
            time.sleep(0.5)
    except KeyboardInterrupt:
        core.stop()


def run_gui():
    """GUI模式"""
    from streamer_ui import StreamerApp
    app = StreamerApp()
    app.run()


def main():
    if "--headless" in sys.argv:
        run_headless()
    elif "--list" in sys.argv or "--device" in sys.argv:
        run_headless()
    else:
        run_gui()


if __name__ == "__main__":
    main()
