-- Run once after importing hmdp.sql; resolve historical duplicates first.
ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
CREATE TABLE order_compensation (
 order_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, voucher_id BIGINT NOT NULL,
 delivered TINYINT NOT NULL DEFAULT 0, KEY idx_delivered(delivered)
);
CREATE INDEX idx_order_expiry ON tb_voucher_order(status,create_time);
