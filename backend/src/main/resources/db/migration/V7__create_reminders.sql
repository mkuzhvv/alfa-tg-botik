CREATE TABLE app.reminders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL CHECK (btrim(title) <> ''),
    schedule JSONB NOT NULL CHECK (schedule->>'type' IN ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    next_fire_at TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ,
    completion_reason VARCHAR(24) CHECK (completion_reason IN ('DELIVERED', 'EXPIRED', 'DELIVERY_UNKNOWN')),
    material_id UUID,
    dataset_version VARCHAR(80),
    plan_step_id UUID REFERENCES app.plan_steps(id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    FOREIGN KEY (dataset_version, material_id) REFERENCES knowledge.materials(dataset_version, id),
    CHECK ((material_id IS NULL) = (dataset_version IS NULL)),
    CHECK ((status = 'ACTIVE') = (next_fire_at IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (completion_reason IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR schedule->>'type' = 'ONCE')
);

CREATE INDEX reminders_user_created_idx ON app.reminders (user_id, created_at DESC, id DESC);
CREATE INDEX reminders_due_idx ON app.reminders (next_fire_at) WHERE status = 'ACTIVE';

CREATE TABLE app.reminder_create_requests (
    user_id UUID NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    request_body JSONB NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, request_key)
);

CREATE TABLE app.reminder_deliveries (
    id UUID PRIMARY KEY,
    reminder_id UUID NOT NULL REFERENCES app.reminders(id) ON DELETE CASCADE,
    reminder_version BIGINT NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'DELIVERED', 'UNKNOWN', 'FAILED', 'SKIPPED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    retry_at TIMESTAMPTZ,
    error_code VARCHAR(32),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX reminder_delivery_pending_idx ON app.reminder_deliveries (reminder_id)
    WHERE status IN ('PENDING', 'SENDING');
CREATE INDEX reminder_delivery_retry_idx ON app.reminder_deliveries (retry_at) WHERE status = 'PENDING';
