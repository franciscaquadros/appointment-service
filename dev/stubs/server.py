"""Local contract stub only. Durable PUT deduplication; no external availability model."""
import json
import os
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATABASE = "/data/reservations.sqlite"
STATE = {"status": 0, "retryAfter": 5, "delaySeconds": 0}
LOCK = threading.Lock()
os.makedirs("/data", exist_ok=True)
with sqlite3.connect(DATABASE) as db:
    db.execute("CREATE TABLE IF NOT EXISTS reservation (key TEXT PRIMARY KEY, payload TEXT NOT NULL)")

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log identifiers, headers, or request bodies.

    def respond(self, status, body=None, headers=None):
        data = json.dumps(body or {}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Deliberately supports caller timeout after a durable write.

    def read_json(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = bytearray()
            while True:
                line = self.rfile.readline(100)
                size = int(line.split(b";", 1)[0].strip(), 16)
                if size == 0:
                    if self.rfile.readline(100) != b"\r\n":
                        raise ValueError("Unexpected trailers")
                    break
                if size < 0 or len(body) + size > 8192:
                    raise ValueError("Invalid length")
                body.extend(self.rfile.read(size))
                if self.rfile.read(2) != b"\r\n":
                    raise ValueError("Invalid chunk")
            return json.loads(body)
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= 8192:
            raise ValueError("Invalid length")
        return json.loads(self.rfile.read(length))

    def do_GET(self):
        if self.path == "/health":
            return self.respond(200)
        if self.path == "/__admin/state":
            with sqlite3.connect(DATABASE) as db:
                total = db.execute("SELECT count(*) FROM reservation").fetchone()[0]
            with LOCK:
                return self.respond(200, dict(STATE, reservations=total))
        self.respond(404)

    def do_POST(self):
        if self.path != "/__admin/config":
            return self.respond(404)
        try:
            data = self.read_json()
            status = int(data.get("status", 0))
            delay = float(data.get("delaySeconds", 0))
            retry = int(data.get("retryAfter", 5))
            if (status != 0 and not 400 <= status <= 599) or not 0 <= delay <= 30 or not 0 <= retry <= 900:
                raise ValueError("Invalid configuration")
            with LOCK:
                STATE.update(status=status, retryAfter=retry, delaySeconds=delay)
            self.respond(200)
        except (ValueError, TypeError):
            self.respond(400)

    def do_PUT(self):
        if not self.path.startswith("/reservations/"):
            return self.respond(404)
        try:
            data = self.read_json()
            appointment = self.path.removeprefix("/reservations/")
            expected = appointment + ":" + os.environ["OPERATION"]
            if self.headers.get("Idempotency-Key") != expected or data.get("appointmentId") != appointment:
                return self.respond(400)
            if set(data) != {"appointmentId", "resourceId", "startsAt", "endsAt"}:
                return self.respond(400)
            payload = json.dumps(data, sort_keys=True)
            with LOCK:
                config = dict(STATE)
            if config["status"]:
                return self.respond(config["status"], headers={"Retry-After": config["retryAfter"]})
            with sqlite3.connect(DATABASE, timeout=5) as db:
                db.execute("BEGIN IMMEDIATE")
                previous = db.execute("SELECT payload FROM reservation WHERE key=?", (expected,)).fetchone()
                if previous and previous[0] != payload:
                    return self.respond(409)
                db.execute("INSERT OR IGNORE INTO reservation VALUES (?,?)", (expected, payload))
            # Commit first: a timeout here is an uncertain remote success; retries remain safe.
            time.sleep(config["delaySeconds"])
            self.respond(200 if previous else 201)
        except (ValueError, TypeError):
            self.respond(400)

ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
