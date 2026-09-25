package com.jobbuddy.backend.modules.chat.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jobbuddy.backend.modules.chat.entity.ChatAttachment;
import com.jobbuddy.backend.modules.chat.mapper.ChatAttachmentMapper;
import java.time.Instant;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 使用真实 PostgreSQL 验证 ORM 映射和附件绑定的原子前置条件。
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ChatAttachmentOrmTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void attachmentLifecyclePreservesOwnershipAndBinding() throws Exception {
    var dataSource =
        new PooledDataSource(
            "org.postgresql.Driver",
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    try {
      try (var connection = dataSource.getConnection();
          var schema =
              Resources.getResourceAsReader(
                  "db/migration/V1_0_16__Create_chat_attachment_schema.sql")) {
        var runner = new ScriptRunner(connection);
        runner.setLogWriter(null);
        runner.setStopOnError(true);
        runner.runScript(schema);
      }
      var configuration = new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      configuration.setEnvironment(
          new Environment("test", new JdbcTransactionFactory(), dataSource));
      configuration.addMapper(ChatAttachmentMapper.class);
      var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
      try (var session = factory.openSession(true)) {
        var mapper = session.getMapper(ChatAttachmentMapper.class);
        var attachment = attachment("first", "ready");
        assertEquals(1, mapper.insert(attachment));
        assertNull(mapper.findByOwner("other", "user", "first"));
        assertNull(mapper.findByOwner("tenant", "other", "first"));
        assertEquals("file'; --.pdf", mapper.findByOwner("tenant", "user", "first").getFileName());
        assertNull(mapper.findByOwner("tenant", "user", "first").getBoundAt());
        var boundAt = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(0, mapper.bindToTurn("other", "user", "first", "session", "turn", boundAt));
        assertEquals(1, mapper.bindToTurn("tenant", "user", "first", "session", "turn", boundAt));
        assertEquals(1, mapper.bindToTurn("tenant", "user", "first", "session", "turn", boundAt));
        assertEquals(0, mapper.bindToTurn("tenant", "user", "first", "session", "other", boundAt));
        assertEquals(boundAt, mapper.findByOwner("tenant", "user", "first").getBoundAt());
        assertEquals(0, mapper.deleteUnbound("tenant", "user", "first"));
        assertTrue(mapper.listBySession("tenant", "other", "session").isEmpty());
        assertEquals(1, mapper.listBySession("tenant", "user", "session").size());
        assertEquals(0, mapper.deleteBySession("other", "user", "session"));
        assertEquals(1, mapper.deleteBySession("tenant", "user", "session"));

        assertEquals(1, mapper.insert(attachment("failed", "failed")));
        assertEquals(0, mapper.bindToTurn("tenant", "user", "failed", "session", "turn", boundAt));
        assertEquals(0, mapper.deleteUnbound("tenant", "other", "failed"));
        assertEquals(1, mapper.deleteUnbound("tenant", "user", "failed"));
        assertNull(mapper.findByOwner("tenant", "user", "failed"));
      }
    } finally {
      dataSource.forceCloseAll();
    }
  }

  private ChatAttachment attachment(String id, String status) {
    var attachment = new ChatAttachment();
    attachment.setAttachmentId(id);
    attachment.setTenantId("tenant");
    attachment.setUserId("user");
    attachment.setFileName("file'; --.pdf");
    attachment.setContentType("application/pdf");
    attachment.setSuffix("pdf");
    attachment.setStoragePath("test/" + id);
    attachment.setSizeBytes(20L);
    attachment.setSha256("test-digest");
    attachment.setParseStatus(status);
    attachment.setCharacterCount(0);
    attachment.setTruncated(false);
    attachment.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return attachment;
  }
}
