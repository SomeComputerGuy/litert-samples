#!/usr/bin/env python3
"""RemoteAI coordinator: find RemoteAI Responder phones on the network, pair with them,
and send them requests over TLS.

  remoteai.py discover                 list phones advertising _remoteai._tcp
  remoteai.py pair [PHONE]             pair (approve on the phone with fingerprint or PIN)
  remoteai.py devices                  list paired phones
  remoteai.py ask PHONE "prompt"       send one message
  remoteai.py proxy PHONE [--port N]   serve a plain local Anthropic-style endpoint on
                                       127.0.0.1 that forwards to the phone, for SDKs
  remoteai.py forget PHONE             delete a pairing on this machine

PHONE is part of a phone's name (as shown by `discover`/`devices`) or HOST[:PORT].

Security: the phone's certificate is self-signed, so it is pinned by SHA-256 fingerprint
at pairing time. During pairing both sides show a 6-digit code derived from the
certificate each side sees; matching codes mean nobody is intercepting the connection.
Discovery needs `pip install zeroconf`; everything else is the standard library.
"""
import argparse
import hashlib
import hmac
import http.client
import http.server
import json
import os
import secrets
import socket
import ssl
import sys
import time
from pathlib import Path

SERVICE_TYPE = "_remoteai._tcp.local."
DEFAULT_PORT = 2424
STORE = Path(os.environ.get("REMOTEAI_HOME", Path.home() / ".config" / "remoteai")) / "devices.json"


class RemoteAIError(Exception):
    pass


# ------------------------------------------------------------------ Storage

def load_devices():
    try:
        return json.loads(STORE.read_text())
    except FileNotFoundError:
        return []


def save_devices(devices):
    STORE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STORE.with_suffix(".tmp")
    # The file holds bearer tokens: owner-only.
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(devices, f, indent=2)
    tmp.replace(STORE)


# ------------------------------------------------------------------ Discovery

def discover(timeout=3.0):
    """Phones advertising the RemoteAI service: [{name, host, port, fingerprint, model}]."""
    try:
        from zeroconf import ServiceBrowser, Zeroconf
    except ImportError:
        raise RemoteAIError("discovery needs the zeroconf package: pip install zeroconf")

    names = set()

    class Listener:
        def add_service(self, zc, type_, name):
            names.add(name)

        def update_service(self, zc, type_, name):
            names.add(name)

        def remove_service(self, zc, type_, name):
            pass

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, SERVICE_TYPE, Listener())
        time.sleep(timeout)
        found = []
        for name in sorted(names):
            info = zc.get_service_info(SERVICE_TYPE, name, timeout=3000)
            if not info:
                continue
            addresses = info.parsed_addresses()
            ipv4 = [a for a in addresses if ":" not in a]
            props = {k.decode(): (v or b"").decode() for k, v in info.properties.items()}
            found.append({
                "name": name[: -len("." + SERVICE_TYPE)],
                "host": (ipv4 or addresses or [None])[0],
                "port": info.port,
                "fingerprint": props.get("fp", ""),
                "model": props.get("model", ""),
            })
        return found
    finally:
        zc.close()


# ------------------------------------------------------------------ TLS + HTTP

def call(host, port, method, path, body=None, token=None, pin=None, timeout=300):
    """One HTTPS request. Returns (status, json, fingerprint seen). Enforces `pin` if given."""
    ctx = ssl.create_default_context()
    # The phone's certificate is self-signed; it is checked by fingerprint below instead.
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    conn = http.client.HTTPSConnection(host, port, context=ctx, timeout=timeout)
    try:
        conn.connect()
        fingerprint = hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest()
        if pin is not None and not hmac.compare_digest(fingerprint, pin):
            raise RemoteAIError(
                f"{host}:{port} presented a different certificate than when it was paired "
                f"({fingerprint[:16]}…, expected {pin[:16]}…). Refusing to send anything. "
                "If the app was reinstalled, `forget` it and pair again."
            )
        headers = {"content-type": "application/json", "anthropic-version": "2023-06-01"}
        if token:
            headers["authorization"] = f"Bearer {token}"
        payload = json.dumps(body).encode() if body is not None else None
        conn.request(method, path, body=payload, headers=headers)
        response = conn.getresponse()
        raw = response.read()
        try:
            data = json.loads(raw) if raw else {}
        except ValueError:
            data = {"raw": raw.decode(errors="replace")}
        return response.status, data, fingerprint
    finally:
        conn.close()


def error_text(data):
    return (data.get("error") or {}).get("message") or json.dumps(data)


# ------------------------------------------------------------------ Pairing

