package com.project.image.segmentation.DTOs;

import java.util.List;

public record SegmentationResult(
        int width,
        int height,
        int threshold,
        int segmentCount,
        byte[] maskPng,       // ПЛЪТНА маска на ЧЕРЕН фон (за „Сегментация“)
        byte[] outlinePng,    // ЧЕРВЕН контур + цианово запълване върху оригинала
        byte[] recoloredPng,  // Оригинал + обектът оцветен (резервен вариант)
        List<Integer> areasPx,
        List<Double> areasPercent
) {}
