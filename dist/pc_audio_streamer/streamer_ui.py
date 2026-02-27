#!/usr/bin/env python3
"""PC Mic Inject — tkinter GUI"""

import tkinter as tk
from tkinter import ttk, scrolledtext
import threading
from streamer_core import AudioStreamerCore


class StreamerApp:
    """PC Mic Inject 推流工具 GUI"""

    def __init__(self):
        self.core = AudioStreamerCore()
        self.core.on_log = self._on_log
        self.core.on_status = self._on_status
        self.core.on_client_change = self._on_client_change
        self.core.on_error = self._on_error
        self.core.on_stopped = self._on_stopped

        self.devices: list[dict] = []
        self._build_ui()

    def _build_ui(self):
        self.root = tk.Tk()
        self.root.title("PC Mic Inject")
        self.root.geometry("520x580")
        self.root.resizable(False, False)
        self.root.configure(bg="#f5f5f5")

        # 尝试设置图标（忽略错误）
        try:
            self.root.iconbitmap(default="")
        except Exception:
            pass

        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#f5f5f5")
        style.configure("TLabel", background="#f5f5f5", font=("Microsoft YaHei UI", 10))
        style.configure("Title.TLabel", background="#f5f5f5", font=("Microsoft YaHei UI", 16, "bold"))
        style.configure("Status.TLabel", background="#f5f5f5", font=("Microsoft YaHei UI", 11))
        style.configure("Info.TLabel", background="#f5f5f5", font=("Microsoft YaHei UI", 9), foreground="#666666")
        style.configure("TButton", font=("Microsoft YaHei UI", 10))
        style.configure("Start.TButton", font=("Microsoft YaHei UI", 11, "bold"))
        style.configure("TCombobox", font=("Microsoft YaHei UI", 10))

        main = ttk.Frame(self.root, padding=16)
        main.pack(fill=tk.BOTH, expand=True)

        # ---- 标题 ----
        ttk.Label(main, text="PC Mic Inject", style="Title.TLabel").pack(anchor=tk.W)
        ttk.Label(main, text="电脑音频推流到手机麦克风", style="Info.TLabel").pack(anchor=tk.W, pady=(0, 12))

        # ---- 设备选择区 ----
        dev_frame = ttk.LabelFrame(main, text="音频输入设备", padding=8)
        dev_frame.pack(fill=tk.X, pady=(0, 8))

        self.device_var = tk.StringVar()
        self.device_combo = ttk.Combobox(dev_frame, textvariable=self.device_var,
                                         state="readonly", width=52)
        self.device_combo.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))

        self.refresh_btn = ttk.Button(dev_frame, text="刷新", width=6, command=self._refresh_devices)
        self.refresh_btn.pack(side=tk.RIGHT)

        # ---- 控制区 ----
        ctrl_frame = ttk.Frame(main)
        ctrl_frame.pack(fill=tk.X, pady=(0, 8))

        self.start_btn = ttk.Button(ctrl_frame, text="▶ 开始推流", style="Start.TButton",
                                     command=self._toggle_stream, width=16)
        self.start_btn.pack(side=tk.LEFT)

        self.status_label = ttk.Label(ctrl_frame, text="就绪", style="Status.TLabel")
        self.status_label.pack(side=tk.LEFT, padx=(12, 0))

        # ---- 连接信息 ----
        info_frame = ttk.LabelFrame(main, text="连接信息", padding=8)
        info_frame.pack(fill=tk.X, pady=(0, 8))

        ip = AudioStreamerCore.get_local_ip()
        self.ip_label = ttk.Label(info_frame, text=f"本机IP: {ip}    端口: {self.core.port}")
        self.ip_label.pack(anchor=tk.W)

        self.client_label = ttk.Label(info_frame, text="已连接客户端: 无", style="Info.TLabel")
        self.client_label.pack(anchor=tk.W, pady=(4, 0))

        # ---- 日志区 ----
        log_frame = ttk.LabelFrame(main, text="日志", padding=4)
        log_frame.pack(fill=tk.BOTH, expand=True)

        self.log_text = scrolledtext.ScrolledText(
            log_frame, height=12, wrap=tk.WORD,
            font=("Consolas", 9), bg="#1e1e1e", fg="#d4d4d4",
            insertbackground="#d4d4d4", selectbackground="#264f78",
            relief=tk.FLAT, bd=0
        )
        self.log_text.pack(fill=tk.BOTH, expand=True)
        self.log_text.configure(state=tk.DISABLED)

        # ---- 底部信息 ----
        ttk.Label(main, text="提示: 手机端安装 KernelSU 模块后，启动推流即可自动替换麦克风",
                  style="Info.TLabel").pack(anchor=tk.W, pady=(6, 0))

        # 初始化设备列表
        self._refresh_devices()

        # 窗口关闭处理
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _refresh_devices(self):
        self.devices = AudioStreamerCore.list_input_devices()
        names = []
        default_idx = AudioStreamerCore.get_default_input_device_index()
        select = 0
        for i, d in enumerate(self.devices):
            mark = " ★" if d["is_virtual"] else ""
            label = f"[{d['index']}] {d['name']} ({d['channels']}ch, {d['samplerate']}Hz){mark}"
            names.append(label)
            if d["index"] == default_idx:
                select = i

        self.device_combo["values"] = names
        if names:
            self.device_combo.current(select)
        self._log_append("[系统] 刷新设备列表完成，共 {} 个输入设备".format(len(self.devices)))

    def _toggle_stream(self):
        if self.core.is_running:
            self._stop_stream()
        else:
            self._start_stream()

    def _start_stream(self):
        idx = self.device_combo.current()
        if idx < 0 or idx >= len(self.devices):
            self._log_append("[错误] 请先选择音频输入设备")
            return

        device = self.devices[idx]
        self.start_btn.configure(text="⏹ 停止推流")
        self.device_combo.configure(state=tk.DISABLED)
        self.refresh_btn.configure(state=tk.DISABLED)
        self.status_label.configure(text="启动中...")
        self.core.start(device["index"])

    def _stop_stream(self):
        self.start_btn.configure(state=tk.DISABLED)
        self.status_label.configure(text="正在停止...")
        threading.Thread(target=self._do_stop, daemon=True).start()

    def _do_stop(self):
        self.core.stop()

    def _on_stopped(self):
        self.root.after(0, self._reset_ui)

    def _reset_ui(self):
        self.start_btn.configure(text="▶ 开始推流", state=tk.NORMAL)
        self.device_combo.configure(state="readonly")
        self.refresh_btn.configure(state=tk.NORMAL)
        self.status_label.configure(text="已停止")
        self.client_label.configure(text="已连接客户端: 无")

    # ---- 回调（从工作线程调用） ----
    def _on_log(self, msg: str):
        self.root.after(0, self._log_append, msg)

    def _on_status(self, status: str):
        self.root.after(0, lambda: self.status_label.configure(text=status))

    def _on_client_change(self, clients: list[str]):
        def _update():
            if clients:
                self.client_label.configure(text=f"已连接客户端: {', '.join(clients)}")
                self.status_label.configure(text="推流中 — 已连接")
            else:
                self.client_label.configure(text="已连接客户端: 无")
                if self.core.is_running:
                    self.status_label.configure(text="推流中 — 等待手机连接")
        self.root.after(0, _update)

    def _on_error(self, err: str):
        self.root.after(0, lambda: self._log_append(f"[错误] {err}"))

    def _log_append(self, msg: str):
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, msg + "\n")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def _on_close(self):
        if self.core.is_running:
            self.core.stop()
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def main():
    app = StreamerApp()
    app.run()


if __name__ == "__main__":
    main()
