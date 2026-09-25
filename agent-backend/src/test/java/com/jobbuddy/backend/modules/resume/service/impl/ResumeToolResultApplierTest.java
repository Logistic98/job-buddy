package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResumeToolResultApplierTest {
  private final ResumeToolResultApplier applier = new ResumeToolResultApplier();

  @Test
  void analysisArgumentsStripLargeSourceWithoutMutatingRecord() {
    ResumeRecord record = new ResumeRecord();
    Map<String, Object> source = Map.of("raw", "large", "provider", "test");
    record.setParsed(
        Map.of(
            "name",
            "candidate",
            "raw_text",
            "large",
            "markdown",
            "large",
            "analysis",
            Map.of("old", true),
            "source",
            source));

    Map<String, Object> args =
        applier.analysisArgs(Path.of("resume.pdf"), record, List.of("skills"));

    assertEquals("resume.pdf", args.get("file_path"));
    assertEquals(List.of("skills"), args.get("sections"));
    assertEquals(
        Map.of("name", "candidate", "source", Map.of("provider", "test")), args.get("parsed"));
    assertEquals("large", ((Map<?, ?>) record.getParsed().get("source")).get("raw"));
    assertTrue(record.getParsed().containsKey("analysis"));
  }

  @Test
  void emptyRecordHasNoSectionsOrParsedData() {
    Map<String, Object> args = applier.analysisArgs(Path.of("resume.pdf"), new ResumeRecord());
    assertEquals(Map.of(), args.get("parsed"));
    assertFalse(args.containsKey("sections"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"merge", "replace", "parse"})
  void failedToolResultPropagatesErrorWithoutReplacingParsedData(String action) {
    ResumeRecord record = new ResumeRecord();
    record.setParsed(Map.of("name", "original"));
    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> apply(action, record, Map.of("success", false, "error", "tool timeout")));
    assertEquals("tool timeout", error.getMessage());
    assertEquals(Map.of("name", "original"), record.getParsed());
    if (action.equals("parse")) {
      assertEquals("fail", record.getParseStatus());
      assertEquals("tool timeout", record.getParseError());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"merge", "replace", "parse"})
  void successfulEnvelopeWithMalformedOutputIsRejected(String action) {
    ResumeRecord record = new ResumeRecord();
    for (Object output : List.of("invalid", Map.of(), Map.of("analysis", "bad", "resume", "bad"))) {
      assertThrows(
          RuntimeException.class,
          () -> apply(action, record, Map.of("success", true, "output", output)));
    }
  }

  @Test
  void mergingAnalysisPreservesOtherSectionsWhileReplacementReplacesThem() {
    ResumeRecord record = new ResumeRecord();
    record.setParsed(
        Map.of("name", "candidate", "analysis", Map.of("skills", "old", "projects", "keep")));
    Map<String, Object> result =
        Map.of("success", true, "output", Map.of("analysis", Map.of("skills", "new")));

    applier.mergeAnalysisResult(record, result);
    assertEquals(Map.of("skills", "new", "projects", "keep"), record.getParsed().get("analysis"));
    applier.applyAnalysisResult(record, result);
    assertEquals(Map.of("skills", "new"), record.getParsed().get("analysis"));
    assertEquals("candidate", record.getParsed().get("name"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"merge", "replace"})
  void analysisCanInitializeAnUnparsedRecord(String action) {
    ResumeRecord record = new ResumeRecord();
    apply(
        action,
        record,
        Map.of("success", true, "output", Map.of("analysis", Map.of("skills", "new"))));
    assertEquals(Map.of("analysis", Map.of("skills", "new")), record.getParsed());
  }

  @Test
  void reparsingPreservesFoldersUnlessToolExplicitlyProvidesOne() {
    ResumeRecord record = new ResumeRecord();
    record.setParsed(Map.of("folder", "old-folder", "resumeFolder", "metadata", "name", "old"));
    record.setParseError("previous failure");
    applier.applyParseResult(
        record,
        Map.of(
            "success",
            true,
            "output",
            Map.of("resume", Map.of("folder", "new-folder", "name", "new"))));
    assertEquals(
        Map.of("folder", "new-folder", "resumeFolder", "metadata", "name", "new"),
        record.getParsed());
    assertEquals("success", record.getParseStatus());
    assertNull(record.getParseError());
  }

  private void apply(String action, ResumeRecord record, Map<String, Object> result) {
    switch (action) {
      case "merge" -> applier.mergeAnalysisResult(record, result);
      case "replace" -> applier.applyAnalysisResult(record, result);
      case "parse" -> applier.applyParseResult(record, result);
      default -> throw new IllegalArgumentException(action);
    }
  }
}
