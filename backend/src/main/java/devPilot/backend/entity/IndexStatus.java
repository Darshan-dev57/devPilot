package devPilot.backend.entity;

/**
 * NOTE: Hibernate generates a CHECK constraint for these values when the table is
 * first created, but ddl-auto=update never widens it. If you add a value here,
 * existing databases need: {@code ALTER TABLE repositories DROP CONSTRAINT
 * repositories_index_status_check;} (fresh databases are unaffected).
 */
public enum IndexStatus {
    PENDING,
    INDEXING,
    PAUSED,
    READY,
    FAILED
}
