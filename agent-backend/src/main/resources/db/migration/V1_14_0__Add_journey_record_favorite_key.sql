ALTER TABLE IF EXISTS journey_record
  ADD COLUMN IF NOT EXISTS favorite_key VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_journey_record_user_favorite
  ON journey_record (user_id, favorite_key, updated_at DESC);
