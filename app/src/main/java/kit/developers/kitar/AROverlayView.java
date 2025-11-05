package kit.developers.kitar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * View для отображения 3D модели поверх камеры в реальном времени
 */
public class AROverlayView extends View {

    private static final String TAG = "AROverlayView";

    private Rect qrBounds;
    private List<Simple3DRenderer.Vector3> vertices;
    private List<Simple3DRenderer.Face> faces;
    private boolean isModelLoaded = false;
    private boolean showQR = false;

    private Paint fillPaint;
    private Paint strokePaint;
    private Paint qrPaint;

    // Трансформации
    private float modelScale = ModelConfig.SCALE;
    private float rotationX = ModelConfig.ROTATION_X;
    private float rotationY = ModelConfig.ROTATION_Y;
    private float rotationZ = ModelConfig.ROTATION_Z;
    private float offsetX = ModelConfig.OFFSET_X;
    private float offsetY = ModelConfig.OFFSET_Y;
    private float offsetZ = ModelConfig.OFFSET_Z;

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

        fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        strokePaint = new Paint();
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2);
        strokePaint.setAntiAlias(true);

        qrPaint = new Paint();
        qrPaint.setStyle(Paint.Style.STROKE);
        qrPaint.setColor(Color.argb(150, 0, 255, 0));
        qrPaint.setStrokeWidth(4);
    }

    /**
     * Устанавливает геометрию модели для рендеринга
     */
    public void setModelGeometry(List<Simple3DRenderer.Vector3> vertices,
                                 List<Simple3DRenderer.Face> faces) {
        this.vertices = vertices;
        this.faces = faces;
        this.isModelLoaded = (vertices != null && !vertices.isEmpty() &&
                faces != null && !faces.isEmpty());
        Log.d(TAG, "Геометрия загружена: " + isModelLoaded);
    }

    /**
     * Обновляет позицию QR-кода для рендеринга
     */
    public void updateQRPosition(Rect bounds) {
        if (bounds != null) {
            this.qrBounds = new Rect(bounds);
            invalidate(); // Перерисовываем
        }
    }

    /**
     * Очищает QR позицию (скрывает модель)
     */
    public void clearQRPosition() {
        this.qrBounds = null;
        invalidate();
    }

    /**
     * Показывать/скрывать рамку QR
     */
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

        // Опционально рисуем рамку QR
        if (showQR) {
            canvas.drawRect(qrBounds, qrPaint);
        }

        // Рендерим 3D модель
        render3DModel(canvas);
    }

    private void render3DModel(Canvas canvas) {
        // Вычисляем центр QR-кода
        float qrCenterX = qrBounds.centerX();
        float qrCenterY = qrBounds.centerY();
        float qrSize = Math.max(qrBounds.width(), qrBounds.height());

        // Масштаб для модели
        float scale = qrSize * modelScale * 0.8f;

        // Применяем смещения
        float centerX = qrCenterX + (offsetX * qrSize);
        float centerY = qrCenterY + (offsetY * qrSize);

        // Проецируем вершины
        List<Vector2> projectedVertices = new ArrayList<>();
        List<Float> zDepths = new ArrayList<>();

        for (Simple3DRenderer.Vector3 v : vertices) {
            Simple3DRenderer.Vector3 transformed = transformVertex(v);
            Vector2 projected = projectVertex(transformed, scale, centerX, centerY);
            projectedVertices.add(projected);
            zDepths.add(transformed.z);
        }

        // Сортируем грани по глубине
        List<FaceDepth> sortedFaces = new ArrayList<>();
        for (Simple3DRenderer.Face face : faces) {
            float avgDepth = 0;
            for (int idx : face.indices) {
                if (idx < zDepths.size()) {
                    avgDepth += zDepths.get(idx);
                }
            }
            avgDepth /= face.indices.length;
            sortedFaces.add(new FaceDepth(face, avgDepth));
        }

        sortedFaces.sort((a, b) -> Float.compare(a.depth, b.depth));

        // Рисуем грани
        for (FaceDepth fd : sortedFaces) {
            Simple3DRenderer.Face face = fd.face;

            // Вычисляем нормаль для освещения
            Simple3DRenderer.Vector3 normal = calculateNormal(face);
            float brightness = Math.max(0.3f, Math.abs(normal.z) * 0.7f + 0.3f);

            // Цвет из конфигурации
            int baseR = (int)(ModelConfig.COLOR_R * brightness);
            int baseG = (int)(ModelConfig.COLOR_G * brightness);
            int baseB = (int)(ModelConfig.COLOR_B * brightness);

            fillPaint.setColor(Color.argb(ModelConfig.ALPHA, baseR, baseG, baseB));
            strokePaint.setColor(Color.argb(255, baseR / 2, baseG / 2, baseB / 2));

            // Рисуем грань
            Path path = new Path();
            if (face.indices.length > 0 && face.indices[0] < projectedVertices.size()) {
                Vector2 first = projectedVertices.get(face.indices[0]);
                path.moveTo(first.x, first.y);

                for (int i = 1; i < face.indices.length; i++) {
                    if (face.indices[i] < projectedVertices.size()) {
                        Vector2 point = projectedVertices.get(face.indices[i]);
                        path.lineTo(point.x, point.y);
                    }
                }
                path.close();

                canvas.drawPath(path, fillPaint);
                canvas.drawPath(path, strokePaint);
            }
        }
    }

    private Simple3DRenderer.Vector3 transformVertex(Simple3DRenderer.Vector3 v) {
        float x = v.x, y = v.y, z = v.z;

        // Поворот вокруг X
        if (rotationX != 0) {
            float cosX = (float) Math.cos(rotationX);
            float sinX = (float) Math.sin(rotationX);
            float y1 = y * cosX - z * sinX;
            float z1 = y * sinX + z * cosX;
            y = y1;
            z = z1;
        }

        // Поворот вокруг Y
        if (rotationY != 0) {
            float cosY = (float) Math.cos(rotationY);
            float sinY = (float) Math.sin(rotationY);
            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            x = x1;
            z = z1;
        }

        // Поворот вокруг Z
        if (rotationZ != 0) {
            float cosZ = (float) Math.cos(rotationZ);
            float sinZ = (float) Math.sin(rotationZ);
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

        Simple3DRenderer.Vector3 edge1 = new Simple3DRenderer.Vector3(
                v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Simple3DRenderer.Vector3 edge2 = new Simple3DRenderer.Vector3(
                v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        float nx = edge1.y * edge2.z - edge1.z * edge2.y;
        float ny = edge1.z * edge2.x - edge1.x * edge2.z;
        float nz = edge1.x * edge2.y - edge1.y * edge2.x;

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

    private static class FaceDepth {
        Simple3DRenderer.Face face;
        float depth;
        FaceDepth(Simple3DRenderer.Face face, float depth) {
            this.face = face;
            this.depth = depth;
        }
    }
}