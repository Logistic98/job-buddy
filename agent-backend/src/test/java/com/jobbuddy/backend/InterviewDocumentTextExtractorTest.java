package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewDocumentTextExtractorImpl;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 验证 InterviewDocumentTextExtractor 的核心行为、异常路径与边界条件。
 */
class InterviewDocumentTextExtractorTest {
  private final InterviewDocumentTextExtractorImpl extractor =
      new InterviewDocumentTextExtractorImpl();

  /**
   * 验证 InterviewDocumentTextExtractor 的文件解析与存储边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldExtractPdfText() throws Exception {
    byte[] content;
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 12);
        stream.newLineAtOffset(72, 720);
        stream.showText("Java large model application development");
        stream.endText();
      }
      document.save(output);
      content = output.toByteArray();
    }

    InterviewDocumentExtractResponse response =
        extractor.extract(
            new MockMultipartFile("file", "reference.pdf", "application/pdf", content));

    assertTrue(response.getText().contains("Java large model application development"));
    assertEquals("reference.pdf", response.getFileName());
    assertFalse(response.getTruncated());
  }

  /**
   * 验证 InterviewDocumentTextExtractor 的文件解析与存储边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldExtractDocxParagraphAndTableText() throws Exception {
    byte[] content;
    try (XWPFDocument document = new XWPFDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.createParagraph().createRun().setText("上海 Java 大模型应用开发岗");
      document.createTable(1, 1).getRow(0).getCell(0).setText("月薪40-50k");
      document.write(output);
      content = output.toByteArray();
    }

    InterviewDocumentExtractResponse response =
        extractor.extract(
            new MockMultipartFile(
                "file",
                "job.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content));

    assertTrue(response.getText().contains("上海 Java 大模型应用开发岗"));
    assertTrue(response.getText().contains("月薪40-50k"));
  }

  /**
   * 验证 InterviewDocumentTextExtractor 的核心业务契约。
   */
  @Test
  void shouldDecodeUtf8AndRemoveBom() {
    byte[] body = "\uFEFF第一行\r\n第二行".getBytes(StandardCharsets.UTF_8);

    InterviewDocumentExtractResponse response =
        extractor.extract(new MockMultipartFile("file", "notes.md", "text/markdown", body));

    assertEquals("第一行\n第二行", response.getText());
    assertEquals(Integer.valueOf(7), response.getCharacterCount());
  }

  /**
   * 验证 InterviewDocumentTextExtractor 的数量、长度与分页边界。
   */
  @Test
  void shouldTruncateExtractedTextAndReportOriginalLength() {
    String text = "a".repeat(InterviewDocumentTextExtractorImpl.MAX_TEXT_CHARACTERS + 5);

    InterviewDocumentExtractResponse response =
        extractor.extract(
            new MockMultipartFile(
                "file", "notes.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)));

    assertEquals(
        InterviewDocumentTextExtractorImpl.MAX_TEXT_CHARACTERS, response.getText().length());
    assertEquals(
        Integer.valueOf(InterviewDocumentTextExtractorImpl.MAX_TEXT_CHARACTERS + 5),
        response.getCharacterCount());
    assertTrue(response.getTruncated());
  }

  /**
   * 验证 InterviewDocumentTextExtractor 中文件的输入校验与拒绝边界。
   */
  @Test
  void shouldRejectUnsupportedOversizedAndInvalidBinaryFiles() {
    assertEquals(
        "仅支持 PDF、DOC、DOCX、TXT、MD、JSON、CSV 文件",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    extractor.extract(
                        new MockMultipartFile(
                            "file", "reference.png", "image/png", new byte[] {1, 2, 3})))
            .getMessage());

    MultipartFile oversized = mock(MultipartFile.class);
    when(oversized.isEmpty()).thenReturn(false);
    when(oversized.getSize())
        .thenReturn(InterviewDocumentTextExtractorImpl.MAX_FILE_SIZE_BYTES + 1);
    when(oversized.getOriginalFilename()).thenReturn("large.pdf");
    assertEquals(
        "文档大小不能超过 10MB",
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(oversized))
            .getMessage());

    assertEquals(
        "DOC 文件格式无效或已损坏",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    extractor.extract(
                        new MockMultipartFile(
                            "file", "broken.doc", "application/msword", new byte[] {1, 2, 3})))
            .getMessage());
  }

  /**
   * 验证共享解析器保留默认 10MB 限制，同时允许调用入口声明更大的边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldHonorCallerSpecificSizeLimit() throws Exception {
    MultipartFile file = mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(InterviewDocumentTextExtractorImpl.MAX_FILE_SIZE_BYTES + 1);
    when(file.getOriginalFilename()).thenReturn("large.txt");
    when(file.getContentType()).thenReturn("text/plain");
    when(file.getBytes()).thenReturn("valid content".getBytes(StandardCharsets.UTF_8));

    InterviewDocumentExtractResponse response = extractor.extract(file, 128L * 1024L * 1024L);

    assertEquals("valid content", response.getText());
  }

  /**
   * 验证 InterviewDocumentTextExtractor 的输入校验与拒绝边界。
   */
  @Test
  void shouldRejectEmptyExtractedText() {
    assertEquals(
        "文档中没有可提取的文本，请确认文件包含可复制文字",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    extractor.extract(
                        new MockMultipartFile(
                            "file",
                            "empty.txt",
                            "text/plain",
                            " \n\t ".getBytes(StandardCharsets.UTF_8))))
            .getMessage());
  }
}
