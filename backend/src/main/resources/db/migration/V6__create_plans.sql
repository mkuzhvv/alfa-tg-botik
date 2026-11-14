CREATE TABLE app.plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    goal VARCHAR(250) NOT NULL CHECK (btrim(goal) <> ''),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX plans_one_active_per_user_idx ON app.plans (user_id) WHERE status = 'ACTIVE';
CREATE INDEX plans_user_idx ON app.plans (user_id, id);

CREATE TABLE app.plan_steps (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES app.plans(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 5),
    title VARCHAR(160) NOT NULL CHECK (btrim(title) <> ''),
    description VARCHAR(600) NOT NULL CHECK (btrim(description) <> ''),
    status VARCHAR(8) NOT NULL CHECK (status IN ('TODO', 'DONE')),
    UNIQUE (plan_id, position)
);

CREATE TABLE app.plan_step_materials (
    step_id UUID NOT NULL REFERENCES app.plan_steps(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 2),
    material_id UUID NOT NULL,
    dataset_version VARCHAR(80) NOT NULL,
    recommendation_reason VARCHAR(300) CHECK (recommendation_reason IS NULL OR btrim(recommendation_reason) <> ''),
    PRIMARY KEY (step_id, material_id),
    UNIQUE (step_id, position),
    FOREIGN KEY (dataset_version, material_id) REFERENCES knowledge.materials(dataset_version, id)
);
