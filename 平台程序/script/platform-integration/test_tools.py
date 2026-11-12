#!/usr/bin/env python3
"""仅验证测试工具；上游是本机工具 fixture，不代表 A/B 应用联验。"""
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import runpy
import socket
import ssl
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parent
PREPARE = runpy.run_path(str(ROOT / "prepare.py"))
PROXY = runpy.run_path(str(ROOT / "tls_proxy.py"))


class FixtureUpstream(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        self.server.seen.append((self.path, body, self.headers.get("X-Test-Signature")))
        request = json.loads(body)
        result = json.dumps({"code": 0, "result": {"estimateId": 123},
                             "receipt": {"status": "SUCCEEDED", "requestKey": request.get("requestKey")}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(result)))
        self.end_headers()
        self.wfile.write(result)


class LocalToolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="platform-integration-tools-")
        cls.directory = Path(cls.temporary.name).resolve() / "private"
        PREPARE["prepare"](cls.directory, 18443)
        PREPARE["a_fixture"](cls.directory, 15432, 16379, 18081, 18083)
        PREPARE["private_write"](cls.directory / "fixture-scopes.json", json.dumps([
            {"orgId": 11, "userId": 21, "postId": 31, "walletId": 41},
            {"orgId": 12, "userId": 22, "postId": 32, "walletId": 42}]))
        PREPARE["a_scopes"](cls.directory)
        PREPARE["b_fixture"](cls.directory)
        source = Path(cls.temporary.name) / "source"
        yaml = source / "yudao-server/src/main/resources/application.yaml"
        sql = source / "sql/postgresql/ruoyi-vue-pro.sql"
        yaml.parent.mkdir(parents=True)
        sql.parent.mkdir(parents=True)
        yaml.write_text("spring:\n  application:\n    name: tool-fixture\n")
        sql.write_text('DROP TABLE IF EXISTS "system_users";\nDROP SEQUENCE IF EXISTS "test_seq";\nBEGIN;\n'
                       'CREATE TABLE system_users(status int,password text);\nCOMMIT;\n')
        PREPARE["a_config"](cls.directory, source)
        cls.upstream = HTTPServer(("127.0.0.1", 0), FixtureUpstream)
        cls.upstream.seen = []
        cls.upstream_thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.upstream_thread.start()
        cls.proxy = PROXY["LocalServer"](("127.0.0.1", 0), PROXY["Proxy"])
        cls.proxy.upstream = PROXY["loopback_origin"](f"http://127.0.0.1:{cls.upstream.server_port}")
        cls.proxy.directory = cls.directory
        cls.proxy.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cls.proxy.tls.load_cert_chain(cls.directory / "server.pem", cls.directory / "server.key")
        cls.proxy_thread = threading.Thread(target=cls.proxy.serve_forever, daemon=True)
        cls.proxy_thread.start()

    @classmethod
    def tearDownClass(cls):
        for server in (cls.proxy, cls.upstream):
            server.shutdown()
            server.server_close()
        cls.proxy_thread.join(timeout=5)
        cls.upstream_thread.join(timeout=5)
        cls.temporary.cleanup()

    def request(self, body, context=None):
        connection = http.client.HTTPSConnection("127.0.0.1", self.proxy.server_port, timeout=5,
                                                context=context or ssl.create_default_context(cafile=self.directory / "ca.pem"))
        try:
            connection.request("POST", "/internal-api/design/v1/platform/create-budget-estimate", body,
                               {"Content-Type": "application/json", "X-Test-Signature": "fixture-signature"})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def test_a_private_files_and_existing_directory_rejected(self):
        for path in self.directory.iterdir():
            self.assertEqual(path.stat().st_mode & 0o077, 0, path.name)
        with self.assertRaises(ValueError):
            PREPARE["prepare"](self.directory, 18443)
        bootstrap = (self.directory / "a-bootstrap.sql").read_text()
        self.assertNotIn("DROP TABLE", bootstrap)
        self.assertNotIn("DROP SEQUENCE", bootstrap)
        self.assertEqual(bootstrap.splitlines().count("BEGIN;"), 1)
        self.assertEqual(bootstrap.splitlines().count("COMMIT;"), 1)
        self.assertLess(bootstrap.index("Bootstrap requires an empty target"), bootstrap.index("CREATE TABLE"))
        self.assertIn("UPDATE system_users SET status=1", bootstrap)
        runtime = (self.directory / "a-runtime.properties").read_text()
        self.assertIn((self.directory / "a-overrides.properties").as_uri(), runtime)
        for name in ("a-scopes.sql", "b-fixture.sql"):
            sql = (self.directory / name).read_text()
            self.assertIn("current_database()", sql)
            self.assertNotIn("DELETE", sql)
            self.assertNotIn("TRUNCATE", sql)
        with self.assertRaises(FileExistsError):
            PREPARE["b_fixture"](self.directory)

    def test_b_default_trust_rejects_test_ca(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.request(b'{"requestKey":"trust-test"}', ssl.create_default_context())

    def test_c_hostname_verification_remains_enabled(self):
        context = ssl.create_default_context(cafile=self.directory / "ca.pem")
        with socket.create_connection(("127.0.0.1", self.proxy.server_port), timeout=5) as raw:
            with self.assertRaises(ssl.SSLCertVerificationError):
                context.wrap_socket(raw, server_hostname="wrong.example.invalid")

    def test_d_exact_utf8_bytes_are_forwarded(self):
        raw = '{ "requestKey" : "bytes", "payload": {"note":"非生产汉字"} }'.encode()
        status, result = self.request(raw)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(result)["code"], 0)
        self.assertEqual(self.upstream.seen[-1][1:], (raw, "fixture-signature"))

    def test_e_drop_once_only_after_upstream_success(self):
        marker = self.directory / "drop-once.json"
        marker.write_text(json.dumps({"businessKey": "loss", "action": "create-budget-estimate",
                                      "callerAppId": "local-integration-b", "orgId": 11}))
        raw = json.dumps({"requestKey": "different-remote-key", "callerAppId": "local-integration-b", "orgId": 11,
                          "payload": {"command": {"idempotencyKey": "loss"}}}).encode()
        self.assertEqual(self.request(b'{"requestKey":"unrelated"}')[0], 200)
        self.assertTrue(marker.exists())
        with self.assertRaises(http.client.RemoteDisconnected):
            self.request(raw)
        self.assertFalse(marker.exists())
        self.assertEqual(self.upstream.seen[-1][1], raw)
        self.assertEqual(self.request(raw)[0], 200)
        events = [json.loads(row) for row in (self.directory / "proxy-events.jsonl").read_text().splitlines()]
        self.assertEqual(sum(row["responseDropped"] for row in events), 1)
        for row in events:
            self.assertEqual(set(row), {"at", "action", "upstreamStatus", "responseDropped"})

    def test_f_external_and_ambiguous_origins_are_rejected(self):
        for origin in ("https://127.0.0.1:1234", "http://example.com:1234", "http://127.0.0.1:1234/",
                       "http://user@127.0.0.1:1234", "http://127.0.0.1:1234?x=1"):
            with self.assertRaises(ValueError):
                PROXY["loopback_origin"](origin)


if __name__ == "__main__":
    unittest.main(verbosity=2)
