package com.mygateapp.files.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** File type is decided from magic bytes, never from the client's content type or file name. */
class FileSniffingTest {

    @Test
    void recognisesAllowedFormats() {
        assertThat(FileService.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00})).contains("image/jpeg");
        assertThat(FileService.sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})).contains("image/png");
        assertThat(FileService.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).contains("image/webp");
        assertThat(FileService.sniff("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).contains("application/pdf");
    }

    @Test
    void rejectsEverythingElse() {
        assertThat(FileService.sniff("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileService.sniff("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(FileService.sniff(new byte[]{(byte) 0xFF})).isEmpty();
        assertThat(FileService.sniff(new byte[0])).isEmpty();
    }

    @Test
    void humanReadableSizes() {
        assertThat(FileService.humanSize(512)).isEqualTo("512 B");
        assertThat(FileService.humanSize(240 * 1024)).isEqualTo("240 KB");
        assertThat(FileService.humanSize(3 * 1024 * 1024 + 512 * 1024)).isEqualTo("3.5 MB");
    }
}
