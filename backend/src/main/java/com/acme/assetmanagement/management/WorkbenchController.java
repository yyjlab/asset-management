package com.acme.assetmanagement.management;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management")
public class WorkbenchController {
    private final WorkbenchService workbench;
    private final AssetHistoryService history;
    private final ManagementReportService reports;
    public WorkbenchController(WorkbenchService workbench, AssetHistoryService history, ManagementReportService reports) { this.workbench = workbench; this.history = history; this.reports = reports; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/workbench") public WorkbenchService.Overview workbench() { return workbench.get(); }
    @GetMapping("/history/{assetId}") public AssetHistoryService.History history(@PathVariable Long assetId) { return history.get(assetId); }
    @GetMapping("/reports") public ManagementReportService.Overview reports() { return reports.get(); }
}
