ALTER TABLE app.telegram_updates ADD COLUMN reply_buttons JSONB NOT NULL DEFAULT '[]';

CREATE TABLE app.action_proposals (
    id UUID PRIMARY KEY REFERENCES app.conversation_turns(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PLAN', 'REMINDER')),
    body JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'AWAITING_REPLACEMENT', 'CONFIRMED', 'CANCELLED')),
    replacement_id UUID,
    replacement_version BIGINT,
    result JSONB,
    result_text TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((replacement_id IS NULL) = (replacement_version IS NULL)),
    CHECK (status <> 'CONFIRMED' OR (result IS NOT NULL AND result_text IS NOT NULL))
);

CREATE INDEX action_proposals_user_idx ON app.action_proposals (user_id, id);

CREATE TABLE app.plan_generation_requests (
    user_id UUID NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    request_body JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    run_id UUID NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    response JSONB,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, request_key),
    CHECK ((status = 'SUCCEEDED') = (response IS NOT NULL))
);
