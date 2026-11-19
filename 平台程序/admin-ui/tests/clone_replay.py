"""Exercise the packaged upload and replay pages in separate web views on the same device."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import shutil
import tempfile
import time
from threading import Thread
from urllib.parse import quote, urlsplit
import wave

from playwright.sync_api import expect, sync_playwright


DIST = Path(__file__).resolve().parents[1] / "dist"
PROVIDER = "https://upload.hifly.cc/sample"


def fragment(value):
    return quote(json.dumps(value), safe="")


class Assets(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        asset = (DIST / path.removeprefix("/admin/")).resolve()
        if path == "/admin/fixture.html":
            data, mime = b"<!doctype html>", "text/html"
        elif path.startswith("/admin/") and asset.is_relative_to(DIST) and asset.is_file():
            data = asset.read_bytes()
            mime = "application/javascript" if asset.suffix == ".js" else mimetypes.guess_type(asset.name)[0]
        else:
            return self.send_error(404)
        self.send_response(200)
        self.send_header("Content-Type", mime or "application/octet-stream")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_args):
        pass


def exercise(context, web, source, mime, upload_id, cache_available=True):
    stored = []
    def accept_upload(route):
        if route.request.method == "OPTIONS":
            route.fulfill(status=204, headers={"Access-Control-Allow-Origin":"*", "Access-Control-Allow-Methods":"PUT", "Access-Control-Allow-Headers":"Content-Type"})
            return
        assert route.request.method == "PUT"
        stored.append(route.request.post_data_buffer)
        route.fulfill(status=200, headers={"Access-Control-Allow-Origin":"*"})

    context.route(PROVIDER, accept_upload)
    page = context.new_page()
    grant = dict(extension=source.suffix[1:], uploadId=upload_id, contentType=mime,
                 uploadUrl=PROVIDER, expiresAt=int(time.time() * 1000) + 60000)
    page.goto(web + "?keepPreview=1#" + fragment(grant))
    expect(page.locator("#file")).to_be_enabled()
    page.locator("#file").set_input_files(source)
    send = page.locator("#send")
    expect(send).to_be_enabled()
    send.click()
    expect(page.locator("html")).to_have_attribute("data-returned", "yes")
    receipt = page.evaluate("window.receipt")
    assert len(receipt["previewKey"]) == 32
    assert stored == [source.read_bytes()]
    assert receipt["sha256"] == hashlib.sha256(stored[0]).hexdigest()
    page.close()

    preview = dict(receipt, extension=grant["extension"])
    page = context.new_page()
    player = page.locator("#preview" if mime.startswith("video/") else "#voice")
    page.goto(web + "#replay=" + fragment(preview))
    if not cache_available:
        expect(page.locator("#status")).to_contain_text("选择刚上传的原文件")
        page.locator("#file").set_input_files(source)
    expect(player).to_be_visible()
    expect(page.locator("#file")).to_be_hidden()
    expect(page.locator("#send")).to_be_hidden()
    elapsed = player.evaluate("""async p => {
      await p.play()
      if (!p.currentTime) await new Promise(resolve => p.addEventListener('timeupdate', resolve, {once:true}))
      return p.currentTime
    }""")
    assert elapsed > 0
    player.evaluate("p => p.pause()")
    page.locator("#back").click()
    expect(page.locator("html")).to_have_attribute("data-returned", "yes")

    # A receipt for another upload cannot open this cached Blob. Only the identical local file can replay.
    page.goto("about:blank")
    page.goto(web + "#replay=" + fragment(dict(preview, uploadId=upload_id + "0")))
    picker = page.locator("#file")
    expect(picker).to_be_visible()
    expect(picker).to_be_enabled()
    expect(player).to_be_hidden()
    picker.set_input_files({"name":source.name, "mimeType":mime, "buffer":bytes(source.stat().st_size)})
    expect(page.locator("#status")).to_contain_text("不一致")
    picker.set_input_files(source)
    expect(player).to_be_visible()
    assert len(stored) == 1
    page.close()
    context.unroute(PROVIDER, accept_upload)


def configure(browser):
    context = browser.new_context()
    context.route("https://res.wx.qq.com/**", lambda route: route.abort())
    context.add_init_script("window.wx={miniProgram:{postMessage:m=>window.receipt=m.data,navigateBack:()=>document.documentElement.dataset.returned='yes'}}")
    return context


def main():
    with tempfile.TemporaryDirectory() as folder, sync_playwright() as playwright, ThreadingHTTPServer(("127.0.0.1", 0), Assets) as server:
        Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}/admin/"
        web = base + "clone-upload.html"
        wav = Path(folder) / "sample.wav"
        # Six seconds of solid black H.264 video at 360x360 and two frames per second.
        mp4 = Path(__file__).parent / "fixtures" / "clone-sample.mp4"
        with wave.open(str(wav), "wb") as audio:
            audio.setparams((1, 2, 8000, 0, "NONE", ""))
            audio.writeframes(bytes(6 * 8000 * 2))
        browser = playwright.chromium.launch(executable_path=os.getenv("CHROME_BIN") or shutil.which("google-chrome"))
        cold = configure(browser)
        loading = cold.new_page()
        loading.route("**/assets/*.js", lambda route: route.abort())
        loading.goto(web)
        expect(loading.locator("#file")).to_be_disabled()
        expect(loading.locator("#send")).to_be_disabled()
        loading.close()
        exercise(cold, web, mp4, "video/mp4", "90071992547409930")
        cold.close()
        context = configure(browser)
        page = context.new_page()
        page.goto(base + "fixture.html")
        page.evaluate("""() => new Promise(resolve => {
          const req = indexedDB.open('zs-clone-replay', 1)
          req.onupgradeneeded = () => req.result.createObjectStore('samples').put(new Blob(['legacy']), 'old')
          req.onsuccess = () => {req.result.close(); resolve()}
        })""")
        page.goto(web)
        page.wait_for_function("async () => !(await indexedDB.databases()).some(db=>db.name==='zs-clone-replay')")
        page.close()
        # Install a virtual clock in the actual worker before storing either sample.
        probe = context.new_page()
        probe.goto(base + "fixture.html")
        probe.evaluate("""async () => {
          const reg = await navigator.serviceWorker.register('./clone-replay-worker.js',{scope:'./clone-replay-session/'})
          const w = reg.active || reg.installing || reg.waiting
          if (w.state !== 'activated') await new Promise(resolve => w.addEventListener('statechange', () => w.state === 'activated' && resolve()))
        }""")
        worker = context.service_workers[0]
        worker.evaluate("""() => {
          let now = Date.now(); Date.now = () => now
          const timers = new Map()
          self.setTimeout = (fn,ms) => {const id = Symbol(); timers.set(id,{fn,at:now+ms}); return id}
          self.clearTimeout = id => timers.delete(id)
          self.advance = ms => {now += ms; for (const t of [...timers.values()]) if (t.at<=now) t.fn()}
        }""")
        exercise(context, web, wav, "audio/wav", "90071992547409931")
        exercise(context, web, mp4, "video/mp4", "90071992547409932")
        assert worker.evaluate("advance(86400000 - 1); samples.size") == 2
        assert worker.evaluate("advance(1); samples.size") == 0
        assert probe.evaluate("async () => [await indexedDB.databases(), await caches.keys()]") == [[], []]
        context.close()
        for failure in ["Promise.reject(Error('storage unavailable'))", "new Promise(()=>{})"]:
            context = configure(browser)
            context.add_init_script(f"navigator.serviceWorker.register = () => {failure}")
            exercise(context, web, wav, "audio/wav", "90071992547409933", cache_available=False)
            context.close()
        browser.close()
        server.shutdown()
        print("Passed: direct audio/video replay, failed and hanging cache, single PUT, active 24h eviction and legacy cleanup.")


if __name__ == "__main__":
    main()
