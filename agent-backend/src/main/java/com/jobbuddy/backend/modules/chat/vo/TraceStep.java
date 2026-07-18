package com.jobbuddy.backend.modules.chat.vo;

public class TraceStep {
  private String nodeId;
  private String name;
  private String status;
  private String detail;

  public TraceStep() {}

  public TraceStep(String nodeId, String name, String status, String detail) {
    this.nodeId = nodeId;
    this.name = name;
    this.status = status;
    this.detail = detail;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getName() {
    return name;
  }

  public String getStatus() {
    return status;
  }

  public String getDetail() {
    return detail;
  }
}
