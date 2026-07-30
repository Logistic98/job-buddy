package com.jobbuddy.backend.modules.chat.service.impl;

/**
 * 聊天显式记忆操作的确定性结果。
 *
 * @param action 操作类型
 * @param success 是否执行成功
 * @param summary 过程摘要
 * @param assistantMessage 面向用户的最终说明
 */
record ChatMemoryMutationResult(
    String action, boolean success, String summary, String assistantMessage) {}
