CREATE TABLE app.conversations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES app.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE app.conversation_turns (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES app.conversations(id) ON DELETE CASCADE,
    position BIGINT GENERATED ALWAYS AS IDENTITY,
    user_text TEXT NOT NULL CHECK (char_length(user_text) BETWEEN 1 AND 4000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    assistant_text TEXT,
    response JSONB,
    error_code VARCHAR(40),
    model_name VARCHAR(100),
    model_revision VARCHAR(80),
    prompt_version VARCHAR(40),
    dataset_version VARCHAR(80) REFERENCES knowledge.datasets(version),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK ((status = 'PROCESSING') = (completed_at IS NULL)),
    CHECK ((status = 'PROCESSING') = (assistant_text IS NULL)),
    CHECK ((status = 'COMPLETED') = (response IS NOT NULL))
);

CREATE INDEX conversation_turns_history_idx ON app.conversation_turns (conversation_id, position DESC);
