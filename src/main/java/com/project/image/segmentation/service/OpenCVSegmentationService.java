package com.project.image.segmentation.service;

import com.project.image.segmentation.DTOs.SegmentationResult;
import com.project.image.segmentation.exceptions.SegmentationException;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

@Service
public class OpenCVSegmentationService {
    private static final Logger log = LoggerFactory.getLogger(OpenCVSegmentationService.class);

    static {
        try {
            nu.pattern.OpenCV.loadShared();
            log.info("OpenCV loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load OpenCV", e);
        }
    }

    public SegmentationResult segmentWithGrabCut(BufferedImage input) {
        try {
            log.info("Starting GrabCut segmentation for image {}x{}", input.getWidth(), input.getHeight());
            Mat image = bufferedImageToMat(input);
            Mat mask = new Mat();
            Mat bgdModel = new Mat();
            Mat fgdModel = new Mat();

            int border = Math.min(input.getWidth(), input.getHeight()) / 10;
            Rect rectangle = new Rect(border, border,
                    input.getWidth() - 2*border,
                    input.getHeight() - 2*border);

            Imgproc.grabCut(image, mask, rectangle, bgdModel, fgdModel, 5, Imgproc.GC_INIT_WITH_RECT);

            Mat finalMask = new Mat();
            Core.bitwise_or(new Mat(mask.size(), mask.type(), new Scalar(Imgproc.GC_FGD)),
                    new Mat(mask.size(), mask.type(), new Scalar(Imgproc.GC_PR_FGD)), finalMask);
            Core.compare(mask, finalMask, finalMask, Core.CMP_EQ);

            boolean[] objectMask = matToBooleanArray(finalMask);

            return generateOpenCVResult(input, objectMask, "GrabCut");

        } catch (Exception e) {
            log.error("GrabCut segmentation failed", e);
            throw new SegmentationException("GrabCut segmentation failed: " + e.getMessage(), e);
        }
    }

