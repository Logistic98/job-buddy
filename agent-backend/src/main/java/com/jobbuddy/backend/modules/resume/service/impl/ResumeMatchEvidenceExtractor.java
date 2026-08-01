package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 从原始 PDF 中提取结构化解析容易遗漏、但会影响岗位判断的公开能力证据。
 *
 * <p>只保留公开链接、开源、技术写作和 AI 原生研发等高信号行，避免把联系方式或整份简历再次无界注入模型。
 */
final class ResumeMatchEvidenceExtractor {
  static final int MAX_SNIPPETS = 24;
  static final int MAX_SNIPPET_CHARS = 500;
  static final int MAX_TOTAL_CHARS = 5000;

  private static final Pattern HIGH_SIGNAL_PATTERN =
      Pattern.compile(
          "(?iu)(https?://|github|gitlab|gitee|开源|作品集|技术博客|博客|blog|公众号|文章|技术写作|"
              + "AI\\s*(?:原生|辅助|编程)|Claude\\s*Code|Codex|Cursor|Copilot|项目仓库|代码仓库|portfolio)");

  private ResumeMatchEvidenceExtractor() {}

  /**
   * 从简历对象存储读取 PDF 并提取补充证据。
   *
   * @param record 简历记录
   * @param storage 简历对象存储
   * @return 有界补充证据
   */
  static List<String> extract(ResumeRecord record, ResumeObjectStorage storage) {
    if (record == null || storage == null || !"pdf".equalsIgnoreCase(record.getSuffix())) {
      return new ArrayList<String>();
    }
    try (InputStream input = storage.openStream(record);
        PDDocument document = PDDocument.load(input)) {
      return extractFromText(new PDFTextStripper().getText(document));
    } catch (Exception exception) {
      throw new RuntimeException("读取 PDF 补充匹配证据失败", exception);
    }
  }

  /**
   * 从 PDF 文本中筛选高信号证据行。
   *
   * @param text PDF 文本
   * @return 去重后的有界证据
   */
  static List<String> extractFromText(String text) {
    Set<String> snippets = new LinkedHashSet<String>();
    int totalChars = 0;
    for (String rawLine : String.valueOf(text == null ? "" : text).split("\\R")) {
      String line = rawLine.replaceAll("\\s+", " ").trim();
      if (line.isEmpty() || !HIGH_SIGNAL_PATTERN.matcher(line).find()) continue;
      if (line.length() > MAX_SNIPPET_CHARS) line = line.substring(0, MAX_SNIPPET_CHARS);
      if (snippets.contains(line)) continue;
      if (totalChars + line.length() > MAX_TOTAL_CHARS || snippets.size() >= MAX_SNIPPETS) break;
      snippets.add(line);
      totalChars += line.length();
    }
    return new ArrayList<String>(snippets);
  }
}
