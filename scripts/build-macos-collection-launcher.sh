#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
MOUNT="$WORK/mount"
cleanup() { hdiutil detach "$MOUNT" -quiet 2>/dev/null || true; rm -rf "$WORK"; }
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
hdiutil convert "$WORK/template.dmg" -format UDTO -o "$WORK/raw" -quiet
DEST="$ROOT/backend/src/main/resources/windows-collection/native/macos-template.dmg"
mkdir -p "$(dirname "$DEST")"
cp "$WORK/raw.cdr" "$DEST"
# Check that payload slots survive raw-image construction and can be mounted after patching.
python3 - "$DEST" "$WORK/test.dmg" <<'PY'
import pathlib, sys
data = bytearray(pathlib.Path(sys.argv[1]).read_bytes())
for marker, size, value in [(b'WFQ_COLLECTION_CONFIG_SLOT_V1', 4096, b'{"test":true}'), (b'WFQ_COLLECTION_SCRIPT_SLOT_V1', 131072, b'function run(){return "collector-template-ok";}')]:
    assert data.count(marker) == 1, 'Slot must occur exactly once'
    pos = data.index(marker)
    assert data[pos+len(marker):pos+size] == b' ' * (size-len(marker)), 'Slot must be contiguous'
    data[pos:pos+size] = value + b' ' * (size-len(value))
pathlib.Path(sys.argv[2]).write_bytes(data)
PY
hdiutil attach "$WORK/test.dmg" -mountpoint "$MOUNT" -readonly -nobrowse -quiet
plutil -lint "$MOUNT/Asset Collector.app/Contents/Info.plist"
RESULT="$(osascript -l JavaScript "$MOUNT/Asset Collector.app/Contents/Resources/CollectMac.js")"
test "$RESULT" = 'collector-template-ok'
hdiutil detach "$MOUNT" -quiet
echo 'PASS: raw DMG slots patched, mounted, and JXA executed'