    public SegmentationResult segmentWithWatershed(BufferedImage input) {
        try {
            log.info("Starting Watershed segmentation for image {}x{}", input.getWidth(), input.getHeight());

            Mat image = bufferedImageToMat(input);
            Mat gray = new Mat();
            Mat binary = new Mat();

            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

            Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel, new Point(-1, -1), 2);

            Mat dist = new Mat();
            Imgproc.distanceTransform(binary, dist, Imgproc.DIST_L2, 5);

            Mat sureFg = new Mat();
            Core.MinMaxLocResult minmax = Core.minMaxLoc(dist);
            Imgproc.threshold(dist, sureFg, 0.5 * minmax.maxVal, 255, Imgproc.THRESH_BINARY);

            Mat sureBg = new Mat();
            Imgproc.dilate(binary, sureBg, kernel, new Point(-1, -1), 3);

            Mat unknown = new Mat();
            sureFg.convertTo(sureFg, CvType.CV_8U);
            Core.subtract(sureBg, sureFg, unknown);

            Mat markers = new Mat();
            Imgproc.connectedComponents(sureFg, markers);
            Core.add(markers, new Scalar(1), markers);
            markers.setTo(new Scalar(0), unknown);

            Imgproc.watershed(image, markers);

            Mat finalMask = new Mat();
            Mat boundaryMat = new Mat(markers.size(), markers.type(), new Scalar(-1));
            Mat backgroundMat = new Mat(markers.size(), markers.type(), new Scalar(1));

            Core.compare(markers, boundaryMat, finalMask, Core.CMP_NE);

            Mat notBackground = new Mat();
            Core.compare(markers, backgroundMat, notBackground, Core.CMP_NE);
            Core.bitwise_and(finalMask, notBackground, finalMask);

            boolean[] objectMask = matToBooleanArray(finalMask);

            return generateOpenCVResult(input, objectMask, "Watershed");

        } catch (Exception e) {
            log.error("Watershed segmentation failed", e);
            throw new SegmentationException("Watershed segmentation failed: " + e.getMessage(), e);
        }
    }

    private Mat bufferedImageToMat(BufferedImage image) {
        BufferedImage bgrImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = bgrImage.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        byte[] pixels = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, pixels);
        return mat;
    }

    private boolean[] matToBooleanArray(Mat mask) {
        int rows = mask.rows();
        int cols = mask.cols();
        boolean[] result = new boolean[rows * cols];
        byte[] data = new byte[rows * cols];
        mask.get(0, 0, data);

        for (int i = 0; i < data.length; i++) {
            result[i] = (data[i] & 0xFF) > 127;
        }
        return result;
    }

    private SegmentationResult generateOpenCVResult(BufferedImage input, boolean[] objectMask, String method) {
        int w = input.getWidth();
        int h = input.getHeight();

        BufferedImage maskImage = createMaskImage(objectMask, w, h);
        BufferedImage overlayImage = createOverlayImage(input, objectMask, w, h);
        BufferedImage recoloredImage = createRecoloredImage(input, objectMask, w, h);

        int totalPixels = 0;
        for (boolean pixel : objectMask) {
            if (pixel) totalPixels++;
        }

        List<Integer> areas = Arrays.asList(totalPixels);
        List<Double> percentages = Arrays.asList(100.0 * totalPixels / (w * h));

        log.info("{} segmentation completed: {} pixels ({:.2f}%)",
                method, totalPixels, percentages.get(0));

        return new SegmentationResult(
                w, h, 0, 1,
                toPng(maskImage), toPng(overlayImage), toPng(recoloredImage),
                areas, percentages
        );
    }

    private BufferedImage createMaskImage(boolean[] objectMask, int w, int h) {
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int i = 0; i < objectMask.length; i++) {
            int x = i % w;
            int y = i / w;

            if (objectMask[i]) {
                mask.setRGB(x, y, 0xFF00B4FF);
            } else {
                mask.setRGB(x, y, 0xFF000000);
            }
        }
        return mask;
    }

    private BufferedImage createOverlayImage(BufferedImage input, boolean[] objectMask, int w, int h) {
        BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = overlay.createGraphics();
        g2d.drawImage(input, 0, 0, null);

        for (int i = 0; i < objectMask.length; i++) {
            if (objectMask[i]) {
                int x = i % w;
                int y = i / w;

                int originalRGB = input.getRGB(x, y);
                int r = (originalRGB >> 16) & 0xFF;
                int g = (originalRGB >> 8) & 0xFF;
                int b = originalRGB & 0xFF;

                int newR = (int)(r * 0.7 + 0 * 0.3);
                int newG = (int)(g * 0.7 + 180 * 0.3);
                int newB = (int)(b * 0.7 + 255 * 0.3);

                overlay.setRGB(x, y, 0xFF000000 | (newR << 16) | (newG << 8) | newB);
            }
        }
        g2d.dispose();
        return overlay;
    }

    private BufferedImage createRecoloredImage(BufferedImage input, boolean[] objectMask, int w, int h) {
        BufferedImage recolored = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = recolored.createGraphics();
        g2d.drawImage(input, 0, 0, null);
        g2d.dispose();

        for (int i = 0; i < objectMask.length; i++) {
            if (objectMask[i]) {
                int x = i % w;
                int y = i / w;

                int originalRGB = input.getRGB(x, y);
                int r = (originalRGB >> 16) & 0xFF;
                int g = (originalRGB >> 8) & 0xFF;
                int b = originalRGB & 0xFF;

                int newR = Math.min(255, (int)(r * 0.4 + 0 * 0.6));
                int newG = Math.min(255, (int)(g * 0.4 + 180 * 0.6));
                int newB = Math.min(255, (int)(b * 0.4 + 255 * 0.6));

                recolored.setRGB(x, y, 0xFF000000 | (newR << 16) | (newG << 8) | newB);
            }
        }
        return recolored;
    }

    private byte[] toPng(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new SegmentationException("Failed to encode image", e);
        }
    }
}