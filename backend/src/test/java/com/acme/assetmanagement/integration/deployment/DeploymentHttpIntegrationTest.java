package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.lookup.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ad-real-http;DB_CLOSE_DELAY=-1", "app.ad-deployment.enabled=false",
        "app.asset-images.directory=${java.io.tmpdir}/asset-http-test-images"})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "ad.sender.python", matches = ".+")
class DeploymentHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired DeploymentAccessService access;
    @Autowired DeploymentCredentialService credentials;
    @Autowired LookupRepository lookups;
    @Autowired AssetRepository assets;
    @Autowired DeploymentRegistrationRepository registrations;
    @Autowired ObjectMapper json;
    @Autowired DeploymentIntakeService intakeService;
    @Autowired DeploymentIntakeRepository intakes;
    @Autowired com.acme.assetmanagement.user.UserRepository users;

    private LookupValue option(LookupType type, String name) {
        return lookups.findByTypeAndNameIgnoreCase(type, name)
                .orElseGet(() -> lookups.saveAndFlush(new LookupValue(type, name)));
    }

    @Test void automaticIntakeApprovalAndRejectionRoundTripThroughActualPythonHttp() throws Exception {
        option(LookupType.COMPANY, "微丰启"); option(LookupType.LOCATION, "公司"); option(LookupType.STATUS, "当前可用");
        var category = option(LookupType.CATEGORY, "笔记本电脑");
        access.save(true, true);
        var credential = credentials.issue("automatic-intake-http-test", Instant.now().plusSeconds(3600));
        long before = assets.count();
        String script = """
                import sys, json, time
                sys.path.insert(0, sys.argv[1])
                import test_pc_deploy as fixtures
                config = json.loads(sys.stdin.readline())
                case = fixtures.PlatformTests('runTest'); case.setUp()
                context = case.app.app_context(); context.push()
                try:
                    response = case.post('/pc-deploy/api/asset-sync/settings', dict(endpoint=config['endpoint'], token=config['token'], enabled=True, revision=0))
                    assert response.status_code == 200, response.json
                    sender = case.app.extensions['pc_deploy_asset_sender']
                    machines = []
                    for index in range(2):
                        machine = fixtures.machine(serial='DUPLICATE-SN-IS-NOT-IDENTITY')
                        machine['system_info'] = dict(cpus=[dict(name='Intake HTTP CPU')], gpus=[])
                        case.register(machine); machines.append(machine)
                    transport = sender.transport
                    calls = []
                    def lost_response(*args):
                        result = transport(*args); calls.append(args[-1])
                        if len(calls) == 1: raise TimeoutError('lost after commit')
                        return result
                    sender.transport = lost_response
                    sender.process_one(); sender.process_one(now=time.time()+10); sender.process_one(now=time.time()+10)
                    with case.store.connect() as c:
                        rows = [dict(r) for r in c.execute('SELECT * FROM asset_sync_outbox ORDER BY created')]
                    assert len(rows) == 2 and all(r['state'] == 'pending_review' for r in rows), rows
                    assert len(calls) == 3
                    assert calls[0] in calls[1:]
                    print(json.dumps(dict(ready=[r['id'] for r in rows])), flush=True)
                    assert sys.stdin.readline().strip() == 'reviewed'
                    for _ in range(2): sender.process_one(now=time.time()+1000)
                    saved = case.client.get('/pc-deploy/api/dashboard').json['asset_registrations']
                    states = {r['sync']['state'] for r in saved}
                    assert states == {'approved','rejected'}, states
                    approved = next(r for r in saved if r['sync']['state'] == 'approved')
                    assert len(approved['asset_tag']) == 12 and approved['asset_tag'].isdigit()
                    for machine in machines:
                        machine['boot_id'] = str(__import__('uuid').uuid4()); case.register(machine)
                    with case.store.connect() as c: assert c.execute('SELECT count(*) FROM asset_sync_outbox').fetchone()[0] == 2
                    print('automatic_intake_http_passed', flush=True)
                finally:
                    context.pop(); case.tearDown()
                """;
        var process = new ProcessBuilder(System.getProperty("ad.sender.python"), "-c", script,
                System.getProperty("ad.sender.repo")).redirectErrorStream(true).start();
        try (var input = process.getOutputStream(); var output = process.inputReader(StandardCharsets.UTF_8);
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            input.write((json.writeValueAsString(Map.of("endpoint", "http://127.0.0.1:" + port, "token", credential.token())) + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
            String ready = pool.submit(() -> output.readLine()).get(30, TimeUnit.SECONDS);
            assertNotNull(ready); assertTrue(ready.startsWith("{\"ready\""), ready.replace(credential.token(), "REDACTED"));
            var ids = json.readTree(ready).get("ready");
            assertEquals(before, assets.count(), "Receiving alone must not create assets");
            var principal = com.acme.assetmanagement.security.AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            try {
                var approval = json.writeValueAsBytes(Map.of("revision", 0, "categoryId", category.getId(), "name", "笔记本电脑", "model", "Intake HTTP model", "cpu", "Intake HTTP CPU"));
                intakeService.review(ids.get(0).asText(), approval, true);
                intakeService.review(ids.get(0).asText(), approval, true);
                intakeService.review(ids.get(1).asText(), json.writeValueAsBytes(Map.of("revision", 0, "note", "重复设备，暂不入库")), false);
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            input.write("reviewed\n".getBytes(StandardCharsets.UTF_8)); input.flush(); input.close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String remaining = output.lines().collect(java.util.stream.Collectors.joining("\n")).replace(credential.token(), "REDACTED");
            assertEquals(0, process.exitValue(), remaining); assertTrue(remaining.contains("automatic_intake_http_passed"), remaining);
            assertEquals(before + 1, assets.count());
        } finally { process.destroyForcibly(); }
    }

    @Test void actualPythonSenderReachesActualHttpReceiverAndReplaysLostResponseWithoutDuplicates() throws Exception {
        option(LookupType.COMPANY, "微丰启"); option(LookupType.LOCATION, "公司"); option(LookupType.STATUS, "当前可用");
        var category = option(LookupType.CATEGORY, "笔记本电脑");
        access.save(true, true);
        var credential = credentials.issue("isolated-python-http-test", Instant.now().plusSeconds(3600));
        long before = assets.count();
        String script = """
                import sys, json, time
                sys.path.insert(0, sys.argv[1])
                import test_pc_deploy as fixtures
                config = json.load(sys.stdin)
                case = fixtures.PlatformTests('runTest')
                case.setUp()
                context = case.app.app_context()
                context.push()
                try:
                    target = dict(endpoint=config['endpoint'], token=config['token'])
                    probe = case.post('/pc-deploy/api/asset-sync/probe', target)
                    assert probe.status_code == 200, probe.json
                    assert any(v['id'] == config['category'] for v in probe.json['categories'])
                    response = case.post('/pc-deploy/api/asset-sync/settings', dict(target,
                        enabled=True, revision=0, category_id=config['category']))
                    assert response.status_code == 200, response.json
                    machine = fixtures.machine()
                    machine['system_info'] = dict(cpus=[dict(name='Live HTTP Fixture CPU')], gpus=[])
                    sender = case.app.extensions['pc_deploy_asset_sender']
                    with case.store.connect(write=True) as c:
                        saved_config = sender.config(c)
                        case.store.put(c, 'asset_sync', dict(saved_config, enabled=False))
                    case.register(machine)
                    with case.store.connect(write=True) as c:
                        case.store.put(c, 'asset_sync', saved_config)
                    def save(version):
                        device = case.client.get('/pc-deploy/api/dashboard').json['devices'][0]
                        result = case.post('/pc-deploy/api/asset-registrations/' + device['id'], dict(
                            asset_tag='009876543210', version=version, inventory_revision=device['asset_inventory_revision']))
                        assert result.status_code == 200, result.json
                    sender = case.app.extensions['pc_deploy_asset_sender']
                    real_transport = sender.transport
                    calls = []
                    def lost_response(*args):
                        result = real_transport(*args)
                        calls.append(args[3])
                        if len(calls) == 1:
                            raise TimeoutError('simulated lost response after receiver committed')
                        return result
                    save(1)
                    sender.transport = lost_response
                    sender.process_one()
                    sender.process_one(now=time.time()+10)
                    assert calls[0] == calls[1]
                    row = case.client.get('/pc-deploy/api/dashboard').json['asset_registrations'][0]
                    assert row['sync']['state'] == 'synced', row['sync']
                    assert row['sync']['result']['replayed'] is True
                    sender.transport = real_transport
                    save(2); sender.process_one()
                    row = case.client.get('/pc-deploy/api/dashboard').json['asset_registrations'][0]
                    assert row['sync']['result']['result'] == 'already_exists', row['sync']
                    device = machine['hardware_uuid']
                    with case.store.connect(write=True) as c:
                        stored = json.loads(c.execute('SELECT data FROM devices WHERE id=?',(device,)).fetchone()[0])
                        stored['system_info']['cpus'] = [dict(name='Changed HTTP Fixture CPU')]
                        c.execute('UPDATE devices SET data=? WHERE id=?',(json.dumps(stored),device))
                    save(3); sender.process_one()
                    row = case.client.get('/pc-deploy/api/dashboard').json['asset_registrations'][0]
                    assert row['sync']['state'] == 'conflict', row['sync']
                    print(json.dumps(dict(assetId=row['sync']['result']['asset']['id'], result='http_interop_passed')))
                finally:
                    context.pop()
                    case.tearDown()
                """;
        var process = new ProcessBuilder(System.getProperty("ad.sender.python"), "-c", script,
                System.getProperty("ad.sender.repo")).redirectErrorStream(true).start();
        try {
            process.getOutputStream().write(json.writeValueAsBytes(Map.of("endpoint", "http://127.0.0.1:" + port,
                    "token", credential.token(), "category", category.getId())));
            process.getOutputStream().close();
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Python sender timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replace(credential.token(), "REDACTED");
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("http_interop_passed"), output);
            assertEquals(before + 1, assets.count());
            var asset = assets.findByAssetTagIgnoreCase("009876543210").orElseThrow();
            assertEquals("Live HTTP Fixture CPU", asset.getCpu());
            assertFalse(asset.isCheckedOut()); assertNull(asset.getAssignedTo());
            assertEquals(3, registrations.count());
        } finally { process.destroyForcibly(); }
    }
}
