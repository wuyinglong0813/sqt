-- Preserve historical phone data; indexed locking reads serialize verified phone binding.
CREATE INDEX idx_sys_user_phone ON sys_user (phone);
