-- 保留已应用 V1；活动下架语义由 CANCELLED 统一为 OFFLINE。
ALTER TABLE activity DROP CHECK ck_activity_status;
UPDATE activity SET status = 'OFFLINE' WHERE status = 'CANCELLED';
ALTER TABLE activity
    ADD COLUMN summary VARCHAR(500) NOT NULL DEFAULT '' COMMENT '活动简介' AFTER venue,
    ADD COLUMN cover_url VARCHAR(2048) NULL COMMENT '封面 HTTP(S) 地址，不在服务端抓取' AFTER description,
    ADD COLUMN published_at DATETIME(6) NULL COMMENT '最近发布时间 UTC' AFTER status,
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '并发更新版本' AFTER published_at,
    ADD CONSTRAINT ck_activity_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    ADD CONSTRAINT ck_activity_version CHECK (version >= 0),
    ADD KEY idx_activity_publication (status, published_at, id);

UPDATE activity SET published_at = created_at WHERE status = 'PUBLISHED';

ALTER TABLE activity_session
    ADD COLUMN sale_starts_at DATETIME(6) NULL COMMENT '场次开售时间 UTC' AFTER ends_at,
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '并发更新版本' AFTER status;
-- 旧数据仅补充明确的开售时间；后续新建必须由管理员输入。
UPDATE activity_session SET sale_starts_at = DATE_SUB(starts_at, INTERVAL 30 DAY);
ALTER TABLE activity_session
    MODIFY COLUMN sale_starts_at DATETIME(6) NOT NULL COMMENT '场次开售时间 UTC',
    ADD CONSTRAINT ck_session_sale_time CHECK (sale_starts_at < starts_at),
    ADD CONSTRAINT ck_session_version CHECK (version >= 0);
