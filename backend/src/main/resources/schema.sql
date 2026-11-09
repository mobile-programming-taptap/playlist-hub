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
CREATE TABLE IF NOT EXISTS accounts (
    user_id VARCHAR(36) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(254) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS account_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES accounts(user_id) ON DELETE CASCADE,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS account_session_owner ON account_sessions(user_id);
CREATE TABLE IF NOT EXISTS public_playlists (
    share_id VARCHAR(32) PRIMARY KEY,
    playlist_id VARCHAR(36) NOT NULL UNIQUE REFERENCES playlists(id) ON DELETE CASCADE,
    snapshot_json TEXT NOT NULL,
    published_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_favourites (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    share_id VARCHAR(32) NOT NULL REFERENCES public_playlists(share_id) ON DELETE CASCADE,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, share_id)
);
ALTER TABLE playlist_items ALTER COLUMN track_id DROP NOT NULL;
ALTER TABLE playlist_items ADD COLUMN IF NOT EXISTS external_key VARCHAR(64);
ALTER TABLE playlist_items ADD COLUMN IF NOT EXISTS external_source_key VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS playlist_external_identity ON playlist_items(playlist_id,external_key);
