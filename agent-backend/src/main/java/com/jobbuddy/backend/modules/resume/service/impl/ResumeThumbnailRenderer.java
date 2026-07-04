package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 简历缩略图渲染：负责 PDF 首页渲染缩放、非 PDF 占位图绘制以及本地缓存路径规划。
 */
class ResumeThumbnailRenderer {

    Path thumbnailCachePath(ResumeRecord record) {
        String uploaded = record.getUploadedAt() == null ? "0" : String.valueOf(record.getUploadedAt().toEpochMilli());
        return Paths.get(System.getProperty("java.io.tmpdir"), "job-buddy-resume-thumbnails", record.getResumeId() + "-" + uploaded + ".png");
    }

    byte[] renderPdfFirstPage(Path pdfFile) throws IOException {
        PDDocument document = PDDocument.load(pdfFile.toFile());
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage rendered = renderer.renderImageWithDPI(0, 92, ImageType.RGB);
            int targetWidth = 260;
            int targetHeight = Math.max(1, rendered.getHeight() * targetWidth / Math.max(1, rendered.getWidth()));
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.drawImage(rendered, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", output);
            return output.toByteArray();
        } finally {
            document.close();
        }
    }

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
            graphics.drawString((record.getSuffix() == null ? "CV" : record.getSuffix()).toUpperCase(java.util.Locale.ROOT), 36, 46);
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
