"""
Windows Bluetooth Driving Mode
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
차량 블루투스 기기가 연결되면 자동으로 운전 모드를 활성화합니다.
  • 운전 모드 진입/해제 시 TTS 음성 안내
  • Windows 알림 방해 금지(DND) 자동 전환
  • 시스템 트레이 상태 표시 및 수동 전환
  • 차량 기기 등록/관리 UI
"""
import logging
import sys
import threading
import tkinter as tk
from tkinter import messagebox, ttk
from typing import Optional

import pystray
from PIL import Image, ImageDraw, ImageFont

from bluetooth_monitor import BluetoothDevice, BluetoothMonitor, get_connected_bluetooth_devices
from config_manager import ConfigManager
from driving_mode import DrivingMode, TextToSpeech

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


# ══════════════════════════════════════════════════════════════════════════════
# Icon generation
# ══════════════════════════════════════════════════════════════════════════════

def _make_icon(driving: bool) -> Image.Image:
    sz = 64
    img = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    bg = "#E65100" if driving else "#1565C0"
    d.ellipse([2, 2, sz - 2, sz - 2], fill=bg)

    if driving:
        # Simple car silhouette
        cx = sz // 2
        # body
        d.rectangle([12, 34, sz - 12, 46], fill="white")
        # roof
        d.polygon([(18, 34), (22, 22), (42, 22), (46, 34)], fill="white")
        # wheels
        d.ellipse([13, 43, 23, 53], fill="#222")
        d.ellipse([41, 43, 51, 53], fill="#222")
        # speed lines
        d.line([(8, 28), (14, 28)], fill="white", width=2)
        d.line([(6, 34), (12, 34)], fill="white", width=2)
    else:
        # BT logo (simplified)
        cx, cy = sz // 2, sz // 2
        d.line([(cx, 14), (cx, 50)], fill="white", width=3)
        d.line([(cx, 14), (cx + 10, 22), (cx, 30), (cx + 10, 38), (cx, 46)],
               fill="white", width=3)
        d.line([(cx, 30), (cx - 10, 22)], fill="white", width=3)
        d.line([(cx, 46), (cx - 10, 38)], fill="white", width=3)

    return img


# ══════════════════════════════════════════════════════════════════════════════
# Device setup dialog
# ══════════════════════════════════════════════════════════════════════════════

class DeviceDialog:
    def __init__(self, parent: tk.Tk, config: ConfigManager):
        self._config = config
        self._devices: list[BluetoothDevice] = []

        self.win = tk.Toplevel(parent)
        self.win.title("차량 블루투스 기기 설정")
        self.win.geometry("520x440")
        self.win.resizable(False, False)
        self.win.grab_set()
        self._build()
        self._refresh()

    # ------------------------------------------------------------------ UI

    def _build(self):
        pad = {"padx": 12, "pady": 6}

        top = ttk.Frame(self.win)
        top.pack(fill="x", **pad)
        ttk.Label(top, text="감지된 블루투스 기기", font=("Segoe UI", 11, "bold")).pack(anchor="w")
        ttk.Label(
            top,
            text="차량 기기를 선택한 뒤 [차량 기기로 등록]을 누르세요.\n"
                 "해당 기기가 연결될 때마다 자동으로 운전 모드가 시작됩니다.",
            foreground="#555", wraplength=490,
        ).pack(anchor="w")

        list_frame = ttk.Frame(self.win)
        list_frame.pack(fill="both", expand=True, **pad)

        self._lb = tk.Listbox(list_frame, font=("Consolas", 10), selectmode=tk.SINGLE, height=8)
        sb = ttk.Scrollbar(list_frame, command=self._lb.yview)
        self._lb.config(yscrollcommand=sb.set)
        self._lb.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

        btn_row = ttk.Frame(self.win)
        btn_row.pack(fill="x", padx=12)
        ttk.Button(btn_row, text="새로고침", command=self._refresh).pack(side="left")
        ttk.Button(btn_row, text="차량 기기로 등록", command=self._add).pack(side="left", padx=6)

        ttk.Separator(self.win).pack(fill="x", padx=12, pady=8)

        ttk.Label(self.win, text="등록된 차량 기기", font=("Segoe UI", 10, "bold")).pack(
            anchor="w", padx=12
        )
        self._reg_frame = ttk.Frame(self.win)
        self._reg_frame.pack(fill="x", padx=16, pady=(2, 6))

        ttk.Button(self.win, text="닫기", command=self.win.destroy).pack(pady=8)

    # ---------------------------------------------------------------- logic

    def _refresh(self):
        self._lb.delete(0, tk.END)
        self._devices = get_connected_bluetooth_devices()
        registered = self._config.car_devices()
        if not self._devices:
            self._lb.insert(tk.END, "  (주변에서 블루투스 기기를 찾을 수 없습니다)")
        else:
            for dev in self._devices:
                marker = " ★" if dev.instance_id in registered else "  "
                self._lb.insert(tk.END, f"{marker} {dev.name}")
        self._rebuild_reg()

    def _add(self):
        sel = self._lb.curselection()
        if not sel:
            messagebox.showwarning("선택 필요", "등록할 기기를 목록에서 선택하세요.", parent=self.win)
            return
        idx = sel[0]
        if idx >= len(self._devices):
            return
        dev = self._devices[idx]
        self._config.add_car_device(dev.instance_id, dev.name)
        messagebox.showinfo("등록 완료", f"'{dev.name}'\n차량 기기로 등록되었습니다.", parent=self.win)
        self._refresh()

    def _remove(self, iid: str):
        self._config.remove_car_device(iid)
        self._rebuild_reg()
        self._refresh()

    def _rebuild_reg(self):
        for w in self._reg_frame.winfo_children():
            w.destroy()
        ids = self._config.car_devices()
        if not ids:
            ttk.Label(self._reg_frame, text="(없음)", foreground="gray").pack(anchor="w")
            return
        for iid in ids:
            name = self._config.device_name(iid)
            row = ttk.Frame(self._reg_frame)
            row.pack(fill="x", pady=1)
            ttk.Label(row, text=f"• {name}").pack(side="left")
            ttk.Button(row, text="삭제", width=5,
                       command=lambda i=iid: self._remove(i)).pack(side="right")


