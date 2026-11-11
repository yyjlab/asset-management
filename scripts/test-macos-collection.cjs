const test = require('node:test');
const assert = require('node:assert/strict');
const { makeInventory, parseDisplays, parseDisks, validEndpoint } = require('../backend/src/main/resources/windows-collection/CollectMac.js');
const uuid = '12345678-1234-1234-1234-123456789abc';
test('Apple Silicon and Intel report actual hardware without Python', () => {
  for (const model of ['Mac14,7', 'MacBookPro16,1']) {
    const result = makeInventory({ SPHardwareDataType: [{ platform_UUID: uuid.toUpperCase(), machine_model: model, machine_name: 'MacBook Pro', chip_type: 'Apple M2', physical_memory: '16 GB', serial_number: 'MAC-SN' }], SPDisplaysDataType: [] }, [], []);
    assert.equal(result.hardwareUuid, uuid); assert.equal(result.collection.computerType, 'laptop');
    assert.equal(result.snapshot.model, model); assert.equal(result.snapshot.cpu, 'Apple M2');
  }
});
test('counts multiple monitors separately, excludes offline entries and does not infer size from pixels', () => {
  const result = parseDisplays({ SPDisplaysDataType: [{ spdisplays_ndrvs: [
    { _name: 'Color LCD', spdisplays_connection_type: 'spdisplays_internal', spdisplays_resolution: '3024 x 1964' },
    { _name: 'DELL U2720Q', spdisplays_connection_type: 'HDMI', 'spdisplays_display-serial-number': 'SAME', 'spdisplays_display-size': '27-inch' },
    { _name: 'DELL U2720Q', spdisplays_connection_type: 'DisplayPort', 'spdisplays_display-serial-number': 'SAME' },
    { _name: 'Offline', spdisplays_online: 'No' }
  ] }] });
  assert.equal(result.monitors.length, 2); assert.ok(result.monitors.every(monitor => monitor.connection !== 'internal'));
  assert.equal(result.monitors[0].sizeInches, 27); assert.equal(result.monitors[1].sizeInches, null); assert.equal(result.status, 'partial');
  assert.equal(parseDisplays({}).status, 'unavailable');
});
test('only known internal physical disk protocols are included', () => {
  const result = parseDisks([
    { Internal: true, TotalSize: 512000000000, MediaName: 'APPLE SSD', BusProtocol: 'Apple Fabric', VirtualOrPhysical: 'Physical' },
    { Internal: false, TotalSize: 64000000000, BusProtocol: 'USB', MediaName: 'USB' },
    { Internal: true, TotalSize: 512000000000, BusProtocol: 'PCI-Express', VirtualOrPhysical: 'Virtual' }
  ]);
  assert.deepEqual(result, [{ model: 'APPLE SSD', capacityBytes: 512000000000, busType: 'AppleFabric' }]);
});
test('mouse and keyboard collection is not executed or uploaded', () => {
  const source = require('node:fs').readFileSync(require.resolve('../backend/src/main/resources/windows-collection/CollectMac.js'), 'utf8');
  assert.doesNotMatch(source, /IOHIDDevice|peripheralBrands|mouseBrands|keyboardBrands/);
  const result = makeInventory({ SPHardwareDataType: [{ platform_UUID: uuid, machine_name: 'Mac mini', machine_model: 'Macmini9,1' }], SPDisplaysDataType: [] }, []);
  assert.equal(result.collection.computerType, 'desktop'); assert.equal('mouseBrands' in result.collection, false);
});
test('invalid UUID and credential-bearing or redirected endpoint paths are rejected', () => {
  assert.throws(() => makeInventory({ SPHardwareDataType: [{ platform_UUID: '00000000-0000-0000-0000-000000000000' }] }, [], []));
  assert.equal(validEndpoint('http://asset.wfq.com/api/integrations/ad-deployment/v1/windows-intakes'), true);
  for (const url of ['http://a:b@asset.wfq.com/api/integrations/ad-deployment/v1/windows-intakes', 'http://asset.wfq.com/evil', 'http://asset.wfq.com/api/integrations/ad-deployment/v1/windows-intakes?token=secret']) assert.equal(validEndpoint(url), false);
});
