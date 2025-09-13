package com.project.image.segmentation.controller;

import com.project.image.segmentation.DTOs.SegmentationResult;
import com.project.image.segmentation.exceptions.SegmentationException;
import com.project.image.segmentation.service.SegmentationService;
import com.project.image.segmentation.service.StorageService;
import com.project.image.segmentation.service.OpenCVSegmentationService;
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

    private static final List<String> SUPPORTED_FORMATS = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/bmp", "image/webp"
    );

    private final SegmentationService segmentationService;
    private final StorageService storageService;
    private final OpenCVSegmentationService openCvService;

    @Value("${app.segmentation.default-min-region-size:50}")
    private int defaultMinRegionSize;

    public SegmentationController(SegmentationService segmentationService,
                                  StorageService storageService,
                                  OpenCVSegmentationService openCvService) {
        this.segmentationService = segmentationService;
        this.storageService = storageService;
        this.openCvService = openCvService;
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

        validateUploadedFile(file);

        log.info("Processing file: {} ({}KB), minRegionSize: {}",
                file.getOriginalFilename(), file.getSize() / 1024, minRegionSize);

        var storedOriginal = storageService.store(file);
        log.debug("File stored as: {}", storedOriginal.filename());

        BufferedImage input = loadAndValidateImage(file);

        // В handleUpload метода, замени try блока с този:

        try {
            // K-means сегментация (традиционна)
            SegmentationResult kmeansResult = segmentationService.segment(input, minRegionSize);
            var kmeansOverlay = storageService.storeResultImage(kmeansResult.outlinePng());
            var kmeansMask = storageService.storeResultImage(kmeansResult.maskPng());

            // GrabCut сегментация (ML-подобна)
            SegmentationResult grabCutResult = openCvService.segmentWithGrabCut(input);
            var grabCutOverlay = storageService.storeResultImage(grabCutResult.outlinePng());
            var grabCutMask = storageService.storeResultImage(grabCutResult.maskPng());

            // Популиране на модела
            model.addAttribute("originalPath", "/" + storedOriginal.relativeWebPath());

            // K-means резултати
            model.addAttribute("kmeansOverlayPath", "/" + kmeansOverlay.relativeWebPath());
            model.addAttribute("kmeansMaskPath", "/" + kmeansMask.relativeWebPath());
            model.addAttribute("kmeansSegments", kmeansResult.segmentCount());
            model.addAttribute("kmeansAreaPercent", String.format("%.2f",
                    kmeansResult.areasPercent().stream().mapToDouble(Double::doubleValue).sum()));

            // GrabCut резултати
            model.addAttribute("grabCutOverlayPath", "/" + grabCutOverlay.relativeWebPath());
            model.addAttribute("grabCutMaskPath", "/" + grabCutMask.relativeWebPath());
            model.addAttribute("grabCutAreaPercent", String.format("%.2f",
                    grabCutResult.areasPercent().stream().mapToDouble(Double::doubleValue).sum()));

            // Общи данни
            model.addAttribute("width", input.getWidth());
            model.addAttribute("height", input.getHeight());
            model.addAttribute("totalPixels", input.getWidth() * input.getHeight());

            log.info("All segmentation methods completed successfully for {}", file.getOriginalFilename());
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

        if (input.getWidth() < 50 || input.getHeight() < 50) {
            throw new SegmentationException("Изображението е твърде малко. Минимален размер: 50x50 пиксела");
        }

        if (input.getWidth() > 4000 || input.getHeight() > 4000) {
            throw new SegmentationException("Изображението е твърде голямо. Максимален размер: 4000x4000 пиксела");
        }

        log.debug("Image loaded successfully: {}x{}", input.getWidth(), input.getHeight());
        return input;
    }

    private String getSuggestionForError(String errorMessage) {
        if (errorMessage.contains("No suitable objects found")) {
            return "Опитайте с по-малък минимален размер на региона или изображение с по-контрастни обекти.";
        } else if (errorMessage.contains("too small")) {
            return "Качете по-голямо изображение (минимум 50x50 пиксела).";
        } else if (errorMessage.contains("corrupted")) {
            return "Опитайте с друго изображение във формат PNG или JPEG.";
        } else if (errorMessage.contains("OpenCV") || errorMessage.contains("GrabCut") || errorMessage.contains("Watershed")) {
            return "OpenCV алгоритмите са временно недостъпни. Използва се K-means сегментация.";
        }
        return "Опитайте с различни параметри или друго изображение.";
    }

    public static record SegmentDetails(int id, int areaPx, String areaPercent) {}
}