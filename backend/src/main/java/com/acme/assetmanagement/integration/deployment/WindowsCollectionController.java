package com.acme.assetmanagement.integration.deployment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static com.acme.assetmanagement.integration.deployment.DeploymentRegistrationRequest.*;

@RestController
public class WindowsCollectionController {
    private final DeploymentCredentialService credentials;
    private final WindowsCollectionPackageRepository packages;
    private final DeploymentAccessService access;
    private final DeploymentIntakeService intakes;
    private final ObjectMapper json;
    public WindowsCollectionController(DeploymentCredentialService credentials, WindowsCollectionPackageRepository packages,
            DeploymentAccessService access, DeploymentIntakeService intakes, ObjectMapper json) {
        this.credentials=credentials; this.packages=packages; this.access=access; this.intakes=intakes; this.json=json;
    }
    @GetMapping("/api/settings/windows-collection/packages")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<DeploymentCredentialService.Info> list() { return credentials.list(true); }

    public record PackageRequest(String name, String baseUrl, Integer days) {}
    @PostMapping("/api/settings/windows-collection/packages")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<byte[]> download(@RequestBody PackageRequest value, HttpServletRequest request) throws IOException {
        if (value.name()==null || value.name().isBlank() || value.name().length()>80 || value.days()==null || value.days()<1 || value.days()>90)
            throw failure("包名称须为1至80字符，有效期须为1至90天");
        URI url;
        try { url=URI.create(value.baseUrl()); } catch (RuntimeException e) { throw failure("请填写资产系统完整地址"); }
        if (url.getHost()==null || !Set.of("http","https").contains(url.getScheme()) || url.getUserInfo()!=null
                || url.getQuery()!=null || url.getFragment()!=null || !Set.of("","/").contains(url.getPath()))
            throw failure("地址须为资产系统根地址，不含账号、路径、参数或片段");
        if (!access.isEnabled() || !access.allowsRequest(request.isSecure()) || !access.allowsRequest("https".equals(url.getScheme())))
            throw failure("请先在装机平台接入中启用登记；HTTP 须明确允许受信内网传输");
        var issued=credentials.issue(value.name(), Instant.now().plusSeconds(value.days()*86400L));
        var row=new WindowsCollectionPackage(); row.id=issued.credential().id(); packages.saveAndFlush(row);
        var output=new ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("Collect.ps1")); zip.write(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});
            try (var script = new ClassPathResource("windows-collection/Collect.ps1").getInputStream()) { zip.write(script.readAllBytes()); }
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("一键采集.cmd"));
            zip.write("@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File \"%~dp0Collect.ps1\"\r\npause\r\n".getBytes(StandardCharsets.US_ASCII)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("config.json"));
            zip.write(json.writeValueAsBytes(Map.of("endpoint", url.toString().replaceAll("/$", "")+DeploymentSecurityConfig.WINDOWS,
                    "token", issued.token()))); zip.closeEntry();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=windows-collection.zip")
                .contentType(MediaType.parseMediaType("application/zip")).body(output.toByteArray());
    }
    @PostMapping("/api/settings/windows-collection/packages/{id}/revoke")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public DeploymentCredentialService.Info revoke(@PathVariable String id) {
        if (!packages.existsById(id)) throw failure("采集包不存在");
        return credentials.revoke(id);
    }
    @PostMapping(value=DeploymentSecurityConfig.WINDOWS, consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeploymentIntakeService.Result> collect(@RequestAttribute(DeploymentSecurityConfig.BODY) byte[] body) {
        Map<?,?> input;
        try { input=object(json.readerFor(Object.class).with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readValue(body),"request"); }
        catch (RuntimeException e) { throw failure("采集资料须为有效 JSON 对象"); }
        keys(input,"request","hardwareUuid","name","capturedAt","snapshot");
        String hardware=text(input,"hardwareUuid",36,true);
        UUID uuid;
        try { uuid=UUID.fromString(hardware); } catch (RuntimeException e) { throw failure("硬件 UUID 无效"); }
        if (!uuid.toString().equalsIgnoreCase(hardware) || uuid.equals(new UUID(0,0)) || uuid.equals(new UUID(-1,-1)))
            throw failure("硬件 UUID 无效，不使用 SN 代替设备身份");
        var value=new LinkedHashMap<String,Object>();
        value.put("externalRegistrationId","windows:"+uuid); value.put("name",input.get("name"));
        value.put("capturedAt",input.get("capturedAt")); value.put("snapshot",input.get("snapshot"));
        value.put("operator",Map.of("id","windows-collector","displayName","Windows一键采集"));
        var result=intakes.receiveWindows(json.writeValueAsBytes(value));
        return ResponseEntity.status("pending_review".equals(result.result())?202:200).cacheControl(CacheControl.noStore()).body(result);
    }
    private DeploymentApiException failure(String message) {
        return new DeploymentApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION_FAILED",message);
    }
}
