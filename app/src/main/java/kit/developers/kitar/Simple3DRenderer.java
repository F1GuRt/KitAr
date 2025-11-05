package kit.developers.kitar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 3D рендерер с поддержкой OBJ моделей
 */
public class Simple3DRenderer {

    private static final String TAG = "Simple3DRenderer";
    private static final String MODEL_PATH = "models/model.obj"; // Путь к OBJ файлу

    private Context context;
    private List<Vector3> vertices;

    public List<Face> getFaces() {
        return faces;
    }

    public List<Vector3> getVertices() {
        return vertices;
    }

    private List<Face> faces;
    // Настройки положения и ротации модели (загружаются из ModelConfig)
    private float modelScale = ModelConfig.SCALE;
    private float rotationX = ModelConfig.ROTATION_X;
    private float rotationY = ModelConfig.ROTATION_Y;
    private float rotationZ = ModelConfig.ROTATION_Z;
    private float offsetX = ModelConfig.OFFSET_X;
    private float offsetY = ModelConfig.OFFSET_Y;
    private float offsetZ = ModelConfig.OFFSET_Z;

    private boolean isModelLoaded = false;

    public boolean isModelLoaded() {
        return isModelLoaded;
    }

    /**
     * Установить настройки модели
     * @param scale Масштаб (1.0 = нормальный размер, 2.0 = в 2 раза больше)
     * @param rotX Поворот по X в радианах (-1.0 до 1.0)
     * @param rotY Поворот по Y в радианах (-1.0 до 1.0)
     * @param rotZ Поворот по Z в радианах (-1.0 до 1.0)
     */
    public void setModelTransform(float scale, float rotX, float rotY, float rotZ) {
        this.modelScale = scale;
        this.rotationX = rotX;
        this.rotationY = rotY;
        this.rotationZ = rotZ;
    }

