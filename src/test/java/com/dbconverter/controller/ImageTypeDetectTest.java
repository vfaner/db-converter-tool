package com.dbconverter.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 校验 OCR 接口的图片类型识别：只认文件头魔数，不认客户端声明的 Content-Type
 */
class ImageTypeDetectTest {

    /** 在给定头部后面补足长度，凑够最小判定长度 */
    private static byte[] withHeader(int... header) {
        byte[] data = new byte[32];
        for (int i = 0; i < header.length; i++) {
            data[i] = (byte) header[i];
        }
        return data;
    }

    @Test
    @DisplayName("识别 PNG")
    void detectsPng() {
        assertEquals("image/png",
                ManualController.detectImageMimeType(withHeader(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)));
    }

    @Test
    @DisplayName("识别 JPEG")
    void detectsJpeg() {
        assertEquals("image/jpeg", ManualController.detectImageMimeType(withHeader(0xFF, 0xD8, 0xFF, 0xE0)));
    }

    @Test
    @DisplayName("识别 BMP")
    void detectsBmp() {
        assertEquals("image/bmp", ManualController.detectImageMimeType(withHeader(0x42, 0x4D)));
    }

    @Test
    @DisplayName("识别 GIF")
    void detectsGif() {
        assertEquals("image/gif", ManualController.detectImageMimeType(withHeader(0x47, 0x49, 0x46, 0x38)));
    }

    @Test
    @DisplayName("识别 WEBP：RIFF 头 + 偏移 8 处的 WEBP 标记")
    void detectsWebp() {
        assertEquals("image/webp", ManualController.detectImageMimeType(
                withHeader(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)));
    }

    @Test
    @DisplayName("RIFF 头但不是 WEBP（如 wav）应拒绝")
    void rejectsNonWebpRiff() {
        assertNull(ManualController.detectImageMimeType(
                withHeader(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45)));
    }

    @Test
    @DisplayName("伪装成图片的 SVG/HTML 文本应拒绝")
    void rejectsSvgText() {
        assertNull(ManualController.detectImageMimeType(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("可执行文件（ELF）应拒绝")
    void rejectsElf() {
        assertNull(ManualController.detectImageMimeType(withHeader(0x7F, 0x45, 0x4C, 0x46)));
    }

    @Test
    @DisplayName("空内容与过短内容应拒绝")
    void rejectsEmptyAndTooShort() {
        assertNull(ManualController.detectImageMimeType(null));
        assertNull(ManualController.detectImageMimeType(new byte[0]));
        assertNull(ManualController.detectImageMimeType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
    }
}
