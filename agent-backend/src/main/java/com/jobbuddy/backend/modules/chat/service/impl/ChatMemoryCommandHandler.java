package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 处理用户明确发出的长期记忆新增、更新与删除命令。
 *
 * <p>该处理器只接受窄范围、可确定执行的表达；普通问答继续交给 Runtime，避免模型口头确认后没有真正修改记忆。
 */
class ChatMemoryCommandHandler {
  private static final Pattern NAME_DELETE_PATTERN =
      Pattern.compile(
          "^(?:请)?(?:(?:忘记|删除|清除|移除)(?:掉)?(?:关于)?我的(?:名字|姓名|称呼)"
              + "|忘记(?:掉)?我是谁)(?:这条)?(?:记忆)?[。！!]*$");
  private static final Pattern NAME_UPDATE_PATTERN =
      Pattern.compile(
          "^(?:请)?(?:(?:把|将)?我的(?:名字|姓名|称呼)(?:从.+?)?"
              + "(?:改成|改为|更新为|换成|设为|设置为)"
              + "|更新我的(?:名字|姓名|称呼)(?:为|成)"
              + "|(?:我(?:其实|实际(?:上)?|真正)|(?:其实|实际(?:上)?|真正)我)"
              + "(?:的(?:名字|姓名))?(?:是|叫))\\s*(.+?)[。！!]*$");
  private static final Pattern NAME_UPSERT_PATTERN =
      Pattern.compile(
          "^(?:请)?(?:(?:记住[\\s，,：:]*)?我叫"
              + "|(?:记住[\\s，,：:]*)?我的(?:名字|姓名)(?:是|叫)"
              + "|(?:以后|今后)(?:请)?叫我"
              + "|请叫我"
              + "|称呼我为)\\s*(.+?)[。！!]*$");
  private static final Pattern EXPLICIT_CREATE_PATTERN =
      Pattern.compile("^(?:请)?(?:记住|帮我记住|替我记住|记一下|记下来)[\\s，,：:]*(.+?)[。！!]*$");
  private static final Pattern EXACT_DELETE_PATTERN =
      Pattern.compile("^(?:请)?(?:删除|忘记|清除|移除)(?:这条)?记忆[\\s，,：:]*(.+?)[。！!]*$");
  private static final Pattern QUOTED_UPDATE_PATTERN =
      Pattern.compile("^(?:请)?(?:把|将)?记忆[“\"](.+?)[”\"](?:改成|改为|更新为)[“\"](.+?)[”\"][。！!]*$");
  private static final Pattern IDENTITY_MEMORY_PATTERN =
      Pattern.compile(
          "(?:^|[\\s，,。.!！？?；;：:])(?:我叫|我的(?:名字|姓名)(?:是|叫)"
              + "|(?:以后|今后)(?:请)?叫我|请称呼我为|称呼我为)(?=[\\p{L}\\p{N}_])");

  private final SystemSettingsService settingsService;

