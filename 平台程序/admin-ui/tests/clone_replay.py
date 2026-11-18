"""Exercise the packaged upload and replay pages in separate web views on the same device."""
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.parse import quote, urlsplit
import wave

from playwright.sync_api import expect, sync_playwright


DIST = Path(__file__).resolve().parents[1] / "dist"
WEB = "https://clone.test/admin/clone-upload.html"
PROVIDER = "https://upload.hifly.cc/sample"


def fragment(value):
    return quote(json.dumps(value), safe="")


def serve(route):
    relative = urlsplit(route.request.url).path.removeprefix("/admin/")
    asset = (DIST / relative).resolve()
    assert asset.is_relative_to(DIST) and asset.is_file(), relative
    mime = "application/javascript" if asset.suffix == ".js" else mimetypes.guess_type(asset.name)[0]
    route.fulfill(path=asset, content_type=mime)


def exercise(context, source, mime, upload_id):
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
    page.goto(WEB + "?keepPreview=1#" + fragment(grant))
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
    page.goto(WEB + "#replay=" + fragment(preview))
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
    page.goto(WEB + "#replay=" + fragment(dict(preview, uploadId=upload_id + "0")))
    picker = page.locator("#file")
    expect(picker).to_be_visible()
    expect(player).to_be_hidden()
    picker.set_input_files({"name":source.name, "mimeType":mime, "buffer":bytes(source.stat().st_size)})
    expect(page.locator("#status")).to_contain_text("不一致")
    picker.set_input_files(source)
    expect(player).to_be_visible()
    assert len(stored) == 1
    page.close()
    context.unroute(PROVIDER, accept_upload)


def main():
    with tempfile.TemporaryDirectory() as folder, sync_playwright() as playwright:
        wav, mp4 = Path(folder) / "sample.wav", Path(folder) / "sample.mp4"
        with wave.open(str(wav), "wb") as audio:
            audio.setparams((1, 2, 8000, 0, "NONE", ""))
            audio.writeframes(bytes(6 * 8000 * 2))
        subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
                        "color=size=360x360:rate=2:duration=6", "-c:v", "libx264",
                        "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(mp4)], check=True)
        browser = playwright.chromium.launch(executable_path=os.getenv("CHROME_BIN") or shutil.which("google-chrome"))
        context = browser.new_context()
        context.route("https://clone.test/admin/**", serve)
        context.route("https://res.wx.qq.com/**", lambda route: route.abort())
        context.add_init_script("window.wx={miniProgram:{postMessage:m=>window.receipt=m.data,navigateBack:()=>document.documentElement.dataset.returned='yes'}}")
        exercise(context, wav, "audio/wav", "90071992547409931")
        exercise(context, mp4, "video/mp4", "90071992547409932")
        browser.close()
        print("Passed: audio and video direct PUT, receipt, same-device replay, isolated handles and offline fallback.")


if __name__ == "__main__":
    main()
