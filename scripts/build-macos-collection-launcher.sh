#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
MOUNT="$WORK/mount"
GUI_MOUNT='/Volumes/Asset Collector'
GUI_ATTACHED=0
cleanup() {
  hdiutil detach "$MOUNT" -quiet 2>/dev/null || true
  if [ "$GUI_ATTACHED" = 1 ]; then hdiutil detach "$GUI_MOUNT" -quiet 2>/dev/null || true; fi
  rm -rf "$WORK"
}
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
<key>NSLocalNetworkUsageDescription</key><string>连接公司内网资产系统，按姓名查询部门并提交本机配置，等待管理员审核。</string>
<key>LSUIElement</key><true/>
</dict></plist>
PLIST
clang -fobjc-arc -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 -framework AppKit "$ROOT/scripts/collection-launchers/MacLauncher.m" -o "$APP/MacOS/collect"
lipo -verify_arch arm64 x86_64 "$APP/MacOS/collect"
codesign --force --sign - --identifier com.wfq.asset.collector "$APP/MacOS/collect"
codesign --verify "$APP/MacOS/collect"
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
# Test fixed-size payload writes, then use the exact Java backend writer to produce a standard DMG.
python3 - "$DEST" "$WORK/test.raw" <<'PY'
import pathlib, sys
data = bytearray(pathlib.Path(sys.argv[1]).read_bytes())
script = b'function run(){ObjC.import("Foundation");var e=Ref();if(!$("collector-template-ok").writeToFileAtomicallyEncodingError($("launch-smoke.txt"),true,$.NSUTF8StringEncoding,e))throw new Error("Cannot write smoke marker");}'
for marker, size, value in [(b'WFQ_COLLECTION_CONFIG_SLOT_V1', 4096, b'{"test":true}'), (b'WFQ_COLLECTION_SCRIPT_SLOT_V1', 131072, script)]:
    assert data.count(marker) == 1, 'Slot must occur exactly once'
    pos = data.index(marker)
    assert data[pos+len(marker):pos+size] == b' ' * (size-len(marker)), 'Slot must be contiguous'
    data[pos:pos+size] = value + b' ' * (size-len(value))
pathlib.Path(sys.argv[2]).write_bytes(data)
PY
mkdir -p "$WORK/classes"
javac -d "$WORK/classes" "$ROOT/backend/src/main/java/com/acme/assetmanagement/integration/deployment/MacDiskImage.java" "$ROOT/scripts/collection-launchers/WrapMacImage.java"
java -cp "$WORK/classes" WrapMacImage "$WORK/test.raw" "$WORK/test.dmg"
hdiutil verify "$WORK/test.dmg"
test ! -d "$GUI_MOUNT"
open -a DiskImageMounter "$WORK/test.dmg"
for attempt in {1..30}; do
  test ! -d "$GUI_MOUNT" || break
  sleep 1
done
test -d "$GUI_MOUNT"
GUI_ATTACHED=1
plutil -lint "$GUI_MOUNT/Asset Collector.app/Contents/Info.plist"
open -W "$GUI_MOUNT/Asset Collector.app"
KEY="$(shasum -a 256 "$GUI_MOUNT/Asset Collector.app/Contents/Resources/config.json" | cut -d ' ' -f 1)"
test "$(cat "$HOME/Library/Application Support/WFQAssetCollector/$KEY/launch-smoke.txt")" = 'collector-template-ok'
hdiutil detach "$GUI_MOUNT" -quiet
echo 'PASS: standard DMG opened by DiskImageMounter; APP launched by Launch Services and ran JXA'
