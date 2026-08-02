ALTER TABLE interview_question
  ADD COLUMN user_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_tenant_user
  ON app_user (tenant_id, user_id);

ALTER TABLE interview_question
  ADD CONSTRAINT fk_interview_question_owner
  FOREIGN KEY (tenant_id, user_id)
  REFERENCES app_user (tenant_id, user_id);

ALTER TABLE interview_question
  ADD CONSTRAINT ck_interview_question_user_required
  CHECK (user_id IS NOT NULL) NOT VALID;

CREATE INDEX idx_interview_question_owner_updated
  ON interview_question (tenant_id, user_id, updated_at DESC);
