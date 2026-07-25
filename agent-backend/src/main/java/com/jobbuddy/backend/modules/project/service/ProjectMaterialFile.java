package com.jobbuddy.backend.modules.project.service;

import java.io.InputStream;

/**
 * 已授权的项目材料流及响应元数据。
 */
public record ProjectMaterialFile(
    String fileName, String contentType, long sizeBytes, InputStream inputStream) {}
