CREATE TABLE post_like (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_post_like_user_post UNIQUE (user_id, post_id)
);

CREATE TABLE post_like_count (
    post_id BIGINT NOT NULL,
    like_count BIGINT NOT NULL,
    PRIMARY KEY (post_id)
);
