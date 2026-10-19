"""Build small Noto Sans SC faces for engineering UI from the installed OFL font."""
from pathlib import Path
import sys, io, json, base64, hashlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/font-subset'))
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

source = Path('C:/Windows/Fonts/NotoSansSC-VF.ttf')
paths = list((ROOT / 'miniprogram/pages/build').glob('*.js')) + list((ROOT / 'miniprogram/pages/build').glob('*.wxml'))
paths += [ROOT / 'scripts/engineering-fixtures.cjs']
text = ''.join(p.read_text(encoding='utf-8') for p in paths if p.name not in ['font-sources.js', 'fonts.js'])
chars = ''.join(sorted(set(text + ''.join(chr(c) for c in range(32, 127)))))
font = TTFont(source)
options = subset.Options()
options.name_IDs = ['*']
options.name_legacy = True
options.name_languages = ['*']
subsetter = subset.Subsetter(options=options)
subsetter.populate(text=chars)
subsetter.subset(font)
buffer = io.BytesIO()
font.save(buffer)
faces = []
out = ROOT / 'qa/engineering-20261018/fonts'
out.mkdir(parents=True, exist_ok=True)
for weight, family in [(400, 'ZSEngineeringRegular'), (500, 'ZSEngineeringMedium'), (700, 'ZSEngineeringBold')]:
    face = instantiateVariableFont(TTFont(io.BytesIO(buffer.getvalue())), {'wght': weight}, inplace=True)
    # New family names distinguish the three static faces from host system fonts.
    for record in face['name'].names:
        if record.nameID in (1, 4, 6, 16):
            record.string = family.encode(record.getEncoding())
    binary = io.BytesIO()
    face.save(binary)
    data = binary.getvalue()
    (out / (family + '.ttf')).write_bytes(data)
    faces.append({'family': family, 'weight': weight, 'source': 'url("data:font/ttf;base64,' + base64.b64encode(data).decode('ascii') + '")'})
sources = ROOT / 'miniprogram/pages/build/font-sources.js'
sources.write_text('module.exports = ' + json.dumps(faces, ensure_ascii=False) + ';\n', encoding='utf-8')
manifest = {'font': 'Noto Sans SC', 'license': 'SIL OFL 1.1', 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'characterCount': len(chars), 'characters': chars, 'moduleBytes': sources.stat().st_size, 'faces': [{k:v for k,v in f.items() if k!='source'} for f in faces]}
(out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'characters': len(chars), 'fontModuleBytes': sources.stat().st_size}))
