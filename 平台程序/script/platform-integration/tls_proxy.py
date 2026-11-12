#!/usr/bin/env python3
"""仅本机 HTTPS -> A HTTP 字节透传；可在 A 成功提交后丢弃指定业务键的一次响应。"""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import logging
import os
from pathlib import Path
import socket
import ssl
import time
from urllib.parse import urlsplit

LOG = logging.getLogger("platform-integration-proxy")
PREFIX = "/internal-api/design/v1/platform/"
LIMIT = 2 * 1024 * 1024
ACTIONS = set((
    "create-project list-projects get-project get-ai-job cancel-ai-job note-polish list-result-versions "
    "create-flat-job create-elevation-job confirm-flat-candidate select-elevation-candidate "
    "create-revision-request validate-scheme-ref list-cases list-case-styles get-case-detail favorite-case "
    "unfavorite-case list-favorites budget-rule-info create-budget-estimate create-upload-ticket "
    "complete-upload create-download-url query-receipt get-settlement"
).split())


def loopback_origin(value):
    url = urlsplit(value)
    if (url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port
            or url.username or url.password or url.path or url.query or url.fragment):
        raise ValueError("upstream must be explicit http://127.0.0.1:<port>")
    return url


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass  # Default request logs are deliberately suppressed; only allowlisted event fields are persisted.

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def reply(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def do_POST(self):
        upstream = None
        try:
            if (not self.path.startswith(PREFIX) or "?" in self.path or "#" in self.path
                    or self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding")):
                self.reply(400)
                return
            length = int(self.headers.get("Content-Length", "-1"))
            if not 0 < length <= 262144:
                self.reply(413)
                return
            raw = self.rfile.read(length)
            if len(raw) != length:
                self.reply(400)
                return
            request = json.loads(raw)
            # The parsed envelope is used only to select the fault. HMAC-protected bytes stay unchanged.
            action = self.path[len(PREFIX):]
            if action not in ACTIONS:
                self.reply(404)
                return
            headers = {key: value for key, value in self.headers.items()
                       if key.lower() not in {"host", "connection", "proxy-connection", "keep-alive", "upgrade"}}
            headers["Connection"] = "close"
            upstream = http.client.HTTPConnection(self.server.upstream.hostname, self.server.upstream.port, timeout=12)
            upstream.request("POST", self.path, body=raw, headers=headers)
            response = upstream.getresponse()
            result = response.read(LIMIT + 1)
            if len(result) > LIMIT:
                raise ValueError("upstream body exceeds limit")
            drop = False
            marker = self.server.directory / "drop-once.json"
            if marker.exists():
                fault = json.loads(marker.read_text(encoding="utf-8"))
                command = (request.get("payload") or {}).get("command") or {}
                if (fault.get("businessKey") and fault["businessKey"] == command.get("idempotencyKey")
                        and fault.get("action") == action and str(fault.get("orgId")) == str(request.get("orgId"))
                        and fault.get("callerAppId") == request.get("callerAppId")):
                    payload = json.loads(result)
                    receipt = payload.get("receipt") or {}
                    if (response.status == 200 and payload.get("code") == 0
                            and receipt.get("status") == "SUCCEEDED"
                            and receipt.get("requestKey") == request.get("requestKey")):
                        marker.unlink()
                        drop = True
            event = {"at": int(time.time()), "action": action, "upstreamStatus": response.status,
                     "responseDropped": drop}
            with (self.server.directory / "proxy-events.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(event) + "\n")
            if drop:
                LOG.warning("dropped one successful A receipt response; downstream must resolve UNKNOWN")
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            self.send_response(response.status)
            self.send_header("Content-Type", response.getheader("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(result)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(result)
            self.close_connection = True
        except (OSError, ValueError, TypeError, http.client.HTTPException) as error:
            LOG.error("local proxy failed (%s); request and response values withheld", type(error).__name__)
            self.close_connection = True
            try:
                self.reply(502)
            except OSError:
                LOG.warning("downstream already closed")
        finally:
            if upstream is not None:
                upstream.close()


class LocalServer(HTTPServer):
    def get_request(self):
        raw, address = self.socket.accept()
        raw.settimeout(10)
        try:
            return self.tls.wrap_socket(raw, server_side=True), address
        except (OSError, ssl.SSLError):
            raw.close()
            LOG.warning("local TLS handshake rejected or timed out")
            raise

    def handle_error(self, *_args):
        LOG.error("local proxy handler failed; raw traceback withheld")


def serve(directory, upstream, port):
    os.umask(0o077)
    if not (directory / "NON_PRODUCTION_FIXTURE").is_file():
        raise ValueError("fixture marker required")
    if not 1024 <= port <= 65535:
        raise ValueError("invalid listen port")
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(directory / "server.pem", directory / "server.key")
    with LocalServer(("127.0.0.1", port), Proxy) as server:
        server.upstream = loopback_origin(upstream)
        server.directory = directory
        server.tls = tls
        LOG.warning("local TLS proxy ready; HMAC bytes forwarded unchanged")
        server.serve_forever(poll_interval=0.2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--upstream", type=loopback_origin, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    try:
        serve(args.directory.resolve(), args.upstream.geturl(), args.port)
    except KeyboardInterrupt:
        LOG.warning("local proxy stopped")
    except (OSError, ValueError) as error:
        LOG.error("proxy startup failed (%s)", type(error).__name__)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
