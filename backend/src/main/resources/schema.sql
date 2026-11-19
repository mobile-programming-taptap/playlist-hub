CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS recommendation_feedback (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    track_id VARCHAR(36) NOT NULL,
    reaction INTEGER NOT NULL CHECK (reaction IN (-1, 1)),
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, track_id)
);
CREATE TABLE IF NOT EXISTS recommendation_tracks (
    id VARCHAR(36) PRIMARY KEY,
    track_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS playlists (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(80) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_items (
    id VARCHAR(36) PRIMARY KEY,
    playlist_id VARCHAR(36) NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    track_id VARCHAR(36) NOT NULL,
    position INTEGER NOT NULL,
    UNIQUE (playlist_id, track_id)
);
CREATE TABLE IF NOT EXISTS ai_usage (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    used_at VARCHAR(40) NOT NULL
);
ALTER TABLE playlist_items ADD COLUMN IF NOT EXISTS track_json TEXT;
ALTER TABLE playlists ADD COLUMN IF NOT EXISTS position INTEGER;
CREATE INDEX IF NOT EXISTS playlists_owner ON playlists(user_id);
CREATE INDEX IF NOT EXISTS ai_usage_time ON ai_usage(used_at);
CREATE TABLE IF NOT EXISTS accounts (
    user_id VARCHAR(36) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(254) UNIQUE,
    name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(256),
    created_at VARCHAR(40) NOT NULL
);
ALTER TABLE accounts ALTER COLUMN email DROP NOT NULL;
ALTER TABLE accounts ALTER COLUMN password_hash DROP NOT NULL;
CREATE TABLE IF NOT EXISTS social_accounts (
    provider VARCHAR(16) NOT NULL,
    subject VARCHAR(64) NOT NULL,
    user_id VARCHAR(36) NOT NULL REFERENCES accounts(user_id) ON DELETE CASCADE,
    profile_email VARCHAR(254),
    PRIMARY KEY (provider,subject),
    UNIQUE (user_id,provider)
);
CREATE TABLE IF NOT EXISTS account_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES accounts(user_id) ON DELETE CASCADE,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS account_session_owner ON account_sessions(user_id);
-- 발견(공개 목록) 기능 제거: 기존 DB에 남은 테이블을 삭제한다.
DROP TABLE IF EXISTS playlist_favourites;
DROP TABLE IF EXISTS community_rules_acceptance;
DROP TABLE IF EXISTS community_author_blocks;
DROP TABLE IF EXISTS community_reports;
DROP TABLE IF EXISTS community_hidden_playlists;
DROP TABLE IF EXISTS public_playlists;
ALTER TABLE playlist_items ALTER COLUMN track_id DROP NOT NULL;
ALTER TABLE playlist_items ADD COLUMN IF NOT EXISTS external_key VARCHAR(64);
ALTER TABLE playlist_items ADD COLUMN IF NOT EXISTS external_source_key VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS playlist_external_identity ON playlist_items(playlist_id,external_key);
