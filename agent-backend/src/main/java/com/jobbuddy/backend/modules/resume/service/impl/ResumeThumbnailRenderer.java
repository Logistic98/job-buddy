package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * 简历缩略图渲染：负责 PDF 首页渲染缩放和非 PDF 占位图绘制。
 */
class ResumeThumbnailRenderer {

  /**
   * 渲染 PDF 首个分页。
   *
   * @param pdfFile PDF 文件
   * @return PDF 首页图像
   * @throws IOException 文件或网络读写失败时抛出
   */
  byte[] renderPdfFirstPage(Path pdfFile) throws IOException {
    try (PDDocument document = PDDocument.load(pdfFile.toFile())) {
      return renderPdfFirstPage(document);
    }
  }

  /**
   * 直接从上传内容渲染 PDF 首页，避免上传后再次从对象存储下载整份文件。
   *
   * @param pdfContent PDF 文件内容
   * @return PDF 首页图像
   * @throws IOException PDF 无法读取或渲染时抛出
   */
  byte[] renderPdfFirstPage(byte[] pdfContent) throws IOException {
    try (ByteArrayInputStream input = new ByteArrayInputStream(pdfContent);
        PDDocument document = PDDocument.load(input)) {
      return renderPdfFirstPage(document);
    }
  }

  /**
   * 渲染已打开 PDF 的首页。
   *
   * @param document PDF 文档
   * @return PDF 首页图像
   * @throws IOException PDF 无法渲染时抛出
   */
  private byte[] renderPdfFirstPage(PDDocument document) throws IOException {
    if (document.getNumberOfPages() < 1) throw new IOException("PDF 不包含可渲染页面");
    PDFRenderer renderer = new PDFRenderer(document);
    BufferedImage rendered = renderer.renderImageWithDPI(0, 92, ImageType.RGB);
    int targetWidth = 260;
    int targetHeight =
        Math.max(1, rendered.getHeight() * targetWidth / Math.max(1, rendered.getWidth()));
    BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = scaled.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.drawImage(rendered, 0, 0, targetWidth, targetHeight, null);
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(scaled, "png", output);
    return output.toByteArray();
  }

  /**
   * 生成占位缩略图。
   *
   * @param record 记录
   * @return 占位缩略图
   */
  byte[] placeholderThumbnail(ResumeRecord record) {
    int width = 260;
    int height = 340;
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(java.awt.Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.setColor(new java.awt.Color(229, 235, 245));
      graphics.fillRect(0, 0, width, height);
      graphics.setColor(new java.awt.Color(49, 87, 255));
      graphics.fillRoundRect(22, 26, 56, 28, 8, 8);
      graphics.setColor(java.awt.Color.WHITE);
      graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 15));
      graphics.drawString(
          (record.getSuffix() == null ? "CV" : record.getSuffix())
              .toUpperCase(java.util.Locale.ROOT),
          36,
          46);
      graphics.setColor(new java.awt.Color(23, 32, 51));
      graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
      graphics.drawString("简历文件", 22, 94);
      graphics.setColor(new java.awt.Color(102, 112, 133));
      graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13));
      String name = record.getOriginalName() == null ? "Resume" : record.getOriginalName();
      if (name.length() > 15) name = name.substring(0, 15) + "...";
      graphics.drawString(name, 22, 122);
    } finally {
      graphics.dispose();
    }
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(image, "png", output);
      return output.toByteArray();
    } catch (IOException e) {
      return new byte[0];
    }
  }
}
