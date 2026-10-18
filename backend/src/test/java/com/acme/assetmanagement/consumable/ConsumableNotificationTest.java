package com.acme.assetmanagement.consumable;

import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserRepository;
import com.acme.assetmanagement.workflow.TaskNotifications;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:consumablenotifydb;DB_CLOSE_DELAY=-1", "app.workflow-scheduling-enabled=false", "app.workflow-notifications.enabled=true"})
@ActiveProfiles("test")
@Transactional
class ConsumableNotificationTest {
    @Autowired ConsumableService service;
    @Autowired UserRepository users;
    @Autowired TaskNotifications notifications;

    @Test void lowStockWarningIsQueuedAndIdempotentWithoutSendingNetworkRequests() {
        var admin = users.findByUsernameIgnoreCase("admin").orElseThrow(); var principal = AccountPrincipal.from(admin);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "N/A", principal.getAuthorities()));
        try {
            long before = notifications.status().pending();
            var create = new ConsumableRequest.Create(UUID.randomUUID().toString(), "低库存电池", "节", 2L, "库存预警");
            var item = service.create(create); assertEquals(before + 1, notifications.status().pending());
            service.create(create); assertEquals(before + 1, notifications.status().pending());
            item = service.move(item.id(), new ConsumableRequest.Move(UUID.randomUUID().toString(), item.version(), "IN", new BigDecimal("5"), "", "补货入库")).item();
            assertEquals(before + 1, notifications.status().pending());
            var checkout = new ConsumableRequest.Move(UUID.randomUUID().toString(), item.version(), "OUT", new BigDecimal("3"), admin.getUsername(), "领用备注不应出现在库存提醒");
            service.move(item.id(), checkout); assertEquals(before + 2, notifications.status().pending());
            service.move(item.id(), checkout); assertEquals(before + 2, notifications.status().pending());
            var pending = notifications.claim().orElseThrow();
            assertEquals("耗材低库存提醒", pending.title()); assertFalse(pending.content().contains("领用备注不应出现在库存提醒"));
            assertFalse(notifications.status().configured()); // No real webhook or secret exists in this test.
        } finally { SecurityContextHolder.clearContext(); }
    }
}