    /**
     * Установить смещение модели относительно центра QR
     * @param offsetX Смещение по X (-1.0 = влево, 1.0 = вправо)
     * @param offsetY Смещение по Y (-1.0 = вверх, 1.0 = вниз)
     * @param offsetZ Смещение по Z (-1.0 = ближе, 1.0 = дальше)
     */
    public void setModelOffset(float offsetX, float offsetY, float offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    public Simple3DRenderer(Context context) {
        this.context = context;
        try {
            loadOBJModel();
            if (!isModelLoaded) {
                // Если OBJ не загрузился, используем fallback
                android.widget.Toast.makeText(context, "OBJ не найден, используется куб",
                        android.widget.Toast.LENGTH_SHORT).show();
                createSimpleCube();
            } else {
                android.widget.Toast.makeText(context, "3D модель загружена успешно",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка в конструкторе", e);
            createSimpleCube(); // Fallback
            android.widget.Toast.makeText(context, "Используется тестовая модель",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Загрузка OBJ модели из assets
     */
    private void loadOBJModel() {
        try {
            InputStream inputStream = context.getAssets().open(MODEL_PATH);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            vertices = new ArrayList<>();
            faces = new ArrayList<>();
            List<int[]> faceIndices = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("v ")) {
                    // Вершина
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);
                        vertices.add(new Vector3(x, y, z));
                    }
                } else if (line.startsWith("f ")) {
                    // Грань
                    String[] parts = line.split("\\s+");
                    int[] indices = new int[parts.length - 1];

                    for (int i = 1; i < parts.length; i++) {
                        String indexStr = parts[i].split("/")[0];
                        indices[i - 1] = Integer.parseInt(indexStr) - 1; // OBJ индексы с 1
                    }

                    faceIndices.add(indices);
                }
            }

            reader.close();

            // Конвертируем индексы в Face объекты
            for (int[] indices : faceIndices) {
                faces.add(new Face(indices));
            }

            if (!vertices.isEmpty() && !faces.isEmpty()) {
                normalizeModel();
                isModelLoaded = true;
                Log.d(TAG, "OBJ модель загружена: " + vertices.size() + " вершин, " + faces.size() + " граней");
            }

        } catch (Exception e) {
            Log.e(TAG, "Не удалось загрузить OBJ модель", e);
            isModelLoaded = false;
        }
    }

    /**
     * Нормализация модели к единичному размеру
     */
    private void normalizeModel() {
        if (vertices.isEmpty()) return;

        // Находим границы модели
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;

        for (Vector3 v : vertices) {
            minX = Math.min(minX, v.x);
            maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z);
            maxZ = Math.max(maxZ, v.z);
        }

        // Центрируем модель
        float centerX = (minX + maxX) / 2;
        float centerY = (minY + maxY) / 2;
        float centerZ = (minZ + maxZ) / 2;

        // Находим максимальный размер
        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;
        float maxSize = Math.max(Math.max(sizeX, sizeY), sizeZ);

        // Нормализуем вершины
        float scale = maxSize > 0 ? 2.0f / maxSize : 1.0f;

        for (Vector3 v : vertices) {
            v.x = (v.x - centerX) * scale;
            v.y = (v.y - centerY) * scale;
            v.z = (v.z - centerZ) * scale;
        }

        Log.d(TAG, "Модель нормализована");
    }

    private void createSimpleCube() {
        vertices = new ArrayList<>();
        faces = new ArrayList<>();

        // Вершины куба
        vertices.add(new Vector3(-1, -1, -1));
        vertices.add(new Vector3(1, -1, -1));
        vertices.add(new Vector3(1, 1, -1));
        vertices.add(new Vector3(-1, 1, -1));
        vertices.add(new Vector3(-1, -1, 1));
        vertices.add(new Vector3(1, -1, 1));
        vertices.add(new Vector3(1, 1, 1));
        vertices.add(new Vector3(-1, 1, 1));

        // Грани куба
        faces.add(new Face(0, 1, 2, 3));
        faces.add(new Face(1, 5, 6, 2));
        faces.add(new Face(5, 4, 7, 6));
        faces.add(new Face(4, 0, 3, 7));
        faces.add(new Face(3, 2, 6, 7));
        faces.add(new Face(4, 5, 1, 0));

        isModelLoaded = true;
    }

    /**
     * Рендерит 3D модель на место QR-кода
     * @param backgroundBitmap Фото с селфи
     * @param qrBounds Координаты QR-кода на фото
     */
    public Bitmap renderModelOnBitmap(Bitmap backgroundBitmap, android.graphics.Rect qrBounds) {
        if (!isModelLoaded) {
            Log.e(TAG, "Модель не загружена");
            android.widget.Toast.makeText(context, "3D модель не загружена",
                    android.widget.Toast.LENGTH_SHORT).show();
            return backgroundBitmap;
        }

        try {
            int width = backgroundBitmap.getWidth();
            int height = backgroundBitmap.getHeight();

            Log.d(TAG, "Рендеринг на изображение " + width + "x" + height);
            Log.d(TAG, "QR позиция: " + qrBounds);

            Bitmap resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(resultBitmap);

            // Рисуем оригинальное фото
            canvas.drawBitmap(backgroundBitmap, 0, 0, null);

            // Рендерим 3D на место QR
            render3DAtPosition(canvas, width, height, qrBounds);

            Log.d(TAG, "Рендеринг завершен");

            return resultBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка рендеринга", e);
            android.widget.Toast.makeText(context, "Ошибка рендеринга: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return backgroundBitmap;
        }
    }

    public Bitmap renderModelOnBitmap(Bitmap backgroundBitmap) {
        // Fallback метод если QR позиция не найдена - рендерим в центре
        android.graphics.Rect centerBounds = new android.graphics.Rect(
                backgroundBitmap.getWidth() / 3,
                backgroundBitmap.getHeight() / 3,
                backgroundBitmap.getWidth() * 2 / 3,
                backgroundBitmap.getHeight() * 2 / 3
        );
        return renderModelOnBitmap(backgroundBitmap, centerBounds);
    }

    private void render3DAtPosition(Canvas canvas, int imgWidth, int imgHeight,
                                    android.graphics.Rect qrBounds) {

        // Вычисляем центр QR-кода
        float qrCenterX = qrBounds.centerX();
        float qrCenterY = qrBounds.centerY();

        // Размер QR-кода (берем больший из сторон для квадратного рендера)
        float qrSize = Math.max(qrBounds.width(), qrBounds.height());

        // Масштаб для модели на основе размера QR
        float scale = qrSize * modelScale * 0.8f; // 0.8 чтобы модель была чуть меньше QR

        // Применяем смещения
        float centerX = qrCenterX + (offsetX * qrSize);
        float centerY = qrCenterY + (offsetY * qrSize);

        Log.d(TAG, "Рендеринг в позиции: X=" + centerX + ", Y=" + centerY + ", Scale=" + scale);

        // Проецируем вершины
        List<Vector2> projectedVertices = new ArrayList<>();
        List<Float> zDepths = new ArrayList<>();

        for (Vector3 v : vertices) {
            // Применяем все трансформации
            Vector3 transformed = transformVertex(v);
            Vector2 projected = projectVertex(transformed, scale, centerX, centerY);
            projectedVertices.add(projected);
            zDepths.add(transformed.z);
        }

        // Сортируем грани по глубине
        List<FaceDepth> sortedFaces = new ArrayList<>();
        for (Face face : faces) {
            float avgDepth = 0;
            for (int idx : face.indices) {
                avgDepth += zDepths.get(idx);
            }
            avgDepth /= face.indices.length;
            sortedFaces.add(new FaceDepth(face, avgDepth));
        }

        sortedFaces.sort((a, b) -> Float.compare(a.depth, b.depth));

        // Настройка красок
        Paint fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);
        fillPaint.setShadowLayer(10, 5, 5, Color.argb(100, 0, 0, 0));

        Paint strokePaint = new Paint();
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2);
        strokePaint.setAntiAlias(true);

        // Рисуем грани
        for (FaceDepth fd : sortedFaces) {
            Face face = fd.face;

            // Вычисляем нормаль для освещения
            Vector3 normal = calculateNormal(face);

            // Освещение с учетом нормали
            float brightness = Math.max(0.3f, Math.abs(normal.z) * 0.7f + 0.3f);

            // Цвет из конфигурации
            int baseR = (int)(ModelConfig.COLOR_R * brightness);
            int baseG = (int)(ModelConfig.COLOR_G * brightness);
            int baseB = (int)(ModelConfig.COLOR_B * brightness);

            fillPaint.setColor(Color.argb(ModelConfig.ALPHA, baseR, baseG, baseB));
            strokePaint.setColor(Color.argb(255, baseR / 2, baseG / 2, baseB / 2));

            // Рисуем грань
            Path path = new Path();
            Vector2 first = projectedVertices.get(face.indices[0]);
            path.moveTo(first.x, first.y);

            for (int i = 1; i < face.indices.length; i++) {
                Vector2 point = projectedVertices.get(face.indices[i]);
                path.lineTo(point.x, point.y);
            }
            path.close();

            canvas.drawPath(path, fillPaint);
            canvas.drawPath(path, strokePaint);
        }
    }

