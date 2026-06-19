-- Extend interview bank into a unified written practice bank.
-- Supports two top-level bank types: LeetCode programming questions and 八股文选择题.

ALTER TABLE interview_question ADD COLUMN IF NOT EXISTS bank_type VARCHAR(32) NOT NULL DEFAULT 'baguwen';
ALTER TABLE interview_question ADD COLUMN IF NOT EXISTS coding_meta_json TEXT;

CREATE INDEX IF NOT EXISTS idx_interview_question_bank_type
  ON interview_question (bank_type, category, difficulty, question_type);

ALTER TABLE interview_exam ADD COLUMN IF NOT EXISTS duration_minutes INT DEFAULT 30;
ALTER TABLE interview_exam ADD COLUMN IF NOT EXISTS strategy_json TEXT;
ALTER TABLE interview_exam ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

UPDATE interview_question
SET bank_type = CASE
  WHEN question_type = '编程题' OR LOWER(COALESCE(category, '')) LIKE '%leetcode%' THEN 'leetcode'
  ELSE 'baguwen'
END
WHERE bank_type IS NULL OR bank_type = '';

UPDATE interview_exam
SET duration_minutes = COALESCE(duration_minutes, 30),
    expires_at = COALESCE(expires_at, started_at + (COALESCE(duration_minutes, 30) * INTERVAL '1 minute'))
WHERE expires_at IS NULL;

