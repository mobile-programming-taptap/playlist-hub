CREATE TABLE IF NOT EXISTS platform_connections (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    token_json TEXT NOT NULL,
    PRIMARY KEY(user_id, provider)
);
CREATE TABLE IF NOT EXISTS platform_oauth_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    verifier TEXT NOT NULL,
    expires_at VARCHAR(40) NOT NULL,
    session_hash VARCHAR(64) NOT NULL
);
ALTER TABLE platform_oauth_states ADD COLUMN IF NOT EXISTS session_hash VARCHAR(64);
DELETE FROM platform_oauth_states WHERE session_hash IS NULL;
CREATE TABLE IF NOT EXISTS platform_documents (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL CHECK(CASE kind WHEN 'import' THEN 1 WHEN 'transfer' THEN 1 WHEN 'sync' THEN 1 ELSE 0 END=1),
    provider VARCHAR(20) NOT NULL,
    document_json TEXT NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    metadata_expires_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS platform_documents_owner ON platform_documents(user_id,kind);
