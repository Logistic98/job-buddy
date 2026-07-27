package com.jobbuddy.backend.modules.interview.service.impl;

import com.jobbuddy.backend.modules.interview.dto.response.InterviewDocumentExtractResponse;
import com.jobbuddy.backend.modules.interview.service.InterviewDocumentTextExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提取 AI 出题参考资料中的纯文本。文件只在当前请求内读取，不进行持久化。
 */
@Service
public class InterviewDocumentTextExtractorImpl implements InterviewDocumentTextExtractor {
  public static final long MAX_FILE_SIZE_BYTES = DEFAULT_MAX_FILE_SIZE_BYTES;
  public static final int MAX_TEXT_CHARACTERS = 20_000;

  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("txt", "md", "markdown", "json", "csv", "pdf", "doc", "docx");
  private static final Set<String> GENERIC_CONTENT_TYPES =
      Set.of("", "application/octet-stream", "binary/octet-stream");
  private static final Map<String, Set<String>> CONTENT_TYPES =
      Map.of(
          "pdf", Set.of("application/pdf"),
          "doc", Set.of("application/msword", "application/x-ole-storage"),
          "docx",
              Set.of(
                  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                  "application/zip"),
          "json", Set.of("application/json", "text/json"),
          "csv", Set.of("text/csv", "application/csv"));

  /**
   * 提取文档文本。
   *
   * @param file 上传文件
   * @return 提取结果
   */
  @Override
  public InterviewDocumentExtractResponse extract(MultipartFile file, long maxFileSizeBytes) {
    validateFile(file, maxFileSizeBytes);
    String fileName = safeFileName(file.getOriginalFilename());
    String extension = extension(fileName);
    String contentType = normalizeContentType(file.getContentType());
    validateContentType(extension, contentType);

    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException error) {
      throw new IllegalArgumentException("文档读取失败，请重新选择文件", error);
    }
    validateSignature(extension, content);

