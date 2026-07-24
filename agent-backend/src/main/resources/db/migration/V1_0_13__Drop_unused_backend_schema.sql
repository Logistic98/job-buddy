DROP TABLE profile_document;

DROP TABLE user_permission;

ALTER TABLE user_login_session
  DROP COLUMN last_seen_at;

ALTER TABLE job_favorite
  DROP CONSTRAINT job_favorite_pkey;

DROP INDEX uk_job_favorite_user_key;

ALTER TABLE job_favorite
  DROP COLUMN favorite_id,
  ADD CONSTRAINT pk_job_favorite PRIMARY KEY (user_id, job_key);

ALTER TABLE interview_exam_question
  DROP COLUMN evaluated_at;
