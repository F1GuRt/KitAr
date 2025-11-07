package kit.developers.kitar;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер для управления 3D моделями
 * Автоматически обнаруживает модели в папке assets/models/
 */
public class ModelManager {

    private static final String TAG = "ModelManager";
    private static final String MODELS_FOLDER = "models";

    private Context context;
    private List<Model3DInfo> availableModels;
    private Model3DInfo currentModel;

    public ModelManager(Context context) {
        this.context = context;
        this.availableModels = new ArrayList<>();
        loadAvailableModels();
    }

    /**
     * Автоматически загружает список доступных моделей из assets/models/
     */
    private void loadAvailableModels() {
        try {
            String[] files = context.getAssets().list(MODELS_FOLDER);

            if (files == null || files.length == 0) {
                Log.w(TAG, "Папка models пуста или не найдена");
                addDefaultModel();
                return;
            }

            Log.d(TAG, "Найдено файлов в папке models: " + files.length);

            // Сканируем папку models
            for (String filename : files) {
                if (filename.toLowerCase().endsWith(".obj")) {
                    String modelPath = MODELS_FOLDER + "/" + filename;
                    String modelName = getModelNameFromFilename(filename);

                    Model3DInfo modelInfo = new Model3DInfo(
                            modelName,
                            modelPath,
                            generateModelDescription(modelName)
                    );

                    availableModels.add(modelInfo);
                    Log.d(TAG, "Добавлена модель: " + modelName + " (" + modelPath + ")");
                }
            }

            // Если модели не найдены, добавляем дефолтную
            if (availableModels.isEmpty()) {
                Log.w(TAG, "OBJ файлы не найдены, используется куб");
                addDefaultModel();
            } else {
                // Выбираем первую модель по умолчанию
                currentModel = availableModels.get(0);
                Log.d(TAG, "Выбрана модель по умолчанию: " + currentModel.getName());
            }

        } catch (IOException e) {
            Log.e(TAG, "Ошибка чтения папки models", e);
            addDefaultModel();
        }
    }

    /**
     * Добавляет дефолтную модель (куб)
     */
    private void addDefaultModel() {
        Model3DInfo defaultModel = new Model3DInfo(
                "Куб (по умолчанию)",
                null, // null означает использование встроенного куба
                "Простой тестовый куб"
        );
        availableModels.add(defaultModel);
        currentModel = defaultModel;
    }

    /**
     * Извлекает читаемое имя модели из имени файла
     */
    private String getModelNameFromFilename(String filename) {
        // Убираем расширение .obj
        String name = filename.replace(".obj", "").replace(".OBJ", "");

        // Заменяем подчеркивания и дефисы на пробелы
        name = name.replace("_", " ").replace("-", " ");

        // Делаем первую букву заглавной
        if (name.length() > 0) {
            name = name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        return name;
    }

    /**
     * Генерирует описание модели на основе имени
     */
    private String generateModelDescription(String modelName) {
        String lowerName = modelName.toLowerCase();

        if (lowerName.contains("cube") || lowerName.contains("куб")) {
            return "Простой куб";
        } else if (lowerName.contains("sphere") || lowerName.contains("сфера")) {
            return "Сфера";
        } else if (lowerName.contains("pyramid") || lowerName.contains("пирамида")) {
            return "Пирамида";
        } else if (lowerName.contains("star") || lowerName.contains("звезда")) {
            return "Звезда";
        } else if (lowerName.contains("heart") || lowerName.contains("сердце")) {
            return "Сердце";
        } else {
            return "3D модель";
        }
    }

    /**
     * Получить список всех доступных моделей
     */
    public List<Model3DInfo> getAvailableModels() {
        return new ArrayList<>(availableModels);
    }

    /**
     * Получить имена всех моделей для отображения в списке
     */
    public String[] getModelNames() {
        String[] names = new String[availableModels.size()];
        for (int i = 0; i < availableModels.size(); i++) {
            names[i] = availableModels.get(i).getDisplayName();
        }
        return names;
    }

    /**
     * Получить текущую выбранную модель
     */
    public Model3DInfo getCurrentModel() {
        return currentModel;
    }

    /**
     * Установить текущую модель по индексу
     */
    public void setCurrentModel(int index) {
        if (index >= 0 && index < availableModels.size()) {
            currentModel = availableModels.get(index);
            Log.d(TAG, "Выбрана модель: " + currentModel.getName());
        }
    }

    /**
     * Установить текущую модель по имени
     */
    public boolean setCurrentModelByName(String name) {
        for (Model3DInfo model : availableModels) {
            if (model.getName().equals(name)) {
                currentModel = model;
                Log.d(TAG, "Выбрана модель: " + currentModel.getName());
                return true;
            }
        }
        return false;
    }

    /**
     * Получить индекс текущей модели
     */
    public int getCurrentModelIndex() {
        return availableModels.indexOf(currentModel);
    }

    /**
     * Получить количество доступных моделей
     */
    public int getModelsCount() {
        return availableModels.size();
    }

    /**
     * Класс для хранения информации о 3D модели
     */
    public static class Model3DInfo {
        private String name;
        private String path;
        private String description;

        public Model3DInfo(String name, String path, String description) {
            this.name = name;
            this.path = path;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getPath() {
            return path;
        }

        public String getDescription() {
            return description;
        }

        public String getDisplayName() {
            return name + " - " + description;
        }

        public boolean isDefaultCube() {
            return path == null;
        }
    }
}

/*
═══════════════════════════════════════════════════════════════
                    КАК ДОБАВИТЬ НОВУЮ МОДЕЛЬ
═══════════════════════════════════════════════════════════════

1. Подготовьте OBJ файл вашей модели
2. Поместите файл в: app/src/main/assets/models/
3. Назовите файл понятным именем, например:
   - star.obj
   - heart.obj
   - pyramid.obj
   - custom_logo.obj

4. Всё! Модель автоматически появится в списке

═══════════════════════════════════════════════════════════════
                    ПРИМЕРЫ ИМЕН ФАЙЛОВ
═══════════════════════════════════════════════════════════════

Хорошие имена (автоматически конвертируются в читаемые):
✅ star.obj           → "Star - 3D модель"
✅ heart.obj          → "Heart - 3D модель"
✅ pyramid.obj        → "Pyramid - Пирамида"
✅ custom_logo.obj    → "Custom logo - 3D модель"
✅ my-model.obj       → "My model - 3D модель"

Плохие имена (непонятные пользователю):
❌ model1.obj
❌ asdf.obj
❌ temp.obj

═══════════════════════════════════════════════════════════════
                    СТРУКТУРА ПАПОК
═══════════════════════════════════════════════════════════════

your-project/
└── app/
    └── src/
        └── main/
            └── assets/
                ├── models/              ← Папка для 3D моделей
                │   ├── star.obj
                │   ├── heart.obj
                │   ├── pyramid.obj
                │   └── custom.obj
                └── watermark.png        ← Ваш водяной знак

═══════════════════════════════════════════════════════════════
*/