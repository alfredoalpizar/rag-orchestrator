-- Conversations table
CREATE TABLE conversations (
    conversation_id VARCHAR(36) PRIMARY KEY,
    caller_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100),
    account_id VARCHAR(100),

    -- Timestamps
    created_at DATETIME NOT NULL DEFAULT GETDATE(),
    updated_at DATETIME NOT NULL DEFAULT GETDATE(),
    last_message_at DATETIME,

    -- Stats
    message_count INT DEFAULT 0,
    tool_calls_count INT DEFAULT 0,
    total_tokens INT DEFAULT 0,

    -- Status
    status VARCHAR(20) DEFAULT 'active', -- active, archived, deleted

    -- Storage
    s3_key VARCHAR(255), -- Where full conversation is in S3 (for Phase 4)

    -- Metadata (JSON as TEXT in Sybase)
    metadata TEXT
);

-- Indexes for fast lookups
CREATE INDEX idx_conversations_caller_id ON conversations(caller_id);
CREATE INDEX idx_conversations_user_id ON conversations(user_id);
CREATE INDEX idx_conversations_created_at ON conversations(created_at);
CREATE INDEX idx_conversations_status ON conversations(status);
CREATE INDEX idx_conversations_caller_created ON conversations(caller_id, created_at);

-- Conversation messages table
CREATE TABLE conversation_messages (
    message_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL, -- user, assistant, tool, system
    content TEXT NOT NULL,
    tool_call_id VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT GETDATE(),
    token_count INT,

    CONSTRAINT fk_message_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES conversations(conversation_id)
        ON DELETE CASCADE
);

-- Indexes for messages
CREATE INDEX idx_messages_conversation ON conversation_messages(conversation_id);
CREATE INDEX idx_messages_created_at ON conversation_messages(created_at);
CREATE INDEX idx_messages_role ON conversation_messages(role);
