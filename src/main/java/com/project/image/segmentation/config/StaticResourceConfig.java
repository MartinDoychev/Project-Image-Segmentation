package com.project.image.segmentation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Експлицитен resource handler за /uploads/** към реалната папка за качвания.
 * Така URL /uploads/... винаги сочи към app.upload.dir, независимо от working directory.
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    // Използваме същата настройка както в StorageService (default: "uploads")
    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path abs = Paths.get(uploadDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + abs.toString() + "/");
    }
}