INSERT INTO interview_question (
  question_id, bank_type, title, category, difficulty, question_type, content, answer, tags_json, coding_meta_json, enabled, updated_at
) VALUES
(
  'seed_leetcode_two_sum', 'leetcode', '1. Two Sum', '数组与哈希表', '简单', '编程题',
  $$给定一个整数数组 nums 和一个整数 target，请返回两个数的下标，使得它们相加等于 target。你可以假设每组输入只对应一个答案，不能重复使用同一个元素。

示例：输入 nums = [2,7,11,15], target = 9，输出 [0,1]。$$,
  $$参考解法使用哈希表记录已遍历元素与下标，时间复杂度 O(n)，空间复杂度 O(n)。$$,
  '[{"label":"LeetCode"},{"label":"数组"},{"label":"哈希表"}]',
  $$ {"language":"javascript","functionName":"twoSum","signature":"function twoSum(nums, target): number[]","template":"function twoSum(nums, target) {\n  const map = new Map()\n  for (let i = 0; i < nums.length; i++) {\n    const need = target - nums[i]\n    if (map.has(need)) return [map.get(need), i]\n    map.set(nums[i], i)\n  }\n  return []\n}","tests":[{"name":"示例 1","args":[[2,7,11,15],9],"expected":[0,1],"sample":true},{"name":"示例 2","args":[[3,2,4],6],"expected":[1,2],"sample":true},{"name":"重复元素","args":[[3,3],6],"expected":[0,1],"sample":false}]} $$,
  TRUE, CURRENT_TIMESTAMP
),
(
  'seed_leetcode_valid_parentheses', 'leetcode', '20. Valid Parentheses', '栈与字符串', '简单', '编程题',
  $$给定一个只包括 (、)、{、}、[、] 的字符串 s，判断字符串是否有效。左括号必须用相同类型的右括号闭合，并且以正确的顺序闭合。

示例：输入 s = "()[]{}"，输出 true。$$,
  $$参考解法使用栈保存左括号，遇到右括号时检查栈顶是否匹配。$$,
  '[{"label":"LeetCode"},{"label":"栈"},{"label":"字符串"}]',
  $$ {"language":"javascript","functionName":"isValid","signature":"function isValid(s): boolean","template":"function isValid(s) {\n  const pairs = { ')': '(', ']': '[', '}': '{' }\n  const stack = []\n  for (const ch of s) {\n    if (ch === '(' || ch === '[' || ch === '{') stack.push(ch)\n    else if (stack.pop() !== pairs[ch]) return false\n  }\n  return stack.length === 0\n}","tests":[{"name":"示例 1","args":["()"],"expected":true,"sample":true},{"name":"示例 2","args":["()[]{}"],"expected":true,"sample":true},{"name":"交叉括号","args":["(]"],"expected":false,"sample":false},{"name":"嵌套括号","args":["{[]}"],"expected":true,"sample":false}]} $$,
  TRUE, CURRENT_TIMESTAMP
),
(
  'seed_leetcode_merge_intervals', 'leetcode', '56. Merge Intervals', '数组与排序', '中等', '编程题',
  $$以数组 intervals 表示若干区间，其中 intervals[i] = [start, end]。请合并所有重叠区间，并返回一个不重叠的区间数组。

示例：输入 [[1,3],[2,6],[8,10],[15,18]]，输出 [[1,6],[8,10],[15,18]]。$$,
  $$参考解法先按左端点排序，再线性扫描并合并重叠区间。$$,
  '[{"label":"LeetCode"},{"label":"数组"},{"label":"排序"}]',
  $$ {"language":"javascript","functionName":"merge","signature":"function merge(intervals): number[][]","template":"function merge(intervals) {\n  intervals.sort((a, b) => a[0] - b[0])\n  const ans = []\n  for (const item of intervals) {\n    const last = ans[ans.length - 1]\n    if (!last || item[0] > last[1]) ans.push([...item])\n    else last[1] = Math.max(last[1], item[1])\n  }\n  return ans\n}","tests":[{"name":"示例 1","args":[[[1,3],[2,6],[8,10],[15,18]]],"expected":[[1,6],[8,10],[15,18]],"sample":true},{"name":"首尾相接","args":[[[1,4],[4,5]]],"expected":[[1,5]],"sample":true},{"name":"无需合并","args":[[[1,2],[3,4],[5,6]]],"expected":[[1,2],[3,4],[5,6]],"sample":false}]} $$,
  TRUE, CURRENT_TIMESTAMP
),
(
  'seed_leetcode_level_order', 'leetcode', '102. Binary Tree Level Order Traversal', '二叉树与 BFS', '中等', '编程题',
  $$给定一个二叉树的根节点 root，返回其节点值的层序遍历结果，即逐层从左到右访问所有节点。

节点结构为 { val, left, right }。$$,
  $$参考解法使用队列进行 BFS，每一轮按当前队列长度取出一层节点。$$,
  '[{"label":"LeetCode"},{"label":"二叉树"},{"label":"BFS"}]',
  $$ {"language":"javascript","functionName":"levelOrder","signature":"function levelOrder(root): number[][]","template":"function levelOrder(root) {\n  if (!root) return []\n  const ans = []\n  const queue = [root]\n  while (queue.length) {\n    const size = queue.length\n    const level = []\n    for (let i = 0; i < size; i++) {\n      const node = queue.shift()\n      level.push(node.val)\n      if (node.left) queue.push(node.left)\n      if (node.right) queue.push(node.right)\n    }\n    ans.push(level)\n  }\n  return ans\n}","tests":[{"name":"三层树","args":[{"val":3,"left":{"val":9,"left":null,"right":null},"right":{"val":20,"left":{"val":15,"left":null,"right":null},"right":{"val":7,"left":null,"right":null}}}],"expected":[[3],[9,20],[15,7]],"sample":true},{"name":"空树","args":[null],"expected":[],"sample":true},{"name":"单节点","args":[{"val":1,"left":null,"right":null}],"expected":[[1]],"sample":false}]} $$,
  TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_hashmap_resize', 'baguwen', 'HashMap 扩容机制判断', 'Java 基础', '中等', '单选',
  $$关于 JDK 8 HashMap 扩容机制，下列说法正确的是哪一项？

A. 扩容后容量通常变为原来的 2 倍，元素会根据高位 bit 判断是否留在原索引或移动到原索引 + oldCap
B. 扩容后所有元素都必须重新计算完整 hash 并进行全量排序
C. HashMap 默认负载因子为 1.0，只有满表才扩容
D. 红黑树节点扩容时一定会退化为链表，且无法再树化$$,
  'A',
  '[{"label":"Java"},{"label":"HashMap"},{"label":"集合"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_jvm_gc_roots', 'baguwen', 'JVM GC Roots 选择', 'JVM', '中等', '多选',
  $$下列哪些对象通常可以作为 JVM GC Roots？

A. 虚拟机栈中引用的对象
B. 方法区中类静态属性引用的对象
C. 本地方法栈中 JNI 引用的对象
D. 任意两个普通对象之间互相引用后都自动成为 GC Roots$$,
  'A,B,C',
  '[{"label":"JVM"},{"label":"GC"},{"label":"内存管理"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_spring_tx', 'baguwen', 'Spring 事务传播行为', 'Spring', '中等', '单选',
  $$关于 Spring 事务传播行为 REQUIRED，下列说法正确的是哪一项？

A. 当前存在事务时加入当前事务，不存在事务时新建事务
B. 无论当前是否存在事务都一定新建一个独立事务
C. 当前存在事务时挂起当前事务并以非事务方式执行
D. 只能用于只读查询，不能用于写操作$$,
  'A',
  '[{"label":"Spring"},{"label":"事务"},{"label":"传播行为"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_mysql_index', 'baguwen', 'MySQL 联合索引最左前缀', 'MySQL', '中等', '单选',
  $$表存在联合索引 idx(a,b,c)，下列哪种查询最符合最左前缀原则并更可能充分利用该索引？

A. WHERE a = 1 AND b = 2
B. WHERE b = 2 AND c = 3
C. WHERE c = 3
D. WHERE b LIKE 'x%'$$,
  'A',
  '[{"label":"MySQL"},{"label":"索引"},{"label":"SQL优化"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_redis_cache', 'baguwen', 'Redis 缓存穿透治理', 'Redis', '中等', '多选',
  $$面对缓存穿透问题，下列哪些方案通常有效？

A. 对不存在的数据缓存空值并设置较短过期时间
B. 使用布隆过滤器拦截明显不存在的 key
C. 所有 key 永不过期即可彻底解决穿透
D. 对热点 key 采用互斥锁或逻辑过期来减少重建风暴$$,
  'A,B',
  '[{"label":"Redis"},{"label":"缓存"},{"label":"高并发"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_thread_pool', 'baguwen', '线程池参数理解', 'Java 并发', '中等', '多选',
  $$关于 Java ThreadPoolExecutor，下列说法正确的是哪些？

A. corePoolSize 表示核心线程数
B. workQueue 用于保存等待执行的任务
C. maximumPoolSize 在任何队列配置下都一定会被优先用满
D. RejectedExecutionHandler 用于处理无法接收的新任务$$,
  'A,B,D',
  '[{"label":"Java"},{"label":"并发"},{"label":"线程池"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_kafka_offset', 'baguwen', 'Kafka Offset 提交语义', 'Kafka', '中等', '单选',
  $$关于 Kafka 消费者 offset，下列说法正确的是哪一项？

A. offset 记录消费者在分区中的消费进度，提交时机影响重复消费或消息丢失风险
B. offset 只由 broker 自动提交，业务代码无法控制
C. offset 与 consumer group 无关，只与 topic 有关
D. 手动提交 offset 一定可以保证 exactly-once 处理语义$$,
  'A',
  '[{"label":"Kafka"},{"label":"消息队列"},{"label":"消费语义"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
),
(
  'seed_baguwen_distributed_lock', 'baguwen', '分布式锁风险边界', '分布式', '困难', '多选',
  $$设计 Redis 分布式锁时，下列哪些点需要重点关注？

A. 加锁需要设置唯一 value，释放时校验 value 再删除
B. 锁必须设置过期时间，避免持锁进程异常导致死锁
C. 业务执行时间可能超过锁 TTL，需要考虑续期或幂等补偿
D. 只要使用 SETNX 就天然具备强一致和可重入能力$$,
  'A,B,C',
  '[{"label":"Redis"},{"label":"分布式锁"},{"label":"可靠性"}]',
  NULL, TRUE, CURRENT_TIMESTAMP
)
ON CONFLICT (question_id) DO NOTHING;
