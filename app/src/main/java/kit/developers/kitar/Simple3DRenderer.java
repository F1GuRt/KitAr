package kit.developers.kitar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D рендерер с поддержкой OBJ, MTL и текстур
 */
public class Simple3DRenderer {

    private static final String TAG = "Simple3DRenderer";
    private static final String MODEL_PATH = "models/model.obj";

    private Context context;
    private List<Vertex> vertices;           // Вершины с UV координатами
    private List<Face> faces;
    private Map<String, Material> materials; // Материалы из MTL
    private boolean useMaterialColors = false;
    private boolean useTextures = false;

    // Настройки трансформации
    private float modelScale = ModelConfig.SCALE;
    private float rotationX = ModelConfig.ROTATION_X;
    private float rotationY = ModelConfig.ROTATION_Y;
    private float rotationZ = ModelConfig.ROTATION_Z;
    private float offsetX = ModelConfig.OFFSET_X;
    private float offsetY = ModelConfig.OFFSET_Y;
    private float offsetZ = ModelConfig.OFFSET_Z;
    private float userScale = 1.0f;

    private boolean isModelLoaded = false;

    public boolean isModelLoaded() {
        return isModelLoaded;
    }

    public List<Face> getFaces() {
        return faces;
    }

    // Для совместимости с AROverlayView
    public List<Simple3DRenderer.Vector3> getVertices() {
        List<Simple3DRenderer.Vector3> result = new ArrayList<>();
        for (Vertex v : vertices) {
            result.add(new Simple3DRenderer.Vector3(v.x, v.y, v.z));
        }
        return result;
    }

    public void setModelTransform(float scale, float rotX, float rotY, float rotZ) {
        this.modelScale = scale;
        this.rotationX = rotX;
        this.rotationY = rotY;
        this.rotationZ = rotZ;
    }

