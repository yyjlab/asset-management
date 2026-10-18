package com.acme.assetmanagement.workflow;

import com.acme.assetmanagement.asset.AssetRepository;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:notificationsdb;DB_CLOSE_DELAY=-1", "app.workflow-scheduling-enabled=false",
        "app.workflow-notifications.enabled=true", "app.workflow-notifications.webhook=https://oapi.dingtalk.com/robot/send?access_token=unit_test_placeholder", "app.workflow-notifications.secret=unit-test-placeholder"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class TaskNotificationsTest {
    @Autowired TaskNotifications notifications;
    @Autowired TaskNotificationRepository repository;
    @Autowired WorkflowNotificationProperties properties;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Test void queueDeduplicatesAndDisabledModeDoesNotEnqueue() {
        notifications.enqueue("test-dedup", "TEST", "测试", "内容"); notifications.enqueue("test-dedup", "TEST", "测试", "重试"); assertEquals(1, repository.count());
        new TaskNotifications(repository, new WorkflowNotificationProperties(false, "", "")).enqueue("disabled", "TEST", "不发送", "内容"); assertEquals(1, repository.count());
    }
    @Test void deliveryLeaseRetryBackoffAndFailureLimitDoNotRepeatBusinessOperations() {
        long count = assets.count(); notifications.enqueue("retry-test", "TEST", "测试", "内容");
        TaskNotifications.Delivery first = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            var delivery = notifications.claim().orElseThrow(); if (first == null) first = delivery;
            assertTrue(notifications.claim().isEmpty());
            notifications.finish(delivery, false); var entry = repository.findById(delivery.id()).orElseThrow();
            assertEquals(attempt, entry.attempts); assertEquals(attempt == 5 ? "FAILED" : "PENDING", entry.state);
            assertFalse(entry.lastError.contains("access_token")); assertFalse(entry.lastError.contains("unit-test"));
            if (attempt < 5) { assertTrue(entry.nextAttemptAt.isAfter(Instant.now())); assertTrue(notifications.claim().isEmpty()); entry.nextAttemptAt = Instant.now().minusSeconds(1); repository.saveAndFlush(entry); }
        }
        assertTrue(notifications.claim().isEmpty()); assertEquals(count, assets.count());
        notifications.retry(first.id()); var delivery = notifications.claim().orElseThrow();
        notifications.finish(first, true); assertEquals("CLAIMED", repository.findById(delivery.id()).orElseThrow().state);
        notifications.finish(delivery, true); assertEquals("SENT", repository.findById(delivery.id()).orElseThrow().state); assertEquals(count, assets.count());
    }
    @Test void expiredLeaseCanBeReclaimedWithoutAcceptingTheOldWorkerResult() {
        notifications.enqueue("lease-test", "TEST", "测试", "内容"); var old = notifications.claim().orElseThrow();
        var entry = repository.findById(old.id()).orElseThrow(); entry.leasedUntil = Instant.now().minusSeconds(1); repository.saveAndFlush(entry);
        var current = notifications.claim().orElseThrow(); assertNotEquals(old.leaseId(), current.leaseId());
        notifications.finish(old, true); assertEquals("CLAIMED", repository.findById(old.id()).orElseThrow().state);
        notifications.finish(current, true); assertEquals("SENT", repository.findById(old.id()).orElseThrow().state);
    }
    @Test void onlySuperAdminCanInspectQueueAndNoCredentialOrBodyIsExposed() throws Exception {
        var admin = users.findByUsernameIgnoreCase("admin").orElseThrow();
        var employee = new UserAccount(); employee.setUsername("notification-ordinary"); employee.setDisplayName("普通员工"); employee.setPasswordHash("unused"); employee.setAccessConfigured(true); employee.setPermissions(Set.of()); employee.setDataScope(DataScope.SELF); users.saveAndFlush(employee);
        notifications.enqueue("private-test", "TEST", "工作流提醒", "不应出现在状态API的业务详情");
        mvc.perform(get("/api/task-notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/task-notifications").with(user(AccountPrincipal.from(employee)))).andExpect(status().isForbidden());
        String response = mvc.perform(get("/api/task-notifications").with(user(AccountPrincipal.from(admin)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("access_token")); assertFalse(response.contains("unit-test-placeholder")); assertFalse(response.contains("不应出现在"));
        var delivery = notifications.claim().orElseThrow();
        mvc.perform(post("/api/task-notifications/{id}/retry", delivery.id()).with(user(AccountPrincipal.from(admin)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/task-notifications/{id}/retry", delivery.id()).with(user(AccountPrincipal.from(admin))).with(csrf())).andExpect(status().isConflict());
    }
}
