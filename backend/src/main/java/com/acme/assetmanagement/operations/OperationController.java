package com.acme.assetmanagement.operations;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import com.acme.assetmanagement.common.ApiException;

@RestController
@RequestMapping("/api/operations")
public class OperationController {
    private final OperationService service;
    public OperationController(OperationService service) { this.service = service; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public OperationService.Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String kind, @RequestParam(defaultValue = "false") boolean mine) { return service.list(page, kind, mine); }
    @GetMapping("/{id}") public OperationService.Detail get(@PathVariable Long id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public OperationService.Detail create(@Valid @RequestBody OperationRequest.Create body) { return service.create(body); }
    @PostMapping("/{id}/actions") public OperationService.Detail action(@PathVariable Long id, @Valid @RequestBody OperationRequest.Action body) { return service.action(id, body); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT) public java.util.Map<String, String> conflict() { return java.util.Map.of("message", "申请重复或设备已有已受理工单，请刷新后重试"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) public java.util.Map<String, String> invalid() { return java.util.Map.of("message", "请求参数格式不正确"); }
}
