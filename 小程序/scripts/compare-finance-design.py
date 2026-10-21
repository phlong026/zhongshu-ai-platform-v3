"""Normalize existing screenshots for QA; originals remain unchanged."""
from PIL import Image, ImageDraw
from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
source = root / "效果图/财务八页效果图_ProductDesign_20261021"
qa = root / "qa/finance-implementation-20261021"
out = qa / "visual-comparison"
out.mkdir(exist_ok=True)
pairs = [
    ("01_加盟商财务专员首页.png", "01-store-staff-home.png"),
    ("02_加盟商财务负责人首页.png", "02-store-lead-home.png"),
    ("03_加盟商收支页.png", "03-store-flows-search-first.png"),
    ("04_加盟商项目账详情.png", "04-store-project-account.png"),
    ("05_总部财务专员首页.png", "05-hq-staff-home.png"),
    ("06_总部财务负责人首页.png", "06-hq-lead-home.png"),
    ("07_总部加盟商往来页.png", "07-hq-partners.png"),
    ("08_设计尾款双方对账详情.png", "08-design-tail-reconciliation.png"),
]
records = []
for i, (reference, screenshot) in enumerate(pairs, 1):
    x = Image.open(source / reference).convert("RGB")
    y = Image.open(qa / "native" / screenshot).convert("RGB")
    record = {"reference": reference, "referencePixels": x.size,
              "native": screenshot, "nativeCapturePixels": y.size,
              "logicalWidth": 390, "nativeLogicalHeight": 844,
              "nativeReportedPixelRatio": 3,
              "normalization": "Both images resized proportionally to 390 logical px wide. Native system status area (47px) and bottom system safe area (34px) removed; no app content is stretched or synthesized."}
    x = x.resize((390, round(x.height * 390 / x.width)), Image.Resampling.LANCZOS)
    y = y.resize((390, round(y.height * 390 / y.width)), Image.Resampling.LANCZOS)
    y = y.crop((0, 47, 390, y.height - 34))
    canvas = Image.new("RGB", (804, max(x.height, y.height) + 70), "#e8e4df")
    canvas.paste(x, (8, 42)); canvas.paste(y, (406, 42))
    draw = ImageDraw.Draw(canvas)
    draw.text((10, 12), "APPROVED REFERENCE - 390 logical px", fill="black")
    draw.text((410, 12), "NATIVE WECHAT - 390 logical px / chrome removed", fill="black")
    filename = f"{i:02}-comparison-final.png"
    canvas.save(out / filename)
    record["comparison"] = filename
    # Same-width top regions keep the brand, type, search and metrics readable.
    focused = Image.new("RGB", (804, 444), "#e8e4df")
    focused.paste(x.crop((0, 0, 390, 400)), (8, 36))
    focused.paste(y.crop((0, 0, 390, 400)), (406, 36))
    d = ImageDraw.Draw(focused)
    d.text((10, 8), "REFERENCE - HEADER / TYPE / FIRST REGION", fill="black")
    d.text((410, 8), "NATIVE - HEADER / TYPE / FIRST REGION", fill="black")
    focused.save(out / f"{i:02}-focused-final.png")
    records.append(record)
(out / "normalization.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"result": "generated", "pairs": len(records), "output": str(out)}, ensure_ascii=False))