def sas_code(fingerprint_hex, client_nonce, server_nonce):
    """The 6-digit comparison code; must match Pairing.code() in the app."""
    digest = hashlib.sha256(bytes.fromhex(fingerprint_hex) + client_nonce + server_nonce).digest()
    value = int.from_bytes(digest[:4], "big") % 1_000_000
    return f"{value:06d}"[:3] + " " + f"{value:06d}"[3:]


def pair(host, port, client_name, on_code=print, expected_fingerprint=None):
    """Pairs with the phone. With `expected_fingerprint` (re-pairing), refuses a different certificate."""
    client_nonce = secrets.token_bytes(32)
    status, data, fingerprint = call(host, port, "POST", "/v1/pair/start", {
        "client_name": client_name,
        "commitment": hashlib.sha256(client_nonce).hexdigest(),
    }, pin=expected_fingerprint)
    if status != 200:
        raise RemoteAIError(f"pairing start failed ({status}): {error_text(data)}")

    code = sas_code(fingerprint, client_nonce, bytes.fromhex(data["server_nonce"]))
    on_code(code, data.get("device_name", host))

    # Waits while the person at the phone approves (up to two minutes).
    status, data, _ = call(host, port, "POST", "/v1/pair/finish", {
        "pairing_id": data["pairing_id"],
        "client_nonce": client_nonce.hex(),
    }, pin=fingerprint, timeout=180)
    if status != 200:
        raise RemoteAIError(f"pairing failed ({status}): {error_text(data)}")

    device = {
        "name": data["device_name"],
        "fingerprint": fingerprint,
        "token": data["token"],
        "client_id": data["client_id"],
        "client_name": client_name,
        "host": host,
        "port": port,
        "paired_at": int(time.time()),
    }
    devices = [d for d in load_devices() if d["fingerprint"] != fingerprint] + [device]
    save_devices(devices)
    return device


# ------------------------------------------------------------------ Lookup

def parse_host(target):
    host, _, port = target.rpartition(":") if target.count(":") == 1 else (target, "", "")
    return (host, int(port)) if port.isdigit() else (target, DEFAULT_PORT)


def looks_like_host(target):
    try:
        socket.getaddrinfo(parse_host(target)[0], None)
        return any(c.isdigit() for c in target) or "." in target
    except OSError:
        return False


def find_paired(target):
    devices = load_devices()
    matches = [d for d in devices if target.lower() in d["name"].lower() or d["fingerprint"].startswith(target.lower())]
    if not matches and looks_like_host(target):
        host, port = parse_host(target)
        matches = [d for d in devices if d["host"] == host and d["port"] == port]
    if len(matches) != 1:
        names = ", ".join(d["name"] for d in (matches or devices)) or "none"
        raise RemoteAIError(f"'{target}' matches {len(matches)} paired phones (paired: {names})")
    return matches[0]


def locate(device):
    """Current address of a paired phone: via mDNS if possible, else the last one used."""
    try:
        for found in discover(timeout=2.0):
            if found["fingerprint"] == device["fingerprint"] and found["host"]:
                if (found["host"], found["port"]) != (device["host"], device["port"]):
                    device["host"], device["port"] = found["host"], found["port"]
                    save_devices([device if d["fingerprint"] == device["fingerprint"] else d for d in load_devices()])
                break
    except RemoteAIError:
        pass  # zeroconf not installed: fall back to the stored address
    return device["host"], device["port"]


def removed_message(device):
    return (f"{device['name']} no longer accepts this coordinator: the pairing was removed on the phone. "
            f"Run `remoteai.py pair \"{device['name']}\"` to pair again (needs approval on the phone).")


def ask_yes_no(question):
    """Asks on the terminal; anything without a terminal (scripts, services) gets no."""
    if not sys.stdin.isatty():
        return False
    return input(f"{question} [y/N] ").strip().lower() in ("y", "yes")


def show_code(code, device_name):
    print(f"\n    Code: {code}\n")
    print(f"Check {device_name} shows the same code, then approve it there with your fingerprint or PIN.")
    print("Waiting up to 2 minutes…")


def send(device, body, timeout=300, ask=ask_yes_no):
    """Sends a Messages request. If the phone has removed this coordinator, offers to pair again."""
    host, port = locate(device)
    status, data, fingerprint = call(host, port, "POST", "/v1/messages", body,
                                     token=device["token"], pin=device["fingerprint"], timeout=timeout)
    if status != 401:
        return status, data, fingerprint

    print(removed_message(device).split(" Run ")[0], file=sys.stderr)
    if not ask("Request pairing again?"):
        raise RemoteAIError(removed_message(device))
    device = pair(host, port, device.get("client_name") or socket.gethostname(),
                  on_code=show_code, expected_fingerprint=device["fingerprint"])
    print(f"Paired with {device['name']} again.", file=sys.stderr)
    return call(host, port, "POST", "/v1/messages", body,
                token=device["token"], pin=device["fingerprint"], timeout=timeout)


