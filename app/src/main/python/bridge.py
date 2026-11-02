"""On-device CuboAI LAN bridge.

Talks only to the camera on the local network. Credentials never leave the
phone except over LAN UDP to the camera the user connected.
"""
from __future__ import annotations

import json
import os
import socket
import sys
import threading
import time
import traceback
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional

from cuboai_pure import _StreamGet
from cuboai_session import get_session
from cuboai_stream_video import apply_env_profile, mux_timed_stream

STREAM_PORT = 18765
_TS_CAP = 2 * 1024 * 1024

_lock = threading.Lock()
_session = None
_http = None
_http_thread = None
_mux_thread = None
_stream_stop = threading.Event()
_streaming = False
_ts_cond = threading.Condition()
_ts_chunks: deque[bytes] = deque()
_ts_bytes = 0
_ts_head = 0
_ts_tail = 0


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
    global _session, _http, _http_thread, _mux_thread, _streaming, _ts_bytes, _ts_head, _ts_tail
    _stream_stop.set()
    with _ts_cond:
        _ts_cond.notify_all()
    mux = _mux_thread
    if mux is not None and mux.is_alive():
        mux.join(timeout=2.0)
    _mux_thread = None
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
    with _ts_cond:
        _ts_chunks.clear()
        _ts_bytes = 0
        _ts_head = 0
        _ts_tail = 0
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


def _log(msg: str) -> None:
    try:
        from android.util import Log
        Log.i("cuboai-mux", msg)
    except Exception:
        print(msg, file=sys.stderr, flush=True)


_java_hub = None


def set_ts_hub(hub) -> str:
    global _java_hub
    _java_hub = hub
    return _ok()


def _push_ts(chunk: bytes) -> None:
    global _ts_bytes, _ts_head, _ts_tail
    if not chunk:
        return
    with _ts_cond:
        _ts_chunks.append(chunk)
        _ts_bytes += len(chunk)
        _ts_tail += len(chunk)
        while _ts_bytes > _TS_CAP and _ts_chunks:
            old = _ts_chunks.popleft()
            _ts_bytes -= len(old)
            _ts_head += len(old)
        _ts_cond.notify_all()
    hub = _java_hub
    if hub is None:
        _log("[mux] TsHub missing; dropping %d bytes" % len(chunk))
        return
    try:
        hub.push(chunk)
    except Exception as exc:
        _log("[mux] TsHub.push failed: %s" % exc)


def _read_ts_from(pos: int, timeout: float) -> tuple[bytes, int]:
    deadline = time.monotonic() + timeout
    with _ts_cond:
        while _ts_tail <= pos and not _stream_stop.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return b"", pos
            _ts_cond.wait(remaining)
        if pos < _ts_head:
            pos = _ts_head
        skip = pos - _ts_head
        parts = []
        for chunk in _ts_chunks:
            if skip >= len(chunk):
                skip -= len(chunk)
                continue
            parts.append(chunk[skip:] if skip else chunk)
            skip = 0
        return b"".join(parts), _ts_tail


def _mux_loop() -> None:
    global _streaming
    _streaming = True
    try:
        def emit(chunk: bytes):
            if _stream_stop.is_set():
                raise BrokenPipeError("stopped")
            _push_ts(chunk)

        while not _stream_stop.is_set():
            sess = _session
            if sess is None:
                break
            _log("[mux] starting MPEG-TS muxer")
            try:
                mux_timed_stream(
                    sess.av_frames_timed(),
                    emit,
                    clean_gop=True,
                    mux_audio=True,
                    log=_log,
                )
                _log("[mux] timed stream ended")
            except (BrokenPipeError, ConnectionResetError, OSError) as exc:
                _log(f"[mux] stream closed: {type(exc).__name__}: {exc}")
                if _stream_stop.is_set():
                    break
            except Exception:
                traceback.print_exc(file=sys.stderr)
            if _stream_stop.is_set():
                break
            time.sleep(0.3)
    finally:
        _streaming = False
        with _ts_cond:
            _ts_cond.notify_all()


def run_mux_loop() -> str:
    """Entry point for a Java thread. Chaquopy Python threads do not run while a
    Java-called function is on the cuboai-python dispatcher."""
    global _mux_thread
    _mux_thread = threading.current_thread()
    try:
        _mux_loop()
    finally:
        if _mux_thread is threading.current_thread():
            _mux_thread = None
    return _ok()


def ts_ready() -> str:
    with _ts_cond:
        if _ts_bytes >= 188 * 24:
            return _ok(bytes=_ts_bytes)
    return _err("not yet")


def wait_ts(timeout_sec: float = 8.0) -> str:
    deadline = time.monotonic() + float(timeout_sec)
    with _ts_cond:
        while not _ts_chunks and not _stream_stop.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            _ts_cond.wait(remaining)
        if _ts_chunks:
            return _ok(bytes=_ts_bytes)
    return _err("No video bytes yet.")


class _LiveHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def _send_ts_headers(self):
        self.send_response(200)
        self.send_header("Content-Type", "video/MP2T")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_HEAD(self):
        if self.path.split("?", 1)[0] != "/live.ts":
            self.send_error(404)
            return
        self._send_ts_headers()

    def do_GET(self):
        if self.path.split("?", 1)[0] != "/live.ts":
            self.send_error(404)
            return
        if _session is None:
            self.send_error(503, "Camera is not connected")
            return
        self._send_ts_headers()
        with _ts_cond:
            pos = _ts_head
        sent_media = False
        idle = 0
        try:
            while not _stream_stop.is_set() and _session is not None:
                data, pos = _read_ts_from(pos, 0.4)
                if data:
                    sent_media = True
                    idle = 0
                    self.wfile.write(data)
                    self.wfile.flush()
                    continue
                idle += 1
                if sent_media and idle % 5 == 0:
                    self.wfile.write(b"\x47\x1f\xff\x10" + b"\xff" * 184)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


def start_stream_server() -> str:
    """Serve MPEG-TS on 127.0.0.1 only. The player never leaves the phone."""
    global _http, _http_thread
    if _session is None:
        return _err("Connect to the camera first.")
    with _lock:
        _stream_stop.clear()
        if _http is None:
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
    with _ts_cond:
        _ts_cond.notify_all()
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
