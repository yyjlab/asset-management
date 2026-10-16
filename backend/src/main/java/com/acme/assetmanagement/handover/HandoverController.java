package com.acme.assetmanagement.handover;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/handovers")
public class HandoverController {
    private final HandoverService service;
    public HandoverController(HandoverService service) { this.service = service; }
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicateRequest() { return Map.of("message", "交接请求已提交或发生冲突，请刷新列表核对，勿重复登记"); }
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> concurrentUpdate() { return Map.of("message", "交接单已被其他人更新，请刷新后重试"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidParameter() { return Map.of("message", "交接请求参数格式错误"); }
    @GetMapping public HandoverService.Page list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String kind, @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "false") boolean mine) {
        return service.list(page, kind, state, mine);
    }
    @GetMapping("/employees") public List<HandoverService.Employee> employees() { return service.employees(); }
    @GetMapping("/{id}") public HandoverService.Detail get(@PathVariable Long id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public HandoverService.Detail create(@Valid @RequestBody HandoverRequest.Create request) { return service.create(request); }
    @PostMapping("/{id}/items/{index}/check")
    public HandoverService.Detail check(@PathVariable Long id, @PathVariable int index, @Valid @RequestBody HandoverRequest.Check request) {
        return service.check(id, index, request);
    }
    @PostMapping("/{id}/items/{index}/confirm")
    public HandoverService.Detail confirm(@PathVariable Long id, @PathVariable int index, @Valid @RequestBody HandoverRequest.Confirm request) {
        return service.confirm(id, index, request);
    }
    @PostMapping("/{id}/complete")
    public HandoverService.Detail complete(@PathVariable Long id, @Valid @RequestBody HandoverRequest.Action request) { return service.complete(id, request); }
    @PostMapping("/{id}/cancel")
    public HandoverService.Detail cancel(@PathVariable Long id, @Valid @RequestBody HandoverRequest.Action request) { return service.cancel(id, request); }
    @PostMapping("/{id}/employee")
    public HandoverService.Detail linkEmployee(@PathVariable Long id, @Valid @RequestBody HandoverRequest.LinkEmployee request) { return service.linkEmployee(id, request); }
}
