-- 用户行锁串行化同一用户的占额和释放；该索引支持场次有效数量查询。
CREATE INDEX idx_order_user_session_status ON ticket_order (user_id, session_id, status, id);
