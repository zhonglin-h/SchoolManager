ALTER TABLE join_attempt_log
    DROP CONSTRAINT IF EXISTS uk_join_attempt_log_event_date_trigger;

ALTER TABLE join_attempt_log
    ALTER COLUMN date DROP NOT NULL;

ALTER TABLE join_attempt_log
    ALTER COLUMN date SET DEFAULT CURRENT_DATE;
