package com.project.image.segmentation.controller;

import com.project.image.segmentation.DTOs.SegmentationResult;
import com.project.image.segmentation.exceptions.SegmentationException;
import com.project.image.segmentation.service.SegmentationService;
import com.project.image.segmentation.service.StorageService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

@Controller
@Validated
public class SegmentationController {
    private static final Logger log = LoggerFactory.getLogger(SegmentationController.class);

    // Поддържани формати на изображения
    private static final List<String> SUPPORTED_FORMATS = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/bmp", "image/webp"
    );

    private final SegmentationService segmentationService;
    private final StorageService storageService;

    @Value("${app.segmentation.default-min-region-size:50}")
    private int defaultMinRegionSize;

    public SegmentationController(SegmentationService segmentationService, StorageService storageService) {
        this.segmentationService = segmentationService;
        this.storageService = storageService;
    }

    @GetMapping("/segment")
    public String showForm(Model model) {
        model.addAttribute("defaultMinRegionSize", defaultMinRegionSize);
        model.addAttribute("supportedFormats", String.join(", ", SUPPORTED_FORMATS));
        return "segment";
    }

    @PostMapping(value = "/segment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String handleUpload(
            @RequestParam("file") @NotNull MultipartFile file,
            @RequestParam(name = "minRegionSize", defaultValue = "50")
            @Min(value = 10, message = "Минималният размер на региона трябва да бъде поне 10 пиксела")
            @Max(value = 5000, message = "Минималният размер на региона не може да бъде повече от 5000 пиксела")
            int minRegionSize,
            Model model
    ) throws IOException {

        // Валидация на файла
        validateUploadedFile(file);

        log.info("Processing file: {} ({}KB), minRegionSize: {}",
                file.getOriginalFilename(), file.getSize() / 1024, minRegionSize);

        // Запазване на оригиналния файл
        var storedOriginal = storageService.store(file);
        log.debug("File stored as: {}", storedOriginal.filename());

        // Зареждане и валидация на изображението
        BufferedImage input = loadAndValidateImage(file);

        try {
            // Извършване на сегментацията
            SegmentationResult result = segmentationService.segment(input, minRegionSize);

            // Запазване на резултатите
            var overlayStored = storageService.storeResultImage(result.outlinePng());
            var maskStored = storageService.storeResultImage(result.maskPng());

            // Подготовка на модела за шаблона
            populateResultModel(model, storedOriginal, overlayStored, maskStored, result);

            log.info("Segmentation completed successfully for {}", file.getOriginalFilename());
            return "result";

        } catch (SegmentationException e) {
            log.warn("Segmentation failed for {}: {}", file.getOriginalFilename(), e.getMessage());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("suggestion", getSuggestionForError(e.getMessage()));
            return "segment";
        }
    }

    private void validateUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Моля изберете файл за качване");
        }

        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_FORMATS.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Неподдържан формат на файла: " + contentType +
                            ". Поддържани формати: " + String.join(", ", SUPPORTED_FORMATS)
            );
        }

        // Проверка за максимален размер (допълнително към Spring конфигурацията)
        long maxSize = 10 * 1024 * 1024; // 10MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("Файлът е твърде голям. Максимален размер: 10MB");
        }
    }

    private BufferedImage loadAndValidateImage(MultipartFile file) throws IOException {
        BufferedImage input;
        try (var inputStream = file.getInputStream()) {
            input = ImageIO.read(inputStream);
        }

        if (input == null) {
            throw new SegmentationException("Файлът не е валидно изображение или е повреден.");
        }

        // Проверка на размерите
        if (input.getWidth() < 50 || input.getHeight() < 50) {
            throw new SegmentationException("Изображението е твърде малко. Минимален размер: 50x50 пиксела");
        }

        if (input.getWidth() > 4000 || input.getHeight() > 4000) {
            throw new SegmentationException("Изображението е твърде голямо. Максимален размер: 4000x4000 пиксела");
        }

        log.debug("Image loaded successfully: {}x{}", input.getWidth(), input.getHeight());
        return input;
    }

    private void populateResultModel(Model model, StorageService.StoredFile original,
                                     StorageService.StoredFile overlay, StorageService.StoredFile mask,
                                     SegmentationResult result) {

        model.addAttribute("originalPath", "/" + original.relativeWebPath());
        model.addAttribute("overlayPath", "/" + overlay.relativeWebPath());
        model.addAttribute("maskPath", "/" + mask.relativeWebPath());

        // Изчисляване на общата площ
        int totalAreaPx = result.areasPx().stream().mapToInt(Integer::intValue).sum();
        double totalAreaPercent = result.areasPercent().stream().mapToDouble(Double::doubleValue).sum();

        model.addAttribute("segments", result.segmentCount());
        model.addAttribute("width", result.width());
        model.addAttribute("height", result.height());
        model.addAttribute("areaPx", totalAreaPx);
        model.addAttribute("areaPercent", String.format("%.2f", totalAreaPercent));
        model.addAttribute("threshold", result.threshold());

        // Детайлна информация за всеки сегмент
        model.addAttribute("segmentDetails", createSegmentDetails(result));
    }

    private List<SegmentDetails> createSegmentDetails(SegmentationResult result) {
        List<SegmentDetails> details = new ArrayList<>();
        for (int i = 0; i < result.segmentCount(); i++) {
            details.add(new SegmentDetails(
                    i + 1,
                    result.areasPx().get(i),
                    String.format("%.2f", result.areasPercent().get(i))
            ));
        }
        return details;
    }

    private String getSuggestionForError(String errorMessage) {
        if (errorMessage.contains("No suitable objects found")) {
            return "Опитайте с по-малък минимален размер на региона или изображение с по-контрастни обекти.";
        } else if (errorMessage.contains("too small")) {
            return "Качете по-голямо изображение (минимум 50x50 пиксела).";
        } else if (errorMessage.contains("corrupted")) {
            return "Опитайте с друго изображение във формат PNG или JPEG.";
        }
        return "Опитайте с различни параметри или друго изображение.";
    }

    // Помощен клас за детайлите на сегментите
    public static record SegmentDetails(int id, int areaPx, String areaPercent) {}
}