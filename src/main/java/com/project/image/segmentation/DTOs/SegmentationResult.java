package com.project.image.segmentation.DTOs;

import java.util.List;

public record SegmentationResult(
        int width,
        int height,
        int threshold,
        int segmentCount,
        byte[] maskPng,
        byte[] outlinePng,
        byte[] recoloredPng,
        List<Integer> areasPx,
        List<Double> areasPercent
) {}
