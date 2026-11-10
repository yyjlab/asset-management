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

    private LookupValue option(LookupType type, String name) {
        return lookups.findByTypeAndNameIgnoreCase(type, name)
                .orElseGet(() -> lookups.saveAndFlush(new LookupValue(type, name)));
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
                    case.register(machine)
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
                    save(0)
                    sender.transport = lost_response
                    sender.process_one()
                    sender.process_one(now=time.time()+10)
                    assert calls[0] == calls[1]
                    row = case.client.get('/pc-deploy/api/dashboard').json['asset_registrations'][0]
                    assert row['sync']['state'] == 'synced', row['sync']
                    assert row['sync']['result']['replayed'] is True
                    sender.transport = real_transport
                    save(1); sender.process_one()
                    row = case.client.get('/pc-deploy/api/dashboard').json['asset_registrations'][0]
                    assert row['sync']['result']['result'] == 'already_exists', row['sync']
                    device = machine['hardware_uuid']
                    with case.store.connect(write=True) as c:
                        stored = json.loads(c.execute('SELECT data FROM devices WHERE id=?',(device,)).fetchone()[0])
                        stored['system_info']['cpus'] = [dict(name='Changed HTTP Fixture CPU')]
                        c.execute('UPDATE devices SET data=? WHERE id=?',(json.dumps(stored),device))
                    save(2); sender.process_one()
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
