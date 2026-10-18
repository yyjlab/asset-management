package com.acme.assetmanagement.consumable;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.operations.OperationService;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class ConsumableService {
    private static final long MAX_STOCK = 1_000_000_000L;
    private final ConsumableItemRepository items;
    private final ConsumableMovementRepository movements;
    private final ConsumableSettingChangeRepository changes;
    private final WorkflowAccess access;
    private final UserRepository users;
    private final TaskNotifications notifications;
    public ConsumableService(ConsumableItemRepository items, ConsumableMovementRepository movements,
            ConsumableSettingChangeRepository changes, WorkflowAccess access, UserRepository users, TaskNotifications notifications) {
        this.items = items; this.movements = movements; this.changes = changes;
        this.access = access; this.users = users; this.notifications = notifications;
    }
    public record Item(Long id, long version, String name, String unit, long quantity, long lowStockThreshold,
            boolean lowStock, String notes, LocalDateTime updatedAt) {}
    public record Page(List<Item> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Movement(Long id, Long consumableId, String consumableName, String unit, String kind, long quantity,
            long balanceAfter, Long recipientId, String recipientName, String actorName, String note, LocalDateTime createdAt) {}
    public record MovementPage(List<Movement> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Result(Item item, Movement movement) {}

    public Page list(int page, String query, boolean lowStock) {
        var actor = access.current(); String search = clean(query);
        if (search.length() > 120) throw fail(HttpStatus.BAD_REQUEST, "搜索内容最多120字");
        Specification<ConsumableItem> spec = (r, q, cb) -> cb.conjunction();
        if (!search.isEmpty()) {
            String escaped = search.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            spec = spec.and((r, q, cb) -> cb.like(cb.lower(r.get("name")), "%" + escaped + "%", '\\'));
        }
        if (lowStock) spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("quantity"), r.get("lowStockThreshold")));
        var result = items.findAll(spec, pageRequest(page));
        return new Page(result.map(this::item).getContent(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), OperationService.canManage(actor));
    }
    public Item get(Long id) { access.current(); return item(find(id, false)); }
    public MovementPage movements(int page, Long consumableId, boolean mine) {
        var actor = access.current(); boolean manage = OperationService.canManage(actor);
        if (consumableId != null && consumableId <= 0) throw fail(HttpStatus.BAD_REQUEST, "耗材编号无效");
        Specification<ConsumableMovement> spec = (r, q, cb) -> manage && !mine ? cb.conjunction()
                : cb.and(cb.equal(r.get("recipientId"), actor.getId()), cb.equal(r.get("kind"), "OUT"));
        if (consumableId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("consumableId"), consumableId));
        var result = movements.findAll(spec, pageRequest(page));
        return new MovementPage(result.map(this::movement).getContent(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), manage);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Item create(ConsumableRequest.Create request) {
        var actor = manager(); String key = key(request.requestKey());
        users.findForWorkflowUpdate(actor.getId()).orElseThrow();
        String hash = hash(clean(request.name()), clean(request.unit()), request.lowStockThreshold(), clean(request.notes()));
        var replay = items.findByRequestKey(key);
        if (replay.isPresent()) { replay(replay.get().creatorId, actor.getId(), replay.get().requestHash, hash); return item(replay.get()); }
        var entity = new ConsumableItem(); entity.requestKey = key; entity.creatorId = actor.getId(); entity.requestHash = hash;
        entity.name = clean(request.name()); entity.unit = clean(request.unit()); entity.notes = clean(request.notes());
        entity.lowStockThreshold = request.lowStockThreshold(); entity.quantity = 0;
        items.saveAndFlush(entity); alert(entity); return item(entity);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Item settings(Long id, ConsumableRequest.Settings request) {
        var actor = manager(); String key = key(request.requestKey());
        users.findForWorkflowUpdate(actor.getId()).orElseThrow(); var entity = find(id, true);
        String hash = hash(id, request.version(), clean(request.name()), clean(request.unit()), request.lowStockThreshold(), clean(request.notes()));
        var replay = changes.findByRequestKey(key);
        if (replay.isPresent()) { replay(replay.get().actorId, actor.getId(), replay.get().requestHash, hash); return item(entity); }
        version(entity, request.version());
        if (entity.quantity > 0 && !entity.unit.equals(clean(request.unit())))
            throw fail(HttpStatus.CONFLICT, "库存不为零时不能更改单位，请先核对库存，避免改变数量含义");
        entity.name = clean(request.name()); entity.unit = clean(request.unit()); entity.notes = clean(request.notes());
        entity.lowStockThreshold = request.lowStockThreshold();
        // Increment even for a metadata no-op: each accepted command has a distinct stock/settings version.
        entity.updatedAt = LocalDateTime.now(); items.saveAndFlush(entity);
        var change = new ConsumableSettingChange(); change.requestKey = key; change.requestHash = hash;
        change.consumableId = id; change.actorId = actor.getId(); change.name = entity.name; change.unit = entity.unit;
        change.lowStockThreshold = entity.lowStockThreshold; change.notes = entity.notes; change.itemVersion = entity.version;
        changes.saveAndFlush(change); alert(entity); return item(entity);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result move(Long id, ConsumableRequest.Move request) {
        var actor = manager(); String key = key(request.requestKey());
        long quantity;
        try { quantity = request.quantity().longValueExact(); }
        catch (NullPointerException | ArithmeticException e) { throw fail(HttpStatus.BAD_REQUEST, "数量必须为整数"); }
        if (request.kind() == null || !Set.of("IN", "OUT").contains(request.kind()) || quantity <= 0 || quantity > MAX_STOCK || clean(request.note()).isEmpty())
            throw fail(HttpStatus.BAD_REQUEST, "出入库类型、数量或备注无效");
        users.findForWorkflowUpdate(actor.getId()).orElseThrow(); var entity = find(id, true);
        String recipient = clean(request.recipientUsername());
        String hash = hash(id, request.version(), request.kind(), quantity, recipient.toLowerCase(Locale.ROOT), clean(request.note()));
        var replay = movements.findByRequestKey(key);
        if (replay.isPresent()) { replay(replay.get().actorId, actor.getId(), replay.get().requestHash, hash); return new Result(item(entity), movement(replay.get())); }
        version(entity, request.version());
        UserAccount target = null;
        if ("OUT".equals(request.kind())) {
            if (recipient.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "领用必须填写明确的员工系统账号");
            target = users.findByUsernameIgnoreCase(recipient).filter(UserAccount::isEnabled)
                    .orElseThrow(() -> fail(HttpStatus.BAD_REQUEST, "领用账号不存在或已停用，请填写系统账号而不是姓名"));
        } else if (!recipient.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "入库不需要填写领用人");
        long after = "IN".equals(request.kind()) ? Math.addExact(entity.quantity, quantity) : entity.quantity - quantity;
        if (after < 0) throw fail(HttpStatus.CONFLICT, "库存不足，不能领用超过当前库存的数量");
        if (after > MAX_STOCK) throw fail(HttpStatus.CONFLICT, "库存不能超过十亿，请核对数量及单位");
        entity.quantity = after; items.saveAndFlush(entity);
        var ledger = new ConsumableMovement(); ledger.requestKey = key; ledger.requestHash = hash;
        ledger.consumableId = id; ledger.consumableName = entity.name; ledger.unit = entity.unit; ledger.kind = request.kind();
        ledger.quantity = quantity; ledger.balanceAfter = after; ledger.actorId = actor.getId();
        ledger.actorName = actor.getDisplayName(); ledger.note = clean(request.note());
        if (target != null) { ledger.recipientId = target.getId(); ledger.recipientName = target.getDisplayName(); }
        movements.saveAndFlush(ledger); alert(entity); return new Result(item(entity), movement(ledger));
    }
    private void alert(ConsumableItem entity) {
        if (entity.quantity <= entity.lowStockThreshold) notifications.enqueue("consumable-low:" + entity.id + ":" + entity.version,
                "CONSUMABLE", "耗材低库存提醒", "耗材：" + entity.name + "\n当前库存：" + entity.quantity + entity.unit
                        + "\n预警阈值：" + entity.lowStockThreshold + entity.unit + "\n请进入资产系统耗材管理核对补货。通知不包含员工领用备注。");
    }
    private UserAccount manager() {
        var actor = access.current();
        if (!OperationService.canManage(actor)) throw fail(HttpStatus.FORBIDDEN, "需要全部资产范围及查看、编辑、分配领用人、归还权限");
        return actor;
    }
    private ConsumableItem find(Long id, boolean lock) {
        if (id == null || id <= 0) throw fail(HttpStatus.BAD_REQUEST, "耗材编号无效");
        return (lock ? items.locked(id) : items.findById(id)).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "耗材不存在"));
    }
    private static void version(ConsumableItem item, Long version) {
        if (version == null || item.version != version) throw fail(HttpStatus.CONFLICT, "库存或配置已变化，请刷新后重试");
    }
    private static void replay(Long originalActor, Long actor, String originalHash, String hash) {
        if (!originalActor.equals(actor) || !originalHash.equals(hash))
            throw fail(HttpStatus.CONFLICT, "请求标识已用于其他操作，请刷新后重新提交");
    }
    private static String key(String value) {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException | NullPointerException e) { throw fail(HttpStatus.BAD_REQUEST, "请求标识必须是完整UUID"); }
    }
    private static String hash(Object... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object field : fields) {
                String value = String.valueOf(field); digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static PageRequest pageRequest(int page) { return PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")); }
    private Item item(ConsumableItem c) { return new Item(c.id, c.version, c.name, c.unit, c.quantity, c.lowStockThreshold, c.quantity <= c.lowStockThreshold, c.notes, c.updatedAt); }
    private Movement movement(ConsumableMovement m) { return new Movement(m.id, m.consumableId, m.consumableName, m.unit, m.kind, m.quantity, m.balanceAfter, m.recipientId, m.recipientName, m.actorName, m.note, m.createdAt); }
    private static ApiException fail(HttpStatus status, String message) { return new ApiException(status, message); }
}
