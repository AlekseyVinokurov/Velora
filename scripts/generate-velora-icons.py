#!/usr/bin/env python3
"""Generate Android resources from our hand-traced SVG (Pillow + CairoSVG).

No Android SDK, Gradle, image service or source bitmap is required.
Run: python3 scripts/generate-velora-icons.py
"""
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'TMessagesProj/src/main/res'
SVG = ROOT / 'docs/design/velora-v-fusion.svg'
ANDROID = 'http://schemas.android.com/apk/res/android'
SVG_NS = {'s': 'http://www.w3.org/2000/svg'}
HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'


def write(folder, name, content):
    target = RES / folder / (name + '.xml')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(HEADER + content + '\n')


def main():
    source = SVG.read_text()
    root = ET.fromstring(source)
    paths = root.findall('s:g/s:path', SVG_NS)
    gradients = {e.attrib['id']: e for e in root.findall('s:defs/s:linearGradient', SVG_NS)}
    vector = (f'<vector xmlns:android="{ANDROID}" xmlns:aapt="http://schemas.android.com/aapt" '
              'android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')
    group = '    <group android:translateX="14" android:translateY="14" android:scaleX="0.064" android:scaleY="0.064">\n'
    foreground = vector + group
    mono = vector + group
    for path in paths:
        data = path.attrib['d']
        gradient = gradients[path.attrib['fill'][5:-1]]
        foreground += f'        <path android:pathData="{data}">\n            <aapt:attr name="android:fillColor">\n'
        coords = ' '.join(f'android:{dest}="{gradient.attrib[src]}"' for src, dest in
                          [('x1', 'startX'), ('y1', 'startY'), ('x2', 'endX'), ('y2', 'endY')])
        foreground += f'                <gradient android:type="linear" {coords}>\n'
        for stop in gradient.findall('s:stop', SVG_NS):
            foreground += f'                    <item android:offset="{stop.attrib["offset"]}" android:color="{stop.attrib["stop-color"]}"/>\n'
        foreground += '                </gradient>\n            </aapt:attr>\n        </path>\n'
        mono += f'        <path android:fillColor="#FFFFFFFF" android:pathData="{data}"/>\n'
    write('drawable', 'velora_icon_foreground', foreground + '    </group>\n</vector>')
    write('drawable', 'velora_icon_monochrome', mono + '    </group>\n</vector>')
    write('drawable', 'velora_icon_background', vector + '''    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#062B55"/>
                <item android:offset="0.45" android:color="#090B2B"/>
                <item android:offset="1" android:color="#310852"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>''')
    for folder in ['mipmap-anydpi-v26', 'mipmap-anydpi-v33']:
        for name in ['velora_launcher', 'velora_launcher_round']:
            monochrome = '\n    <monochrome android:drawable="@drawable/velora_icon_monochrome"/>' if folder.endswith('33') else ''
            write(folder, name, f'''<adaptive-icon xmlns:android="{ANDROID}">
    <background android:drawable="@drawable/velora_icon_background"/>
    <foreground android:drawable="@drawable/velora_icon_foreground"/>{monochrome}
</adaptive-icon>''')
    # Android 7.x fallback: crop the adaptive overscan, use proper transparent corners.
    defs, content = source.split('<g transform=', 1)
    defs = defs.replace('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"')
    bg = '''<defs><linearGradient id="bg" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#062B55"/><stop offset="0.45" stop-color="#090B2B"/><stop offset="1" stop-color="#310852"/></linearGradient></defs>'''
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        directory = RES / ('mipmap-' + density)
        directory.mkdir(exist_ok=True)
        for suffix, radius in [('', 17), ('_round', 36)]:
            svg = defs + bg + f'<rect x="18" y="18" width="72" height="72" rx="{radius}" fill="url(#bg)"/>' + '<g transform=' + content
            rendered = cairosvg.svg2png(bytestring=svg.encode(), output_width=size * 4, output_height=size * 4)
            with Image.open(BytesIO(rendered)) as image:
                image.resize((size, size), Image.Resampling.LANCZOS).save(directory / ('velora_launcher' + suffix + '.png'))


if __name__ == '__main__':
    main()
