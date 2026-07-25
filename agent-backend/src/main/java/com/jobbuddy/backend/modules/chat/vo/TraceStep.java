package com.jobbuddy.backend.modules.chat.vo;

/**
 * 定义 Trace 步骤。
 */
public class TraceStep {
  private String nodeId;
  private String name;
  private String status;
  private String detail;

  /**
   * 创建 Trace 步骤实例。
   */
  public TraceStep() {}

  /**
   * 创建 Trace 步骤实例。
   *
   * @param nodeId 节点标识
   * @param name 名称
   * @param status 状态
   * @param detail 详情
   */
  public TraceStep(String nodeId, String name, String status, String detail) {
    this.nodeId = nodeId;
    this.name = name;
    this.status = status;
    this.detail = detail;
  }

  /**
   * 获取节点标识。
   *
   * @return 节点标识
   */
  public String getNodeId() {
    return nodeId;
  }

  /**
   * 获取名称。
   *
   * @return 名称
   */
  public String getName() {
    return name;
  }

  /**
   * 获取状态。
   *
   * @return 状态
   */
  public String getStatus() {
    return status;
  }

  /**
   * 获取详情。
   *
   * @return 详情
   */
  public String getDetail() {
    return detail;
  }
}
