"""
Bluetooth device monitoring via PowerShell queries.
Polls connected devices at a configurable interval and fires callbacks on changes.
"""
import json
import logging
import subprocess
import threading
import time
from dataclasses import dataclass
from typing import Callable, List, Optional

logger = logging.getLogger(__name__)

_PS_FLAGS = {"creationflags": 0x08000000}  # CREATE_NO_WINDOW


@dataclass
class BluetoothDevice:
    name: str
    instance_id: str

    def __eq__(self, other):
        return isinstance(other, BluetoothDevice) and self.instance_id == other.instance_id

    def __hash__(self):
        return hash(self.instance_id)

    def __repr__(self):
        return f"BluetoothDevice({self.name!r})"


_QUERY = r"""
$results = @()

# Classic BT devices visible as PnP (paired & connected state readable via Status)
$bt = Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Status -eq 'OK' -and
        $_.FriendlyName -notmatch '(Bluetooth.*Radio|Microsoft|Generic|Enumerator|HID|BTHLE)'
    } |
    Select-Object FriendlyName, InstanceId
foreach ($d in $bt) {
    $results += [PSCustomObject]@{ Name = $d.FriendlyName; InstanceId = $d.InstanceId }
}

# Bluetooth audio endpoints (hands-free / A2DP appear here when connected)
$audio = Get-WmiObject Win32_SoundDevice -ErrorAction SilentlyContinue |
    Where-Object { $_.PNPDeviceID -match '^(BTHENUM|BTHHFENUM|BTHLE)' } |
    Select-Object Name, PNPDeviceID
foreach ($d in $audio) {
    $results += [PSCustomObject]@{ Name = $d.Name; InstanceId = $d.PNPDeviceID }
}

if ($results.Count -eq 0) { '[]' } else { $results | ConvertTo-Json -Compress }
"""


def get_connected_bluetooth_devices() -> List[BluetoothDevice]:
    """Return currently connected Bluetooth devices (classic + audio)."""
    try:
        proc = subprocess.run(
            ["powershell", "-NonInteractive", "-NoProfile", "-Command", _QUERY],
            capture_output=True, text=True, timeout=15, **_PS_FLAGS
        )
        raw = proc.stdout.strip()
        if not raw:
            return []
        data = json.loads(raw)
        if isinstance(data, dict):
            data = [data]
        return [
            BluetoothDevice(d["Name"], d["InstanceId"])
            for d in data
            if d.get("Name") and d.get("InstanceId")
        ]
    except (subprocess.TimeoutExpired, json.JSONDecodeError, Exception) as e:
        logger.debug("Device query failed: %s", e)
        return []


class BluetoothMonitor:
    """Background thread that polls Bluetooth device list and fires callbacks."""

    def __init__(self, poll_interval: int = 5):
        self.poll_interval = poll_interval
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._known: List[BluetoothDevice] = []
        self._lock = threading.Lock()

        self.on_connected: Optional[Callable[[BluetoothDevice], None]] = None
        self.on_disconnected: Optional[Callable[[BluetoothDevice], None]] = None

    # ------------------------------------------------------------------ public

    def start(self):
        self._running = True
        self._known = get_connected_bluetooth_devices()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="bt-monitor")
        self._thread.start()
        logger.info("Bluetooth monitor started (interval=%ds)", self.poll_interval)

    def stop(self):
        self._running = False

    def current_devices(self) -> List[BluetoothDevice]:
        with self._lock:
            return list(self._known)

    # ----------------------------------------------------------------- private

    def _loop(self):
        while self._running:
            time.sleep(self.poll_interval)
            try:
                current = get_connected_bluetooth_devices()
                with self._lock:
                    prev = self._known
                    self._known = current

                added = [d for d in current if d not in prev]
                removed = [d for d in prev if d not in current]

                for d in added:
                    logger.info("Connected: %s", d)
                    if self.on_connected:
                        self.on_connected(d)
                for d in removed:
                    logger.info("Disconnected: %s", d)
                    if self.on_disconnected:
                        self.on_disconnected(d)

            except Exception as e:
                logger.error("Monitor loop error: %s", e)
