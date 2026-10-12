#!/usr/bin/env python3
"""DLNA control-point simulator for KBTVCaster e2e (VERIFICATION.md criterion 2/4).

Drives the REAL receiver chain without a phone or SSDP multicast (emulator
NAT drops multicast by design):

  1. Serve a generated WAV on the host (device reaches it as 10.0.2.2).
  2. Read the device UDN from logcat (logged by DLNAUtils on registration).
  3. Enumerate the app's LISTEN ports via /proc/net/tcp*, adb-forward each,
     GET /dev/<UDN>/desc to identify the Cling stream server.
  4. Parse the device descriptor for the AVTransport controlURL.
  5. SOAP SetAVTransportURI + Play (exactly what a phone app sends).
  6. Assert the run trace (files/trace/cast.jsonl) shows play_started.

Usage:  python dlna_control_point.py [--serial <adb-serial>]
Exit 0 = chain verified; nonzero with reason = failure.
Stdlib only. Requires a debug build installed and the service running.
"""
import argparse
import http.server
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import wave
import xml.etree.ElementTree as ET

PKG = "com.kbtv.caster"
AVT_TYPE = "urn:schemas-upnp-org:service:AVTransport"
ADB = ["adb"]


def adb(*args, timeout=30):
    r = subprocess.run(ADB + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def app_uid():
    out = adb("shell", f"dumpsys package {PKG}")
    m = re.search(r"userId=(\d+)", out)
    return int(m.group(1)) if m else None


def listen_ports():
    out = adb("shell", "cat /proc/net/tcp /proc/net/tcp6")
    result = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) > 7 and parts[3] == "0A":  # TCP_LISTEN
            try:
                lport = int(parts[1].split(":")[1], 16)
                uid = int(parts[7])
                result.add((lport, uid))
            except ValueError:
                continue
    return result


def udn_from_logcat():
    # The JSONL trace is durable (logcat rotates on chatty systems) — try it first.
    out = adb("shell", f"run-as {PKG} cat files/trace/cast.jsonl")
    m = re.findall(r'"event":"upnp_registered","udn":"([^"]+)"', out)
    if m:
        return m[-1]
    out = adb("logcat", "-d", "-s", "DLNAUtils")
    matches = re.findall(r"UDN=([0-9a-fA-F:.\-]+)", out)
    return matches[-1].strip() if matches else None


def free_local_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def descriptor_path_from_trace():
    """The app reports Cling's exact descriptor path in the trace — use it verbatim."""
    out = adb("shell", f"run-as {PKG} cat files/trace/cast.jsonl")
    m = re.findall(r'"event":"upnp_descriptor_path","path":"([^"]+)"', out)
    return m[-1] if m else None


def find_descriptor(udn, uid, desc_path):
    """Probe each forwarded candidate port for the device descriptor. Returns (local_port, device_port, xml)."""
    for lport, owner in sorted(listen_ports()):
        if uid is not None and owner != uid:
            continue
        local = free_local_port()
        out = adb("forward", f"tcp:{local}", f"tcp:{lport}")
        if "error" in out.lower():
            continue
        try:
            req = urllib.request.Request(f"http://127.0.0.1:{local}{desc_path}")
            with urllib.request.urlopen(req, timeout=5) as r:
                body = r.read().decode("utf-8", "replace")
                if "<device" in body and "MediaRenderer" in body:
                    print(f"[+] Cling stream server: device port {lport} (local {local})")
                    return local, lport, body
        except Exception:
            pass
        adb("forward", "--remove", f"tcp:{local}")
    return None


def avtransport_control_url(desc_xml, local_port):
    """Extract the AVTransport controlURL (absolute, on the forwarded local port)."""
    xml = re.sub(r" xmlns=\"[^\"]+\"", "", desc_xml)  # strip default ns for flat querying
    root = ET.fromstring(xml)
    for service in root.iter("service"):
        st = service.findtext("serviceType", "")
        if "AVTransport" in st:
            control = service.findtext("controlURL", "")
            if control.startswith("http"):
                return control
            return f"http://127.0.0.1:{local_port}{control}"
    return None


