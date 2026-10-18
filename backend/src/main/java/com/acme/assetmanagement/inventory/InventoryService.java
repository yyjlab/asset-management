package com.acme.assetmanagement.inventory;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.operations.OperationService;
import com.acme.assetmanagement.user.UserAccount;
import com.acme.assetmanagement.workflow.*;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class InventoryService {
    private final InventoryRepository batches;
    private final AssetRepository assets;
    private final WorkflowAccess access;
    private final TaskNotifications notifications;
    public InventoryService(InventoryRepository batches, AssetRepository assets, WorkflowAccess access, TaskNotifications notifications) {
        this.batches = batches; this.assets = assets; this.access = access; this.notifications = notifications;
    }
    public record Create(@NotBlank @Size(max = 36) String requestKey, @NotBlank @Size(max = 160) String title,
            @NotNull @Size(max = 120) String department, @NotNull @FutureOrPresent LocalDate dueDate) {}
    public record Action(@NotNull @Min(0) Long version, @NotBlank @Pattern(regexp = "CONFIRM|RESOLVE|CLOSE|CANCEL") String action,
            @Min(0) Integer index, @Pattern(regexp = "PRESENT|MISSING|MISMATCH") String result, @Size(max = 1000) String note) {}
    public record Summary(Long id, long version, String title, String department, LocalDate dueDate, String state, int total, long confirmed, long resolved) {}
    public record Page(List<Summary> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Item(int index, Long assetId, long assetVersion, String assetTag, String name, Long ownerId, String ownerName,
            String result, String employeeNote, boolean resolved, String resolution, boolean ledgerChanged) {}
    public record Event(LocalDateTime occurredAt, String actor, String description) {}
    public record Detail(Summary batch, List<Item> items, List<Event> events, boolean canManage) {}
    private UserAccount manager() { var actor = access.current(); if (!OperationService.canManage(actor)) throw fail(HttpStatus.FORBIDDEN, "盘点管理需要全部资产范围及查看、编辑、分配、归还权限"); return actor; }
    public Page list(int page) {
        var actor = access.current(); boolean manage = OperationService.canManage(actor);
        Specification<InventoryBatch> spec = (r, q, cb) -> {
            if (manage) return cb.conjunction();
            var sub = q.subquery(Long.class); var batch = sub.from(InventoryBatch.class); var item = batch.join("items");
            sub.select(batch.get("id")).where(cb.equal(item.get("ownerId"), actor.getId()));
            return r.get("id").in(sub);
        };
        var result = batches.findAll(spec, PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")));
        return new Page(result.stream().map(b -> summary(b, actor, manage)).toList(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), manage);
    }
    public Detail get(Long id) { return detail(visible(id, false)); }
    @Transactional public Detail create(Create request) {
        var actor = manager();
        try { UUID.fromString(request.requestKey()); } catch (IllegalArgumentException e) { throw fail(HttpStatus.BAD_REQUEST, "请求标识无效"); }
        var replay = batches.findByRequestKey(request.requestKey()); if (replay.isPresent()) return detail(replay.get());
        String department = request.department().trim();
        var selected = department.isEmpty() ? assets.findTop501ByOrderByAssetTagAsc() : assets.findTop501ByOwnershipDepartmentOrderByAssetTagAsc(department);
        if (selected.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "此部门暂无资产，请核对部门名称");
        if (selected.size() > 500) throw fail(HttpStatus.BAD_REQUEST, "单次盘点最多500项，请按部门分批；不会静默遗漏设备");
        var b = new InventoryBatch(); b.requestKey = request.requestKey(); b.title = request.title().trim(); b.department = department; b.dueDate = request.dueDate();
        for (var a : selected) {
            var i = new InventoryBatch.Item(); i.assetId = a.getId(); i.assetVersion = a.getVersion(); i.assetTag = a.getAssetTag(); i.name = a.getName();
            i.ownerId = a.getAssignedUserId(); i.ownerName = a.getAssignedTo(); b.items.add(i);
        }
        event(b, actor, "发起部门盘点，仅保存快照，不修改正式台账"); return save(b);
    }
    @Transactional public Detail action(Long id, Action request) {
        var actor = access.current(); var b = visible(id, true);
        if (b.version != request.version()) throw fail(HttpStatus.CONFLICT, "盘点记录已更新，请刷新后重试");
        if (!"OPEN".equals(b.state)) throw fail(HttpStatus.CONFLICT, "盘点已关闭，不再接受修改");
        String note = request.note() == null ? "" : request.note().trim();
        if (Set.of("CLOSE", "CANCEL").contains(request.action())) {
            manager(); if (note.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "请填写盘点总结或取消原因");
            if ("CLOSE".equals(request.action()) && b.items.stream().anyMatch(i -> !i.resolved)) throw fail(HttpStatus.CONFLICT, "请先处理所有未确认项及差异");
            b.state = "CLOSE".equals(request.action()) ? "COMPLETED" : "CANCELLED";
            event(b, actor, ("CLOSE".equals(request.action()) ? "完成盘点：" : "取消盘点：") + note); return save(b);
        }
        if (request.index() == null || request.index() >= b.items.size()) throw fail(HttpStatus.BAD_REQUEST, "盘点项无效");
        var i = b.items.get(request.index());
        if (i.resolved) throw fail(HttpStatus.CONFLICT, "已核实的盘点项不可覆盖");
        if ("CONFIRM".equals(request.action())) {
            if (!Objects.equals(i.ownerId, actor.getId())) throw fail(HttpStatus.FORBIDDEN, "只能确认本人盘点项，管理员不能代签");
            if (i.result != null) throw fail(HttpStatus.CONFLICT, "已提交盘点结果，修改请联系管理员记录处理");
            if (request.result() == null) throw fail(HttpStatus.BAD_REQUEST, "请选择在手、缺失或信息不符");
            if (!"PRESENT".equals(request.result()) && note.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "差异必须填写说明");
            i.result = request.result(); i.employeeNote = note; i.confirmedAt = LocalDateTime.now();
            String resultLabel = switch (i.result) { case "PRESENT" -> "设备在手"; case "MISSING" -> "设备缺失"; default -> "信息不符"; };
            event(b, actor, "本人确认 " + i.assetTag + "：" + resultLabel);
        } else {
            manager(); if (note.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "请填写实物核实及差异处理说明");
            i.resolved = true; i.resolution = note;
            event(b, actor, "核实 " + i.assetTag + "：" + note + "；台账未自动改动");
        }
        return save(b);
    }
    private InventoryBatch visible(Long id, boolean lock) {
        var actor = access.current(); var b = (lock ? batches.locked(id) : batches.findById(id)).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "盘点不存在"));
        if (!OperationService.canManage(actor) && b.items.stream().noneMatch(i -> Objects.equals(i.ownerId, actor.getId()))) throw fail(HttpStatus.NOT_FOUND, "盘点不存在");
        return b;
    }
    private List<InventoryBatch.Item> scoped(InventoryBatch b, UserAccount actor, boolean manage) { return b.items.stream().filter(i -> manage || Objects.equals(i.ownerId, actor.getId())).toList(); }
    private Summary summary(InventoryBatch b, UserAccount actor, boolean manage) {
        var items = scoped(b, actor, manage); return new Summary(b.id, b.version, b.title, b.department, b.dueDate, b.state, items.size(), items.stream().filter(i -> i.result != null).count(), items.stream().filter(i -> i.resolved).count());
    }
    private Detail detail(InventoryBatch b) {
        var actor = access.current(); boolean manage = OperationService.canManage(actor); var items = new ArrayList<Item>();
        var ids = scoped(b, actor, manage).stream().map(i -> i.assetId).toList();
        var current = new HashMap<Long, Long>(); assets.findAllById(ids).forEach(a -> current.put(a.getId(), a.getVersion()));
        for (int index = 0; index < b.items.size(); index++) { var i = b.items.get(index); if (manage || Objects.equals(i.ownerId, actor.getId()))
            items.add(new Item(index, i.assetId, i.assetVersion, i.assetTag, i.name, i.ownerId, i.ownerName, i.result, i.employeeNote, i.resolved, i.resolution, !Objects.equals(current.get(i.assetId), i.assetVersion))); }
        // Batch event history includes other employees; never expose it to ordinary employees.
        return new Detail(summary(b, actor, manage), items, manage ? b.events.stream().map(e -> new Event(e.occurredAt, e.actor, e.description)).toList() : List.of(), manage);
    }
    private void event(InventoryBatch b, UserAccount actor, String text) { b.events.add(new InventoryBatch.Event(actor.getDisplayName(), text)); b.updatedAt = LocalDateTime.now(); }
    private Detail save(InventoryBatch b) {
        batches.saveAndFlush(b);
        String stateLabel = switch (b.state) { case "OPEN" -> "盘点进行中"; case "COMPLETED" -> "已完成"; default -> "已取消"; };
        notifications.enqueue("inventory:" + b.id + ":" + b.version, "INVENTORY", "资产盘点进度", "盘点 #" + b.id + "\n状态：" + stateLabel + "\n请进入资产系统查看本人盘点项或处理差异。");
        return detail(b);
    }
    private static ApiException fail(HttpStatus status, String message) { return new ApiException(status, message); }
}
