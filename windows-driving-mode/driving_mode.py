"""
Driving mode logic: TTS engine (queue-based, thread-safe) + Windows DND toggle.
"""
import logging
import queue
import subprocess
import threading
import winreg
from typing import Callable, List

logger = logging.getLogger(__name__)

_PS_FLAGS = {"creationflags": 0x08000000}  # CREATE_NO_WINDOW

# ─────────────────────────────── TTS ──────────────────────────────────────────

class TextToSpeech:
    """Thread-safe TTS using pyttsx3 in a dedicated worker thread."""

    def __init__(self, rate: int = 160):
        self._q: queue.Queue = queue.Queue()
        self._rate = rate
        self._worker = threading.Thread(target=self._run, daemon=True, name="tts")
        self._worker.start()

    def speak(self, text: str):
        """Queue text for immediate speech (non-blocking)."""
        # Drop if queue is filling up (avoid backlog while driving)
        if self._q.qsize() < 3:
            self._q.put(text)

    def stop(self):
        self._q.put(None)

    def _run(self):
        import pyttsx3
        engine = pyttsx3.init()
        engine.setProperty("rate", self._rate)
        # Prefer Korean voice if available
        voices = engine.getProperty("voices")
        for v in voices:
            if "ko" in v.id.lower() or "korean" in v.name.lower():
                engine.setProperty("voice", v.id)
                break
        while True:
            text = self._q.get()
            if text is None:
                break
            try:
                engine.say(text)
                engine.runAndWait()
            except Exception as e:
                logger.error("TTS error: %s", e)


# ─────────────────────────────── DND ──────────────────────────────────────────

_NOTIF_REG = (
    r"Software\Microsoft\Windows\CurrentVersion\Notifications\Settings"
)

def _set_dnd(enable: bool):
    """
    Suppress Windows toast notifications via registry.
    enable=True  → DND on  (toasts disabled)
    enable=False → DND off (toasts enabled)
    """
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, _NOTIF_REG, 0,
            winreg.KEY_SET_VALUE
        )
        winreg.SetValueEx(
            key, "NOC_GLOBAL_SETTING_TOASTS_ENABLED", 0,
            winreg.REG_DWORD, 0 if enable else 1
        )
        winreg.CloseKey(key)
    except OSError as e:
        logger.warning("DND registry write failed: %s", e)

    # Attempt Focus Assist via PowerShell for Windows 11 (best-effort)
    level = "2" if enable else "0"  # 0=off, 1=priority, 2=alarms only
    ps = (
        "$path = 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\"
        "CloudStore\\Store\\DefaultAccount\\Current\\"
        "default$windows.data.notifications.quiethourssettings\\"
        "windows.data.notifications.quiethourssettings'; "
        f"if (Test-Path $path) {{ Set-ItemProperty -Path $path -Name 'Level' -Value {level} -Type DWord -Force }}"
    )
    try:
        subprocess.run(
            ["powershell", "-NonInteractive", "-NoProfile", "-Command", ps],
            capture_output=True, timeout=5, **_PS_FLAGS
        )
    except Exception:
        pass  # Not critical; registry change above is the primary method


# ─────────────────────────────── DrivingMode ──────────────────────────────────

class DrivingMode:
    def __init__(self, tts: TextToSpeech, dnd_enabled: bool = True):
        self.active = False
        self.connected_device: str = ""
        self._tts = tts
        self._dnd_enabled = dnd_enabled
        self._on_activate: List[Callable[[str], None]] = []
        self._on_deactivate: List[Callable[[str], None]] = []

    # --------------------------------------------------------- event hooks

    def on_activate(self, fn: Callable[[str], None]):
        self._on_activate.append(fn)

    def on_deactivate(self, fn: Callable[[str], None]):
        self._on_deactivate.append(fn)

    # --------------------------------------------------------- state control

    def activate(self, device_name: str = ""):
        if self.active:
            return
        self.active = True
        self.connected_device = device_name
        logger.info("Driving mode ON (%s)", device_name)
        if self._dnd_enabled:
            _set_dnd(True)
        self._tts.speak(f"운전 모드 시작. {device_name} 연결됨.")
        for fn in self._on_activate:
            fn(device_name)

    def deactivate(self, device_name: str = ""):
        if not self.active:
            return
        self.active = False
        self.connected_device = ""
        logger.info("Driving mode OFF (%s)", device_name)
        if self._dnd_enabled:
            _set_dnd(False)
        self._tts.speak("운전 모드 종료.")
        for fn in self._on_deactivate:
            fn(device_name)