def soap_action(url, action, args_xml):
    body = (
        '<?xml version="1.0"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f"<s:Body><u:{action} xmlns:u=\"{AVT_TYPE}:1\">{args_xml}</u:{action}></s:Body>"
        "</s:Envelope>"
    )
    req = urllib.request.Request(
        url,
        data=body.encode("utf-8"),
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            # UPnP requires the SOAPACTION value QUOTED — Cling treats an
            # unquoted value as a missing header (errorCode 401).
            "SOAPACTION": f'"{AVT_TYPE}:1#{action}"',
        },
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, r.read().decode("utf-8", "replace")


def trace_has_event(event, timeout_s=30):
    deadline = time.time() + timeout_s
    needle = f'"event":"{event}"'
    while time.time() < deadline:
        out = adb("shell", f"run-as {PKG} cat files/trace/cast.jsonl")
        if needle in out:
            print(f"[+] trace shows {event}")
            return True
        time.sleep(1)
    print(f"[-] trace never showed {event}; last 5 lines:")
    for line in out.strip().splitlines()[-5:]:
        print("    " + line)
    return False


class WavHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/cast-test.wav":
            self.send_error(404)
            return
        data = self.server.wav_bytes
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


def make_wav(path, seconds=5, rate=8000):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        import math
        import struct
        frames = bytearray()
        for i in range(seconds * rate):
            v = int(math.sin(2 * math.pi * 440 * i / rate) * 12000)
            frames += struct.pack("<h", v)
        w.writeframes(bytes(frames))
    with open(path, "rb") as f:
        return f.read()


def main():
    global ADB
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--host-port", type=int, default=18080)
    parser.add_argument("--wait-service", type=int, default=30)
    args = parser.parse_args()

    if args.serial:
        ADB = ["adb", "-s", args.serial]

    # 0. Device present? Exact match — the error text "no devices/emulators
    #    found" contains the substring "device" and would pass a naive check.
    if adb("get-state").strip().splitlines()[-1] != "device":
        print("[-] no adb device")
        return 2

    # 1. Service up? (trace shows service_running in debug builds)
    if not trace_has_event("service_running", timeout_s=args.wait_service):
        print("[-] service not running (start the app / ACTION_START first)")
        return 3

    # 2. Host media server (device-side URL uses the emulator host alias 10.0.2.2).
    wav_bytes = make_wav("cast-test.wav")
    httpd = http.server.HTTPServer(("0.0.0.0", args.host_port), WavHandler)
    httpd.wav_bytes = wav_bytes
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    media_url = f"http://10.0.2.2:{args.host_port}/cast-test.wav"
    print(f"[+] serving test media at {media_url}")

    # 3. Find the Cling descriptor (exact path from the app's trace; legacy fallback includes /upnp base).
    udn = udn_from_logcat()
    if not udn:
        print("[-] UDN not found in logcat/trace (is this a debug build with DLNAUtils logging?)")
        return 4
    print(f"[+] device UDN: {udn}")
    desc_path = descriptor_path_from_trace() or f"/upnp/dev/{udn}/desc"
    print(f"[+] descriptor path: {desc_path}")
    found = find_descriptor(udn, app_uid(), desc_path)
    if not found:
        print("[-] Cling stream server not found among forwarded ports")
        return 5
    local_port, device_port, desc_xml = found

    control = avtransport_control_url(desc_xml, local_port)
    if not control:
        print("[-] AVTransport controlURL not found in descriptor")
        return 6
    print(f"[+] AVTransport control: {control}")

    # 4. Drive it like a phone app would.
    try:
        status, _ = soap_action(
            control, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>"
            f"<CurrentURI>{media_url}</CurrentURI>"
            "<CurrentURIMetaData></CurrentURIMetaData>",
        )
        print(f"[+] SetAVTransportURI -> {status}")
        status, _ = soap_action(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        print(f"[+] Play -> {status}")
    except Exception as e:
        print(f"[-] SOAP failed: {e}")
        return 7

    # 5. Verify the chain left evidence.
    ok = trace_has_event("play_started", timeout_s=30)
    adb("forward", "--remove", f"tcp:{local_port}")
    httpd.shutdown()
    print("[=] RESULT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
