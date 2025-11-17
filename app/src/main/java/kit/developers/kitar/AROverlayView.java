package kit.developers.kitar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Оптимизированный View с кэшированием и поддержкой текстур
 */
public class AROverlayView extends View {

    private static final String TAG = "AROverlayView";
    private static final long FRAME_TIME_MS = 33; // ~30 FPS

    private Rect qrBounds;
    private List<Simple3DRenderer.Vector3> vertices;
    private List<Simple3DRenderer.Face> faces;
    private Map<String, MaterialInfo> materials;
    private Map<String, Bitmap> textures; // Кэш текстур
    private boolean isModelLoaded = false;
    private boolean showQR = false;
    private boolean useMaterialColors = false;
    private boolean useTextures = false;

    private Paint qrPaint;
    private Paint bitmapPaint;

    // Кэшированная отрисовка модели
    private Bitmap cachedModelBitmap;
    private Matrix transformMatrix;
    private RectF lastQrBoundsF;

    private static final int CACHE_SIZE = 8000;

    // Трансформации модели
    private float modelScale = ModelConfig.SCALE;
    private float rotationX = ModelConfig.ROTATION_X;
    private float rotationY = ModelConfig.ROTATION_Y;
    private float rotationZ = ModelConfig.ROTATION_Z;
    private float offsetX = ModelConfig.OFFSET_X;
    private float offsetY = ModelConfig.OFFSET_Y;
    private float offsetZ = ModelConfig.OFFSET_Z;

    // Пользовательский масштаб
    private float userScale = 1.0f;
    private static final float MIN_SCALE = 0.2f;
    private static final float MAX_SCALE = 5.0f;

    // Предвычисленные значения
    private float cosX, sinX, cosY, sinY, cosZ, sinZ;

    // Асинхронная генерация кэша
    private ExecutorService renderExecutor;
    private AtomicBoolean isGeneratingCache = new AtomicBoolean(false);
    private AtomicBoolean needsRegenerateCache = new AtomicBoolean(true);

    // FPS контроль
    private long lastFrameTime = 0;

    // Сглаживание
    private float smoothCenterX, smoothCenterY, smoothScale;
    private static final float SMOOTH_FACTOR = 0.95f;

    // Детектор жестов
    private ScaleGestureDetector scaleGestureDetector;

    public interface OnScaleChangeListener {
        void onScaleChanged(float scale);
    }
    private OnScaleChangeListener scaleChangeListener;

    public AROverlayView(Context context) {
        super(context);
        init();
    }

    public AROverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        qrPaint = new Paint();
        qrPaint.setStyle(Paint.Style.STROKE);
        qrPaint.setColor(Color.argb(150, 0, 255, 0));
        qrPaint.setStrokeWidth(4);

        bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        transformMatrix = new Matrix();
        lastQrBoundsF = new RectF();
        materials = new HashMap<>();
        textures = new HashMap<>();

