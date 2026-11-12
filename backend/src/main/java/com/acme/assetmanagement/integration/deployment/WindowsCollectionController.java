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
    private final com.acme.assetmanagement.asset.RegistrationRosterRepository roster;
    private final NativeCollectionPackage nativePackages;
    public WindowsCollectionController(DeploymentCredentialService credentials, WindowsCollectionPackageRepository packages,
            DeploymentAccessService access, DeploymentIntakeService intakes, ObjectMapper json, com.acme.assetmanagement.asset.RegistrationRosterRepository roster, NativeCollectionPackage nativePackages) {
        this.credentials=credentials; this.packages=packages; this.access=access; this.intakes=intakes; this.json=json; this.roster=roster;this.nativePackages=nativePackages;
    }
    @GetMapping("/api/settings/windows-collection/packages")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<DeploymentCredentialService.Info> list() { return credentials.list(true); }

    @GetMapping("/api/settings/windows-collection/formats")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String,Boolean> formats(){return nativePackages.formats();}

    public record PackageRequest(String name, String baseUrl, Integer days, String platform, String format) {}
    @PostMapping("/api/settings/windows-collection/packages")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<byte[]> download(@RequestBody PackageRequest value, HttpServletRequest request) throws IOException {
        if (value.name()==null || value.name().isBlank() || value.name().length()>80 || value.days()==null || value.days()<1 || value.days()>90)
            throw failure("包名称须为1至80字符，有效期须为1至90天");
        String platform=value.platform()==null ? "windows" : value.platform();
        if(!Set.of("windows","macos").contains(platform))throw failure("请选择 Windows 或 macOS");
        String format=value.format()==null?"zip":value.format();
        if(!Set.of("zip","native").contains(format))throw failure("请选择免安装文件或脚本包");
        if(format.equals("native")&&!nativePackages.formats().get(platform))throw failure("该平台的免安装文件尚未构建，请先完成构建，不会生成无效下载");
        URI url;
        try { url=URI.create(value.baseUrl()); } catch (RuntimeException e) { throw failure("请填写资产系统完整地址"); }
        if (url.getHost()==null || !Set.of("http","https").contains(url.getScheme()) || url.getUserInfo()!=null
                || url.getQuery()!=null || url.getFragment()!=null || !Set.of("","/").contains(url.getPath()))
            throw failure("地址须为资产系统根地址，不含账号、路径、参数或片段");
        if (!access.isEnabled() || !access.allowsRequest(request.isSecure()) || !access.allowsRequest("https".equals(url.getScheme())))
            throw failure("请先在装机平台接入中启用登记；HTTP 须明确允许受信内网传输");
        var issued=credentials.issue(value.name(), Instant.now().plusSeconds(value.days()*86400L));
        var row=new WindowsCollectionPackage(); row.id=issued.credential().id(); packages.saveAndFlush(row);
        String configuration=json.writeValueAsString(Map.of("endpoint",url.toString().replaceAll("/$", "")+DeploymentSecurityConfig.WINDOWS,"token",issued.token()));
        if(format.equals("native")){
            boolean windows=platform.equals("windows");
            byte[] file=windows?nativePackages.windows(configuration):nativePackages.macos(configuration);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+platform+"-collection."+(windows?"exe":"dmg"))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM).body(file);
        }
        var output=new ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            if(platform.equals("macos")) {
                zip.putNextEntry(new ZipEntry("Collect.js"));
                try(var script=new ClassPathResource("windows-collection/CollectMac.js").getInputStream()){zip.write(script.readAllBytes());} zip.closeEntry();
                zip.putNextEntry(new ZipEntry("一键采集.command"));
                zip.write("#!/bin/bash\numask 077\ncd -- \"$(dirname -- \"$0\")\" || exit 1\n/usr/bin/osascript -l JavaScript Collect.js\nstatus=$?\nprintf '\\nPress Enter to close...'\nread -r _\nexit \"$status\"\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("使用说明.txt"));
                zip.write("完整解压后，在终端输入 bash 和空格，再把“一键采集.command”拖进终端并回车。无需 sudo，不安装常驻程序。填写实际使用人姓名后自动匹配部门，可修改或手填，确认后提交等待审核。取消不发送。系统安全限制可能阻止直接双击，勿关闭系统安全保护。采集包含专用凭据，请勿公开。\n".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            } else {
            zip.putNextEntry(new ZipEntry("Collect.ps1")); zip.write(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});
            try (var script = new ClassPathResource("windows-collection/Collect.ps1").getInputStream()) { zip.write(script.readAllBytes()); }
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("一键采集.cmd"));
            zip.write("@echo off\r\nstart \"\" \"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%~dp0Collect.ps1\"\r\n".getBytes(StandardCharsets.US_ASCII)); zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("config.json"));
            zip.write(json.writeValueAsBytes(Map.of("endpoint", url.toString().replaceAll("/$", "")+DeploymentSecurityConfig.WINDOWS,
                    "token", issued.token()))); zip.closeEntry();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+platform+"-collection.zip")
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
        keys(input,"request","hardwareUuid","name","capturedAt","snapshot","reportedUserName","reportedDepartment","collection");
        var details=CollectionDetails.parse(input.get("collection"));
        boolean mac=details!=null && details.platform().equals("macos");
        String reportedUserName=text(input,"reportedUserName",120,false);
        String reportedDepartment=text(input,"reportedDepartment",120,false);
        String hardware=text(input,"hardwareUuid",36,true);
        UUID uuid;
        try { uuid=UUID.fromString(hardware); } catch (RuntimeException e) { throw failure("硬件 UUID 无效"); }
        if (!uuid.toString().equalsIgnoreCase(hardware) || uuid.equals(new UUID(0,0)) || uuid.equals(new UUID(-1,-1)))
            throw failure("硬件 UUID 无效，不使用 SN 代替设备身份");
        var value=new LinkedHashMap<String,Object>();
        value.put("externalRegistrationId",(mac ? "macos:" : "windows:")+uuid); value.put("name",input.get("name"));
        value.put("capturedAt",input.get("capturedAt")); value.put("snapshot",input.get("snapshot"));
        value.put("operator",Map.of("id",mac ? "macos-collector" : "windows-collector","displayName",mac ? "macOS一键采集" : "Windows一键采集"));
        var result=intakes.receiveWindows(json.writeValueAsBytes(value), reportedUserName, reportedDepartment, details);
        return ResponseEntity.status("pending_review".equals(result.result())?202:200).cacheControl(CacheControl.noStore()).body(result);
    }
    public record DepartmentMatch(String result, String department) {}
    @PostMapping(value=DeploymentSecurityConfig.PERSON, consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DepartmentMatch> department(@RequestAttribute(DeploymentSecurityConfig.BODY) byte[] body) {
        Map<?,?> input;
        try {input=object(json.readerFor(Object.class).with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readValue(body),"request");}
        catch(RuntimeException e){throw failure("须提交有效姓名 JSON");}
        keys(input,"request","name");
        String name=text(input,"name",120,true).strip();
        var department=roster.departmentByNameHash(DeploymentCredentialService.hash(name));
        var result=department.isEmpty() ? new DepartmentMatch("not_found",null)
                : department.get().isBlank() ? new DepartmentMatch("empty_department",null) : new DepartmentMatch("matched",department.get());
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=UTF-8"))
                .cacheControl(CacheControl.noStore()).body(result);
    }
    private DeploymentApiException failure(String message) {
        return new DeploymentApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION_FAILED",message);
    }
}
