package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.security.DingTalkProperties;
import com.acme.assetmanagement.user.*;
import com.acme.assetmanagement.workflow.*;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DingTalkOaService {
    private final DingTalkOaProperties props;
    private final DingTalkProperties login;
    private final DingTalkOaClient client;
    private final OperationRepository tasks;
    private final AssetRepository assets;
    private final UserRepository users;
    private final WorkflowAccess access;
    private final OperationService operations;
    private final TaskNotifications notifications;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public DingTalkOaService(DingTalkOaProperties props, DingTalkProperties login, DingTalkOaClient client,
            OperationRepository tasks, AssetRepository assets, UserRepository users, WorkflowAccess access,
            OperationService operations, TaskNotifications notifications, ObjectMapper json, PlatformTransactionManager transactions) {
        this.props = props; this.login = login; this.client = client; this.tasks = tasks; this.assets = assets;
        this.users = users; this.access = access; this.operations = operations; this.notifications = notifications;
        this.json = json; tx = new TransactionTemplate(transactions);
    }
    public record Create(@NotBlank @Size(max=36) String requestKey, @Pattern(regexp="ISSUE|RETURN") @NotNull String kind,
                         @NotNull @Positive Long assetId, @NotNull @PositiveOrZero Long assetVersion,
                         @NotBlank @Size(max=1000) String description, @AssertTrue boolean confirmed) {}
    public record Item(Long id, String requestKey, String kind, String state, String approvalState, Long ownerId,
                       String ownerName, String assetTag, String instanceId, String error, String description,
                       LocalDateTime checkedAt, LocalDateTime updatedAt, long version) {}
    public record Page(List<Item> items, int page, long totalElements, int totalPages, boolean canManage) {}
    public record Status(boolean enabled, boolean identityReady, boolean streamEnabled) {}
    public record Candidate(Long id, long version, String assetTag, String name) {}
    private record Prepared(Long id, boolean created, String unionId, String code, String key, String form) {}
    private record Pending(Long id, String instanceId) {}
    public Status status() {
        var actor = access.current();
        return new Status(props.enabled() && login.enabled(), actor.getDingtalkUnionId() != null && actor.getDingtalkIdentity() != null, props.enabled() && login.enabled() && props.streamEnabled());
    }
    public List<Candidate> candidates(String kind) {
        requireEnabled(); var actor = access.current();
        if (!Set.of("ISSUE", "RETURN").contains(kind)) throw fail("申请类型无效");
        // Employees see only the minimal allocation catalogue, never other employees, prices, SN or photos.
        return tx.execute(status -> ("RETURN".equals(kind) ? assets.findTop101ByAssignedUserIdOrderByAssetTagAsc(actor.getId())
                : assets.oaAvailable(PageRequest.of(0,500))).stream()
                .filter(a -> "RETURN".equals(kind) ? a.isCheckedOut() : available(a))
                .map(a -> new Candidate(a.getId(), a.getVersion(), a.getAssetTag(), a.getName())).toList());
    }
    public Page list(int page, boolean mine) {
        var actor = access.current(); boolean manage = OperationService.canManage(actor);
        return tx.execute(status -> {
            var result = tasks.findAll((r,q,cb) -> cb.and(cb.isNotNull(r.get("oaState")),
                    manage && !mine ? cb.conjunction() : cb.equal(r.get("ownerId"), actor.getId())),
                    PageRequest.of(Math.max(0,page),20,Sort.by(Sort.Direction.DESC,"id")));
            return new Page(result.map(this::item).getContent(),result.getNumber(),result.getTotalElements(),result.getTotalPages(),manage);
        });
    }
    public Item create(Create request) {
        requireEnabled(); var actor = access.current();
        try { if (!UUID.fromString(request.requestKey()).toString().equals(request.requestKey())) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw fail("请求标识无效"); }
        if (!request.confirmed()) throw fail("必须由本人确认实物，不可代签");
        Prepared prepared = tx.execute(status -> {
            var owner = users.findForWorkflowUpdate(actor.getId()).filter(UserAccount::isEnabled).orElseThrow(() -> fail("账号不可用"));
            var replay = tasks.findByRequestKey(request.requestKey());
            if (replay.isPresent()) {
                var t = replay.get();
                if (!t.ownerId.equals(owner.getId()) || t.oaState == null) throw fail("请求标识已使用");
                return new Prepared(t.id,false,null,null,null,null);
            }
            if (owner.getDingtalkUnionId() == null || owner.getDingtalkIdentity() == null) throw fail("请先使用钉钉重新登录或绑定，不能按姓名关联员工");
            if (tasks.countByOwnerIdAndCreatedAtAfter(owner.getId(),LocalDateTime.now().minusDays(1)) >= 30)
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"今日申请较多，请联系管理员");
            var a = assets.findForRegistrationLink(request.assetId()).orElseThrow(() -> fail("设备不存在"));
            if (a.getVersion() != request.assetVersion()) throw fail("设备已变化，请刷新后重新核对");
            if ("ISSUE".equals(request.kind()) ? !available(a) : !a.isCheckedOut() || !Objects.equals(a.getAssignedUserId(),owner.getId()))
                throw fail("设备不可领用或不属于本人，不能按姓名匹配");
            if (tasks.existsByReservedAssetId(a.getId())) throw fail("此设备已有办理中的工单，请先完结原工单");
            var t = new OperationTask(); t.requestKey=request.requestKey(); t.kind=request.kind(); t.state="OA_PENDING";
            t.ownerId=owner.getId(); t.ownerName=owner.getDisplayName(); t.targetId=owner.getId(); t.targetName=t.ownerName;
            t.assetId=a.getId(); t.assetVersion=a.getVersion(); t.assetTag=a.getAssetTag(); t.reservedAssetId=a.getId();
            t.title="钉钉 OA " + ("ISSUE".equals(t.kind)?"实物领用确认":"实物归还验收"); t.description=request.description().trim();
            t.oaState="STARTING"; t.oaProcessCode=props.processCode(t.kind); t.oaCorpId=props.corpId(); t.oaIdentity=owner.getDingtalkIdentity();
            t.oaFormValue=json.writeValueAsString(Map.of("业务单号",t.requestKey,"类型",t.kind,"员工账号ID",owner.getId(),
                    "资产ID",a.getId(),"资产编号",a.getAssetTag(),"资产名称",a.getName(),"资产版本",a.getVersion(),"说明",t.description));
            t.events.add(new OperationTask.Event(owner.getDisplayName(),"本人确认实物并提交钉钉 OA；审批通过前台账不变"));
            tasks.saveAndFlush(t);
            return new Prepared(t.id,true,owner.getDingtalkUnionId(),t.oaProcessCode,t.requestKey,t.oaFormValue);
        });
        if (prepared.created()) {
            try {
                var employee = client.employee(prepared.unionId());
                boolean start = Boolean.TRUE.equals(tx.execute(s -> {
                    var t=locked(prepared.id()); if (!"STARTING".equals(t.oaState)) return false;
                    t.oaUserId=employee.userId(); tasks.saveAndFlush(t); return true;
                }));
                if (start) {
                    var remote = client.start(prepared.code(),employee,prepared.key(),prepared.form());
                    tx.executeWithoutResult(s -> {
                        var t=locked(prepared.id());
                        if (t.oaInstanceId == null) t.oaInstanceId=remote.instanceId();
                        if ("STARTING".equals(t.oaState)) { t.oaState="WAITING"; t.oaDirty=true; }
                        tasks.saveAndFlush(t);
                    });
                }
            } catch (Exception e) {
                // HTTP timeouts may occur after OA created the instance. Never automatically create another.
                markError(prepared.id(),"START_UNCERTAIN","发起结果待核实；请管理员在钉钉检查业务单号并关联审批实例，禁止重复发起");
            }
        }
        return tx.execute(s -> item(locked(prepared.id())));
    }
    public Item sync(Long id) {
        requireEnabled(); var actor=access.current();
        tx.executeWithoutResult(s -> visible(id,actor));
        refresh(id);
        return tx.execute(s -> item(visible(id,actor)));
    }
    public Item attach(Long id, String instanceId) {
        requireEnabled(); var actor=access.current();
        if (!OperationService.canManage(actor)) throw new ApiException(HttpStatus.FORBIDDEN,"仅管理员可核实不确定的发起结果");
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,160}")) throw fail("审批实例ID格式无效");
        // Only an explicit administrative recovery may attach an instance. It must carry the exact private correlation payload.
        JsonNode remote;
        try { remote=client.detail(instanceId); }
        catch (Exception e) { throw fail("读取钉钉审批失败，请检查实例ID、应用权限或网络；不会新建审批"); }
        tx.executeWithoutResult(s -> {
            var t=locked(id);
            if (t.oaState == null || t.oaInstanceId != null || !Set.of("STARTING","START_UNCERTAIN").contains(t.oaState)) throw fail("此工单不需要关联审批实例");
            if ("STARTING".equals(t.oaState) && t.createdAt.isAfter(LocalDateTime.now().minusMinutes(2))) throw fail("审批仍在发起中，请两分钟后再核实");
            verify(t,remote); t.oaInstanceId=instanceId; t.oaState="WAITING"; t.oaError=null; t.oaDirty=true;
            t.events.add(new OperationTask.Event(actor.getDisplayName(),"核实并关联 OA 审批实例："+instanceId)); tasks.saveAndFlush(t);
        });
        return sync(id);
    }
    public Item closeException(Long id, long version, String note) {
        requireEnabled(); var actor=access.current();
        if (!OperationService.canManage(actor)) throw new ApiException(HttpStatus.FORBIDDEN,"仅管理员可关闭异常工单");
        return tx.execute(s -> {
            var t=locked(id);
            if (t.version!=version) throw fail("工单已变化，请刷新后核对");
            if (t.oaState==null || !Set.of("STARTING","START_UNCERTAIN","LEDGER_FAILED").contains(t.oaState)) throw fail("只能关闭尚未入账的异常工单");
            if ("STARTING".equals(t.oaState) && t.createdAt.isAfter(LocalDateTime.now().minusMinutes(2))) throw fail("审批仍在发起中，请两分钟后再核实");
            t.oaState="SKIPPED"; t.state="CANCELLED"; t.reservedAssetId=null; t.oaDirty=false; t.updatedAt=LocalDateTime.now();
            t.oaError="管理员关闭异常，未修改台账；不代表钉钉审批已撤销";
            t.events.add(new OperationTask.Event(actor.getDisplayName(),"关闭异常且不改台账："+note.trim()));
            tasks.saveAndFlush(t);
            notifications.enqueue("oa:"+id+":SKIPPED","OPERATION","资产交接异常已关闭","工单 #"+id+" 由管理员关闭，台账未修改；钉钉审批需另外核实。");
            return item(t);
        });
    }
    List<Long> pending() {
        if (!props.enabled()) return List.of();
        return tx.execute(s -> tasks.oaPending(LocalDateTime.now().minusSeconds(60),PageRequest.of(0,20)));
    }
    void signal(String corpId, String code, String instanceId) {
        if (!props.enabled() || !props.corpId().equals(corpId) || instanceId == null) return;
        tx.executeWithoutResult(s -> tasks.findByOaInstanceId(instanceId).ifPresent(found -> {
            var t=locked(found.id);
            if (Objects.equals(t.oaCorpId,corpId) && Objects.equals(t.oaProcessCode,code)
                    && Set.of("WAITING","CHECK_FAILED").contains(t.oaState)) { t.oaDirty=true; tasks.saveAndFlush(t); }
        }));
    }
    void refresh(Long id) {
        Pending pending=tx.execute(s -> {
            var t=locked(id);
            if (t.oaState == null || t.oaInstanceId == null || terminal(t.oaState)) return null;
            t.oaCheckedAt=LocalDateTime.now(); t.oaDirty=false; tasks.saveAndFlush(t);
            return new Pending(t.id,t.oaInstanceId);
        });
        if (pending == null) return;
        JsonNode remote;
        try { remote=client.detail(pending.instanceId()); }
        catch (Exception e) { markError(id,"CHECK_FAILED","读取钉钉审批失败，将自动重试；请检查应用权限或网络"); return; }
        try {
            tx.executeWithoutResult(s -> {
                var t=locked(id);
                if (terminal(t.oaState)) return;
                verify(t,remote);
                String state=remote.path("status").asText().toUpperCase(Locale.ROOT);
                String result=remote.path("result").asText().toLowerCase(Locale.ROOT);
                if ("TERMINATED".equals(state) || "CANCELED".equals(state)) { close(t,"TERMINATED","CANCELLED"); return; }
                if (!"COMPLETED".equals(state)) { t.oaState="WAITING"; t.oaError=null; tasks.saveAndFlush(t); return; }
                if ("refuse".equals(result)) { close(t,"REFUSED","REJECTED"); return; }
                if (!"agree".equals(result)) throw fail("审批结束但未明确通过，不执行资产变更");
                String approver=physicalApprover(t,remote);
                t.oaState="AGREED"; t.oaError=null;
                operations.completeFromOa(t,approver);
            });
        } catch (ApiException e) { markError(id,"LEDGER_FAILED",e.getMessage()); }
        catch (Exception e) { markError(id,"LEDGER_FAILED","审批同步或台账办理失败，未覆盖资产；请管理员核对后重新同步"); }
    }
    private void verify(OperationTask t, JsonNode remote) {
        if (!Objects.equals(props.corpId(),t.oaCorpId) || !Objects.equals(props.processCode(t.kind),t.oaProcessCode)
                || t.oaUserId == null || !t.oaUserId.equals(remote.path("originatorUserId").asText())) throw fail("审批企业、模板配置或申请人不匹配，拒绝办理");
        Map<String,String> form=new HashMap<>();
        for (var field:remote.path("formComponentValues")) {
            String name=field.path("name").asText();
            if (form.put(name,field.path("value").asText()) != null) throw fail("审批表单存在重复字段，拒绝办理");
        }
        if (!t.requestKey.equals(form.get(DingTalkOaClient.KEY_FIELD)) || !t.oaFormValue.equals(form.get(DingTalkOaClient.DETAIL_FIELD))
                || !DingTalkOaClient.ATTESTATION.equals(form.get(DingTalkOaClient.CONFIRM_FIELD))) throw fail("审批设备明细或员工确认已改变，拒绝自动写入台账");
    }
    private String physicalApprover(OperationTask t, JsonNode remote) {
        for (var task:remote.path("tasks")) {
            String user=task.path("userId").asText();
            if (props.approvers().contains(user) && !user.equals(t.oaUserId)
                    && "COMPLETED".equalsIgnoreCase(task.path("status").asText()) && "agree".equalsIgnoreCase(task.path("result").asText())) return user;
        }
        throw fail("未找到独立运维审批人的实物验收通过记录，不自动改账");
    }
    private void close(OperationTask t,String oa,String state) {
        t.oaState=oa; t.state=state; t.reservedAssetId=null; t.oaError=null; t.updatedAt=LocalDateTime.now();
        t.events.add(new OperationTask.Event("钉钉 OA","审批已"+("REFUSED".equals(oa)?"驳回":"终止")+"，未修改资产"));
        tasks.saveAndFlush(t);
        notifications.enqueue("oa:"+t.id+":"+oa,"OPERATION","钉钉资产审批进度","工单 #"+t.id+" 审批已"+("REFUSED".equals(oa)?"驳回":"终止")+"，台账未修改。");
    }
    private void markError(Long id,String state,String error) {
        tx.executeWithoutResult(s -> {
            var t=locked(id);
            if (terminal(t.oaState)) return;
            t.oaState=state; t.oaError=error.substring(0,Math.min(300,error.length())); t.updatedAt=LocalDateTime.now();
            if ("LEDGER_FAILED".equals(state)) t.state="OA_FAILED";
            tasks.saveAndFlush(t);
            notifications.enqueue("oa:"+id+":"+state,"OPERATION","钉钉资产审批需要处理","工单 #"+id+"："+t.oaError+"。请进入资产系统查看。");
        });
    }
    private OperationTask visible(Long id,UserAccount actor) {
        var t=tasks.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"OA工单不存在"));
        if (t.oaState == null || !OperationService.canManage(actor) && !t.ownerId.equals(actor.getId())) throw new ApiException(HttpStatus.NOT_FOUND,"OA工单不存在");
        return t;
    }
    private OperationTask locked(Long id) { return tasks.locked(id).orElseThrow(() -> fail("OA工单不存在")); }
    private Item item(OperationTask t) { return new Item(t.id,t.requestKey,t.kind,t.state,t.oaState,t.ownerId,t.ownerName,t.assetTag,t.oaInstanceId,t.oaError,t.description,t.oaCheckedAt,t.updatedAt,t.version); }
    private static boolean terminal(String state) { return Set.of("APPLIED","REFUSED","TERMINATED","SKIPPED").contains(state); }
    private boolean available(Asset a) { return !a.isCheckedOut() && a.getAssignedUserId()==null && (a.getAssignedTo()==null || a.getAssignedTo().isBlank()) && Set.of("当前可用","可领用").contains(a.getStatus().getName()); }
    private void requireEnabled() { if (!props.enabled() || !login.enabled()) throw fail("钉钉 OA 尚未启用，请管理员先配置模板及审批权限"); }
    private static ApiException fail(String message) { return new ApiException(HttpStatus.CONFLICT,message); }
}