# ------------------------------------------------------------------ Proxy

def serve_proxy(device, port):
    host, phone_port = locate(device)

    class Handler(http.server.BaseHTTPRequestHandler):
        def _forward(self, method):
            length = int(self.headers.get("content-length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            try:
                # Any API key the client sends is ignored; the phone token is used instead.
                status, data, _ = call(host, phone_port, method, self.path.split("?")[0], body,
                                       token=device["token"], pin=device["fingerprint"])
            except (RemoteAIError, OSError) as e:
                status, data = 502, {"type": "error", "error": {"type": "api_error", "message": str(e)}}
            if status == 401:
                # The proxy can't ask anyone, so it explains instead of re-pairing.
                message = removed_message(device)
                sys.stderr.write(f"proxy: {message}\n")
                data = {"type": "error", "error": {"type": "authentication_error", "message": message}}
            out = json.dumps(data).encode()
            self.send_response(status)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)

        def do_GET(self):
            self._forward("GET")

        def do_POST(self):
            self._forward("POST")

        def log_message(self, fmt, *args):
            sys.stderr.write("proxy: " + fmt % args + "\n")

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Forwarding http://127.0.0.1:{port} → {device['name']} ({host}:{phone_port}, TLS, pinned)")
    print(f"Use base_url=http://127.0.0.1:{port} with any api_key. Ctrl-C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


# ------------------------------------------------------------------ CLI

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("discover")
    p = sub.add_parser("pair")
    p.add_argument("phone", nargs="?")
    p.add_argument("--name", default=socket.gethostname(), help="name shown on the phone")
    sub.add_parser("devices")
    p = sub.add_parser("forget")
    p.add_argument("phone")
    p = sub.add_parser("ask")
    p.add_argument("phone")
    p.add_argument("prompt")
    p.add_argument("--system")
    p.add_argument("--max-tokens", type=int, default=1024)
    p = sub.add_parser("proxy")
    p.add_argument("phone")
    p.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    try:
        if args.command == "discover":
            paired = {d["fingerprint"] for d in load_devices()}
            found = discover()
            if not found:
                print("No phones found. Is the API on, and is this machine on the same network?")
            for f in found:
                mark = "paired" if f["fingerprint"] in paired else "not paired"
                print(f"{f['name']:32} {f['host']}:{f['port']:<6} {f['model']:28} {mark}")

        elif args.command == "pair":
            if args.phone and looks_like_host(args.phone):
                host, port = parse_host(args.phone)
            else:
                found = [f for f in discover() if not args.phone or args.phone.lower() in f["name"].lower()]
                if len(found) != 1:
                    names = ", ".join(f["name"] for f in found) or "none"
                    raise RemoteAIError(f"found {len(found)} matching phones ({names}); name one, or give HOST[:PORT]")
                host, port = found[0]["host"], found[0]["port"]

            def show(code, device_name):
                print(f"Pairing with {device_name} at {host}:{port} as \"{args.name}\".")
                show_code(code, device_name)

            device = pair(host, port, args.name, on_code=show)
            print(f"Paired with {device['name']}. Certificate pinned: {device['fingerprint'][:16]}…")
            print(f"Saved to {STORE}")

        elif args.command == "devices":
            for d in load_devices():
                print(f"{d['name']:32} {d['host']}:{d['port']:<6} fp {d['fingerprint'][:16]}…  "
                      f"paired {time.strftime('%Y-%m-%d', time.localtime(d['paired_at']))}")

        elif args.command == "forget":
            device = find_paired(args.phone)
            save_devices([d for d in load_devices() if d["fingerprint"] != device["fingerprint"]])
            print(f"Forgot {device['name']}. Remove it on the phone too, under Paired coordinators.")

        elif args.command == "ask":
            body = {"model": "phone", "max_tokens": args.max_tokens,
                    "messages": [{"role": "user", "content": args.prompt}]}
            if args.system:
                body["system"] = args.system
            status, data, _ = send(find_paired(args.phone), body)
            if status != 200:
                raise RemoteAIError(f"request failed ({status}): {error_text(data)}")
            print("".join(b.get("text", "") for b in data["content"]))

        elif args.command == "proxy":
            serve_proxy(find_paired(args.phone), args.port)
    except RemoteAIError as e:
        sys.exit(f"error: {e}")
    except (OSError, http.client.HTTPException) as e:
        sys.exit(f"error: could not reach the phone: {e}")


if __name__ == "__main__":
    main()
