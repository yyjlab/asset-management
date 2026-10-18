package com.acme.assetmanagement.procurement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.consumable.*;
import com.acme.assetmanagement.management.*;
import com.acme.assetmanagement.operations.OperationService;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.TaskNotifications;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service @Transactional(readOnly = true)
public class ProcurementService {
    private final ProcurementRepository orders;
    private final ManagementAccess access;
    private final UserRepository users;
    private final AssetRepository assets;
    private final ConsumableService consumables;
    private final AssetSupportService support;
    private final TaskNotifications notifications;
    public ProcurementService(ProcurementRepository orders, ManagementAccess access, UserRepository users, AssetRepository assets,
            ConsumableService consumables, AssetSupportService support, TaskNotifications notifications) {
        this.orders = orders; this.access = access; this.users = users; this.assets = assets; this.consumables = consumables; this.support = support; this.notifications = notifications;
    }
    public record Summary(Long id, long version, Long ownerId, String ownerName, String kind, String state, String title,
            int quantity, String unit, BigDecimal estimatedUnitPrice, LocalDate neededDate, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Page(List<Summary> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Event(LocalDateTime occurredAt, String actor, String description) {}
    public record Detail(Summary order, String reason, Long supplierId, String supplierName, String orderNumber, LocalDate expectedDate,
            BigDecimal orderedUnitPrice, Long consumableId, Long receiptMovementId, Set<Long> assetIds, List<Event> events) {}
    public Page list(int page, boolean mine) {
        var actor = access.current(); boolean manage = OperationService.canManage(actor);
        Specification<ProcurementOrder> spec = (r, q, cb) -> manage && !mine ? cb.conjunction() : cb.equal(r.get("ownerId"), actor.getId());
        var result = orders.findAll(spec, PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")));
        return new Page(result.stream().map(this::summary).toList(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), manage);
    }
    public Detail get(Long id) { return detail(visible(id, false)); }
    @Transactional public Detail create(ProcurementRequest.Create request) {
        var actor = access.current(); String key = ManagementAccess.key(request.requestKey()); users.findForWorkflowUpdate(actor.getId()).orElseThrow();
        var replay = orders.findByRequestKey(key);
        if (replay.isPresent()) { if (!replay.get().ownerId.equals(actor.getId())) throw fail(HttpStatus.NOT_FOUND, "采购申请不存在"); return detail(replay.get()); }
        if (orders.countByOwnerIdAndCreatedAtAfter(actor.getId(), LocalDateTime.now().minusDays(1)) >= 20) throw fail(HttpStatus.TOO_MANY_REQUESTS, "今日采购申请较多，请联系管理员");
        int quantity = request.quantity().intValueExact(); if ("ASSET".equals(request.kind()) && quantity > 100) throw fail(HttpStatus.BAD_REQUEST, "设备采购单次最多100台，请分批申请");
        var o = new ProcurementOrder(); o.requestKey = key; o.ownerId = actor.getId(); o.ownerName = actor.getDisplayName(); o.kind = request.kind();
        o.title = request.title().trim(); o.reason = request.reason().trim(); o.quantity = quantity; o.unit = request.unit().trim(); o.estimatedUnitPrice = request.estimatedUnitPrice(); o.neededDate = request.neededDate();
        event(o, actor, "提交采购申请；未扣款、未改变资产或耗材库存"); return save(o);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail action(Long id, ProcurementRequest.Action request) {
        var actor = access.current(); var o = visible(id, true);
        if (o.version != request.version()) throw fail(HttpStatus.CONFLICT, "采购单已变化，请刷新后重试");
        if (Set.of("RECEIVED", "REJECTED", "CANCELLED").contains(o.state)) throw fail(HttpStatus.CONFLICT, "已关闭采购单不能再修改");
        String label;
        switch (request.action()) {
            case "CANCEL" -> { if (!o.ownerId.equals(actor.getId()) || !"SUBMITTED".equals(o.state)) access.manager(); o.state = "CANCELLED"; label = "取消申请，历史保留"; }
            case "REJECT" -> { access.manager(); state(o, "SUBMITTED"); o.state = "REJECTED"; label = "驳回申请"; }
            case "APPROVE" -> { access.manager(); state(o, "SUBMITTED"); o.state = "APPROVED"; label = "内部需求审核通过（不代替财务审批）"; }
            case "ORDER" -> {
                access.manager(); state(o, "APPROVED");
                if (request.supplierId() == null || ManagementAccess.clean(request.orderNumber()).isEmpty() || request.expectedDate() == null || request.orderedUnitPrice() == null)
                    throw fail(HttpStatus.BAD_REQUEST, "请填写供应商、订单号、预计到货日期和确认单价");
                var supplier = support.supplier(request.supplierId()); if (!supplier.active()) throw fail(HttpStatus.CONFLICT, "供应商已停用");
                o.supplierId = supplier.id(); o.supplierName = supplier.name(); o.orderNumber = request.orderNumber().trim(); o.expectedDate = request.expectedDate(); o.orderedUnitPrice = request.orderedUnitPrice();
                o.state = "ORDERED"; label = "登记已下单";
            }
            case "RECEIVE" -> {
                access.manager(); state(o, "ORDERED"); if (!Boolean.TRUE.equals(request.confirmed())) throw fail(HttpStatus.BAD_REQUEST, "请明确确认整单数量、实物与入库目标已核对");
                if ("CONSUMABLE".equals(o.kind)) {
                    if (request.consumableId() == null || request.consumableVersion() == null) throw fail(HttpStatus.BAD_REQUEST, "请选择耗材目录并提供当前库存版本");
                    var item = consumables.get(request.consumableId()); if (!item.unit().equals(o.unit)) throw fail(HttpStatus.CONFLICT, "采购单位与耗材单位不同，不能直接入库");
                    var receiptKey = UUID.nameUUIDFromBytes((o.requestKey + ":receipt").getBytes(StandardCharsets.UTF_8)).toString();
                    String stockNote = "采购单 #" + o.id + " 整单到货验收；" + request.note();
                    var receipt = consumables.move(item.id(), new ConsumableRequest.Move(receiptKey, request.consumableVersion(), "IN", BigDecimal.valueOf(o.quantity), "", stockNote.substring(0, Math.min(1000, stockNote.length()))));
                    o.consumableId = item.id(); o.receiptMovementId = receipt.movement().id();
                } else {
                    var ids = request.assetIds() == null ? List.<Long>of() : request.assetIds(); var distinct = new TreeSet<>(ids);
                    if (distinct.size() != o.quantity || distinct.size() != ids.size()) throw fail(HttpStatus.BAD_REQUEST, "设备入库关联数量必须与整单数量一致，不能重复编号");
                    for (Long assetId : distinct) {
                        var a = assets.findForRegistrationLink(assetId).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "关联资产不存在"));
                        if (a.getCreatedAt().isBefore(o.createdAt) || a.isCheckedOut() || a.getAssignedUserId() != null || !ManagementAccess.clean(a.getAssignedTo()).isEmpty()
                                || !Set.of("当前可用", "可领用").contains(a.getStatus().getName()))
                            throw fail(HttpStatus.CONFLICT, "只能关联申请后新建且尚未领用的设备，不能拿历史资产充当到货");
                    }
                    o.receivedAssetIds.addAll(distinct);
                }
                o.state = "RECEIVED"; label = "整单到货验收完成，已记录入库关联";
            }
            default -> throw fail(HttpStatus.BAD_REQUEST, "操作无效");
        }
        event(o, actor, label + "：" + request.note().trim()); return save(o);
    }
    private ProcurementOrder visible(Long id, boolean lock) {
        var actor = access.current(); var o = (lock ? orders.locked(id) : orders.findById(id)).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "采购申请不存在"));
        if (!o.ownerId.equals(actor.getId()) && !OperationService.canManage(actor)) throw fail(HttpStatus.NOT_FOUND, "采购申请不存在"); return o;
    }
    private void state(ProcurementOrder o, String state) { if (!state.equals(o.state)) throw fail(HttpStatus.CONFLICT, "当前采购阶段不允许此操作"); }
    private void event(ProcurementOrder o, UserAccount actor, String description) { o.events.add(new ProcurementOrder.Event(actor.getDisplayName(), description)); o.updatedAt = LocalDateTime.now(); }
    private Detail save(ProcurementOrder o) {
        orders.saveAndFlush(o); notifications.enqueue("procurement:" + o.id + ":" + o.version, "PROCUREMENT", "采购申请进度", "采购单 #" + o.id + "\n请进入资产系统采购补货查看当前进度；通知不包含金额或审批说明。"); return detail(o);
    }
    private Summary summary(ProcurementOrder o) { return new Summary(o.id, o.version, o.ownerId, o.ownerName, o.kind, o.state, o.title, o.quantity, o.unit, o.estimatedUnitPrice, o.neededDate, o.createdAt, o.updatedAt); }
    private Detail detail(ProcurementOrder o) { return new Detail(summary(o), o.reason, o.supplierId, o.supplierName, o.orderNumber, o.expectedDate, o.orderedUnitPrice, o.consumableId, o.receiptMovementId, Set.copyOf(o.receivedAssetIds), o.events.stream().map(e -> new Event(e.occurredAt, e.actor, e.description)).toList()); }
    private static ApiException fail(HttpStatus status, String message) { return new ApiException(status, message); }
}
