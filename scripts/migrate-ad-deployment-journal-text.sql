-- MySQL 8.x. Back up first; run explicitly against the asset database.
-- Changes only the integration journal, not assets or lookup values.
-- ALTER TABLE may wait for locks. Existing journal content is preserved.
SELECT DATABASE() AS target_database;
ALTER TABLE ad_deployment_registrations
    MODIFY COLUMN request_json LONGTEXT NOT NULL,
    MODIFY COLUMN response_json LONGTEXT NOT NULL;
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ad_deployment_registrations'
    AND COLUMN_NAME IN ('request_json', 'response_json');
