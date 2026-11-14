CREATE TABLE app.telegram_updates (
    id UUID PRIMARY KEY,
    bot_id BIGINT NOT NULL CHECK (bot_id > 0),
    update_id INTEGER NOT NULL CHECK (update_id >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'PROCESSING', 'PENDING', 'SENDING', 'DONE', 'FAILED', 'DELIVERY_UNKNOWN', 'IGNORED'
    )),
    chat_id BIGINT CHECK (chat_id > 0),
    response_text TEXT,
    help_button BOOLEAN NOT NULL DEFAULT FALSE,
    callback_query_id TEXT,
    callback_text VARCHAR(200),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    UNIQUE (bot_id, update_id),
    CHECK ((chat_id IS NULL) = (response_text IS NULL)),
    CHECK (NOT help_button OR chat_id IS NOT NULL),
    CHECK (callback_text IS NULL OR callback_query_id IS NOT NULL),
    CHECK ((status = 'PROCESSING') = (processed_at IS NULL))
);

CREATE INDEX telegram_updates_pending_idx
    ON app.telegram_updates (bot_id, next_attempt_at, update_id)
    WHERE status = 'PENDING';
