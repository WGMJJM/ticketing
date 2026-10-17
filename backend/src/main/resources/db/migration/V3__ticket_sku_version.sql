ALTER TABLE ticket_sku
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '票档管理并发更新版本' AFTER status,
    ADD CONSTRAINT ck_sku_version CHECK (version >= 0);
