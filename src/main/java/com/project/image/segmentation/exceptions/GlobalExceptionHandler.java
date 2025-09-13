package com.project.image.segmentation.exceptions;

import com.project.image.segmentation.exceptions.SegmentationException;
import com.project.image.segmentation.exceptions.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.validation.ConstraintViolationException;
import java.io.IOException;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({StorageException.class, SegmentationException.class})
    public String handleDomainExceptions(RuntimeException ex, Model model) {
        log.warn("Domain error: {}", ex.getMessage());
        model.addAttribute("error", ex.getMessage());
        return "index";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, Model model) {
        log.warn("File upload size exceeded: {}", ex.getMessage());
        model.addAttribute("error", "Файлът е твърде голям. Максимален размер: 10MB");
        return "segment";
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public String handleValidationErrors(ConstraintViolationException ex, Model model) {
        log.warn("Validation error: {}", ex.getMessage());
        model.addAttribute("error", "Невалидни параметри. Моля проверете въведените данни.");
        return "segment";
    }

    @ExceptionHandler(IOException.class)
    public String handleIOException(IOException ex, Model model) {
        log.error("IO error occurred", ex);
        model.addAttribute("error", "Грешка при обработката на файла. Моля опитайте с друго изображение.");
        return "segment";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleIllegalArgument(IllegalArgumentException ex, Model model) {
        log.warn("Invalid argument: {}", ex.getMessage());
        model.addAttribute("error", "Невалидни параметри: " + ex.getMessage());
        return "segment";
    }

    @ExceptionHandler(Exception.class)
    public String handleUnknownException(Exception ex, Model model) {
        log.error("Unhandled error occurred", ex);
        model.addAttribute("error", "Възникна неочаквана грешка. Моля опитайте отново или се свържете с администратора.");
        return "index";
    }
}