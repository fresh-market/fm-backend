-- Redis 장애 시 관리자 opaque Refresh Token을 DB 해시로 역조회하는 fallback 경로용.
CREATE INDEX idx_admin_refresh_token_hash ON admin (refresh_token_hash);
