package com.project.image.segmentation;

import com.project.image.segmentation.DTOs.SegmentationResult;
import com.project.image.segmentation.service.SegmentationService;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentationServiceTest {
    private final SegmentationService service = new SegmentationService();

    @Test
    void segment_syntheticTwoSquares_detectsRegions() {
// Build a 100x100 image with two white squares on black background
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK); g.fillRect(0,0,100,100);
        g.setColor(Color.WHITE); g.fillRect(10,10,20,20); // 400 px
        g.setColor(Color.WHITE); g.fillRect(60,60,30,30); // 900 px
        g.dispose();

        SegmentationResult res = service.segment(img, 200); // minRegionSize filters noise

        assertThat(res.threshold()).isBetween(1, 254);
        assertThat(res.segmentCount()).isGreaterThanOrEqualTo(2);
        assertThat(res.segmentedPng()).isNotEmpty();
    }
}