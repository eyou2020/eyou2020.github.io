"""
Persistent JSON config stored in %USERPROFILE%\.bluetooth-driving-mode\config.json
"""
import json
import logging
from pathlib import Path
from typing import Dict, List

logger = logging.getLogger(__name__)

_DEFAULTS: Dict = {
    "car_devices": [],       # list of registered InstanceIds
    "device_names": {},      # InstanceId → friendly name
    "tts_enabled": True,
    "tts_rate": 160,
    "dnd_enabled": True,
    "poll_interval": 5,
}


class ConfigManager:
    def __init__(self):
        self._dir = Path.home() / ".bluetooth-driving-mode"
        self._file = self._dir / "config.json"
        self._dir.mkdir(parents=True, exist_ok=True)
        self._data = self._load()

    # ------------------------------------------------------------------ I/O

    def _load(self) -> Dict:
        if self._file.exists():
            try:
                with open(self._file, "r", encoding="utf-8") as f:
                    saved = json.load(f)
                return {**_DEFAULTS, **saved}
            except Exception as e:
                logger.warning("Config load failed: %s", e)
        return dict(_DEFAULTS)

    def save(self):
        try:
            with open(self._file, "w", encoding="utf-8") as f:
                json.dump(self._data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.error("Config save failed: %s", e)

    # ---------------------------------------------------------------- access

    def get(self, key: str, default=None):
        return self._data.get(key, default)

    def set(self, key: str, value):
        self._data[key] = value
        self.save()

    # --------------------------------------------------------- device registry

    def car_devices(self) -> List[str]:
        return list(self._data.get("car_devices", []))

    def is_car_device(self, instance_id: str) -> bool:
        return instance_id in self._data.get("car_devices", [])

    def add_car_device(self, instance_id: str, name: str):
        devices: List[str] = self._data.setdefault("car_devices", [])
        if instance_id not in devices:
            devices.append(instance_id)
        names: Dict = self._data.setdefault("device_names", {})
        names[instance_id] = name
        self.save()

    def remove_car_device(self, instance_id: str):
        devices: List[str] = self._data.get("car_devices", [])
        if instance_id in devices:
            devices.remove(instance_id)
        self.save()

    def device_name(self, instance_id: str) -> str:
        return self._data.get("device_names", {}).get(instance_id, instance_id[:30])
