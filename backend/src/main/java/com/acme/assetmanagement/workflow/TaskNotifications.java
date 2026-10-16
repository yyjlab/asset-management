package com.acme.assetmanagement.workflow;

import com.acme.assetmanagement.common.ApiException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class TaskNotifications {
    private final TaskNotificationRepository repository;
    private final WorkflowNotificationProperties properties;
    public TaskNotifications(TaskNotificationRepository repository, WorkflowNotificationProperties properties) {
        this.repository = repository; this.properties = properties;
    }
    public void enqueue(String key, String category, String title, String content) {
        if (!properties.enabled() || repository.existsByEventKey(key)) return;
        var entity = new TaskNotification(); entity.eventKey = key; entity.category = category; entity.title = title;
        entity.content = content.length() > 1900 ? content.substring(0, 1900) : content;
        repository.saveAndFlush(entity);
    }
    public void registration(Long id, long version, String ownerName, String state) {
        String label = switch (state) { case "SUBMITTED" -> "待管理员审核"; case "NEEDS_INFO" -> "请员工补充资料";
            case "APPROVED" -> "审核通过"; case "REJECTED" -> "已驳回"; default -> "已撤回"; };
        enqueue("registration:" + id + ":" + version, "REGISTRATION", "资产登记进度",
                "设备登记 #" + id + "\n员工：" + ownerName + "\n状态：" + label + "\n请进入资产系统“员工自助”查看详情。此通知不包含SN、价格或照片。");
    }
    public void handover(Long id, long version, String ownerName, String state, long waitingCheck, long waitingConfirm) {
        String step = switch (state) { case "COMPLETED" -> "交接记录已完结"; case "CANCELLED" -> "交接记录已取消";
            default -> waitingCheck > 0 ? "请管理员核对配发／验收" : waitingConfirm > 0 ? "请员工确认收到／交还" : "请管理员核对并完结交接单"; };
        enqueue("handover:" + id + ":" + version, "HANDOVER", "资产交接进度",
                "交接单 #" + id + "\n员工：" + ownerName + "\n" + step + "\n请进入资产系统核对；通知不代表资产台账已修改。");
    }
    public record Delivery(Long id, String leaseId, String title, String content) {}
    public Optional<Delivery> claim() {
        var candidates = repository.claimable(Instant.now(), PageRequest.of(0, 1));
        if (candidates.isEmpty()) return Optional.empty();
        var entity = candidates.getFirst();
        if (entity.attempts >= 5) { entity.state = "FAILED"; repository.saveAndFlush(entity); return Optional.empty(); }
        entity.state = "CLAIMED"; entity.attempts++; entity.leaseId = UUID.randomUUID().toString(); entity.leasedUntil = Instant.now().plusSeconds(45);
        repository.saveAndFlush(entity);
        return Optional.of(new Delivery(entity.id, entity.leaseId, entity.title, entity.content));
    }
    public void finish(Delivery delivery, boolean success) {
        var entity = repository.locked(delivery.id()).orElseThrow();
        if (!"CLAIMED".equals(entity.state) || !delivery.leaseId().equals(entity.leaseId)) return;
        entity.leaseId = null; entity.leasedUntil = null;
        if (success) { entity.state = "SENT"; entity.sentAt = Instant.now(); entity.lastError = null; }
        else { entity.state = entity.attempts >= 5 ? "FAILED" : "PENDING"; entity.lastError = "钉钉投递失败，请检查机器人配置、加签及网络";
            entity.nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 60L << (entity.attempts - 1))); }
        repository.saveAndFlush(entity);
    }
    public record Item(Long id, String category, String title, String state, int attempts, String lastError, Instant createdAt, Instant sentAt) {}
    public record Status(boolean enabled, boolean configured, long pending, long failed, List<Item> recent) {}
    @Transactional(readOnly = true)
    public Status status() {
        return new Status(properties.enabled(), properties.configured(), repository.countByState("PENDING") + repository.countByState("CLAIMED"),
                repository.countByState("FAILED"), repository.findAll(PageRequest.of(0, 30, Sort.by(Sort.Direction.DESC, "id")))
                .stream().map(n -> new Item(n.id, n.category, n.title, n.state, n.attempts, n.lastError, n.createdAt, n.sentAt)).toList());
    }
    public void retry(Long id) {
        if (!properties.configured()) throw new ApiException(HttpStatus.CONFLICT, "请先在服务器启用并配置工作流通知及加签机器人");
        var entity = repository.locked(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "通知不存在"));
        if (!"FAILED".equals(entity.state)) throw new ApiException(HttpStatus.CONFLICT, "只能重试已失败的通知");
        entity.state = "PENDING"; entity.attempts = 0; entity.nextAttemptAt = Instant.now(); entity.lastError = null;
        repository.saveAndFlush(entity);
    }
}