# ══════════════════════════════════════════════════════════════════════════════
# Driving overlay (always-on-top floating badge)
# ══════════════════════════════════════════════════════════════════════════════

class DrivingOverlay:
    def __init__(self, root: tk.Tk):
        self._root = root
        self._win: Optional[tk.Toplevel] = None

    def show(self, device: str = ""):
        if self._win and self._win.winfo_exists():
            return
        w = tk.Toplevel(self._root)
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        w.attributes("-alpha", 0.88)
        w.configure(bg="#E65100")

        sw = w.winfo_screenwidth()
        width, height = 220, 56
        w.geometry(f"{width}x{height}+{sw - width - 20}+20")

        tk.Label(
            w, text="🚗  운전 모드 활성",
            bg="#E65100", fg="white",
            font=("Segoe UI", 13, "bold"),
        ).pack(expand=True)
        if device:
            tk.Label(
                w, text=device[:30],
                bg="#E65100", fg="#FFCCBC",
                font=("Segoe UI", 8),
            ).pack()

        self._win = w

    def hide(self):
        if self._win and self._win.winfo_exists():
            self._win.destroy()
        self._win = None


# ══════════════════════════════════════════════════════════════════════════════
# Main application
# ══════════════════════════════════════════════════════════════════════════════

class App:
    def __init__(self):
        self.config = ConfigManager()
        self.tts = TextToSpeech(rate=self.config.get("tts_rate", 160))
        self.driving = DrivingMode(
            tts=self.tts,
            dnd_enabled=self.config.get("dnd_enabled", True),
        )
        self.monitor = BluetoothMonitor(
            poll_interval=self.config.get("poll_interval", 5)
        )

        # Tkinter root (hidden; used for dialogs and overlay)
        self.root = tk.Tk()
        self.root.withdraw()
        self.overlay = DrivingOverlay(self.root)

        self.tray: Optional[pystray.Icon] = None

        self._wire_events()

    # ──────────────────────────────── wiring ─────────────────────────────────

    def _wire_events(self):
        self.monitor.on_connected = self._on_connected
        self.monitor.on_disconnected = self._on_disconnected
        self.driving.on_activate(self._on_mode_activate)
        self.driving.on_deactivate(self._on_mode_deactivate)

    def _on_connected(self, dev: BluetoothDevice):
        if self.config.is_car_device(dev.instance_id):
            self.driving.activate(dev.name)

    def _on_disconnected(self, dev: BluetoothDevice):
        if self.config.is_car_device(dev.instance_id):
            self.driving.deactivate(dev.name)

    def _on_mode_activate(self, device: str):
        self.root.after(0, lambda: self.overlay.show(device))
        self._refresh_tray()

    def _on_mode_deactivate(self, device: str):
        self.root.after(0, self.overlay.hide)
        self._refresh_tray()

    # ──────────────────────────────── tray ───────────────────────────────────

    def _make_menu(self):
        status = "🚗 운전 모드: 활성" if self.driving.active else "💤 운전 모드: 비활성"
        toggle_label = "운전 모드 해제" if self.driving.active else "운전 모드 수동 시작"
        return pystray.Menu(
            pystray.MenuItem(status, None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(toggle_label, self._toggle_mode),
            pystray.MenuItem("차량 기기 설정...", self._open_settings),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("종료", self._quit),
        )

    def _refresh_tray(self):
        if self.tray:
            self.tray.icon = _make_icon(self.driving.active)
            self.tray.menu = self._make_menu()
            state = "운전중" if self.driving.active else "대기중"
            self.tray.title = f"블루투스 운전 모드 — {state}"

    def _toggle_mode(self, icon=None, item=None):
        if self.driving.active:
            self.driving.deactivate("수동")
        else:
            self.driving.activate("수동")

    def _open_settings(self, icon=None, item=None):
        self.root.after(0, self._show_device_dialog)

    def _show_device_dialog(self):
        DeviceDialog(self.root, self.config)

    def _quit(self, icon=None, item=None):
        self.monitor.stop()
        if self.driving.active:
            self.driving.deactivate()
        self.tts.stop()
        if self.tray:
            self.tray.stop()
        self.root.after(0, self.root.quit)

    # ──────────────────────────────── run ────────────────────────────────────

    def run(self):
        self.monitor.start()

        self.tray = pystray.Icon(
            "bt-driving-mode",
            _make_icon(False),
            "블루투스 운전 모드 — 대기중",
            menu=self._make_menu(),
        )

        # Run tray in background thread; tkinter owns the main thread
        t = threading.Thread(target=self.tray.run, daemon=True, name="tray")
        t.start()

        logger.info("Application started — check the system tray")
        self.root.mainloop()


# ══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    if sys.platform != "win32":
        print("이 프로그램은 Windows 전용입니다.")
        sys.exit(1)
    App().run()
