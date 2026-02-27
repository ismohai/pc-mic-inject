#!/usr/bin/env python3
"""PC Mic Inject — tkinter GUI. Connects TO phone daemon."""

import tkinter as tk
from tkinter import ttk, scrolledtext
import threading
from streamer_core import AudioStreamerCore


class StreamerApp:
    def __init__(self):
        self.core = AudioStreamerCore()
        self.core.on_log = self._on_log
        self.core.on_status = self._on_status
        self.core.on_error = self._on_error
        self.core.on_stopped = self._on_stopped
        self.devices: list[dict] = []
        self._build_ui()

    def _build_ui(self):
        self.root = tk.Tk()
        self.root.title("PC Mic Inject")
        self.root.geometry("520x620")
        self.root.resizable(False, False)
        self.root.configure(bg="#f5f5f5")

        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#f5f5f5")
        style.configure("TLabel", background="#f5f5f5", font=("Segoe UI", 10))
        style.configure("Title.TLabel", background="#f5f5f5", font=("Segoe UI", 16, "bold"))
        style.configure("Status.TLabel", background="#f5f5f5", font=("Segoe UI", 11))
        style.configure("Info.TLabel", background="#f5f5f5", font=("Segoe UI", 9), foreground="#666")
        style.configure("TButton", font=("Segoe UI", 10))
        style.configure("Start.TButton", font=("Segoe UI", 11, "bold"))

        main = ttk.Frame(self.root, padding=16)
        main.pack(fill=tk.BOTH, expand=True)

        ttk.Label(main, text="PC Mic Inject", style="Title.TLabel").pack(anchor=tk.W)
        ttk.Label(main, text="Stream PC audio to phone microphone",
                  style="Info.TLabel").pack(anchor=tk.W, pady=(0, 12))

        # Device selection
        dev_frame = ttk.LabelFrame(main, text="Audio Input Device", padding=8)
        dev_frame.pack(fill=tk.X, pady=(0, 8))
        self.device_var = tk.StringVar()
        self.device_combo = ttk.Combobox(dev_frame, textvariable=self.device_var,
                                         state="readonly", width=48)
        self.device_combo.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))
        self.refresh_btn = ttk.Button(dev_frame, text="Refresh", width=8,
                                       command=self._refresh_devices)
        self.refresh_btn.pack(side=tk.RIGHT)

        # Phone connection
        conn_frame = ttk.LabelFrame(main, text="Phone Connection", padding=8)
        conn_frame.pack(fill=tk.X, pady=(0, 8))

        ip_row = ttk.Frame(conn_frame)
        ip_row.pack(fill=tk.X, pady=(0, 4))
        ttk.Label(ip_row, text="Phone IP:").pack(side=tk.LEFT)
        self.ip_entry = ttk.Entry(ip_row, width=20)
        self.ip_entry.pack(side=tk.LEFT, padx=(6, 12))
        self.ip_entry.insert(0, "192.168.")
        ttk.Label(ip_row, text="Port:").pack(side=tk.LEFT)
        self.port_entry = ttk.Entry(ip_row, width=8)
        self.port_entry.pack(side=tk.LEFT, padx=(6, 0))
        self.port_entry.insert(0, "9876")

        ttk.Label(conn_frame, text="Enter your phone's WiFi IP address (Settings > WiFi > current network)",
                  style="Info.TLabel").pack(anchor=tk.W)

        # Controls
        ctrl_frame = ttk.Frame(main)
        ctrl_frame.pack(fill=tk.X, pady=(0, 8))
        self.start_btn = ttk.Button(ctrl_frame, text="Connect & Stream",
                                     style="Start.TButton", command=self._toggle, width=20)
        self.start_btn.pack(side=tk.LEFT)
        self.status_label = ttk.Label(ctrl_frame, text="Ready", style="Status.TLabel")
        self.status_label.pack(side=tk.LEFT, padx=(12, 0))

        # Log
        log_frame = ttk.LabelFrame(main, text="Log", padding=4)
        log_frame.pack(fill=tk.BOTH, expand=True)
        self.log_text = scrolledtext.ScrolledText(
            log_frame, height=14, wrap=tk.WORD,
            font=("Consolas", 9), bg="#1e1e1e", fg="#d4d4d4",
            relief=tk.FLAT, bd=0)
        self.log_text.pack(fill=tk.BOTH, expand=True)
        self.log_text.configure(state=tk.DISABLED)

        ttk.Label(main, text="Tip: Phone must have KSU module installed. Both devices on same WiFi.",
                  style="Info.TLabel").pack(anchor=tk.W, pady=(6, 0))

        self._refresh_devices()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _refresh_devices(self):
        self.devices = AudioStreamerCore.list_input_devices()
        names = []
        default_idx = AudioStreamerCore.get_default_input_device_index()
        select = 0
        for i, d in enumerate(self.devices):
            mark = " *" if d["is_virtual"] else ""
            names.append(f"[{d['index']}] {d['name']} ({d['channels']}ch){mark}")
            if d["index"] == default_idx:
                select = i
        self.device_combo["values"] = names
        if names:
            self.device_combo.current(select)
        self._log_append(f"[System] Found {len(self.devices)} input device(s)")

    def _toggle(self):
        if self.core.is_running:
            self._stop()
        else:
            self._start()

    def _start(self):
        idx = self.device_combo.current()
        if idx < 0 or idx >= len(self.devices):
            self._log_append("[Error] Select an audio device first")
            return
        ip = self.ip_entry.get().strip()
        if not ip:
            self._log_append("[Error] Enter phone IP address")
            return
        try:
            port = int(self.port_entry.get().strip())
        except ValueError:
            port = 9876

        self.start_btn.configure(text="Disconnect")
        self.device_combo.configure(state=tk.DISABLED)
        self.ip_entry.configure(state=tk.DISABLED)
        self.port_entry.configure(state=tk.DISABLED)
        self.refresh_btn.configure(state=tk.DISABLED)
        self.core.start(self.devices[idx]["index"], ip, port)

    def _stop(self):
        self.start_btn.configure(state=tk.DISABLED)
        self.status_label.configure(text="Stopping...")
        threading.Thread(target=self.core.stop, daemon=True).start()

    def _on_stopped(self):
        self.root.after(0, self._reset_ui)

    def _reset_ui(self):
        self.start_btn.configure(text="Connect & Stream", state=tk.NORMAL)
        self.device_combo.configure(state="readonly")
        self.ip_entry.configure(state=tk.NORMAL)
        self.port_entry.configure(state=tk.NORMAL)
        self.refresh_btn.configure(state=tk.NORMAL)
        self.status_label.configure(text="Disconnected")

    def _on_log(self, msg):
        self.root.after(0, self._log_append, msg)

    def _on_status(self, s):
        self.root.after(0, lambda: self.status_label.configure(text=s))

    def _on_error(self, err):
        self.root.after(0, lambda: self._log_append(f"[Error] {err}"))

    def _log_append(self, msg):
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


if __name__ == "__main__":
    StreamerApp().run()
