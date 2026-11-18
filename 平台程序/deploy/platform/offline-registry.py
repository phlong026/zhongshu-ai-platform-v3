#!/usr/bin/env python3
"""Serve the verified offline Linux/amd64 release objects on IPv4 loopback."""
import argparse
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("objects",type=Path)
    parser.add_argument("--port",type=int,default=5000)
    args=parser.parse_args()
    catalog=json.loads((args.objects/"catalog.json").read_text())

    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, include_body):
            if self.path=="/v2/":
                self.send_response(200)
                self.send_header("Docker-Distribution-API-Version","registry/2.0")
                self.send_header("Content-Length","0")
                self.end_headers()
                return
            matched=re.fullmatch(r"/v2/(paired|library/postgres|library/redis)/(blobs|manifests)/(sha256:[0-9a-f]{64})(?:\?[^ ]*)?",self.path)
            if matched:
                name,kind,oid=matched.groups()
                item=catalog.get(name,{}).get(oid)
                if item and item[0]==kind:
                    path=args.objects/oid.split(":")[1]
                    self.send_response(200)
                    self.send_header("Content-Type",item[1])
                    self.send_header("Docker-Content-Digest",oid)
                    self.send_header("Content-Length",str(path.stat().st_size))
                    self.end_headers()
                    if include_body:
                        with path.open("rb") as f:shutil.copyfileobj(f,self.wfile)
                    return
            self.send_error(404)

    ThreadingHTTPServer(("127.0.0.1",args.port),Handler).serve_forever()

if __name__=="__main__":main()
