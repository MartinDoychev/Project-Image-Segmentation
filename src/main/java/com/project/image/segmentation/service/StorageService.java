package com.project.image.segmentation.service;

import com.project.image.segmentation.exceptions.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private final Path rootDir;
    public StorageService(@Value("${app.upload.dir:uploads}") String root) {
        this.rootDir = Paths.get(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
            log.info("Using upload directory: {}", this.rootDir);
        } catch (IOException e) {
            throw new StorageException("Cannot create upload directory: " + rootDir, e);
        }
    }
    public record StoredFile(Path path, String filename, String relativeWebPath) {}
    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new StorageException("Empty upload");
        }
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename());
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new StorageException("Only image uploads are allowed (received: " + contentType + ")");
        }
        String safeBase = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(LocalDateTime.now());
        String filename = timestamp + "_" + safeBase;
        Path target = rootDir.resolve(filename);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(target, filename, "uploads/" + filename);
        } catch (IOException e) {
            throw new StorageException("Failed to store file", e);
        }
    }
    public StoredFile storeResultImage(byte[] pngBytes) {
        String filename = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(LocalDateTime.now()) + "_result.png";
        Path target = rootDir.resolve(filename);
        try {
            Files.write(target, pngBytes);
            return new StoredFile(target, filename, "uploads/" + filename);
        } catch (IOException e) {
            throw new StorageException("Failed to store result image", e);
        }
    }
}