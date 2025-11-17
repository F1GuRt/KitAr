package kit.developers.kitar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Класс для наложения водяного знака на изображение /
 */
public class WatermarkHelper {

    private static final String TAG = "WatermarkHelper";
    private Context context;
    private Bitmap watermarkBitmap;

    public WatermarkHelper(Context context) {
        this.context = context;
        loadWatermark();
    }

    /**
     * Загружает водяной знак из assets
     */
    private void loadWatermark() {
        try {
            InputStream is = context.getAssets().open(WatermarkConfig.WATERMARK_FILE);
            watermarkBitmap = BitmapFactory.decodeStream(is);
            is.close();

            if (watermarkBitmap != null) {
                Log.d(TAG, "Водяной знак загружен: " + watermarkBitmap.getWidth() + "x" + watermarkBitmap.getHeight());
            } else {
                Log.e(TAG, "Не удалось загрузить водяной знак");
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка загрузки водяного знака: " + e.getMessage());
            watermarkBitmap = null;
        }
    }

    /**
     * Проверяет, загружен ли водяной знак
     */
    public boolean isWatermarkLoaded() {
        return watermarkBitmap != null && !watermarkBitmap.isRecycled();
    }

    /**
     * Накладывает водяной знак на изображение
     *
     * @param originalBitmap Исходное изображение
     * @return Изображение с водяным знаком
     */
    public Bitmap applyWatermark(Bitmap originalBitmap) {
        if (!isWatermarkLoaded()) {
            Log.w(TAG, "Водяной знак не загружен, возвращаем оригинал");
            return originalBitmap;
        }

        try {
            Log.d(TAG, "Наложение водяного знака...");

            // Создаем копию оригинала
            Bitmap result = Bitmap.createBitmap(
                    originalBitmap.getWidth(),
                    originalBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(result);

            // Рисуем оригинальное изображение
            canvas.drawBitmap(originalBitmap, 0, 0, null);

            // Вычисляем размер и позицию водяного знака
            int targetWidth = (int) (originalBitmap.getWidth() * WatermarkConfig.WATERMARK_SCALE);

            // Сохраняем пропорции
            float aspectRatio = (float) watermarkBitmap.getHeight() / watermarkBitmap.getWidth();
            int targetHeight = (int) (targetWidth * aspectRatio);

            // Масштабируем водяной знак
            Bitmap scaledWatermark = Bitmap.createScaledBitmap(
                    watermarkBitmap,
                    targetWidth,
                    targetHeight,
                    true
            );

            // Вычисляем позицию
            float[] position = calculatePosition(
                    originalBitmap.getWidth(),
                    originalBitmap.getHeight(),
                    targetWidth,
                    targetHeight
            );

            float x = position[0];
            float y = position[1];

            Log.d(TAG, "Позиция водяного знака: x=" + x + ", y=" + y);
            Log.d(TAG, "Размер водяного знака: " + targetWidth + "x" + targetHeight);

            // Настраиваем Paint для водяного знака
            Paint watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            watermarkPaint.setAlpha(WatermarkConfig.ALPHA);

            // Добавляем тень если включено
            if (WatermarkConfig.ENABLE_SHADOW) {
                watermarkPaint.setShadowLayer(
                        WatermarkConfig.SHADOW_RADIUS,
                        WatermarkConfig.SHADOW_DX,
                        WatermarkConfig.SHADOW_DY,
                        WatermarkConfig.SHADOW_COLOR
                );
            }

            // Рисуем водяной знак
            canvas.drawBitmap(scaledWatermark, x, y, watermarkPaint);

            // Освобождаем временный bitmap
            scaledWatermark.recycle();

            Log.d(TAG, "Водяной знак успешно наложен");
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка наложения водяного знака", e);
            return originalBitmap;
        }
    }

    /**
     * Вычисляет координаты для размещения водяного знака
     */
    private float[] calculatePosition(int imageWidth, int imageHeight,
                                      int watermarkWidth, int watermarkHeight) {
        float x = 0;
        float y = 0;

        switch (WatermarkConfig.POSITION) {
            case TOP_LEFT:
                x = WatermarkConfig.MARGIN_HORIZONTAL;
                y = WatermarkConfig.MARGIN_VERTICAL;
                break;

            case TOP_RIGHT:
                x = imageWidth - watermarkWidth - WatermarkConfig.MARGIN_HORIZONTAL;
                y = WatermarkConfig.MARGIN_VERTICAL;
                break;

            case BOTTOM_LEFT:
                x = WatermarkConfig.MARGIN_HORIZONTAL;
                y = imageHeight - watermarkHeight - WatermarkConfig.MARGIN_VERTICAL;
                break;

            case BOTTOM_RIGHT:
                x = imageWidth - watermarkWidth - WatermarkConfig.MARGIN_HORIZONTAL;
                y = imageHeight - watermarkHeight - WatermarkConfig.MARGIN_VERTICAL;
                break;

            case CENTER:
                x = (imageWidth - watermarkWidth) / 2f;
                y = (imageHeight - watermarkHeight) / 2f;
                break;

            case CUSTOM:
                // Пользовательская позиция (от 0.0 до 1.0)
                x = (imageWidth * WatermarkConfig.CUSTOM_X) - (watermarkWidth / 2f);
                y = (imageHeight * WatermarkConfig.CUSTOM_Y) - (watermarkHeight / 2f);

                // Убеждаемся что водяной знак не выходит за границы
                x = Math.max(0, Math.min(x, imageWidth - watermarkWidth));
                y = Math.max(0, Math.min(y, imageHeight - watermarkHeight));
                break;
        }

        return new float[]{x, y};
    }

    /**
     * Очистка ресурсов
     */
    public void cleanup() {
        if (watermarkBitmap != null && !watermarkBitmap.isRecycled()) {
            watermarkBitmap.recycle();
            watermarkBitmap = null;
            Log.d(TAG, "Водяной знак освобожден");
        }
    }

    /**
     * Создает тестовый водяной знак программно (если файл не найден)
     */
    public static Bitmap createTestWatermark() {
        int size = 400;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Рисуем круг
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(200, 33, 150, 243));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 20, circlePaint);

        // Рисуем обводку
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(10);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 20, strokePaint);

        // Рисуем текст
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(80);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        canvas.drawText("KIT AR", size / 2f, size / 2f - 20, textPaint);
        canvas.drawText("APP", size / 2f, size / 2f + 60, textPaint);

        Log.d(TAG, "Создан тестовый водяной знак");
        return bitmap;
    }
}