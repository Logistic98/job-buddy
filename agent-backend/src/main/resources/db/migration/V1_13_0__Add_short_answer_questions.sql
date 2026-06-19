-- Add 八股文简答题 seed questions for mock practice.

INSERT INTO interview_question (
  question_id, bank_type, title, category, difficulty, question_type, content, answer, tags_json, coding_meta_json, enabled, updated_at
) VALUES
(
  'seed_shortanswer_hashmap_principle', 'baguwen', '简述 HashMap 的底层实现原理', 'Java 基础', '中等', '简答',
  $$请简述 JDK 8 中 HashMap 的底层数据结构、put 流程和扩容机制。$$,
  $$数组加链表加红黑树；put 时先计算 hash 定位桶；链表长度超过 8 且容量达到 64 时树化；扩容为原容量 2 倍；元素按高位 bit 拆分到原索引或原索引加旧容量$$,
  '[{"label":"Java"},{"label":"HashMap"},{"label":"简答"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_shortanswer_spring_ioc', 'baguwen', '简述 Spring IoC 容器的核心机制', 'Spring', '中等', '简答',
  $$请简述 Spring IoC 容器的职责、Bean 生命周期的关键阶段以及依赖注入的常见方式。$$,
  $$IoC 容器负责对象创建和依赖管理；Bean 生命周期包括实例化、属性填充、初始化、销毁；依赖注入方式有构造器注入、Setter 注入、字段注入$$,
  '[{"label":"Spring"},{"label":"IoC"},{"label":"简答"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_shortanswer_mysql_transaction', 'baguwen', '简述 MySQL 事务隔离级别', 'MySQL', '中等', '简答',
  $$请简述 MySQL InnoDB 支持的四种事务隔离级别以及各自解决的并发问题。$$,
  $$读未提交存在脏读；读已提交解决脏读；可重复读解决不可重复读，InnoDB 通过 MVCC 和间隙锁基本避免幻读；串行化解决幻读但并发最低$$,
  '[{"label":"MySQL"},{"label":"事务"},{"label":"简答"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_shortanswer_redis_persistence', 'baguwen', '简述 Redis 持久化方案', 'Redis', '中等', '简答',
  $$请简述 Redis 的 RDB 与 AOF 两种持久化机制的原理和优缺点。$$,
  $$RDB 定时生成内存快照，恢复快但可能丢失最近数据；AOF 追加写命令日志，数据更安全但文件更大恢复更慢；生产常用 RDB 加 AOF 混合持久化$$,
  '[{"label":"Redis"},{"label":"持久化"},{"label":"简答"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
)
ON CONFLICT (question_id) DO NOTHING;
