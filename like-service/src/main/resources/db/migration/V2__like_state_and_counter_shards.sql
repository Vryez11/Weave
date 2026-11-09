-- 취소해도 행을 남겨 반영한 가장 큰 version을 기억한다. 기존 행은 누른 상태이고 어떤 실제 요청(version >= 1)이든 이긴다
-- (add-idempotent-like design 결정 3)
ALTER TABLE post_like
    ADD COLUMN liked BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD INDEX idx_post_like_post_liked (post_id, liked);

-- 게시글의 수를 칸 여러 개에 나눠 쓸 수 있게 한다. 기존 수는 0번 칸으로 이어진다(design 결정 6)
ALTER TABLE post_like_count
    ADD COLUMN shard INT NOT NULL DEFAULT 0,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (post_id, shard);