    /**
     * Применяет все трансформации к вершине
     */
    private Vector3 transformVertex(Vector3 v) {
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

        // Применяем смещение по Z
        z += offsetZ;

        return new Vector3(x, y, z);
    }

    private Vector2 projectVertex(Vector3 v, float scale, float centerX, float centerY) {
        // Перспективная проекция
        float distance = 5.0f;
        float factor = scale / (distance + v.z);

        float x = v.x * factor + centerX;
        float y = -v.y * factor + centerY;

        return new Vector2(x, y);
    }

    private Vector3 calculateNormal(Face face) {
        if (face.indices.length < 3) {
            return new Vector3(0, 0, 1);
        }

        Vector3 v0 = vertices.get(face.indices[0]);
        Vector3 v1 = vertices.get(face.indices[1]);
        Vector3 v2 = vertices.get(face.indices[2]);

        Vector3 edge1 = new Vector3(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3 edge2 = new Vector3(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        // Векторное произведение
        float nx = edge1.y * edge2.z - edge1.z * edge2.y;
        float ny = edge1.z * edge2.x - edge1.x * edge2.z;
        float nz = edge1.x * edge2.y - edge1.y * edge2.x;

        // Нормализация
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) {
            nx /= length;
            ny /= length;
            nz /= length;
        }

        return new Vector3(nx, ny, nz);
    }

    // Вспомогательные классы (теперь public для использования в AROverlayView)
    public static class Vector3 {
        public float x, y, z;
        public Vector3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static class Vector2 {
        public float x, y;
        public Vector2(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static class Face {
        public int[] indices;
        public Face(int... indices) {
            this.indices = indices;
        }
    }

    private static class FaceDepth {
        Face face;
        float depth;
        FaceDepth(Face face, float depth) {
            this.face = face;
            this.depth = depth;
        }
    }

    public void destroy() {
        if (vertices != null) {
            vertices.clear();
        }
        if (faces != null) {
            faces.clear();
        }
    }
}