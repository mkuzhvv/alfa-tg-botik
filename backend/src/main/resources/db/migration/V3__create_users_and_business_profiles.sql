CREATE TABLE app.users (
    id UUID PRIMARY KEY,
    telegram_user_id BIGINT NOT NULL UNIQUE CHECK (telegram_user_id > 0),
    display_name VARCHAR(160) CHECK (display_name IS NULL OR length(btrim(display_name)) > 0),
    telegram_chat_id BIGINT CHECK (telegram_chat_id IS NULL OR telegram_chat_id <> 0),
    can_receive_reminders BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (NOT can_receive_reminders OR telegram_chat_id IS NOT NULL)
);

CREATE TABLE app.business_profiles (
    user_id UUID PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
    business_stage VARCHAR(16) CHECK (business_stage IN ('IDEA', 'LAUNCH', 'OPERATING', 'GROWTH')),
    industry VARCHAR(100) CHECK (industry IS NULL OR length(btrim(industry)) > 0),
    experience VARCHAR(16) CHECK (experience IN ('BEGINNER', 'EXPERIENCED')),
    current_task VARCHAR(500) CHECK (current_task IS NULL OR length(btrim(current_task)) > 0),
    tax_regime VARCHAR(16) CHECK (tax_regime IN ('NPD', 'USN_6', 'USN_15', 'PATENT', 'OSNO', 'AUSN')),
    time_zone VARCHAR(64) CHECK (time_zone IS NULL OR length(btrim(time_zone)) > 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
