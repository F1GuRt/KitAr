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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Оптимизированный View с кэшированием отрисованной модели в Bitmap
 * + поддержка масштабирования жестами
 */
public class AROverlayView extends View {

    private static final String TAG = "AROverlayView";
    private static final long FRAME_TIME_MS = 33; // ~30 FPS

    private Rect qrBounds;
    private List<Simple3DRenderer.Vector3> vertices;
    private List<Simple3DRenderer.Face> faces;
    private boolean isModelLoaded = false;
    private boolean showQR = false;

    private Paint qrPaint;
    private Paint bitmapPaint;

    // Кэшированная отрисовка модели
    private Bitmap cachedModelBitmap;
    private Matrix transformMatrix;
    private RectF lastQrBoundsF;

    // Базовый размер для кэша (квадрат)
    private static final int CACHE_SIZE = 8000;

    // Трансформации модели
    private float modelScale = ModelConfig.SCALE;
    private float rotationX = ModelConfig.ROTATION_X;
    private float rotationY = ModelConfig.ROTATION_Y;
    private float rotationZ = ModelConfig.ROTATION_Z;
    private float offsetX = ModelConfig.OFFSET_X;
    private float offsetY = ModelConfig.OFFSET_Y;
    private float offsetZ = ModelConfig.OFFSET_Z;

    // Пользовательский масштаб (через жесты)
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

    // Детектор жестов масштабирования
    private ScaleGestureDetector scaleGestureDetector;

    // Listener для оповещения об изменении масштаба
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

        renderExecutor = Executors.newSingleThreadExecutor();

        // Инициализируем детектор жестов
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

    /**
     * Слушатель жестов масштабирования
     */
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();

            // Применяем новый масштаб с ограничениями
            userScale *= scaleFactor;
            userScale = Math.max(MIN_SCALE, Math.min(userScale, MAX_SCALE));

            // Оповещаем listener об изменении
            if (scaleChangeListener != null) {
                scaleChangeListener.onScaleChanged(userScale);
            }

            // Обновляем отрисовку
            invalidate();

