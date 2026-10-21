package com.acme.assetmanagement.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dingtalk-oa")
public class DingTalkOaController {
    private final DingTalkOaService service;
    public DingTalkOaController(DingTalkOaService service) { this.service=service; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping("/status") public DingTalkOaService.Status status() { return service.status(); }
    @GetMapping("/candidates") public java.util.List<DingTalkOaService.Candidate> candidates(@RequestParam String kind) { return service.candidates(kind); }
    @GetMapping public DingTalkOaService.Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="true") boolean mine) { return service.list(page,mine); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public DingTalkOaService.Item create(@Valid @RequestBody DingTalkOaService.Create body) { return service.create(body); }
    @PostMapping("/{id}/sync") public DingTalkOaService.Item sync(@PathVariable Long id) { return service.sync(id); }
    public record Attach(@NotBlank @Size(max=160) String instanceId) {}
    @PostMapping("/{id}/attach") public DingTalkOaService.Item attach(@PathVariable Long id,@Valid @RequestBody Attach body) { return service.attach(id,body.instanceId()); }
    public record Close(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String note,@AssertTrue boolean confirmed) {}
    @PostMapping("/{id}/close-exception") public DingTalkOaService.Item close(@PathVariable Long id,@Valid @RequestBody Close body) { return service.closeException(id,body.version(),body.note()); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public java.util.Map<String,String> conflict() { return java.util.Map.of("message","设备已有办理中的工单或审批实例已关联，请刷新后重试"); }
}
