-- MySQL 8.x only. Review against a backup/test database first; do NOT run during application startup.
-- Removes only single-column UNIQUE indexes on assets.manufacturer_serial_number.
-- Asset-tag/QR uniqueness and all asset rows remain unchanged. ALTER TABLE may wait for locks.
SELECT DATABASE() AS target_database;
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'assets'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SET @ad_sn_drop_clauses = (
    SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(sn_index.INDEX_NAME, '`', '``'), '`') SEPARATOR ', ')
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'assets' AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'
        GROUP BY INDEX_NAME
        HAVING COUNT(*) = 1 AND MIN(COLUMN_NAME) = 'manufacturer_serial_number'
    ) AS sn_index
);
SET @ad_sn_sql = IF(@ad_sn_drop_clauses IS NULL,
    'SELECT ''No single-column SN unique index found; no change'' AS result',
    CONCAT('ALTER TABLE `assets` ', @ad_sn_drop_clauses));
SELECT @ad_sn_sql AS reviewed_statement;
PREPARE ad_sn_statement FROM @ad_sn_sql;
EXECUTE ad_sn_statement;
DEALLOCATE PREPARE ad_sn_statement;

-- Expected: zero rows. Re-running this script is a no-op after the unique SN index is removed.
SELECT INDEX_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'assets' AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'
GROUP BY INDEX_NAME
HAVING COUNT(*) = 1 AND MIN(COLUMN_NAME) = 'manufacturer_serial_number';
