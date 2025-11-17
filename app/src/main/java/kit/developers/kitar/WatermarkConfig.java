package kit.developers.kitar;

/**
 * Конфигурация водяного знака (логотип + текст)
 * Измените эти значения для настройки положения и размера
 */
public class WatermarkConfig {

    // ==================== ИМЯ ФАЙЛА ====================

    /**
     * Имя PNG файла в папке assets
     * Поместите ваш PNG файл в app/src/main/assets/watermark.png
     */
    public static final String WATERMARK_FILE = "watermark.png";

    // ==================== РАЗМЕР ====================

    /**
     * Размер водяного знака относительно ширины изображения
     * 0.2 = 20% от ширины фото
     * 0.3 = 30% от ширины фото
     * 0.5 = 50% от ширины фото
     */
    public static final float WATERMARK_SCALE = 0.25f; // 25% от ширины фото

    // ==================== ПОЛОЖЕНИЕ ====================

    /**
     * Позиция водяного знака на фото
     * Доступные варианты:
     * - TOP_LEFT (левый верхний угол)
     * - TOP_RIGHT (правый верхний угол)
     * - BOTTOM_LEFT (левый нижний угол)
     * - BOTTOM_RIGHT (правый нижний угол)
     * - CENTER (центр)
     * - CUSTOM (пользовательская позиция, см. ниже)
     */
    public static final Position POSITION = Position.BOTTOM_RIGHT;

    // ==================== ОТСТУПЫ ====================

    /**
     * Отступы от краев изображения (в пикселях)
     */
    public static final int MARGIN_HORIZONTAL = 40; // Отступ слева/справа
    public static final int MARGIN_VERTICAL = 40;   // Отступ сверху/снизу

    // ==================== ПОЛЬЗОВАТЕЛЬСКАЯ ПОЗИЦИЯ ====================

    /**
     * Если POSITION = CUSTOM, используются эти координаты
     * Значения от 0.0 до 1.0 (процент от размера изображения)
     *
     * Примеры:
     * CUSTOM_X = 0.5, CUSTOM_Y = 0.5 - центр
     * CUSTOM_X = 0.0, CUSTOM_Y = 0.0 - левый верхний угол
     * CUSTOM_X = 1.0, CUSTOM_Y = 1.0 - правый нижний угол
     */
    public static final float CUSTOM_X = 0.5f; // По горизонтали (0.0-1.0)
    public static final float CUSTOM_Y = 0.9f; // По вертикали (0.0-1.0)

    // ==================== ПРОЗРАЧНОСТЬ ====================

    /**
     * Прозрачность водяного знака (0-255)
     * 255 = полностью непрозрачный
     * 200 = слегка прозрачный
     * 128 = полупрозрачный
     * 50 = очень прозрачный
     */
    public static final int ALPHA = 230;

    // ==================== ТЕНЬ ====================

    /**
     * Добавить тень под водяным знаком для лучшей видимости
     */
    public static final boolean ENABLE_SHADOW = true;
    public static final int SHADOW_RADIUS = 8;
    public static final int SHADOW_DX = 4;
    public static final int SHADOW_DY = 4;
    public static final int SHADOW_COLOR = 0x80000000; // Полупрозрачный черный

    // ==================== ENUM ПОЗИЦИЙ ====================

    public enum Position {
        TOP_LEFT,       // Левый верхний угол
        TOP_RIGHT,      // Правый верхний угол
        BOTTOM_LEFT,    // Левый нижний угол
        BOTTOM_RIGHT,   // Правый нижний угол
        CENTER,         // Центр
        CUSTOM          // Пользовательская позиция (см. CUSTOM_X, CUSTOM_Y)
    }

    // ==================== ПРИМЕРЫ НАСТРОЕК ====================

    /*
     * ПРИМЕР 1: Маленький логотип в правом нижнем углу
     * WATERMARK_SCALE = 0.15f
     * POSITION = Position.BOTTOM_RIGHT
     * MARGIN_HORIZONTAL = 20
     * MARGIN_VERTICAL = 20
     *
     * ПРИМЕР 2: Большой водяной знак в центре (полупрозрачный)
     * WATERMARK_SCALE = 0.5f
     * POSITION = Position.CENTER
     * ALPHA = 100 f
     *
     * ПРИМЕР 3: Логотип в верхнем левом углу
     * WATERMARK_SCALE = 0.2f
     * POSITION = Position.TOP_LEFT
     * MARGIN_HORIZONTAL = 30
     * MARGIN_VERTICAL = 30
     *
     * ПРИМЕР 4: Пользовательская позиция (чуть выше центра)
     * WATERMARK_SCALE = 0.3f
     * POSITION = Position.CUSTOM
     * CUSTOM_X = 0.5f
     * CUSTOM_Y = 0.4f
     */
}