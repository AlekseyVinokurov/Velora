#!/usr/bin/env python3
"""Check launcher/notification wiring without Android tooling or dependencies."""
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'TMessagesProj/src/main'
RES = MAIN / 'res'
A = '{http://schemas.android.com/apk/res/android}'


def main():
    resources = set()
    xml_count = 0
    for directory in RES.iterdir():
        if not directory.is_dir():
            continue
        kind = directory.name.split('-')[0]
        for path in directory.iterdir():
            if not path.is_file():
                continue
            resources.add((kind, path.stem.removesuffix('.9')))
            if kind == 'values' and path.suffix == '.xml':
                for element in ET.parse(path).getroot():
                    name = element.get('name', '')
                    resources.add((element.tag, name))
                    resources.add((element.tag, name.replace('.', '_')))
    controller = (MAIN / 'java/org/telegram/ui/LauncherIconController.java').read_text()
    for kind, name in re.findall(r'R\.(\w+)\.(\w+)', controller):
        assert (kind, name) in resources, f'Missing controller resource: {kind}/{name}'
    # App overlays may refer to resources owned by the corresponding app module.
    for module in ROOT.glob('TMessagesProj_App*'):
        for path in (module / 'src/main/res').glob('*/*'):
            if path.is_file():
                resources.add((path.parent.name.split('-')[0], path.stem.removesuffix('.9')))
    paths = list(RES.glob('*/velora_*.xml'))
    paths += [MAIN / 'AndroidManifest.xml', *list((ROOT / 'TMessagesProj/config').rglob('AndroidManifest*.xml'))]
    paths += list(RES.glob('values*/styles.xml'))
    for path in paths:
        tree = ET.parse(path)
        xml_count += 1
        for element in tree.iter():
            for value in element.attrib.values():
                if not value.startswith('@') or value.startswith(('@android:', '@null', '@+')):
                    continue
                if '/' in value:
                    kind, name = value[1:].split('/', 1)
                    assert (kind, name) in resources, f'Missing reference {value} in {path}'
    app = ET.parse(MAIN / 'AndroidManifest.xml').find('application')
    aliases = {element.get(A + 'name').rsplit('.', 1)[-1]: element for element in app.findall('activity-alias')}
    keys = re.findall(r'^        \w+\("(\w+)"', controller, re.M)
    assert len(keys) == len(set(keys)) == 16
    for key in keys:
        assert key in aliases, f'Undeclared alias: {key}'
    assert aliases['BlueIcon'].get(A + 'icon') == '@mipmap/ic_launcher'
    launchers = [el for el in app.findall('activity-alias')
                 if el.find(f'intent-filter/category[@{A}name="android.intent.category.LAUNCHER"]') is not None]
    enabled = [el.get(A + 'name') for el in launchers if el.get(A + 'enabled', 'true') == 'true']
    assert enabled == ['org.telegram.messenger.VeloraIcon'], enabled
    assert aliases['VeloraIcon'].find('meta-data').get(A + 'resource') == '@xml/shortcuts'
    launch = next(el for el in app.findall('activity') if el.get(A + 'name') == 'org.telegram.ui.LaunchActivity')
    assert launch.get(A + 'enabled', 'true') == 'true'
    assert launch.find(f'intent-filter/data[@{A}scheme="tg"]') is not None, 'Telegram deep links preserved'
    for manifest in (ROOT / 'TMessagesProj/config').rglob('AndroidManifest*.xml'):
        overlay = ET.parse(manifest).find('application')
        assert overlay.get(A + 'icon') == '@mipmap/velora_launcher'
        for alias in overlay.findall('activity-alias'):
            assert alias.get(A + 'enabled', 'true') == 'false', 'No overlay may add a second default launcher'
    for folder in ['mipmap-anydpi-v26', 'mipmap-anydpi-v33']:
        for name in ['velora_launcher', 'velora_launcher_round']:
            adaptive = ET.parse(RES / folder / (name + '.xml')).getroot()
            assert adaptive.tag == 'adaptive-icon'
            assert adaptive.find('foreground').get(A + 'drawable') == '@drawable/velora_icon_foreground'
            assert adaptive.find('background').get(A + 'drawable') == '@drawable/velora_icon_background'
            if folder.endswith('33'):
                assert adaptive.find('monochrome').get(A + 'drawable') == '@drawable/velora_icon_monochrome'
    notification = ET.parse(RES / 'drawable/velora_notification.xml').getroot()
    assert notification.tag == 'vector'
    assert notification.get(A + 'width') == notification.get(A + 'height') == '24dp'
    for element in notification.iter():
        assert element.tag in ['vector', 'path']
        if element.tag == 'path':
            assert set(element.attrib) == {A + 'fillColor', A + 'pathData'}
            assert element.get(A + 'fillColor') == '#FFFFFFFF', 'Notification must be opaque white on transparent'
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        for suffix in ['', '_round']:
            data = (RES / ('mipmap-' + density) / ('velora_launcher' + suffix + '.png')).read_bytes()
            assert data[:8] == b'\x89PNG\r\n\x1a\n'
            assert struct.unpack('>II', data[16:24]) == (size, size)
            assert data[25] == 6, 'Fallback must have an alpha channel'
    java = MAIN / 'java'
    for file in ['org/telegram/messenger/NotificationsController.java', 'org/telegram/messenger/NotificationsService.java',
                 'org/telegram/messenger/FilesMigrationService.java', 'app/nimarkogram/messenger/updater/NimarkoUpdater.java']:
        text = (java / file).read_text()
        assert '.setSmallIcon(R.drawable.notification)' not in text
        assert '.setSmallIcon(org.telegram.ui.LauncherIconController.getNotificationIcon())' in text
    music = (java / 'org/telegram/messenger/MusicPlayerService.java').read_text()
    assert music.count('.setSmallIcon(org.telegram.ui.LauncherIconController.getNotificationIcon(R.drawable.player))') == 2
    theme = (java / 'org/telegram/ui/ThemeActivity.java').read_text()
    for expected in ['notificationIconRow = rowCount++', 'position == notificationIconRow', 'setVeloraNotificationIcon(which == 0)']:
        assert expected in theme
    print(f'Icon resources, {xml_count} XML files, all 16 aliases, 10 fallbacks, notification wiring: OK')


if __name__ == '__main__':
    main()