        renderExecutor = Executors.newSingleThreadExecutor();
        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleListener());

        precomputeTransforms();
    }

    private void precomputeTransforms() {
        if (rotationX != 0) {
            cosX = (float) Math.cos(rotationX);
            sinX = (float) Math.sin(rotationX);
        }
        if (rotationY != 0) {
            cosY = (float) Math.cos(rotationY);
            sinY = (float) Math.sin(rotationY);
        }
        if (rotationZ != 0) {
            cosZ = (float) Math.cos(rotationZ);
            sinZ = (float) Math.sin(rotationZ);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleGestureDetector.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            userScale *= scaleFactor;
            userScale = Math.max(MIN_SCALE, Math.min(userScale, MAX_SCALE));

            if (scaleChangeListener != null) {
                scaleChangeListener.onScaleChanged(userScale);
            }

            invalidate();
            return true;
        }
    }

    public void setOnScaleChangeListener(OnScaleChangeListener listener) {
        this.scaleChangeListener = listener;
    }

    public float getUserScale() {
        return userScale;
    }

    public void setUserScale(float scale) {
        this.userScale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
        invalidate();
    }

    public void resetScale() {
        this.userScale = 1.0f;
        if (scaleChangeListener != null) {
            scaleChangeListener.onScaleChanged(userScale);
        }
        invalidate();
    }

    /**
     * Установка геометрии модели с текстурами
     */
    public void setModelGeometry(List<Simple3DRenderer.Vector3> vertices,
                                 List<Simple3DRenderer.Face> faces) {
        this.vertices = vertices;
        this.faces = faces;
        this.isModelLoaded = (vertices != null && !vertices.isEmpty() &&
                faces != null && !faces.isEmpty());

        // Проверяем наличие материалов
        if (isModelLoaded && faces != null) {
            useMaterialColors = false;
            for (Simple3DRenderer.Face face : faces) {
                if (face.materialName != null) {
                    useMaterialColors = true;
                    break;
                }
            }
        }

        if (isModelLoaded) {
            Log.d(TAG, "Геометрия загружена: " + vertices.size() + " вершин, " +
                    "Текстуры: " + (useTextures ? "Да" : "Нет"));
            needsRegenerateCache.set(true);
            generateCachedModel();
        }
    }

    /**
     * Установка материалов и текстур
     */
    public void setMaterialsAndTextures(Map<String, MaterialInfo> materials,
                                        Map<String, Bitmap> textures) {
        this.materials.clear();
        this.textures.clear();

        if (materials != null) {
            this.materials.putAll(materials);
            useMaterialColors = !this.materials.isEmpty();
        }

        if (textures != null) {
            this.textures.putAll(textures);
            useTextures = !this.textures.isEmpty();
        }

        Log.d(TAG, "Установлено материалов: " + this.materials.size() +
                ", текстур: " + this.textures.size());
    }

    public void updateQRPosition(Rect bounds) {
        if (bounds != null) {
            this.qrBounds = new Rect(bounds);

            if (needsRegenerateCache.get() && cachedModelBitmap == null) {
                generateCachedModel();
            }

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFrameTime >= FRAME_TIME_MS) {
                lastFrameTime = currentTime;
                invalidate();
            }
        }
    }

    /**
     * Генерация кэша с текстурами
     */
    private void generateCachedModel() {
        if (!isModelLoaded || isGeneratingCache.get()) {
            return;
        }

        isGeneratingCache.set(true);

        renderExecutor.execute(() -> {
            try {
                Log.d(TAG, "Генерация кэша модели (текстуры: " + useTextures + ")...");

                Bitmap bitmap = Bitmap.createBitmap(CACHE_SIZE, CACHE_SIZE, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);

                renderModelToCanvas(canvas, CACHE_SIZE, CACHE_SIZE);

                cachedModelBitmap = bitmap;
                needsRegenerateCache.set(false);

                Log.d(TAG, "Кэш модели готов!");
                postInvalidate();

            } catch (Exception e) {
                Log.e(TAG, "Ошибка генерации кэша", e);
            } finally {
                isGeneratingCache.set(false);
            }
        });
    }

    /**
     * Получить цвет из текстуры по UV gd
     */
    private int getTextureColor(Bitmap texture, float u, float v, float brightness) {
        if (texture == null) {
            return Color.WHITE;
        }

        // Нормализуем UV
        u = u - (float)Math.floor(u);
        v = v - (float)Math.floor(v);

        // Конвертируем в пиксели
        int x = Math.max(0, Math.min((int)(u * (texture.getWidth() - 1)), texture.getWidth() - 1));
        int y = Math.max(0, Math.min((int)((1.0f - v) * (texture.getHeight() - 1)), texture.getHeight() - 1));

        int pixel = texture.getPixel(x, y);

        int r = (int)(Color.red(pixel) * brightness);
        int g = (int)(Color.green(pixel) * brightness);
        int b = (int)(Color.blue(pixel) * brightness);
        int a = Color.alpha(pixel);

        return Color.argb(a, r, g, b);
    }

    /**
     * Рендерит модель с текстурами
     */
    private void renderModelToCanvas(Canvas canvas, int width, int height) {
        float centerX = width / 2f;
        float centerY = height / 2f;
        float scale = width * 0.35f;

        // Проецируем вершины (с сохранением индексов)
        List<ProjectedVertex> projectedVertices = new ArrayList<>();

        for (int i = 0; i < vertices.size(); i++) {
            Simple3DRenderer.Vector3 v = vertices.get(i);
            Simple3DRenderer.Vector3 transformed = transformVertex(v);
            Vector2 projected = projectVertex(transformed, scale, centerX, centerY);

            // UV координаты будут извлечены из граней
            projectedVertices.add(new ProjectedVertex(projected, transformed.z, 0, 0));
        }

        // Сортируем грани
        List<FaceDepth> sortedFaces = new ArrayList<>();
        for (Simple3DRenderer.Face face : faces) {
            float avgDepth = 0;
            for (int idx : face.vertexIndices) {
                if (idx < projectedVertices.size()) {
                    avgDepth += projectedVertices.get(idx).z;
                }
            }
            avgDepth /= face.vertexIndices.length;
            sortedFaces.add(new FaceDepth(face, avgDepth));
        }

        sortedFaces.sort((a, b) -> Float.compare(a.depth, b.depth));

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1);

        Path path = new Path();

        // Рисуем грани
        for (FaceDepth fd : sortedFaces) {
            Simple3DRenderer.Face face = fd.face;

            Simple3DRenderer.Vector3 normal = calculateNormal(face);
            float brightness = Math.max(0.4f, Math.abs(normal.z) * 0.6f + 0.4f);

            // Получаем цвет (с текстурой если есть)
            int color = getFaceColor(face, brightness);

            fillPaint.setColor(color);
            strokePaint.setColor(Color.argb(
                    Math.min(255, Color.alpha(color) + 30),
                    Color.red(color) / 2,
                    Color.green(color) / 2,
                    Color.blue(color) / 2
            ));

            path.rewind();
            if (face.vertexIndices.length > 0 && face.vertexIndices[0] < projectedVertices.size()) {
                Vector2 first = projectedVertices.get(face.vertexIndices[0]).position;
                path.moveTo(first.x, first.y);

                for (int i = 1; i < face.vertexIndices.length; i++) {
                    if (face.vertexIndices[i] < projectedVertices.size()) {
                        Vector2 point = projectedVertices.get(face.vertexIndices[i]).position;
                        path.lineTo(point.x, point.y);
                    }
                }
                path.close();

                canvas.drawPath(path, fillPaint);
                canvas.drawPath(path, strokePaint);
            }
        }
    }

    /**
     * Получить цвет грани с учетом текстуры
     */
    private int getFaceColor(Simple3DRenderer.Face face, float brightness) {
        if (face.materialName != null && materials.containsKey(face.materialName)) {
            MaterialInfo material = materials.get(face.materialName);

            // Если есть текстура для этого материала
            if (useTextures && textures.containsKey(face.materialName)) {
                Bitmap texture = textures.get(face.materialName);

                // Берем UV первой вершины (упрощение)
                if (face.uvIndices != null && face.uvIndices.length > 0) {
                    // UV координаты сохранены в face, используем центр грани
                    float avgU = 0.5f;
                    float avgV = 0.5f;
                    return getTextureColor(texture, avgU, avgV, brightness);
                }
            }

            // Цвет материала без текстуры
            int r = (int)(material.colorR * brightness);
            int g = (int)(material.colorG * brightness);
            int b = (int)(material.colorB * brightness);
            int alpha = material.alpha;

            return Color.argb(alpha, r, g, b);
        } else {
            // Стандартный цвет
            int r = (int)(ModelConfig.COLOR_R * brightness);
            int g = (int)(ModelConfig.COLOR_G * brightness);
            int b = (int)(ModelConfig.COLOR_B * brightness);
            int alpha = ModelConfig.ALPHA;

            return Color.argb(alpha, r, g, b);
        }
    }

    public void clearQRPosition() {
        this.qrBounds = null;
        invalidate();
    }

    public void setShowQRFrame(boolean show) {
        this.showQR = show;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (qrBounds == null || !isModelLoaded) {
            return;
        }

        if (showQR) {
            canvas.drawRect(qrBounds, qrPaint);
        }

        if (cachedModelBitmap != null) {
            drawTransformedModel(canvas);
        }
    }

    private void drawTransformedModel(Canvas canvas) {
        float qrCenterX = qrBounds.centerX();
        float qrCenterY = qrBounds.centerY();
        float qrSize = Math.max(qrBounds.width(), qrBounds.height());

        float targetCenterX = qrCenterX + (offsetX * qrSize);
        float targetCenterY = qrCenterY + (offsetY * qrSize);
        float targetScale = (qrSize * modelScale * userScale * 0.8f) / (CACHE_SIZE * 0.35f);

        if (smoothCenterX == 0 && smoothCenterY == 0) {
            smoothCenterX = targetCenterX;
            smoothCenterY = targetCenterY;
            smoothScale = targetScale;
        } else {
            smoothCenterX += (targetCenterX - smoothCenterX) * SMOOTH_FACTOR;
            smoothCenterY += (targetCenterY - smoothCenterY) * SMOOTH_FACTOR;
            smoothScale += (targetScale - smoothScale) * SMOOTH_FACTOR;
        }

        transformMatrix.reset();
        transformMatrix.postTranslate(-CACHE_SIZE / 2f, -CACHE_SIZE / 2f);
        transformMatrix.postScale(smoothScale, smoothScale);
        transformMatrix.postTranslate(smoothCenterX, smoothCenterY);

        canvas.drawBitmap(cachedModelBitmap, transformMatrix, bitmapPaint);
    }

    private Simple3DRenderer.Vector3 transformVertex(Simple3DRenderer.Vector3 v) {
        float x = v.x, y = v.y, z = v.z;

        if (rotationX != 0) {
            float y1 = y * cosX - z * sinX;
            float z1 = y * sinX + z * cosX;
            y = y1;
            z = z1;
        }

        if (rotationY != 0) {
            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            x = x1;
            z = z1;
        }

        if (rotationZ != 0) {
            float x1 = x * cosZ - y * sinZ;
            float y1 = x * sinZ + y * cosZ;
            x = x1;
            y = y1;
        }

        z += offsetZ;
        return new Simple3DRenderer.Vector3(x, y, z);
    }

    private Vector2 projectVertex(Simple3DRenderer.Vector3 v, float scale,
                                  float centerX, float centerY) {
        float distance = 5.0f;
        float factor = scale / (distance + v.z);
        float x = v.x * factor + centerX;
        float y = -v.y * factor + centerY;
        return new Vector2(x, y);
    }

    private Simple3DRenderer.Vector3 calculateNormal(Simple3DRenderer.Face face) {
        if (face.vertexIndices.length < 3 || vertices == null || vertices.isEmpty()) {
            return new Simple3DRenderer.Vector3(0, 0, 1);
        }

        if (face.vertexIndices[0] >= vertices.size() ||
                face.vertexIndices[1] >= vertices.size() ||
                face.vertexIndices[2] >= vertices.size()) {
            return new Simple3DRenderer.Vector3(0, 0, 1);
        }

        Simple3DRenderer.Vector3 v0 = vertices.get(face.vertexIndices[0]);
        Simple3DRenderer.Vector3 v1 = vertices.get(face.vertexIndices[1]);
        Simple3DRenderer.Vector3 v2 = vertices.get(face.vertexIndices[2]);

        float e1x = v1.x - v0.x, e1y = v1.y - v0.y, e1z = v1.z - v0.z;
        float e2x = v2.x - v0.x, e2y = v2.y - v0.y, e2z = v2.z - v0.z;

        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;

        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) {
            nx /= length;
            ny /= length;
            nz /= length;
        }

        return new Simple3DRenderer.Vector3(nx, ny, nz);
    }

    private static class Vector2 {
        float x, y;
        Vector2(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class ProjectedVertex {
        Vector2 position;
        float z;
        float u, v;

        ProjectedVertex(Vector2 position, float z, float u, float v) {
            this.position = position;
            this.z = z;
            this.u = u;
            this.v = v;
        }
    }

    private static class FaceDepth {
        Simple3DRenderer.Face face;
        float depth;
        FaceDepth(Simple3DRenderer.Face face, float depth) {
            this.face = face;
            this.depth = depth;
        }
    }

    public static class MaterialInfo {
        public int colorR;
        public int colorG;
        public int colorB;
        public int alpha;

        public MaterialInfo(int r, int g, int b, int alpha) {
            this.colorR = r;
            this.colorG = g;
            this.colorB = b;
            this.alpha = alpha;
        }
    }

    public void cleanup() {
        if (renderExecutor != null) {
            renderExecutor.shutdown();
        }
        if (cachedModelBitmap != null && !cachedModelBitmap.isRecycled()) {
            cachedModelBitmap.recycle();
            cachedModelBitmap = null;
        }
        if (materials != null) {
            materials.clear();
        }
        if (textures != null) {
            // НЕ освобождаем текстуры здесь - они принадлежат рендереру
            textures.clear();
        }
    }
}