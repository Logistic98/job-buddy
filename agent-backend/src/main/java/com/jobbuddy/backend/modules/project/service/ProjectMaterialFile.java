package com.jobbuddy.backend.modules.project.service;

import java.io.InputStream;

/** Authorized project material stream and response metadata. */
public record ProjectMaterialFile(
    String fileName, String contentType, long sizeBytes, InputStream inputStream) {}