  ChatMemoryCommandHandler(SystemSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  Optional<ChatMemoryMutationResult> handle(String tenantId, String userId, String rawMessage) {
    Optional<MemoryCommand> parsed = parse(rawMessage);
    if (parsed.isEmpty()) return Optional.empty();
    MemoryCommand command = parsed.get();
    return Optional.of(
        switch (command.action()) {
          case CREATE -> create(tenantId, userId, command.content());
          case UPDATE_NAME -> updateName(tenantId, userId, command.content());
          case UPDATE_EXACT -> updateExact(tenantId, userId, command.selector(), command.content());
          case DELETE_NAME -> deleteName(tenantId, userId);
          case DELETE_EXACT -> deleteExact(tenantId, userId, command.selector());
        });
  }

  private ChatMemoryMutationResult create(String tenantId, String userId, String content) {
    settingsService.addMemory(tenantId, userId, request(content));
    return success("create", "长期记忆已新增。", "已记住：" + content);
  }

  private ChatMemoryMutationResult updateName(String tenantId, String userId, String content) {
    List<SystemMemoryResponse> matches = identityMemories(tenantId, userId);
    if (matches.size() > 1) return ambiguous("update", "姓名");
    if (matches.isEmpty()) {
      settingsService.addMemory(tenantId, userId, request(content));
      return success("create", "姓名记忆不存在，已新增。", "已记住：" + content);
    }
    settingsService.updateMemory(tenantId, userId, matches.get(0).getId(), request(content));
    return success("update", "姓名记忆已更新。", "已更新长期记忆：" + content);
  }

  private ChatMemoryMutationResult updateExact(
      String tenantId, String userId, String selector, String content) {
    List<SystemMemoryResponse> matches = exactMemories(tenantId, userId, selector);
    if (matches.size() > 1) return ambiguous("update", selector);
    if (matches.isEmpty()) return notFound("update", selector);
    settingsService.updateMemory(tenantId, userId, matches.get(0).getId(), request(content));
    return success("update", "长期记忆已更新。", "已把“" + selector + "”更新为“" + content + "”。");
  }

  private ChatMemoryMutationResult deleteName(String tenantId, String userId) {
    List<SystemMemoryResponse> matches = identityMemories(tenantId, userId);
    if (matches.size() > 1) return ambiguous("delete", "姓名");
    if (matches.isEmpty()) return notFound("delete", "姓名");
    settingsService.deleteMemory(tenantId, userId, matches.get(0).getId());
    return success("delete", "姓名记忆已删除。", "已删除关于你姓名的长期记忆。");
  }

  private ChatMemoryMutationResult deleteExact(String tenantId, String userId, String selector) {
    List<SystemMemoryResponse> matches = exactMemories(tenantId, userId, selector);
    if (matches.size() > 1) return ambiguous("delete", selector);
    if (matches.isEmpty()) return notFound("delete", selector);
    settingsService.deleteMemory(tenantId, userId, matches.get(0).getId());
    return success("delete", "长期记忆已删除。", "已删除长期记忆：“" + selector + "”。");
  }

  private List<SystemMemoryResponse> identityMemories(String tenantId, String userId) {
    List<SystemMemoryResponse> result = new ArrayList<SystemMemoryResponse>();
    for (SystemMemoryResponse item : settingsService.listMemories(tenantId, userId)) {
      if (item != null
          && IDENTITY_MEMORY_PATTERN.matcher(stripSavePrefix(item.getContent())).find()) {
        result.add(item);
      }
    }
    return result;
  }

  private List<SystemMemoryResponse> exactMemories(
      String tenantId, String userId, String selector) {
    String expected = memoryKey(selector);
    List<SystemMemoryResponse> result = new ArrayList<SystemMemoryResponse>();
    for (SystemMemoryResponse item : settingsService.listMemories(tenantId, userId)) {
      if (item != null && expected.equals(memoryKey(stripSavePrefix(item.getContent())))) {
        result.add(item);
      }
    }
    return result;
  }

  private Optional<MemoryCommand> parse(String rawMessage) {
    String message = safe(rawMessage).trim();
    if (message.isEmpty()) return Optional.empty();
    if (NAME_DELETE_PATTERN.matcher(message).matches()) {
      return Optional.of(new MemoryCommand(Action.DELETE_NAME, "", ""));
    }
    Optional<String> name = capture(NAME_UPDATE_PATTERN, message, 1);
    if (name.isPresent()) {
      if (!validName(name.get())) return Optional.empty();
      return Optional.of(new MemoryCommand(Action.UPDATE_NAME, "姓名", identityContent(name.get())));
    }
    name = capture(NAME_UPSERT_PATTERN, message, 1);
    if (name.isPresent() && validName(name.get())) {
      return Optional.of(new MemoryCommand(Action.UPDATE_NAME, "姓名", identityContent(name.get())));
    }
    Matcher quotedUpdate = QUOTED_UPDATE_PATTERN.matcher(message);
    if (quotedUpdate.matches()) {
      return Optional.of(
          new MemoryCommand(
              Action.UPDATE_EXACT, clean(quotedUpdate.group(1)), clean(quotedUpdate.group(2))));
    }
    Optional<String> deleted = capture(EXACT_DELETE_PATTERN, message, 1);
    if (deleted.isPresent()) {
      return Optional.of(new MemoryCommand(Action.DELETE_EXACT, deleted.get(), ""));
    }
    Optional<String> created = capture(EXPLICIT_CREATE_PATTERN, message, 1);
    if (created.isPresent()) {
      return Optional.of(new MemoryCommand(Action.CREATE, "", created.get()));
    }
    return Optional.empty();
  }

  private Optional<String> capture(Pattern pattern, String message, int group) {
    Matcher matcher = pattern.matcher(message);
    if (!matcher.matches()) return Optional.empty();
    String value = clean(matcher.group(group));
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  private boolean validName(String value) {
    String normalized = clean(value);
    return !normalized.isEmpty()
        && normalized.length() <= 40
        && !normalized.matches(".*[，,。.!！？?；;：:、\\r\\n].*")
        && !normalized.matches(".*(?:什么|怎么|啥|谁|哪位|吗|呢|[?？]).*");
  }

  private String identityContent(String name) {
    return "我的名字是" + clean(name);
  }

  private SystemMemoryRequest request(String content) {
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setContent(clean(content));
    request.setSource("chat");
    request.setEnabled(Boolean.TRUE);
    return request;
  }

  private ChatMemoryMutationResult success(String action, String summary, String message) {
    return new ChatMemoryMutationResult(action, true, summary, message);
  }

  private ChatMemoryMutationResult notFound(String action, String target) {
    return new ChatMemoryMutationResult(
        action, false, "未找到可操作的长期记忆。", "没有找到关于“" + target + "”的长期记忆，因此未做修改。");
  }

  private ChatMemoryMutationResult ambiguous(String action, String target) {
    return new ChatMemoryMutationResult(
        action,
        false,
        "匹配到多条长期记忆，需要明确目标。",
        "找到了多条关于“" + target + "”的长期记忆。为避免误操作，请在记忆管理中选择具体记录，或明确说出原记忆内容。");
  }

  private String stripSavePrefix(String value) {
    return safe(value).replaceFirst("^(?:请)?(?:记住|帮我记住|替我记住)[\\s，,：:]*", "");
  }

  private String memoryKey(String value) {
    return clean(value)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s　]+", "")
        .replace('，', ',')
        .replace('。', '.');
  }

  private String clean(String value) {
    return safe(value).trim().replaceFirst("^[：:]\\s*", "").replaceFirst("\\s*[。！!]$", "");
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private enum Action {
    CREATE,
    UPDATE_NAME,
    UPDATE_EXACT,
    DELETE_NAME,
    DELETE_EXACT
  }

  private record MemoryCommand(Action action, String selector, String content) {}
}
