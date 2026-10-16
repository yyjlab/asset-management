package com.acme.assetmanagement.handover;

import com.acme.assetmanagement.asset.Asset;
import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class HandoverService {
    private final HandoverRepository tasks;
    private final UserRepository users;
    private final AssetRepository assets;
    private final com.acme.assetmanagement.workflow.TaskNotifications notifications;

    public HandoverService(HandoverRepository tasks, UserRepository users, AssetRepository assets,
            com.acme.assetmanagement.workflow.TaskNotifications notifications) {
        this.tasks = tasks; this.users = users; this.assets = assets;
        this.notifications = notifications;
    }

    private record Actor(Long id, String name, boolean manager) {}
    private Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal))
            throw error(HttpStatus.UNAUTHORIZED, "请先登录");
        var account = users.findById(principal.id()).filter(UserAccount::isEnabled)
                .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "账号不可用"));
        var permissions = account.effectivePermissions();
        boolean manager = account.getDataScope() == DataScope.ALL && permissions.containsAll(
                Set.of(Permission.ASSET_VIEW, Permission.ASSET_CHECKOUT, Permission.ASSET_RETURN));
        return new Actor(account.getId(), account.getDisplayName(), manager);
    }
    private Actor manager() {
        var actor = actor();
        if (!actor.manager()) throw error(HttpStatus.FORBIDDEN, "管理交接需要全部资产范围、查看资产、分配领用人及归还资产权限");
        return actor;
    }

    public record Summary(Long id, long version, String kind, String state, Long employeeUserId, String employeeName,
                          String department, java.time.LocalDate plannedDate, int totalItems, long checkedItems,
                          long confirmedItems, LocalDateTime createdAt) {}
    public record Page(List<Summary> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Employee(Long id, String username, String displayName, boolean enabled) {}
    public record Item(int index, Long assetId, String assetTag, String name, String model, String serialNumber,
                       String accessories, boolean checked, String checkedBy, LocalDateTime checkedAt,
                       String confirmationMethod, String confirmedBy, LocalDateTime confirmedAt, String confirmationNote) {}
    public record Event(LocalDateTime occurredAt, String actorName, String description) {}
    public record Detail(Long id, long version, String kind, String state, Long employeeUserId, String employeeName,
                         String department, java.time.LocalDate plannedDate, String approvalReference, String notes,
                         String createdByName, LocalDateTime createdAt, List<Item> items, List<Event> events) {}

    public Page list(int page, String kind, String state, boolean mine) {
        var actor = actor();
        validateFilter(kind, Set.of("ONBOARDING", "OFFBOARDING"));
        validateFilter(state, Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED"));
        Specification<HandoverTask> spec = (root, query, cb) -> actor.manager() && !mine ? cb.conjunction() : cb.equal(root.get("employeeUserId"), actor.id());
        if (!kind.isBlank()) spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
        if (!state.isBlank()) spec = spec.and((root, query, cb) -> cb.equal(root.get("state"), state));
        var result = tasks.findAll(spec, PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new Page(result.map(task -> new Summary(task.id, task.version, task.kind, task.state, task.employeeUserId,
                task.employeeName, task.department, task.plannedDate, task.items.size(),
                task.items.stream().filter(i -> i.checked).count(), task.items.stream().filter(i -> i.confirmedAt != null).count(),
                task.createdAt)).getContent(), result.getNumber(), result.getTotalElements(), result.getTotalPages(), actor.manager() && !mine);
    }

    public List<Employee> employees() {
        manager();
        return users.findAllByOrderByRoleAscCreatedAtAsc().stream()
                .map(u -> new Employee(u.getId(), u.getUsername(), u.getDisplayName(), u.isEnabled())).toList();
    }

    public Detail get(Long id) { return detail(visible(id, actor())); }

    @Transactional
    public Detail create(HandoverRequest.Create request) {
        var actor = manager();
        try { UUID.fromString(request.requestKey()); }
        catch (IllegalArgumentException exception) { throw error(HttpStatus.BAD_REQUEST, "交接请求标识无效"); }
        var existing = tasks.findByRequestKey(request.requestKey());
        if (existing.isPresent()) return detail(existing.get());
        var task = new HandoverTask();
        task.requestKey = request.requestKey(); task.kind = request.kind();
        task.employeeUserId = request.employeeUserId();
        if (task.employeeUserId != null) {
            var employee = requireEmployee(task.employeeUserId);
            if ("ONBOARDING".equals(task.kind) && !employee.isEnabled()) throw error(HttpStatus.BAD_REQUEST, "入职配发不能关联已停用账号");
            task.employeeName = employee.getDisplayName();
        } else task.employeeName = clean(request.employeeName());
        if (task.employeeName.isBlank()) throw error(HttpStatus.BAD_REQUEST, "请选择员工账号或填写员工姓名");
        task.department = clean(request.department()); task.plannedDate = request.plannedDate();
        task.approvalReference = clean(request.approvalReference()); task.notes = clean(request.notes());
        task.createdById = actor.id(); task.createdByName = actor.name();
        var selected = new LinkedHashMap<Long, Asset>();
        if (request.includeAssignedAssets()) {
            if (!"OFFBOARDING".equals(task.kind) || task.employeeUserId == null)
                throw error(HttpStatus.BAD_REQUEST, "自动列出资产需要选择离职员工的明确账号");
            assets.findTop101ByAssignedUserIdOrderByAssetTagAsc(task.employeeUserId).forEach(a -> selected.put(a.getId(), a));
        }
        for (Long id : request.assetIds()) selected.put(id, assets.findById(id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "所选资产不存在，请刷新后重试")));
        if (selected.size() + request.manualItems().size() > 100) throw error(HttpStatus.BAD_REQUEST, "每张交接单最多100项，请分批创建");
        for (Asset asset : selected.values()) {
            if ("OFFBOARDING".equals(task.kind)) {
                if (asset.getAssignedUserId() != null && !Objects.equals(task.employeeUserId, asset.getAssignedUserId()))
                    throw error(HttpStatus.BAD_REQUEST, "所选资产关联了其他员工，请核对领用账号");
                if (asset.getAssignedUserId() == null && !request.allowLegacyAssets())
                    throw error(HttpStatus.BAD_REQUEST, "未关联账号的旧资产需要明确确认后才能加入清单");
            } else if (asset.isCheckedOut() || asset.getAssignedUserId() != null || !clean(asset.getAssignedTo()).isBlank()) {
                throw error(HttpStatus.BAD_REQUEST, "所选配发资产仍有领用记录，请先核实台账");
            }
            if ("ONBOARDING".equals(task.kind) && (asset.getStatus().getName().contains("报废")
                    || asset.getStatus().getName().contains("维修") || asset.getStatus().getName().contains("维护")))
                throw error(HttpStatus.BAD_REQUEST, "不能将维修、维护或报废设备列为待配发设备");
            var item = new HandoverTask.Item();
            item.assetId = asset.getId(); item.assetTag = asset.getAssetTag(); item.name = asset.getName();
            item.model = asset.getModel().getName(); item.serialNumber = asset.getManufacturerSerialNumber();
            item.accessories = clip(asset.getAccessories().stream().map(a -> a.getName() + " ×" + a.getQuantity())
                    .collect(java.util.stream.Collectors.joining("；")), 4000);
            task.items.add(item);
        }
        for (String name : request.manualItems()) {
            var item = new HandoverTask.Item(); item.name = name.trim(); task.items.add(item);
        }
        if (task.items.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "未找到已关联资产，请添加待核对项；不能据此认定员工没有资产");
        event(task, actor, "创建" + ("ONBOARDING".equals(task.kind) ? "入职配发" : "离职交接") + "单；仅保存清单，不修改资产台账");
        return save(task);
    }

    @Transactional
    public Detail check(Long id, int index, HandoverRequest.Check request) {
        var actor = manager(); var task = writable(id, request.version(), actor); var item = item(task, index);
        if (item.confirmedAt != null) throw error(HttpStatus.CONFLICT, "此项已确认，不能覆盖原验收记录");
        item.checked = request.checked(); item.checkedAt = request.checked() ? LocalDateTime.now() : null;
        item.checkedBy = request.checked() ? actor.name() : null;
        progress(task); event(task, actor, (request.checked() ? "验收／配发确认：" : "撤销验收：") + item.name);
        return save(task);
    }

    @Transactional
    public Detail confirm(Long id, int index, HandoverRequest.Confirm request) {
        var actor = actor(); var task = writable(id, request.version(), actor); var item = item(task, index);
        if (request.offline()) {
            if (!actor.manager()) throw error(HttpStatus.FORBIDDEN, "只有交接管理员可以登记线下确认");
            if (clean(request.note()).isBlank()) throw error(HttpStatus.BAD_REQUEST, "请填写线下签收或验收的凭证说明");
        } else if (!Objects.equals(task.employeeUserId, actor.id())) throw error(HttpStatus.FORBIDDEN, "只能确认本人交接事项，不能代替员工线上确认");
        if (!item.checked) throw error(HttpStatus.CONFLICT, "请先由管理员验收或确认配发");
        if (item.confirmedAt != null) throw error(HttpStatus.CONFLICT, "此项已确认，不能覆盖原确认记录");
        item.confirmationMethod = request.offline() ? "OFFLINE" : "ONLINE";
        item.confirmedAt = LocalDateTime.now(); item.confirmedBy = actor.name(); item.confirmationNote = clean(request.note());
        progress(task); event(task, actor, (request.offline() ? "登记线下确认：" : "员工线上确认：") + item.name + (request.offline() ? "；凭证：" + item.confirmationNote : ""));
        return save(task);
    }

    @Transactional
    public Detail complete(Long id, HandoverRequest.Action request) {
        var actor = manager(); var task = writable(id, request.version(), actor);
        if (task.items.isEmpty() || task.items.stream().anyMatch(i -> !i.checked || i.confirmedAt == null))
            throw error(HttpStatus.CONFLICT, "请完成每项验收及员工确认，无法线上确认时登记线下凭证");
        task.state = "COMPLETED"; event(task, actor, "交接记录完结；资产台账未自动修改" + note(request.note()));
        return save(task);
    }

    @Transactional
    public Detail cancel(Long id, HandoverRequest.Action request) {
        var actor = manager(); var task = writable(id, request.version(), actor);
        if (clean(request.note()).isBlank()) throw error(HttpStatus.BAD_REQUEST, "请填写取消原因");
        task.state = "CANCELLED"; event(task, actor, "取消交接单：" + request.note().trim());
        return save(task);
    }

    @Transactional
    public Detail linkEmployee(Long id, HandoverRequest.LinkEmployee request) {
        var actor = manager(); var task = visible(id, actor); version(task, request.version());
        if (task.employeeUserId != null || "CANCELLED".equals(task.state)) throw error(HttpStatus.CONFLICT, "已有明确关联或已取消的交接单不能重新关联");
        var employee = requireEmployee(request.employeeUserId());
        task.employeeUserId = employee.getId();
        event(task, actor, "人工确认关联账号：" + employee.getUsername() + "（" + employee.getDisplayName() + "）；原姓名快照保留");
        return save(task);
    }

    private HandoverTask visible(Long id, Actor actor) {
        var task = tasks.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "交接单不存在或无权访问"));
        if (!actor.manager() && !Objects.equals(task.employeeUserId, actor.id())) throw error(HttpStatus.NOT_FOUND, "交接单不存在或无权访问");
        return task;
    }
    private HandoverTask writable(Long id, long version, Actor actor) {
        var task = visible(id, actor); version(task, version);
        if (!Set.of("PENDING", "IN_PROGRESS").contains(task.state)) throw error(HttpStatus.CONFLICT, "已完结或已取消的交接单不能修改");
        return task;
    }
    private void version(HandoverTask task, long version) {
        if (task.version != version) throw error(HttpStatus.CONFLICT, "交接单已被其他人更新，请刷新后重试");
    }
    private HandoverTask.Item item(HandoverTask task, int index) {
        if (index < 0 || index >= task.items.size()) throw error(HttpStatus.NOT_FOUND, "交接项目不存在");
        return task.items.get(index);
    }
    private UserAccount requireEmployee(Long id) { return users.findById(id).orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "员工账号不存在")); }
    private void progress(HandoverTask task) { task.state = "IN_PROGRESS"; }
    private void event(HandoverTask task, Actor actor, String text) {
        var event = new HandoverTask.Event(); event.occurredAt = LocalDateTime.now();
        event.actorId = actor.id(); event.actorName = actor.name(); event.description = text;
        task.events.add(event); task.updatedAt = event.occurredAt;
    }
    private Detail save(HandoverTask task) {
        var saved = tasks.saveAndFlush(task);
        notifications.handover(saved.id, saved.version, saved.employeeName, saved.state,
                saved.items.stream().filter(item -> !item.checked).count(),
                saved.items.stream().filter(item -> item.checked && item.confirmedAt == null).count());
        return detail(saved);
    }
    private Detail detail(HandoverTask task) {
        List<Item> items = new ArrayList<>();
        for (int index = 0; index < task.items.size(); index++) {
            var i = task.items.get(index);
            items.add(new Item(index, i.assetId, i.assetTag, i.name, i.model, i.serialNumber, i.accessories,
                    i.checked, i.checkedBy, i.checkedAt, i.confirmationMethod, i.confirmedBy, i.confirmedAt, i.confirmationNote));
        }
        return new Detail(task.id, task.version, task.kind, task.state, task.employeeUserId, task.employeeName,
                task.department, task.plannedDate, task.approvalReference, task.notes, task.createdByName, task.createdAt,
                items, task.events.stream().map(e -> new Event(e.occurredAt, e.actorName, e.description)).toList());
    }
    private static void validateFilter(String value, Set<String> options) {
        if (!value.isBlank() && !options.contains(value)) throw error(HttpStatus.BAD_REQUEST, "交接筛选条件无效");
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static String note(String value) { return clean(value).isBlank() ? "" : "；" + value.trim(); }
    private static ApiException error(HttpStatus status, String message) { return new ApiException(status, message); }
}