    public void setModelOffset(float offsetX, float offsetY, float offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    public void setUserScale(float scale) {
        this.userScale = scale;
        Log.d(TAG, "Пользовательский масштаб установлен: " + scale);
    }

    public float getUserScale() {
        return userScale;
    }

    public Simple3DRenderer(Context context) {
        this.context = context;
        this.materials = new HashMap<>();
        try {
            loadOBJModel();
            if (!isModelLoaded) {
                android.widget.Toast.makeText(context, "Модель не найдена",
                        android.widget.Toast.LENGTH_SHORT).show();
                createSimpleCube();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка в конструкторе", e);
            createSimpleCube();
            android.widget.Toast.makeText(context, "Используется тестовая модель",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Загрузка MTL файла с поддержкой текстур
     */
    private void loadMTLFile(String mtlPath) {
        try {
            Log.d(TAG, "Загрузка MTL файла: " + mtlPath);

            InputStream inputStream = context.getAssets().open(mtlPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            Material currentMaterial = null;
            String line;
            String modelFolder = mtlPath.substring(0, mtlPath.lastIndexOf('/') + 1);

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("newmtl ")) {
                    String materialName = line.substring(7).trim();
                    currentMaterial = new Material(materialName);
                    materials.put(materialName, currentMaterial);
                    Log.d(TAG, "Создан материал: " + materialName);

                } else if (line.startsWith("Kd ") && currentMaterial != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float r = Float.parseFloat(parts[1]);
                        float g = Float.parseFloat(parts[2]);
                        float b = Float.parseFloat(parts[3]);
                        currentMaterial.diffuseColor = new float[]{r, g, b};
                    }

                } else if (line.startsWith("Ka ") && currentMaterial != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float r = Float.parseFloat(parts[1]);
                        float g = Float.parseFloat(parts[2]);
                        float b = Float.parseFloat(parts[3]);
                        currentMaterial.ambientColor = new float[]{r, g, b};
                    }

                } else if (line.startsWith("Ks ") && currentMaterial != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float r = Float.parseFloat(parts[1]);
                        float g = Float.parseFloat(parts[2]);
                        float b = Float.parseFloat(parts[3]);
                        currentMaterial.specularColor = new float[]{r, g, b};
                    }

                } else if (line.startsWith("d ") && currentMaterial != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        currentMaterial.transparency = Float.parseFloat(parts[1]);
                    }

                } else if (line.startsWith("Tr ") && currentMaterial != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        currentMaterial.transparency = 1.0f - Float.parseFloat(parts[1]);
                    }

                } else if (line.startsWith("map_Kd ") && currentMaterial != null) {
                    // Диффузная текстура (основная)
                    String textureName = line.substring(7).trim();
                    String texturePath = modelFolder + textureName;
                    Bitmap texture = loadTexture(texturePath);
                    if (texture != null) {
                        currentMaterial.diffuseTexture = texture;
                        useTextures = true;
                        Log.d(TAG, "Текстура загружена: " + textureName +
                                " (" + texture.getWidth() + "x" + texture.getHeight() + ")");
                    }

                } else if (line.startsWith("map_d ") && currentMaterial != null) {
                    // Карта прозрачности
                    String textureName = line.substring(6).trim();
                    String texturePath = modelFolder + textureName;
                    Bitmap texture = loadTexture(texturePath);
                    if (texture != null) {
                        currentMaterial.alphaTexture = texture;
                        Log.d(TAG, "Карта прозрачности загружена: " + textureName);
                    }
                }
            }

            reader.close();
            Log.d(TAG, "MTL файл загружен. Материалов: " + materials.size() +
                    ", Текстуры: " + (useTextures ? "Да" : "Нет"));
            useMaterialColors = !materials.isEmpty();

        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки MTL файла: " + mtlPath, e);
            useMaterialColors = false;
            useTextures = false;
        }
    }

    /**
     * Загрузка текстуры из assets
     */
    private Bitmap loadTexture(String texturePath) {
        try {
            InputStream is = context.getAssets().open(texturePath);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Не удалось загрузить текстуру: " + texturePath);
            return null;
        }
    }

    /**
     * Загрузка OBJ модели с UV координатами
     */
    private void loadOBJModel() {
        try {
            InputStream inputStream = context.getAssets().open(MODEL_PATH);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            vertices = new ArrayList<>();
            List<UV> uvCoords = new ArrayList<>(); // Текстурные координаты
            faces = new ArrayList<>();
            String currentMaterialName = null;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("mtllib ")) {
                    String mtlFileName = line.substring(7).trim();
                    String mtlPath = "models/" + mtlFileName;
                    loadMTLFile(mtlPath);

                } else if (line.startsWith("usemtl ")) {
                    currentMaterialName = line.substring(7).trim();
                    Log.d(TAG, "Переключение на материал: " + currentMaterialName);

                } else if (line.startsWith("v ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);
                        vertices.add(new Vertex(x, y, z));
                    }

                } else if (line.startsWith("vt ")) {
                    // Текстурные координаты
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        float u = Float.parseFloat(parts[1]);
                        float v = Float.parseFloat(parts[2]);
                        uvCoords.add(new UV(u, v));
                    }

                } else if (line.startsWith("f ")) {
                    String[] parts = line.split("\\s+");
                    int[] vertexIndices = new int[parts.length - 1];
                    int[] uvIndices = new int[parts.length - 1];

                    for (int i = 1; i < parts.length; i++) {
                        String[] indices = parts[i].split("/");
                        vertexIndices[i - 1] = Integer.parseInt(indices[0]) - 1;

                        // UV индексы (если есть)
                        if (indices.length > 1 && !indices[1].isEmpty()) {
                            uvIndices[i - 1] = Integer.parseInt(indices[1]) - 1;
                        } else {
                            uvIndices[i - 1] = -1;
                        }
                    }

                    Face face = new Face(vertexIndices, uvIndices);
                    face.materialName = currentMaterialName;
                    faces.add(face);
                }
            }

            reader.close();

            // Присваиваем UV координаты вершинам
            assignUVToVertices(uvCoords);

            if (!vertices.isEmpty() && !faces.isEmpty()) {
                normalizeModel();
                isModelLoaded = true;
                Log.d(TAG, "OBJ модель загружена: " + vertices.size() + " вершин, " +
                        faces.size() + " граней, " + materials.size() + " материалов");

                if (useTextures) {
                    Log.d(TAG, "Используются текстуры из MTL файла");
                } else if (useMaterialColors) {
                    Log.d(TAG, "Используются цвета из MTL файла");
                } else {
                    Log.d(TAG, "Используется стандартный цвет из ModelConfig");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Не удалось загрузить OBJ модель", e);
            isModelLoaded = false;
        }
    }

    /**
     * Присваивание UV координат вершинам
     */
    private void assignUVToVertices(List<UV> uvCoords) {
        for (Face face : faces) {
            for (int i = 0; i < face.vertexIndices.length; i++) {
                int vIndex = face.vertexIndices[i];
                int uvIndex = face.uvIndices[i];

                if (vIndex >= 0 && vIndex < vertices.size() &&
                        uvIndex >= 0 && uvIndex < uvCoords.size()) {

                    Vertex vertex = vertices.get(vIndex);
                    UV uv = uvCoords.get(uvIndex);
                    vertex.u = uv.u;
                    vertex.v = uv.v;
                    vertex.hasUV = true;
                }
            }
        }
    }

    private void normalizeModel() {
        if (vertices.isEmpty()) return;

        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;

        for (Vertex v : vertices) {
            minX = Math.min(minX, v.x);
            maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z);
            maxZ = Math.max(maxZ, v.z);
        }

        float centerX = (minX + maxX) / 2;
        float centerY = (minY + maxY) / 2;
        float centerZ = (minZ + maxZ) / 2;

        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;
        float maxSize = Math.max(Math.max(sizeX, sizeY), sizeZ);

        float scale = maxSize > 0 ? 2.0f / maxSize : 1.0f;

        for (Vertex v : vertices) {
            v.x = (v.x - centerX) * scale;
            v.y = (v.y - centerY) * scale;
            v.z = (v.z - centerZ) * scale;
        }

        Log.d(TAG, "Модель нормализована");
    }

    private void createSimpleCube() {
        vertices = new ArrayList<>();
        faces = new ArrayList<>();

        vertices.add(new Vertex(-1, -1, -1));
        vertices.add(new Vertex(1, -1, -1));
        vertices.add(new Vertex(1, 1, -1));
        vertices.add(new Vertex(-1, 1, -1));
        vertices.add(new Vertex(-1, -1, 1));
        vertices.add(new Vertex(1, -1, 1));
        vertices.add(new Vertex(1, 1, 1));
        vertices.add(new Vertex(-1, 1, 1));

        faces.add(new Face(new int[]{0, 1, 2, 3}, new int[]{-1, -1, -1, -1}));
        faces.add(new Face(new int[]{1, 5, 6, 2}, new int[]{-1, -1, -1, -1}));
        faces.add(new Face(new int[]{5, 4, 7, 6}, new int[]{-1, -1, -1, -1}));
        faces.add(new Face(new int[]{4, 0, 3, 7}, new int[]{-1, -1, -1, -1}));
        faces.add(new Face(new int[]{3, 2, 6, 7}, new int[]{-1, -1, -1, -1}));
        faces.add(new Face(new int[]{4, 5, 1, 0}, new int[]{-1, -1, -1, -1}));

        isModelLoaded = true;
        useMaterialColors = false;
        useTextures = false;
    }

    public boolean loadModel(String modelPath) {
        if (modelPath == null) {
            createSimpleCube();
            return true;
        }

        try {
            Log.d(TAG, "Загрузка модели: " + modelPath);

            // Очищаем старые данные
            materials.clear();
            useMaterialColors = false;
            useTextures = false;

            InputStream inputStream = context.getAssets().open(modelPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            vertices = new ArrayList<>();
            List<UV> uvCoords = new ArrayList<>();
            faces = new ArrayList<>();
            String currentMaterialName = null;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("mtllib ")) {
                    String mtlFileName = line.substring(7).trim();
                    String modelFolder = modelPath.substring(0, modelPath.lastIndexOf('/') + 1);
                    String mtlPath = modelFolder + mtlFileName;
                    loadMTLFile(mtlPath);

                } else if (line.startsWith("usemtl ")) {
                    currentMaterialName = line.substring(7).trim();

                } else if (line.startsWith("v ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);
                        vertices.add(new Vertex(x, y, z));
                    }

                } else if (line.startsWith("vt ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        float u = Float.parseFloat(parts[1]);
                        float v = Float.parseFloat(parts[2]);
                        uvCoords.add(new UV(u, v));
                    }

                } else if (line.startsWith("f ")) {
                    String[] parts = line.split("\\s+");
                    int[] vertexIndices = new int[parts.length - 1];
                    int[] uvIndices = new int[parts.length - 1];

                    for (int i = 1; i < parts.length; i++) {
                        String[] indices = parts[i].split("/");
                        vertexIndices[i - 1] = Integer.parseInt(indices[0]) - 1;

                        if (indices.length > 1 && !indices[1].isEmpty()) {
                            uvIndices[i - 1] = Integer.parseInt(indices[1]) - 1;
                        } else {
                            uvIndices[i - 1] = -1;
                        }
                    }

                    Face face = new Face(vertexIndices, uvIndices);
                    face.materialName = currentMaterialName;
                    faces.add(face);
                }
            }

            reader.close();

            assignUVToVertices(uvCoords);

            if (!vertices.isEmpty() && !faces.isEmpty()) {
                normalizeModel();
                isModelLoaded = true;
                Log.d(TAG, "Модель загружена: " + vertices.size() + " вершин, " +
                        faces.size() + " граней, " + materials.size() + " материалов");
                return true;
            } else {
                Log.w(TAG, "Модель пуста");
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки модели: " + modelPath, e);
            return false;
        }
    }

    /**
     * Получить цвет пикселя из текстуры по UV координатам
     */
    private int getTextureColor(Bitmap texture, float u, float v, float brightness) {
        if (texture == null) {
            return Color.WHITE;
        }

        // Нормализуем UV координаты (0.0-1.0)
        u = u - (float)Math.floor(u);
        v = v - (float)Math.floor(v);

        // Конвертируем в пиксельные координаты
        int x = (int)(u * (texture.getWidth() - 1));
        int y = (int)((1.0f - v) * (texture.getHeight() - 1)); // Инвертируем V

        // Получаем цвет пикселя
        int pixel = texture.getPixel(x, y);

        // Применяем освещение
        int r = (int)(Color.red(pixel) * brightness);
        int g = (int)(Color.green(pixel) * brightness);
        int b = (int)(Color.blue(pixel) * brightness);
        int a = Color.alpha(pixel);

        return Color.argb(a, r, g, b);
    }

    /**
     * Получить цвет для грани с учетом текстур
     */
    private int getFaceColorWithTexture(Face face, float brightness, Vector2[] projectedUV) {
        if (useTextures && face.materialName != null && materials.containsKey(face.materialName)) {
            Material material = materials.get(face.materialName);

            if (material.diffuseTexture != null && projectedUV != null && projectedUV.length > 0) {
                // Используем UV координаты первой вершины для упрощения
                int vIndex = face.vertexIndices[0];
                if (vIndex >= 0 && vIndex < vertices.size()) {
                    Vertex v = vertices.get(vIndex);
                    if (v.hasUV) {
                        return getTextureColor(material.diffuseTexture, v.u, v.v, brightness);
                    }
                }
            }

            // Fallback на цвет материала
            float[] diffuse = material.diffuseColor;
            int r = (int)(diffuse[0] * 255 * brightness);
            int g = (int)(diffuse[1] * 255 * brightness);
            int b = (int)(diffuse[2] * 255 * brightness);
            int alpha = (int)(material.transparency * 255);

            return Color.argb(alpha, r, g, b);
        } else if (useMaterialColors && face.materialName != null && materials.containsKey(face.materialName)) {
            Material material = materials.get(face.materialName);
            float[] diffuse = material.diffuseColor;

            int r = (int)(diffuse[0] * 255 * brightness);
            int g = (int)(diffuse[1] * 255 * brightness);
            int b = (int)(diffuse[2] * 255 * brightness);
            int alpha = (int)(material.transparency * 255);

            return Color.argb(alpha, r, g, b);
        } else {
            // Стандартный цвет из ModelConfig
            int r = (int)(ModelConfig.COLOR_R * brightness);
            int g = (int)(ModelConfig.COLOR_G * brightness);
            int b = (int)(ModelConfig.COLOR_B * brightness);

            return Color.argb(ModelConfig.ALPHA, r, g, b);
        }
    }

    public void render3DToCanvas(Canvas canvas, float centerX, float centerY, float scale) {
        if (!isModelLoaded) {
            return;
        }

        try {
            // Проецируем вершины
            List<ProjectedVertex> projectedVertices = new ArrayList<>();

            for (Vertex v : vertices) {
                Vector3 transformed = transformVertex(new Vector3(v.x, v.y, v.z));
                Vector2 projected = projectVertex(transformed, scale, centerX, centerY);
                projectedVertices.add(new ProjectedVertex(projected, transformed.z, v.u, v.v));
            }

            // Сортируем грани
            List<FaceDepth> sortedFaces = new ArrayList<>();
            for (Face face : faces) {
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

            Paint fillPaint = new Paint();
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setAntiAlias(true);

            Paint strokePaint = new Paint();
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1);
            strokePaint.setAntiAlias(true);

            // Рисуем грани
            for (FaceDepth fd : sortedFaces) {
                Face face = fd.face;

                Vector3 normal = calculateNormal(face);
                float brightness = Math.max(0.4f, Math.abs(normal.z) * 0.6f + 0.4f);

                // Получаем UV координаты для текстурирования
                Vector2[] uvCoords = new Vector2[face.vertexIndices.length];
                for (int i = 0; i < face.vertexIndices.length; i++) {
                    int vIndex = face.vertexIndices[i];
                    if (vIndex < projectedVertices.size()) {
                        ProjectedVertex pv = projectedVertices.get(vIndex);
                        uvCoords[i] = new Vector2(pv.u, pv.v);
                    }
                }

                int color = getFaceColorWithTexture(face, brightness, uvCoords);
                fillPaint.setColor(color);

                int strokeColor = Color.argb(
                        Math.min(255, Color.alpha(color) + 50),
                        Color.red(color) / 2,
                        Color.green(color) / 2,
                        Color.blue(color) / 2
                );
                strokePaint.setColor(strokeColor);

                Path path = new Path();
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

        } catch (Exception e) {
            Log.e(TAG, "Ошибка рендеринга", e);
        }
    }

    public Bitmap renderModelOnBitmap(Bitmap backgroundBitmap, android.graphics.Rect qrBounds) {
        if (!isModelLoaded) {
            return backgroundBitmap;
        }

        try {
            Bitmap resultBitmap = Bitmap.createBitmap(
                    backgroundBitmap.getWidth(),
                    backgroundBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(resultBitmap);
            canvas.drawBitmap(backgroundBitmap, 0, 0, null);

            float qrCenterX = qrBounds.centerX();
            float qrCenterY = qrBounds.centerY();
            float qrSize = Math.max(qrBounds.width(), qrBounds.height());
            float scale = qrSize * modelScale * userScale * 0.8f;
            float centerX = qrCenterX + (offsetX * qrSize);
            float centerY = qrCenterY + (offsetY * qrSize);

            render3DToCanvas(canvas, centerX, centerY, scale);

            return resultBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка рендеринга", e);
            return backgroundBitmap;
        }
    }

    private Vector3 transformVertex(Vector3 v) {
        float x = v.x, y = v.y, z = v.z;

        if (rotationX != 0) {
            float cosX = (float) Math.cos(rotationX);
            float sinX = (float) Math.sin(rotationX);
            float y1 = y * cosX - z * sinX;
            float z1 = y * sinX + z * cosX;
            y = y1;
            z = z1;
        }

        if (rotationY != 0) {
            float cosY = (float) Math.cos(rotationY);
            float sinY = (float) Math.sin(rotationY);
            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            x = x1;
            z = z1;
        }

        if (rotationZ != 0) {
            float cosZ = (float) Math.cos(rotationZ);
            float sinZ = (float) Math.sin(rotationZ);
            float x1 = x * cosZ - y * sinZ;
            float y1 = x * sinZ + y * cosZ;
            x = x1;
            y = y1;
        }

        z += offsetZ;
        return new Vector3(x, y, z);
    }

    private Vector2 projectVertex(Vector3 v, float scale, float centerX, float centerY) {
        float distance = 5.0f;
        float factor = scale / (distance + v.z);
        float x = v.x * factor + centerX;
        float y = -v.y * factor + centerY;
        return new Vector2(x, y);
    }

    private Vector3 calculateNormal(Face face) {
        if (face.vertexIndices.length < 3) {
            return new Vector3(0, 0, 1);
        }

        if (face.vertexIndices[0] >= vertices.size() ||
                face.vertexIndices[1] >= vertices.size() ||
                face.vertexIndices[2] >= vertices.size()) {
            return new Vector3(0, 0, 1);
        }

        Vertex v0 = vertices.get(face.vertexIndices[0]);
        Vertex v1 = vertices.get(face.vertexIndices[1]);
        Vertex v2 = vertices.get(face.vertexIndices[2]);

        Vector3 edge1 = new Vector3(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3 edge2 = new Vector3(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        float nx = edge1.y * edge2.z - edge1.z * edge2.y;
        float ny = edge1.z * edge2.x - edge1.x * edge2.z;
        float nz = edge1.x * edge2.y - edge1.y * edge2.x;

        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) {
            nx /= length;
            ny /= length;
            nz /= length;
        }

        return new Vector3(nx, ny, nz);
    }

    // Вспомогательные классы
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

    /**
     * Вершина с UV координатами
     */
    private static class Vertex {
        float x, y, z;
        float u, v;        // Текстурные координаты
        boolean hasUV = false;

        Vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Текстурные координаты
     */
    private static class UV {
        float u, v;
        UV(float u, float v) {
            this.u = u;
            this.v = v;
        }
    }

    /**
     * Грань с индексами вершин и UV
     */
    public static class Face {
        public int[] vertexIndices;
        public int[] uvIndices;
        public String materialName;

        public Face(int[] vertexIndices, int[] uvIndices) {
            this.vertexIndices = vertexIndices;
            this.uvIndices = uvIndices;
        }

        // Для совместимости со старым кодом
        public int[] indices = null;

        public Face(int... indices) {
            this.vertexIndices = indices;
            this.uvIndices = new int[indices.length];
            for (int i = 0; i < indices.length; i++) {
                uvIndices[i] = -1;
            }
            this.indices = indices;
        }
    }

    private static class ProjectedVertex {
        Vector2 position;
        float z;
        float u, v;  // UV координаты

        ProjectedVertex(Vector2 position, float z, float u, float v) {
            this.position = position;
            this.z = z;
            this.u = u;
            this.v = v;
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

    /**
     * Класс для хранения материала с текстурами
     */
    private static class Material {
        String name;
        float[] diffuseColor = {1.0f, 1.0f, 1.0f};
        float[] ambientColor = {1.0f, 1.0f, 1.0f};
        float[] specularColor = {1.0f, 1.0f, 1.0f};
        float transparency = 1.0f;

        // Текстуры
        Bitmap diffuseTexture = null;   // map_Kd
        Bitmap alphaTexture = null;     // map_d
        Bitmap normalTexture = null;    // map_bump
        Bitmap specularTexture = null;  // map_Ks

        Material(String name) {
            this.name = name;
        }
    }

    public void destroy() {
        if (vertices != null) {
            vertices.clear();
        }
        if (faces != null) {
            faces.clear();
        }
        if (materials != null) {
            // Освобождаем текстуры ff
            for (Material mat : materials.values()) {
                if (mat.diffuseTexture != null && !mat.diffuseTexture.isRecycled()) {
                    mat.diffuseTexture.recycle();
                }
                if (mat.alphaTexture != null && !mat.alphaTexture.isRecycled()) {
                    mat.alphaTexture.recycle();
                }
                if (mat.normalTexture != null && !mat.normalTexture.isRecycled()) {
                    mat.normalTexture.recycle();
                }
                if (mat.specularTexture != null && !mat.specularTexture.isRecycled()) {
                    mat.specularTexture.recycle();
                }
            }
            materials.clear();
        }
    }
}