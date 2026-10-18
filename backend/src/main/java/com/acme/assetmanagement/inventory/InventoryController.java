package com.acme.assetmanagement.inventory;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventories")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public InventoryService.Page list(@RequestParam(defaultValue = "0") int page) { return service.list(page); }
    @GetMapping("/{id}") public InventoryService.Detail get(@PathVariable Long id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public InventoryService.Detail create(@Valid @RequestBody InventoryService.Create body) { return service.create(body); }
    @PostMapping("/{id}/actions") public InventoryService.Detail action(@PathVariable Long id, @Valid @RequestBody InventoryService.Action body) { return service.action(id, body); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT) public java.util.Map<String, String> conflict() { return java.util.Map.of("message", "盘点请求重复，请刷新重试"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) public java.util.Map<String, String> invalid() { return java.util.Map.of("message", "请求参数格式不正确"); }
}
