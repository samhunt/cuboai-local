"""On-device CuboAI LAN bridge.

Talks only to the camera on the local network. Credentials never leave the
phone except over LAN UDP to the camera the user connected.
"""
from __future__ import annotations

import json
import os
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional

from cuboai_pure import _StreamGet
from cuboai_session import get_session
from cuboai_stream_video import apply_env_profile, mux_timed_stream

STREAM_PORT = 18765

_lock = threading.Lock()
_session = None
_http = None
_http_thread = None
_stream_stop = threading.Event()
_streaming = False


def _ok(**extra):
    payload = {"ok": True}
    payload.update(extra)
    return json.dumps(payload)


def _err(message: str, **extra):
    payload = {"ok": False, "error": message}
    payload.update(extra)
    return json.dumps(payload)


def _inner():
    if _session is None:
        return None
    return getattr(_session, "_inner", _session)


def connect(uid: str, account: str, password: str, camera_ip: str) -> str:
    """Open a LAN session. Fast-start so the first frame arrives quickly."""
    global _session
    uid = (uid or "").strip()
    account = (account or "").strip()
    password = password or ""
    camera_ip = (camera_ip or "").strip()
    if not uid or not account or not password or not camera_ip:
        return _err("UID, account, password, and camera IP are required.")
    try:
        socket.inet_aton(camera_ip)
    except OSError:
        return _err("Camera IP must be an IPv4 address on your LAN.")

    with _lock:
        disconnect()
        apply_env_profile(raw=False)
        os.environ.setdefault("CUBOAI_MUX_AUDIO", "1")
        try:
            sess = get_session(
                uid, account, password,
                camera_ip=camera_ip,
                auto_discover_lib=False,
                full_fidelity=False,
                defer_stream_start=False,
                defer_video_start_late=False,
            )
            sess.connect(timeout_sec=20)
        except Exception as exc:
            return _err(str(exc) or type(exc).__name__)
        _session = sess
        return _ok(ip=camera_ip)


def disconnect() -> str:
    """Stop HTTP + AV and close the camera session."""
    global _session, _http, _http_thread, _streaming
    _stream_stop.set()
    http = _http
    if http is not None:
        try:
            http.shutdown()
        except Exception:
            pass
        try:
            http.server_close()
        except Exception:
            pass
    if _http_thread is not None:
        _http_thread.join(timeout=2.0)
    _http = None
    _http_thread = None
    _streaming = False
    sess = _session
    _session = None
    if sess is not None:
        try:
            sess.disconnect()
        except Exception:
            pass
    return _ok()


def is_connected() -> str:
    sess = _session
    return _ok(connected=bool(sess is not None and getattr(sess, "connected", False)))


def stream_url() -> str:
    return _ok(url=f"http://127.0.0.1:{STREAM_PORT}/live.ts", port=STREAM_PORT)


class _LiveHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def do_HEAD(self):
        if self.path.split("?", 1)[0] != "/live.ts":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "video/MP2T")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_GET(self):
        global _streaming
        if self.path.split("?", 1)[0] != "/live.ts":
            self.send_error(404)
            return
        sess = _session
        if sess is None:
            self.send_error(503, "Camera is not connected")
            return
        self.send_response(200)
        self.send_header("Content-Type", "video/MP2T")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        _stream_stop.clear()
        _streaming = True

        def emit(chunk: bytes):
            if _stream_stop.is_set():
                raise BrokenPipeError("stopped")
            self.wfile.write(chunk)
            self.wfile.flush()

        try:
            mux_timed_stream(
                sess.av_frames_timed(),
                emit,
                clean_gop=True,
                mux_audio=True,
                log=lambda *_a, **_k: None,
            )
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        except Exception:
            pass
        finally:
            _streaming = False
            try:
                inner = _inner()
                if inner is not None:
                    inner._stop_reader()
            except Exception:
                pass


def start_stream_server() -> str:
    """Serve MPEG-TS on 127.0.0.1 only. The player never leaves the phone."""
    global _http, _http_thread
    if _session is None:
        return _err("Connect to the camera first.")
    with _lock:
        if _http is not None:
            return stream_url()
        try:
            httpd = ThreadingHTTPServer(("127.0.0.1", STREAM_PORT), _LiveHandler)
            httpd.daemon_threads = True
        except OSError as exc:
            return _err(f"Could not bind local stream port: {exc}")
        _http = httpd
        t = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.3}, daemon=True)
        _http_thread = t
        t.start()
        return stream_url()


def stop_stream() -> str:
    _stream_stop.set()
    inner = _inner()
    if inner is not None:
        try:
            inner._stop_reader()
        except Exception:
            pass
    return _ok()


def _ioctl_safe(io_type: int, payload: bytes, timeout: float = 3.0) -> Optional[bytes]:
    """Send an IOCTL. Uses the stream inject slot while AV is running."""
    inner = _inner()
    if inner is None:
        raise RuntimeError("Not connected")
    th = getattr(inner, "_av_reader_thread", None)
    if th is not None and th.is_alive():
        with inner._inject_lock:
            gi = _StreamGet(io_type, payload)
            inner._get_inject = gi
            try:
                if not gi.done.wait(timeout):
                    return None
                return gi.result
            finally:
                inner._get_inject = None
    resp_type, data = inner.ioctl(io_type, payload)
    return data