    String extracted = extractText(extension, content);
    String normalized = normalizeText(extracted);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("文档中没有可提取的文本，请确认文件包含可复制文字");
    }
    int characterCount = normalized.length();
    boolean truncated = characterCount > MAX_TEXT_CHARACTERS;
    String text = truncated ? normalized.substring(0, MAX_TEXT_CHARACTERS) : normalized;
    return new InterviewDocumentExtractResponse(
        fileName,
        contentType.isEmpty() ? "application/octet-stream" : contentType,
        text,
        characterCount,
        truncated);
  }

  /**
   * 校验文件。
   *
   * @param file 上传文件
   */
  private void validateFile(MultipartFile file, long maxFileSizeBytes) {
    if (file == null || file.isEmpty() || file.getSize() <= 0) {
      throw new IllegalArgumentException("上传文档不能为空");
    }
    if (maxFileSizeBytes <= 0) {
      throw new IllegalArgumentException("文档大小上限配置无效");
    }
    if (file.getSize() > maxFileSizeBytes) {
      throw new IllegalArgumentException("文档大小不能超过 " + formatFileSize(maxFileSizeBytes));
    }
    String fileName = safeFileName(file.getOriginalFilename());
    String extension = extension(fileName);
    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException("仅支持 PDF、DOC、DOCX、TXT、MD、JSON、CSV 文件");
    }
  }

  private String formatFileSize(long bytes) {
    long megabytes = bytes / 1024L / 1024L;
    return megabytes > 0 && megabytes * 1024L * 1024L == bytes ? megabytes + "MB" : bytes + "B";
  }

  /**
   * 提取文本。
   *
   * @param extension 文件扩展名
   * @param content 内容
   * @return 文本
   */
  private String extractText(String extension, byte[] content) {
    try {
      switch (extension) {
        case "pdf":
          return extractPdf(content);
        case "doc":
          return extractDoc(content);
        case "docx":
          return extractDocx(content);
        default:
          return decodeUtf8(content);
      }
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException(formatErrorMessage(extension), error);
    }
  }

  /**
   * 提取 PDF 文档文本。
   *
   * @param content 内容
   * @return  PDF 文档文本
   * @throws IOException 文件或网络读写失败时抛出
   */
  private String extractPdf(byte[] content) throws IOException {
    try (PDDocument document = PDDocument.load(content)) {
      if (document.isEncrypted()) throw new IllegalArgumentException("暂不支持加密 PDF，请解除密码后重试");
      return new PDFTextStripper().getText(document);
    }
  }

  /**
   * 提取 DOC 文档文本。
   *
   * @param content 内容
   * @return  DOC 文档文本
   * @throws IOException 文件或网络读写失败时抛出
   */
  private String extractDoc(byte[] content) throws IOException {
    try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
        WordExtractor extractor = new WordExtractor(document)) {
      return extractor.getText();
    }
  }

  /**
   * 提取 DOCX 文档文本。
   *
   * @param content 内容
   * @return  DOCX 文档文本
   * @throws IOException 文件或网络读写失败时抛出
   */
  private String extractDocx(byte[] content) throws IOException {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
        XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
      return extractor.getText();
    }
  }

  /**
   * 按 UTF-8 解码文本。
   *
   * @param content 内容
   * @return UTF-8 解码文本
   */
  private String decodeUtf8(byte[] content) {
    int offset =
        content.length >= 3
                && content[0] == (byte) 0xEF
                && content[1] == (byte) 0xBB
                && content[2] == (byte) 0xBF
            ? 3
            : 0;
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content, offset, content.length - offset))
          .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码", error);
    }
  }

  /**
   * 校验内容类型。
   *
   * @param extension 文件扩展名
   * @param contentType 内容类型
   */
  private void validateContentType(String extension, String contentType) {
    if (GENERIC_CONTENT_TYPES.contains(contentType)) return;
    if (isTextExtension(extension) && contentType.startsWith("text/")) return;
    Set<String> accepted = CONTENT_TYPES.get(extension);
    if (accepted != null && accepted.contains(contentType)) return;
    throw new IllegalArgumentException("文件类型与扩展名不匹配，请重新选择文档");
  }

  /**
   * 校验资源令牌签名。
   *
   * @param extension 文件扩展名
   * @param content 内容
   */
  private void validateSignature(String extension, byte[] content) {
    if ("pdf".equals(extension) && !startsWith(content, new int[] {0x25, 0x50, 0x44, 0x46, 0x2D})) {
      throw new IllegalArgumentException("PDF 文件格式无效或已损坏");
    }
    if ("doc".equals(extension)
        && !startsWith(content, new int[] {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1})) {
      throw new IllegalArgumentException("DOC 文件格式无效或已损坏");
    }
    if ("docx".equals(extension)
        && !startsWith(content, new int[] {0x50, 0x4B, 0x03, 0x04})
        && !startsWith(content, new int[] {0x50, 0x4B, 0x05, 0x06})
        && !startsWith(content, new int[] {0x50, 0x4B, 0x07, 0x08})) {
      throw new IllegalArgumentException("DOCX 文件格式无效或已损坏");
    }
  }

  /**
   * 判断文件内容是否以指定签名字节开头。
   *
   * @param content 内容
   * @param signature 文件签名字节
   * @return 文件内容是否以指定签名字节开头是否成立
   */
  private boolean startsWith(byte[] content, int[] signature) {
    if (content.length < signature.length) return false;
    for (int index = 0; index < signature.length; index++) {
      if ((content[index] & 0xFF) != signature[index]) return false;
    }
    return true;
  }

  /**
   * 规范化文本。
   *
   * @param text 文本
   * @return 规范化后的文本
   */
  private String normalizeText(String text) {
    if (text == null) return "";
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n');
    StringBuilder safe = new StringBuilder(normalized.length());
    for (int index = 0; index < normalized.length(); index++) {
      char value = normalized.charAt(index);
      if (value == '\n' || value == '\t' || !Character.isISOControl(value)) safe.append(value);
    }
    return safe.toString().replaceAll("[\\t ]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
  }

  /**
   * 格式化错误消息。
   *
   * @param extension 文件扩展名
   * @return 格式化后的错误消息
   */
  private String formatErrorMessage(String extension) {
    if ("pdf".equals(extension)) return "PDF 解析失败，请确认文件未加密且内容完整";
    if ("doc".equals(extension) || "docx".equals(extension)) {
      return "Word 文档解析失败，请确认文件内容完整且格式正确";
    }
    return "文档读取失败，请重新选择文件";
  }

  /**
   * 判断是否为文本文件扩展名。
   *
   * @param extension 文件扩展名
   * @return 是否为文本文件扩展名
   */
  private boolean isTextExtension(String extension) {
    return Set.of("txt", "md", "markdown", "json", "csv").contains(extension);
  }

  /**
   * 规范化内容类型。
   *
   * @param contentType 内容类型
   * @return 规范化后的内容类型
   */
  private String normalizeContentType(String contentType) {
    if (contentType == null) return "";
    int separator = contentType.indexOf(';');
    String value = separator >= 0 ? contentType.substring(0, separator) : contentType;
    return value.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 获取安全数据文件名称。
   *
   * @param fileName 文件名称
   * @return 安全数据文件名称
   */
  private String safeFileName(String fileName) {
    if (fileName == null || fileName.trim().isEmpty()) return "document";
    String normalized = fileName.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return (separator >= 0 ? normalized.substring(separator + 1) : normalized).trim();
  }

  /**
   * 提取文件扩展名。
   *
   * @param fileName 文件名称
   * @return 文件扩展名
   */
  private String extension(String fileName) {
    int separator = fileName.lastIndexOf('.');
    return separator < 0 ? "" : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
  }
}
