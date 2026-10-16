package com.acme.assetmanagement.registration;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registrations")
public class RegistrationController {
    private final RegistrationService service;
    public RegistrationController(RegistrationService service) { this.service = service; }
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict() { return Map.of("message", "登记、SN或所选资产已有审核结果，请刷新核对，勿重复入库"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidParameter() { return Map.of("message", "登记请求参数格式错误"); }
    @GetMapping public RegistrationService.Page list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String state, @RequestParam(defaultValue = "false") boolean review) { return service.list(page, state, review); }
    @GetMapping("/{id}") public RegistrationService.Detail get(@PathVariable Long id) { return service.get(id); }
    @GetMapping("/photos/{id}") public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        var photo = service.photo(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(photo.mediaType())).body(photo.bytes());
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public RegistrationService.Detail submit(@Valid @RequestPart("data") RegistrationRequest.Submit request,
            @RequestPart(value = "photos", required = false) List<MultipartFile> photos) { return service.submit(request, photos); }
    @PostMapping(value = "/{id}/supplement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RegistrationService.Detail supplement(@PathVariable Long id, @Valid @RequestPart("data") RegistrationRequest.Submit request,
            @RequestPart(value = "photos", required = false) List<MultipartFile> photos) { return service.supplement(id, request, photos); }
    @PostMapping("/{id}/withdraw") public RegistrationService.Detail withdraw(@PathVariable Long id, @Valid @RequestBody RegistrationRequest.Withdraw request) { return service.withdraw(id, request); }
    @PostMapping("/{id}/review") public RegistrationService.Detail review(@PathVariable Long id, @Valid @RequestBody RegistrationRequest.Review request) { return service.review(id, request); }
    @PostMapping("/{id}/approve-create") public RegistrationService.Detail approveCreate(@PathVariable Long id, @Valid @RequestBody RegistrationRequest.ApproveCreate request) { return service.approveCreate(id, request); }
    @PostMapping("/{id}/approve-existing") public RegistrationService.Detail approveExisting(@PathVariable Long id, @Valid @RequestBody RegistrationRequest.ApproveExisting request) { return service.approveExisting(id, request); }
}
