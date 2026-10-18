package com.acme.assetmanagement.consumable;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/consumables")
public class ConsumableController {
    private final ConsumableService service;
    public ConsumableController(ConsumableService service) { this.service = service; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public ConsumableService.Page list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "false") boolean lowStock) { return service.list(page, query, lowStock); }
    @GetMapping("/{id}") public ConsumableService.Item get(@PathVariable Long id) { return service.get(id); }
    @GetMapping("/movements") public ConsumableService.MovementPage movements(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long consumableId, @RequestParam(defaultValue = "false") boolean mine) { return service.movements(page, consumableId, mine); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ConsumableService.Item create(@Valid @RequestBody ConsumableRequest.Create request) { return service.create(request); }
    @PostMapping("/{id}/settings") public ConsumableService.Item settings(@PathVariable Long id, @Valid @RequestBody ConsumableRequest.Settings request) { return service.settings(id, request); }
    @PostMapping("/{id}/movements") public ConsumableService.Result move(@PathVariable Long id, @Valid @RequestBody ConsumableRequest.Move request) { return service.move(id, request); }
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT) public Map<String, String> conflict() { return Map.of("message", "库存或请求已变化，请刷新后重试"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String, String> invalid() { return Map.of("message", "请求参数格式不正确"); }
}
