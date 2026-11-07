"""Controle do Windows: volume geral, teclas de mídia e abrir junto com o Windows."""
import ctypes
import sys

try:
    import comtypes
except Exception:  # fora do Windows (testes)
    comtypes = None


def _endpoint():
    if comtypes:
        try:
            comtypes.CoInitialize()
        except Exception:
            pass
    from pycaw.pycaw import AudioUtilities
    speakers = AudioUtilities.GetSpeakers()
    ev = getattr(speakers, "EndpointVolume", None)
    if ev is not None:
        return ev
    # pycaw antigo
    from ctypes import POINTER, cast
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import IAudioEndpointVolume
    iface = speakers.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(iface, POINTER(IAudioEndpointVolume))


def get_percent() -> int:
    try:
        return round(_endpoint().GetMasterVolumeLevelScalar() * 100)
    except Exception:
        return 50


def set_percent(p: int) -> int:
    p = max(0, min(100, int(p)))
    try:
        ev = _endpoint()
        ev.SetMute(0, None)
        ev.SetMasterVolumeLevelScalar(p / 100.0, None)
    except Exception:
        pass
    return get_percent()


def level() -> int:
    return max(0, min(10, round(get_percent() / 10)))


def step(delta_levels: int) -> int:
    return set_percent(get_percent() + 10 * delta_levels)


def is_muted() -> bool:
    try:
        return bool(_endpoint().GetMute())
    except Exception:
        return False


def mute(on: bool):
    try:
        _endpoint().SetMute(1 if on else 0, None)
    except Exception:
        pass


# ---------------------------------------------------------------- teclas de mídia (Spotify, navegador...)

KEYS = {"play_pause": 0xB3, "next": 0xB0, "previous": 0xB1, "stop": 0xB2}


def media_key(name: str):
    if sys.platform != "win32":
        return
    vk = KEYS[name]
    ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
    ctypes.windll.user32.keybd_event(vk, 0, 2, 0)


# ---------------------------------------------------------------- abrir junto com o Windows

def set_startup(enabled: bool):
    if sys.platform != "win32" or not getattr(sys, "frozen", False):
        return
    import winreg
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                         winreg.KEY_SET_VALUE)
    try:
        if enabled:
            winreg.SetValueEx(key, "Zuri", 0, winreg.REG_SZ, f'"{sys.executable}"')
        else:
            try:
                winreg.DeleteValue(key, "Zuri")
            except FileNotFoundError:
                pass
    finally:
        winreg.CloseKey(key)
