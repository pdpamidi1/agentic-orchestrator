-- V1: urls table + fallback counter sequence (Design: data_model.urls, url_code_seq; ADR-002, ADR-004).
-- Forward-only Flyway migration. Never edit once integration tests have run against it; add V2+ instead.

CREATE TABLE urls (
    short_code  varchar(32)   NOT NULL,
    long_url    varchar(2048) NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    expires_at  timestamptz   NULL,
    created_by  varchar(255)  NULL,
    code_source varchar(16)   NOT NULL,
    CONSTRAINT pk_urls PRIMARY KEY (short_code),
    CONSTRAINT urls_code_source_chk CHECK (code_source IN ('redis', 'db_sequence')),
    CONSTRAINT urls_short_code_format_chk CHECK (short_code ~ '^[0-9a-zA-Z]{3,32}$'),
    CONSTRAINT urls_long_url_len_chk CHECK (char_length(long_url) BETWEEN 1 AND 2048)
);

-- Supports expiry lookups / sweeps; rows that never expire are not indexed.
CREATE INDEX idx_urls_expires_at ON urls (expires_at) WHERE expires_at IS NOT NULL;

-- Redis-outage fallback counter (AC-11). Seeded at 62^3 = 238328 (AMB-11 / ADR-004) so that even a
-- raw sequence value encodes to a base62 code of at least three characters; the allocator additionally
-- adds shortener.counter-seed-offset before encoding. Values are never reused; gaps are acceptable.
CREATE SEQUENCE url_code_seq START WITH 238328 INCREMENT BY 1 NO CYCLE CACHE 1;

COMMENT ON COLUMN urls.created_by IS 'Reserved for a future auth scenario; left NULL in this release';
COMMENT ON COLUMN urls.code_source IS 'redis = batched Redis INCRBY counter, db_sequence = url_code_seq fallback';
COMMENT ON SEQUENCE url_code_seq IS 'Fallback short-code counter, seeded at 62^3 so codes are >= 3 chars';
