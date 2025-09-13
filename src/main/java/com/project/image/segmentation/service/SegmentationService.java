package com.project.image.segmentation.service;

import com.project.image.segmentation.DTOs.SegmentationResult;
import com.project.image.segmentation.exceptions.SegmentationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

@Service
public class SegmentationService {
    private static final Logger log = LoggerFactory.getLogger(SegmentationService.class);

    private static final Color MASK_OBJECT_COLOR = new Color(0, 180, 255);
    private static final Color OUTLINE_COLOR     = new Color(255, 0, 0);
    private static final Color MASK_BACKGROUND   = new Color(0, 0, 0);
    private static final float TINT_ALPHA = 0.65f;
    private static final float FILL_ALPHA = 0.45f;

    public SegmentationResult segment(BufferedImage input, int minRegionSize) {
        if (input.getWidth() <= 1 || input.getHeight() <= 1) {
            throw new SegmentationException("Image too small to segment.");
        }

        log.info("Starting segmentation for image {}x{}, minRegionSize={}",
                input.getWidth(), input.getHeight(), minRegionSize);

        final int w = input.getWidth(), h = input.getHeight(), n = w * h;

        float[] lab = new float[n * 3];
        int[] gray  = new int[n];
        int[] argb  = new int[n];
        input.getRGB(0, 0, w, h, argb, 0, w);

        for (int i = 0; i < n; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            float[] LAb = sRGBtoLab(r, g, b);
            lab[3 * i] = LAb[0]; lab[3 * i + 1] = LAb[1]; lab[3 * i + 2] = LAb[2];
            gray[i] = (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
        }

        int k = (w * h < 200 * 200) ? 3 : 4;
        log.debug("Using {} clusters for segmentation", k);

        // Фиксиран seed за консистентност
        int[] cluster = kmeans(lab, n, k, 15, 12345);
        int bgCluster = dominantClusterOnBorder(cluster, w, h, k);
        log.debug("Background cluster identified as: {}", bgCluster);

        int thr = otsuThreshold(gray);
        log.debug("Otsu threshold: {}", thr);

        boolean[] allow = new boolean[n];
        boolean[] fg    = new boolean[n];
        final int slack = 15;

        for (int i = 0; i < n; i++) {
            boolean byCluster = (cluster[i] != bgCluster);
            boolean byGray    = (gray[i] <= thr + slack);
            allow[i] = byCluster;
            fg[i]    = byCluster && byGray;
        }

        log.debug("Applying morphological operations...");
        fg = morphOpen8(fg, w, h, 1);
        fg = morphClose8(fg, w, h, 2);

        int[] labels = new int[n];
        int nextLabel = 1;
        ArrayDeque<Point> q = new ArrayDeque<>();
        List<Integer> keptLabels = new ArrayList<>();
        List<Integer> areasPx = new ArrayList<>();
        List<Double>  areasPercent = new ArrayList<>();

        int minKeep = Math.max(minRegionSize, Math.max(100, (w * h) / 1000));
        log.debug("Minimum region size set to: {}", minKeep);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!fg[idx] || labels[idx] != 0) continue;

                int area = floodFill(fg, labels, q, x, y, w, h, nextLabel);

                if (area >= minKeep) {
                    keptLabels.add(nextLabel);
                    areasPx.add(area);
                    areasPercent.add(100.0 * area / n);
                    log.debug("Kept region {} with area {} pixels ({:.2f}%)",
                            nextLabel, area, 100.0 * area / n);
                }
                nextLabel++;
            }
        }

        if (keptLabels.isEmpty()) {
            log.warn("No suitable objects found with current parameters");
            throw new SegmentationException("No suitable objects found. Try adjusting the minimum region size.");
        }

        log.info("Found {} valid regions", keptLabels.size());

        boolean[] obj = new boolean[n];
        for (int i = 0; i < n; i++) {
            int labId = labels[i];
            if (labId != 0 && contains(keptLabels, labId)) {
                obj[i] = true;
            }
        }

        obj = constrainedGrow(obj, allow, w, h, 2);
        obj = openingByReconstruction(obj, w, h, 1);
        obj = morphClose8(obj, w, h, 1);
        obj = fillHoles(obj, w, h);

        // Cleanup маската
        obj = cleanupMask(obj, w, h, minRegionSize);

        // Генерираме edges
        boolean[] edge = generateEdges(obj, w, h);

        BufferedImage mask = createMaskImage(obj, w, h);
        BufferedImage overlay = createOverlayImage(input, obj, edge, w, h);
        BufferedImage recolored = createRecoloredImage(input, obj, w, h);

        int segments = keptLabels.size();
        log.info("Segmentation completed successfully with {} segments", segments);

        return new SegmentationResult(
                w, h, thr, segments,
                toPng(mask),
                toPng(overlay),
                toPng(recolored),
                areasPx, areasPercent
        );
    }

    // Липсващ метод cleanupMask
    private boolean[] cleanupMask(boolean[] mask, int w, int h, int minRegionSize) {
        // Морфологично отваряне за премахване на шум
        mask = morphOpen8(mask, w, h, 1);

        // Премахваме малки региони
        mask = removeSmallRegions(mask, w, h, minRegionSize);

        // Морфологично затваряне за свързване на близки части
        mask = morphClose8(mask, w, h, 1);

        return mask;
    }

    // Липсващ метод generateEdges
    private boolean[] generateEdges(boolean[] objectMask, int w, int h) {
        boolean[] eroded = erode8(objectMask, w, h);
        boolean[] dilated = dilate8(objectMask, w, h);
        boolean[] edges = new boolean[w * h];

        for (int i = 0; i < w * h; i++) {
            edges[i] = (dilated[i] && !eroded[i]);
        }

        return edges;
    }

    // Липсващ помощен метод removeSmallRegions
    private boolean[] removeSmallRegions(boolean[] mask, int w, int h, int minSize) {
        boolean[] result = new boolean[w * h];
        boolean[] visited = new boolean[w * h];
        ArrayDeque<Point> queue = new ArrayDeque<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;

                if (mask[idx] && !visited[idx]) {
                    // Започваме флъд фил от тази точка
                    List<Point> region = new ArrayList<>();
                    queue.clear();
                    queue.add(new Point(x, y));
                    visited[idx] = true;

                    while (!queue.isEmpty()) {
                        Point p = queue.removeFirst();
                        region.add(p);
                        int px = p.x, py = p.y;

                        // Проверяваме 4-connected съседи
                        int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
                        for (int[] dir : dirs) {
                            int nx = px + dir[0];
                            int ny = py + dir[1];
                            if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                                int nIdx = ny * w + nx;
                                if (mask[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true;
                                    queue.add(new Point(nx, ny));
                                }
                            }
                        }
                    }

                    // Ако региона е достатъчно голям, запазваме го
                    if (region.size() >= minSize) {
                        for (Point p : region) {
                            result[p.y * w + p.x] = true;
                        }
                    }
                }
            }
        }

        return result;
    }

    private int floodFill(boolean[] fg, int[] labels, ArrayDeque<Point> q, int startX, int startY, int w, int h, int label) {
        q.clear();
        q.add(new Point(startX, startY));
        labels[startY * w + startX] = label;
        int area = 0;

        while (!q.isEmpty()) {
            Point p = q.removeFirst();
            int px = p.x, py = p.y;
            area++;

            int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
            for (int[] dir : dirs) {
                int nx = px + dir[0];
                int ny = py + dir[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    int nIdx = ny * w + nx;
                    if (fg[nIdx] && labels[nIdx] == 0) {
                        labels[nIdx] = label;
                        q.add(new Point(nx, ny));
                    }
                }
            }
        }
        return area;
    }

    private static boolean[] erode8(boolean[] src, int w, int h) {
        boolean[] dst = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!src[idx]) {
                    dst[idx] = false;
                    continue;
                }

                boolean canErode = true;
                for (int dy = -1; dy <= 1 && canErode; dy++) {
                    for (int dx = -1; dx <= 1 && canErode; dx++) {
                        int yy = y + dy;
                        int xx = x + dx;

                        if (yy < 0 || yy >= h || xx < 0 || xx >= w) {
                            canErode = false;
                        } else if (!src[yy * w + xx]) {
                            canErode = false;
                        }
                    }
                }
                dst[idx] = canErode;
            }
        }
        return dst;
    }

    private static boolean[] dilate8(boolean[] src, int w, int h) {
        boolean[] dst = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                boolean shouldDilate = src[idx];

                if (!shouldDilate) {
                    for (int dy = -1; dy <= 1 && !shouldDilate; dy++) {
                        for (int dx = -1; dx <= 1 && !shouldDilate; dx++) {
                            int yy = y + dy;
                            int xx = x + dx;
                            if (yy >= 0 && yy < h && xx >= 0 && xx < w) {
                                if (src[yy * w + xx]) {
                                    shouldDilate = true;
                                }
                            }
                        }
                    }
                }
                dst[idx] = shouldDilate;
            }
        }
        return dst;
    }

    private BufferedImage createMaskImage(boolean[] obj, int w, int h) {
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int maskARGB = (0xFF << 24) | (MASK_OBJECT_COLOR.getRed() << 16) |
                (MASK_OBJECT_COLOR.getGreen() << 8) | MASK_OBJECT_COLOR.getBlue();

        Graphics2D graphics = mask.createGraphics();
        graphics.setColor(MASK_BACKGROUND);
        graphics.fillRect(0, 0, w, h);
        graphics.dispose();

        for (int i = 0; i < obj.length; i++) {
            if (obj[i]) {
                mask.setRGB(i % w, i / w, maskARGB);
            }
        }
        return mask;
    }

    private BufferedImage createOverlayImage(BufferedImage input, boolean[] obj, boolean[] edge, int w, int h) {
        BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = overlay.createGraphics();
        graphics.drawImage(input, 0, 0, null);
        graphics.dispose();

        int redComponent = MASK_OBJECT_COLOR.getRed();
        int greenComponent = MASK_OBJECT_COLOR.getGreen();
        int blueComponent = MASK_OBJECT_COLOR.getBlue();
        int outlineARGB = (0xFF << 24) | (OUTLINE_COLOR.getRed() << 16) |
                (OUTLINE_COLOR.getGreen() << 8) | OUTLINE_COLOR.getBlue();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (obj[idx]) {
                    int base = overlay.getRGB(x, y);
                    int rValue = (base >> 16) & 0xFF;
                    int gValue = (base >> 8) & 0xFF;
                    int bValue = base & 0xFF;

                    int newRed = blend(rValue, redComponent, FILL_ALPHA);
                    int newGreen = blend(gValue, greenComponent, FILL_ALPHA);
                    int newBlue = blend(bValue, blueComponent, FILL_ALPHA);

                    overlay.setRGB(x, y, (0xFF << 24) | (newRed << 16) | (newGreen << 8) | newBlue);
                }
            }
        }

        for (int i = 0; i < edge.length; i++) {
            if (edge[i]) {
                overlay.setRGB(i % w, i / w, outlineARGB);
            }
        }

        return overlay;
    }

    private BufferedImage createRecoloredImage(BufferedImage input, boolean[] obj, int w, int h) {
        BufferedImage recolored = deepCopy(input);
        int redComponent = MASK_OBJECT_COLOR.getRed();
        int greenComponent = MASK_OBJECT_COLOR.getGreen();
        int blueComponent = MASK_OBJECT_COLOR.getBlue();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!obj[idx]) continue;

                int rgba = recolored.getRGB(x, y);
                int rValue = (rgba >> 16) & 0xFF;
                int gValue = (rgba >> 8) & 0xFF;
                int bValue = rgba & 0xFF;

                int newRed = blend(rValue, redComponent, TINT_ALPHA);
                int newGreen = blend(gValue, greenComponent, TINT_ALPHA);
                int newBlue = blend(bValue, blueComponent, TINT_ALPHA);

                recolored.setRGB(x, y, (0xFF << 24) | (newRed << 16) | (newGreen << 8) | newBlue);
            }
        }
        return recolored;
    }

    private static boolean contains(List<Integer> list, int v) {
        for (int x : list) if (x == v) return true;
        return false;
    }

    private static boolean[] constrainedGrow(boolean[] src, boolean[] allow, int w, int h, int iters) {
        boolean[] cur = Arrays.copyOf(src, src.length);
        for (int it = 0; it < iters; it++) {
            boolean[] d = dilate8(cur, w, h);
            for (int i = 0; i < d.length; i++) {
                d[i] = d[i] && allow[i];
            }
            cur = d;
        }
        return cur;
    }

    private static int dominantClusterOnBorder(int[] cluster, int w, int h, int k) {
        int[] c = new int[k];
        for (int x = 0; x < w; x++) {
            c[cluster[x]]++;
            c[cluster[(h - 1) * w + x]]++;
        }
        for (int y = 1; y < h - 1; y++) {
            c[cluster[y * w]]++;
            c[cluster[y * w + (w - 1)]]++;
        }
        int bg = 0;
        for (int i = 1; i < k; i++) {
            if (c[i] > c[bg]) bg = i;
        }
        return bg;
    }

    private static int otsuThreshold(int[] gray) {
        int[] hist = new int[256];
        for (int v : gray) hist[v & 0xFF]++;

        int total = gray.length;
        long sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += (long) i * hist[i];

        long sumB = 0;
        int wB = 0;
        double maxBetween = -1.0;
        int threshold = 0;

        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;

            int wF = total - wB;
            if (wF == 0) break;

            sumB += (long) t * hist[t];
            double mB = sumB / (double) wB;
            double mF = (sumAll - sumB) / (double) wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);

            if (between > maxBetween) {
                maxBetween = between;
                threshold = t;
            }
        }
        return threshold;
    }

    private static boolean[] openingByReconstruction(boolean[] src, int w, int h, int iters) {
        boolean[] seed = Arrays.copyOf(src, src.length);
        for (int i = 0; i < iters; i++) seed = erode8(seed, w, h);

        boolean[] rec = Arrays.copyOf(seed, seed.length);
        boolean changed = true;
        int safety = 0;

        while (changed && safety++ < 64) {
            boolean[] dil = dilate8(rec, w, h);
            changed = false;
            for (int i = 0; i < rec.length; i++) {
                boolean v = dil[i] && src[i];
                if (v != rec[i]) changed = true;
                rec[i] = v;
            }
        }
        return rec;
    }

    private static boolean[] fillHoles(boolean[] src, int w, int h) {
        int n = w * h;
        boolean[] visited = new boolean[n];
        boolean[] outside = new boolean[n];
        ArrayDeque<Point> q = new ArrayDeque<>();

        for (int x = 0; x < w; x++) {
            if (!src[x]) {
                outside[x] = true;
                visited[x] = true;
                q.add(new Point(x, 0));
            }
            int idx = (h - 1) * w + x;
            if (!src[idx]) {
                outside[idx] = true;
                visited[idx] = true;
                q.add(new Point(x, h - 1));
            }
        }

        for (int y = 1; y < h - 1; y++) {
            int l = y * w;
            if (!src[l]) {
                outside[l] = true;
                visited[l] = true;
                q.add(new Point(0, y));
            }
            int r = y * w + (w - 1);
            if (!src[r]) {
                outside[r] = true;
                visited[r] = true;
                q.add(new Point(w - 1, y));
            }
        }

        while (!q.isEmpty()) {
            Point p = q.removeFirst();
            int x = p.x, y = p.y;

            int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    int nIdx = ny * w + nx;
                    if (!src[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true;
                        outside[nIdx] = true;
                        q.add(new Point(nx, ny));
                    }
                }
            }
        }

        boolean[] out = Arrays.copyOf(src, n);
        for (int i = 0; i < n; i++) {
            if (!src[i] && !outside[i]) out[i] = true;
        }
        return out;
    }

    private static boolean[] morphOpen8(boolean[] src, int w, int h, int iters) {
        boolean[] out = Arrays.copyOf(src, src.length);
        for (int i = 0; i < iters; i++) {
            out = erode8(out, w, h);
            out = dilate8(out, w, h);
        }
        return out;
    }

    private static boolean[] morphClose8(boolean[] src, int w, int h, int iters) {
        boolean[] out = Arrays.copyOf(src, src.length);
        for (int i = 0; i < iters; i++) {
            out = dilate8(out, w, h);
            out = erode8(out, w, h);
        }
        return out;
    }

    private static int[] kmeans(float[] labSpace, int n, int k, int iters, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        float[] cent = new float[k * 3];

        for (int c = 0; c < k; c++) {
            int idx = rnd.nextInt(n);
            cent[3 * c]     = labSpace[3 * idx];
            cent[3 * c + 1] = labSpace[3 * idx + 1];
            cent[3 * c + 2] = labSpace[3 * idx + 2];
        }

        int[] assign = new int[n];
        Arrays.fill(assign, -1);
        float[] sum = new float[k * 3];
        int[] cnt = new int[k];

        for (int it = 0; it < iters; it++) {
            boolean changed = false;

            for (int i = 0; i < n; i++) {
                float L = labSpace[3 * i], A = labSpace[3 * i + 1], B = labSpace[3 * i + 2];
                int best = 0;
                float bestD = dist2(L, A, B, cent[0], cent[1], cent[2]);

                for (int c = 1; c < k; c++) {
                    float d = dist2(L, A, B, cent[3 * c], cent[3 * c + 1], cent[3 * c + 2]);
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }

                if (assign[i] != best) {
                    assign[i] = best;
                    changed = true;
                }
            }

            if (!changed && it > 0) break;

            Arrays.fill(sum, 0);
            Arrays.fill(cnt, 0);

            for (int i = 0; i < n; i++) {
                int c = assign[i];
                sum[3 * c]     += labSpace[3 * i];
                sum[3 * c + 1] += labSpace[3 * i + 1];
                sum[3 * c + 2] += labSpace[3 * i + 2];
                cnt[c]++;
            }

            for (int c = 0; c < k; c++) {
                if (cnt[c] == 0) continue;
                cent[3 * c]     = sum[3 * c] / cnt[c];
                cent[3 * c + 1] = sum[3 * c + 1] / cnt[c];
                cent[3 * c + 2] = sum[3 * c + 2] / cnt[c];
            }
        }
        return assign;
    }

    private static float dist2(float l1, float a1, float b1, float l2, float a2, float b2) {
        float dl = l1 - l2, da = a1 - a2, db = b1 - b2;
        return dl*dl + da*da + db*db;
    }

    private static float[] sRGBtoLab(int r8, int g8, int b8) {
        double r = invGamma(r8 / 255.0), g = invGamma(g8 / 255.0), b = invGamma(b8 / 255.0);
        double X = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double Y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double Z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;
        double Xn = 0.95047, Yn = 1.00000, Zn = 1.08883;
        double fx = fxyz(X / Xn), fy = fxyz(Y / Yn), fz = fxyz(Z / Zn);
        float L = (float) (116.0 * fy - 16.0);
        float A = (float) (500.0 * (fx - fy));
        float B = (float) (200.0 * (fy - fz));
        return new float[]{L, A, B};
    }

    private static double invGamma(double c) {
        return (c <= 0.04045) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double fxyz(double t) {
        double e = Math.pow(6.0/29.0,3);
        return (t > e) ? Math.cbrt(t) : (t / (3 * Math.pow(6.0/29.0,2)) + 4.0/29.0);
    }

    private static BufferedImage deepCopy(BufferedImage bi) {
        BufferedImage copy = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(bi, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static int blend(int orig, int tint, float alpha) {
        return clamp(Math.round(alpha * tint + (1f - alpha) * orig));
    }

    private static int clamp(int v) {
        return (v < 0) ? 0 : Math.min(255, v);
    }

    private static byte[] toPng(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
        catch (Exception e) {
            throw new SegmentationException("Failed to encode image", e);
        }
    }
}