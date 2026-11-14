CREATE TABLE knowledge.datasets (
    version VARCHAR(80) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    source_snapshot VARCHAR(71) NOT NULL,
    materials_count INTEGER NOT NULL CHECK (materials_count > 0),
    chunks_count INTEGER NOT NULL CHECK (chunks_count > 0),
    materials_sha256 CHAR(64) NOT NULL,
    chunks_sha256 CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE knowledge.materials (
    dataset_version VARCHAR(80) NOT NULL REFERENCES knowledge.datasets(version),
    id UUID NOT NULL,
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN (
        'ARTICLE', 'COURSE', 'LESSON', 'DOCUMENT', 'WEBINAR', 'PODCAST', 'GLOSSARY', 'CALCULATOR'
    )),
    source_id UUID NOT NULL,
    title VARCHAR(250) NOT NULL,
    description VARCHAR(1000),
    url VARCHAR(2000) NOT NULL CHECK (url LIKE 'https://%'),
    is_paid BOOLEAN NOT NULL,
    source_cost BIGINT CHECK (source_cost >= 0),
    answer_allowed BOOLEAN NOT NULL,
    external_metadata_allowed BOOLEAN NOT NULL,
    duration VARCHAR(100),
    topics JSONB NOT NULL,
    industries JSONB NOT NULL,
    parent_material_ids JSONB NOT NULL,
    source_updated_at TIMESTAMPTZ,
    PRIMARY KEY (dataset_version, id),
    UNIQUE (dataset_version, source_type, source_id),
    CHECK (NOT is_paid OR NOT answer_allowed)
);

CREATE TABLE knowledge.chunks (
    dataset_version VARCHAR(80) NOT NULL,
    id UUID NOT NULL,
    material_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position > 0),
    heading VARCHAR(250),
    text TEXT NOT NULL CHECK (char_length(text) BETWEEN 30 AND 6000),
    content_hash CHAR(64) NOT NULL,
    external_text_allowed BOOLEAN NOT NULL,
    PRIMARY KEY (dataset_version, id),
    UNIQUE (dataset_version, material_id, position),
    FOREIGN KEY (dataset_version, material_id) REFERENCES knowledge.materials(dataset_version, id)
);

CREATE TABLE knowledge.search_indexes (
    id UUID PRIMARY KEY,
    dataset_version VARCHAR(80) NOT NULL REFERENCES knowledge.datasets(version),
    model_name VARCHAR(100) NOT NULL,
    model_revision VARCHAR(100) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions = 768),
    format_version VARCHAR(40) NOT NULL,
    vector_count INTEGER NOT NULL CHECK (vector_count > 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (dataset_version, model_name, model_revision, format_version),
    UNIQUE (id, dataset_version)
);

CREATE TABLE knowledge.search_vectors (
    index_id UUID NOT NULL,
    dataset_version VARCHAR(80) NOT NULL,
    document_id UUID NOT NULL,
    material_id UUID NOT NULL,
    chunk_id UUID,
    embedding public.vector(768) NOT NULL,
    PRIMARY KEY (index_id, document_id),
    FOREIGN KEY (index_id, dataset_version) REFERENCES knowledge.search_indexes(id, dataset_version),
    FOREIGN KEY (dataset_version, material_id) REFERENCES knowledge.materials(dataset_version, id),
    FOREIGN KEY (dataset_version, chunk_id) REFERENCES knowledge.chunks(dataset_version, id)
);

CREATE TABLE knowledge.catalog_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    active_index_id UUID NOT NULL REFERENCES knowledge.search_indexes(id)
);