            Log.d(TAG, "Масштаб изменен: " + userScale);
            return true;
        }
    }

    public void setOnScaleChangeListener(OnScaleChangeListener listener) {
        this.scaleChangeListener = listener;
    }

    /**
     * Получить текущий пользовательский масштаб
     */
    public float getUserScale() {
        return userScale;
    }

    /**
     * Установить пользовательский масштаб программно
     */
    public void setUserScale(float scale) {
        this.userScale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
        invalidate();
    }

    /**
     * Сбросить масштаб к исходному
     */
    public void resetScale() {
        this.userScale = 1.0f;
        if (scaleChangeListener != null) {
            scaleChangeListener.onScaleChanged(userScale);
        }
        invalidate();
    }

    public void setModelGeometry(List<Simple3DRenderer.Vector3> vertices,
                                 List<Simple3DRenderer.Face> faces) {
        this.vertices = vertices;
        this.faces = faces;
        this.isModelLoaded = (vertices != null && !vertices.isEmpty() &&
                faces != null && !faces.isEmpty());

        if (isModelLoaded) {
            Log.d(TAG, "Геометрия загружена: " + vertices.size() + " вершин");
            needsRegenerateCache.set(true);
            generateCachedModel();
        }
    }

    public void updateQRPosition(Rect bounds) {
        if (bounds != null) {
            this.qrBounds = new Rect(bounds);

            // Генерируем кэш если нужно
            if (needsRegenerateCache.get() && cachedModelBitmap == null) {
                generateCachedModel();
            }

            // Контроль FPS
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFrameTime >= FRAME_TIME_MS) {
                lastFrameTime = currentTime;
                invalidate();
            }
        }
    }

    /**
     * Генерирует кэшированную отрисовку модели ОДИН РАЗ
     */
    private void generateCachedModel() {
        if (!isModelLoaded || isGeneratingCache.get()) {
            return;
        }

        isGeneratingCache.set(true);

        renderExecutor.execute(() -> {
            try {
                Log.d(TAG, "Генерация кэша модели...");

                // Создаем bitmap для кэша
                Bitmap bitmap = Bitmap.createBitmap(CACHE_SIZE, CACHE_SIZE, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);

                // Рендерим модель в центр bitmap
                renderModelToCanvas(canvas, CACHE_SIZE, CACHE_SIZE);

                // Сохраняем кэш
                cachedModelBitmap = bitmap;
                needsRegenerateCache.set(false);

                Log.d(TAG, "Кэш модели готов!");

                // Обновляем UI
                postInvalidate();

            } catch (Exception e) {
                Log.e(TAG, "Ошибка генерации кэша", e);
            } finally {
                isGeneratingCache.set(false);
            }
        });
    }

    /**
     * Рендерит 3D модель на canvas (вызывается ОДИН РАЗ для создания кэша)
     */
    private void renderModelToCanvas(Canvas canvas, int width, int height) {
        float centerX = width / 2f;
        float centerY = height / 2f;
        float scale = width * 0.35f; // Модель занимает ~70% bitmap

        // Проецируем вершины
        List<ProjectedVertex> projectedVertices = new ArrayList<>();

        for (Simple3DRenderer.Vector3 v : vertices) {
            Simple3DRenderer.Vector3 transformed = transformVertex(v);
            Vector2 projected = projectVertex(transformed, scale, centerX, centerY);
            projectedVertices.add(new ProjectedVertex(projected, transformed.z));
        }

        // Сортируем грани по глубине
        List<FaceDepth> sortedFaces = new ArrayList<>();
        for (Simple3DRenderer.Face face : faces) {
            float avgDepth = 0;
            for (int idx : face.indices) {
                if (idx < projectedVertices.size()) {
                    avgDepth += projectedVertices.get(idx).z;
                }
            }
            avgDepth /= face.indices.length;
            sortedFaces.add(new FaceDepth(face, avgDepth));
        }

        sortedFaces.sort((a, b) -> Float.compare(a.depth, b.depth));

        // Рисуем грани
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2);

        Path path = new Path();

        for (FaceDepth fd : sortedFaces) {
            Simple3DRenderer.Face face = fd.face;

            // Вычисляем нормаль для освещения
            Simple3DRenderer.Vector3 normal = calculateNormal(face);
            float brightness = Math.max(0.3f, Math.abs(normal.z) * 0.7f + 0.3f);

            // Цвет
            int baseR = (int)(ModelConfig.COLOR_R * brightness);
            int baseG = (int)(ModelConfig.COLOR_G * brightness);
            int baseB = (int)(ModelConfig.COLOR_B * brightness);

            fillPaint.setColor(Color.argb(ModelConfig.ALPHA, baseR, baseG, baseB));
            strokePaint.setColor(Color.argb(255, baseR / 2, baseG / 2, baseB / 2));

            // Строим путь
            path.rewind();
            if (face.indices.length > 0 && face.indices[0] < projectedVertices.size()) {
                Vector2 first = projectedVertices.get(face.indices[0]).position;
                path.moveTo(first.x, first.y);

                for (int i = 1; i < face.indices.length; i++) {
                    if (face.indices[i] < projectedVertices.size()) {
                        Vector2 point = projectedVertices.get(face.indices[i]).position;
                        path.lineTo(point.x, point.y);
                    }
                }
                path.close();

                canvas.drawPath(path, fillPaint);
                canvas.drawPath(path, strokePaint);
            }
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

        // Рисуем рамку QR
        if (showQR) {
            canvas.drawRect(qrBounds, qrPaint);
        }

        // Рисуем кэшированную модель с трансформацией
        if (cachedModelBitmap != null) {
            drawTransformedModel(canvas);
        }
    }

    /**
     * БЫСТРАЯ отрисовка - просто трансформируем и рисуем готовый bitmap
     * Теперь с учетом пользовательского масштаба
     */
    private void drawTransformedModel(Canvas canvas) {
        float qrCenterX = qrBounds.centerX();
        float qrCenterY = qrBounds.centerY();
        float qrSize = Math.max(qrBounds.width(), qrBounds.height());

        // Целевая позиция и размер (теперь с userScale)
        float targetCenterX = qrCenterX + (offsetX * qrSize);
        float targetCenterY = qrCenterY + (offsetY * qrSize);
        float targetScale = (qrSize * modelScale * userScale * 0.8f) / (CACHE_SIZE * 0.35f);

        // Плавное сглаживание
        if (smoothCenterX == 0 && smoothCenterY == 0) {
            smoothCenterX = targetCenterX;
            smoothCenterY = targetCenterY;
            smoothScale = targetScale;
        } else {
            smoothCenterX += (targetCenterX - smoothCenterX) * SMOOTH_FACTOR;
            smoothCenterY += (targetCenterY - smoothCenterY) * SMOOTH_FACTOR;
            smoothScale += (targetScale - smoothScale) * SMOOTH_FACTOR;
        }

        // Создаем матрицу трансформации
        transformMatrix.reset();

        // 1. Смещаем bitmap так чтобы центр был в (0,0)
        transformMatrix.postTranslate(-CACHE_SIZE / 2f, -CACHE_SIZE / 2f);

        // 2. Масштабируем
        transformMatrix.postScale(smoothScale, smoothScale);

        // 3. Перемещаем в финальную позицию
        transformMatrix.postTranslate(smoothCenterX, smoothCenterY);

        // РИСУЕМ - это ОЧЕНЬ быстрая операция!
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
        if (face.indices.length < 3 || vertices == null || vertices.isEmpty()) {
            return new Simple3DRenderer.Vector3(0, 0, 1);
        }

        if (face.indices[0] >= vertices.size() ||
                face.indices[1] >= vertices.size() ||
                face.indices[2] >= vertices.size()) {
            return new Simple3DRenderer.Vector3(0, 0, 1);
        }

        Simple3DRenderer.Vector3 v0 = vertices.get(face.indices[0]);
        Simple3DRenderer.Vector3 v1 = vertices.get(face.indices[1]);
        Simple3DRenderer.Vector3 v2 = vertices.get(face.indices[2]);

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
        ProjectedVertex(Vector2 position, float z) {
            this.position = position;
            this.z = z;
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

    public void cleanup() {
        if (renderExecutor != null) {
            renderExecutor.shutdown();
        }
        if (cachedModelBitmap != null && !cachedModelBitmap.isRecycled()) {
            cachedModelBitmap.recycle();
            cachedModelBitmap = null;
        }
    }
}