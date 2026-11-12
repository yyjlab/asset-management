#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
MOUNT="$WORK/mount"
GUI_MOUNT='/Volumes/Asset Collector'
cleanup() { hdiutil detach "$MOUNT" -quiet 2>/dev/null || true; hdiutil detach "$GUI_MOUNT" -quiet 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT
APP="$WORK/source/Asset Collector.app/Contents"
mkdir -p "$APP/MacOS" "$APP/Resources" "$MOUNT"
cat > "$APP/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>collect</string>
<key>CFBundleIdentifier</key><string>com.wfq.asset.collector</string>
<key>CFBundleName</key><string>Asset Collector</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSUIElement</key><true/>
</dict></plist>
PLIST
cat > "$APP/MacOS/collect" <<'LAUNCH'
#!/bin/bash
set -euo pipefail
SOURCE="$(cd "$(dirname "$0")/../Resources" && pwd)"
KEY="$(shasum -a 256 "$SOURCE/config.json" | cut -d ' ' -f 1)"
BASE="$HOME/Library/Application Support/WFQAssetCollector"
WORK="$BASE/$KEY"
umask 077
mkdir -p "$WORK"
if [ -L "$BASE" ] || [ -L "$WORK" ]; then exit 1; fi
for FILE in config.json CollectMac.js; do
  if [ -L "$WORK/$FILE" ]; then exit 1; fi
  cp "$SOURCE/$FILE" "$WORK/$FILE"
done
cd "$WORK"
exec /usr/bin/osascript -l JavaScript CollectMac.js
LAUNCH
chmod +x "$APP/MacOS/collect"
python3 - "$APP/Resources" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
for name, marker, size in [('config.json', b'WFQ_COLLECTION_CONFIG_SLOT_V1', 4096), ('CollectMac.js', b'WFQ_COLLECTION_SCRIPT_SLOT_V1', 131072)]:
    (root / name).write_bytes(marker + b' ' * (size - len(marker)))
PY
plutil -lint "$APP/Info.plist"
hdiutil create -size 16m -fs HFS+ -volname 'Asset Collector' -srcfolder "$WORK/source" -format UDRW "$WORK/template.dmg" -quiet
hdiutil attach "$WORK/template.dmg" -mountpoint "$MOUNT" -readwrite -nobrowse -quiet
# Request a Finder window when the user opens the image, not just an invisible mount.
bless --folder "$MOUNT" --openfolder "$MOUNT"
hdiutil detach "$MOUNT" -quiet
DEST="$ROOT/backend/src/main/resources/windows-collection/native/macos-template.dmg"
mkdir -p "$(dirname "$DEST")"
cp "$WORK/template.dmg" "$DEST"
# Test the same fixed-size payload writes made by the Java backend, inside a real UDIF container.
python3 - "$DEST" "$WORK/test.dmg" <<'PY'
import pathlib, sys
data = bytearray(pathlib.Path(sys.argv[1]).read_bytes())
assert data[-512:-508] == b'koly', 'Expected a real UDIF DMG, not a renamed raw/CD image'
script = b'function run(){ObjC.import("Foundation");var e=Ref();if(!$("collector-template-ok").writeToFileAtomicallyEncodingError($("launch-smoke.txt"),true,$.NSUTF8StringEncoding,e))throw new Error("Cannot write smoke marker");}'
for marker, size, value in [(b'WFQ_COLLECTION_CONFIG_SLOT_V1', 4096, b'{"test":true}'), (b'WFQ_COLLECTION_SCRIPT_SLOT_V1', 131072, script)]:
    assert data.count(marker) == 1, 'Slot must occur exactly once'
    pos = data.index(marker)
    assert data[pos+len(marker):pos+size] == b' ' * (size-len(marker)), 'Slot must be contiguous'
    data[pos:pos+size] = value + b' ' * (size-len(value))
pathlib.Path(sys.argv[2]).write_bytes(data)
PY
test ! -d "$GUI_MOUNT"
open -a DiskImageMounter "$WORK/test.dmg"
for attempt in {1..30}; do
  test ! -d "$GUI_MOUNT" || break
  sleep 1
done
test -d "$GUI_MOUNT"
plutil -lint "$GUI_MOUNT/Asset Collector.app/Contents/Info.plist"
open -W "$GUI_MOUNT/Asset Collector.app"
KEY="$(shasum -a 256 "$GUI_MOUNT/Asset Collector.app/Contents/Resources/config.json" | cut -d ' ' -f 1)"
test "$(cat "$HOME/Library/Application Support/WFQAssetCollector/$KEY/launch-smoke.txt")" = 'collector-template-ok'
hdiutil detach "$GUI_MOUNT" -quiet
echo 'PASS: standard DMG opened by DiskImageMounter; APP launched by Launch Services and ran JXA'
