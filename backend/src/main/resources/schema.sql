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
CREATE INDEX IF NOT EXISTS playlists_owner ON playlists(user_id);
CREATE INDEX IF NOT EXISTS ai_usage_time ON ai_usage(used_at);