def sensors() -> str:
    sess = _session
    if sess is None:
        return _err("Not connected")
    inner = _inner()
    out = {}
    names = (
        "get_hw_control",
        "get_night_light",
        "get_light_style",
        "get_sleep_mode",
        "get_lullaby",
        "get_lullaby_schedule",
        "get_wifi",
    )
    try:
        if inner is not None and getattr(inner, "_av_reader_thread", None) is not None:
            for name in names:
                try:
                    out[name] = inner.get_during_stream(name, timeout=2.5) or {}
                except Exception as exc:
                    out[name] = {"error": str(exc)}
        else:
            getters = {
                "get_hw_control": sess.get_hw_control,
                "get_night_light": sess.get_night_light,
                "get_light_style": sess.get_light_style,
                "get_sleep_mode": sess.get_sleep_mode,
                "get_lullaby": sess.get_lullaby,
                "get_lullaby_schedule": sess.get_lullaby_schedule,
                "get_wifi": sess.get_wifi,
            }
            for name, fn in getters.items():
                try:
                    out[name] = fn() or {}
                except Exception as exc:
                    out[name] = {"error": str(exc)}
    except Exception as exc:
        return _err(str(exc))

    hw = out.get("get_hw_control") or {}
    light = out.get("get_night_light") or {}
    style = out.get("get_light_style") or {}
    sleep = out.get("get_sleep_mode") or {}
    lullaby = out.get("get_lullaby") or {}
    schedule = out.get("get_lullaby_schedule") or {}
    wifi = out.get("get_wifi") or {}
    night_style = style.get("night_light") or {}
    return _ok(
        temperature_c=hw.get("temp_c"),
        humidity_pct=hw.get("humidity_pct"),
        firmware=hw.get("firmware"),
        wifi_quality=hw.get("wifi_strength", wifi.get("quality_pct")),
        wifi_ssid=hw.get("ssid") or wifi.get("ssid"),
        night_light_on=light.get("on", hw.get("night_light_on")),
        brightness=night_style.get("brightness", style.get("brightness")),
        sleep_mode=sleep.get("enabled"),
        lullaby_playing=lullaby.get("is_playing"),
        lullaby_name=lullaby.get("current_sound"),
        lullaby_volume=schedule.get("volume"),
        speaker_level=hw.get("speaker_level"),
        night_vision=hw.get("night_vision"),
    )


def set_night_light(on: bool) -> str:
    sess = _session
    if sess is None:
        return _err("Not connected")
    try:
        sess.set_night_light(bool(on)) if not _streaming else _set_via_inject(
            *_set_night_payload(bool(on))
        )
        return _ok()
    except Exception as exc:
        return _err(str(exc))


def _set_night_payload(on: bool):
    import cuboai_messages as cm
    return cm.build_set_night_light(on)


def _set_via_inject(io_type: int, payload: bytes) -> None:
    data = _ioctl_safe(io_type, payload)
    if data is None:
        raise TimeoutError("Camera did not acknowledge the command")


def set_brightness(value: int) -> str:
    sess = _session
    if sess is None:
        return _err("Not connected")
    value = max(0, min(100, int(value)))
    try:
        import cuboai_messages as cm
        io_type, payload = cm.build_set_light_style_brightness(value)
        if _streaming:
            _set_via_inject(io_type, payload)
        else:
            sess.set_light_brightness(value)
        return _ok(brightness=value)
    except Exception as exc:
        return _err(str(exc))


def set_sleep_mode(enabled: bool) -> str:
    sess = _session
    if sess is None:
        return _err("Not connected")
    try:
        import cuboai_messages as cm
        io_type, payload = cm.build_set_sleep_mode(bool(enabled))
        if _streaming:
            _set_via_inject(io_type, payload)
        else:
            sess.set_sleep_mode(bool(enabled))
        return _ok()
    except Exception as exc:
        return _err(str(exc))


def set_lullaby(sound_id: str) -> str:
    sess = _session
    if sess is None:
        return _err("Not connected")
    try:
        import cuboai_messages as cm
        io_type, payload = cm.build_set_lullaby_play(sound_id)
        if _streaming:
            _set_via_inject(io_type, payload)
        else:
            sess.set_lullaby(sound_id)
        return _ok()
    except Exception as exc:
        return _err(str(exc))


def stop_lullaby() -> str:
    sess = _session
    if sess is None:
        return _err("Not connected")
    try:
        if _streaming:
            import cuboai_messages as cm
            uuid = ""
            inner = _inner()
            if inner is not None:
                cur = inner.get_during_stream("get_lullaby", timeout=2.0) or {}
                uuid = cur.get("uuid") or ""
            io_type, payload = cm.build_set_lullaby_stop(uuid)
            _set_via_inject(io_type, payload)
        else:
            sess.set_lullaby_stop()
        return _ok()
    except Exception as exc:
        return _err(str(exc))


def lullabies() -> str:
    import cuboai_messages as cm
    songs = [
        {"uuid": uuid, "name": name, "category": category}
        for uuid, (_key, name, category) in cm.LULLABY_CATALOG.items()
    ]
    return _ok(songs=songs)
