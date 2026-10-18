package com.acme.assetmanagement.procurement;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/procurements")
public class ProcurementController {
    private final ProcurementService service;
    public ProcurementController(ProcurementService service) { this.service = service; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public ProcurementService.Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "false") boolean mine) { return service.list(page, mine); }
    @GetMapping("/{id}") public ProcurementService.Detail get(@PathVariable Long id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ProcurementService.Detail create(@Valid @RequestBody ProcurementRequest.Create body) { return service.create(body); }
    @PostMapping("/{id}/actions") public ProcurementService.Detail action(@PathVariable Long id, @Valid @RequestBody ProcurementRequest.Action body) { return service.action(id, body); }
}
