package com.acme.assetmanagement;

import com.acme.assetmanagement.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:initializerdb;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class DataInitializerTest {
    @Autowired
    DataInitializer dataInitializer;
    @Autowired com.acme.assetmanagement.asset.AssetTagGenerator generator;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.acme.assetmanagement.asset.AssetRepository assets;
    @Autowired com.acme.assetmanagement.lookup.LookupRepository lookups;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void startupPreservesRepairAndScrapResultsAndTheirOwnership() {
        var asset = assets.findByAssetTagIgnoreCase("202609010001").orElseThrow();
        for (String status : java.util.List.of("维修中", "已报废")) {
            asset.setStatus(lookups.findByTypeAndNameIgnoreCase(
                    com.acme.assetmanagement.lookup.LookupType.STATUS, status).orElseThrow());
            asset.setCheckedOut(true);
            asset.setAssignedTo("仍负责交还实物的员工");
            asset.setAssignedUserId(123L);
            assets.saveAndFlush(asset);
            long version = asset.getVersion();
            dataInitializer.run();
            entityManager.flush();
            entityManager.refresh(asset);
            assertEquals(status, asset.getStatus().getName());
            assertEquals(version, asset.getVersion());
            assertTrue(asset.isCheckedOut());
            assertEquals(123L, asset.getAssignedUserId());
            assertEquals("仍负责交还实物的员工", asset.getAssignedTo());
        }
    }

    @Test
    void concurrentFirstAllocationsAreUnique() throws Exception {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 4; index++) jobs.add(executor.submit(() -> {
                start.await();
                return transactions.execute(status -> generator.nextTag());
            }));
            start.countDown();
            var tags = new java.util.HashSet<String>();
            for (var job : jobs) tags.add(job.get(15, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertEquals(4, tags.size());
        }
    }

    @Test
    void rerunsLegacyNormalizationAgainstExistingAssetsInsideTransaction() {
        assertDoesNotThrow(() -> dataInitializer.run());
    }
}
