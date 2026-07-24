ALTER TABLE chat_message_log
  ADD COLUMN turn_id VARCHAR(128);

CREATE UNIQUE INDEX uk_chat_message_user_turn
  ON chat_message_log (tenant_id, user_id, session_id, turn_id)
  WHERE role = 'user' AND turn_id IS NOT NULL;
