-- Redis가 완전히 죽었을 때 MemberTokenService.reissueViaDbFallback()이 오래된 리프레시 토큰의
-- 해시로 회원을 거꾸로 찾아야 한다(opaque 토큰이라 다른 신원 확인 수단이 없다). 인덱스가 없으면
-- 매 재발급 폴백마다 member 테이블 풀스캔이 된다.
CREATE INDEX idx_member_refresh_token_hash ON member (refresh_token_hash);
