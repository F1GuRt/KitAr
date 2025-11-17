package kit.developers.kitar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Помощник для сегментации человека на изображении
 * Позволяет наложить 3D модель ЗА человеком
 */
public class SegmentationHelper {

    private static final String TAG = "SegmentationHelper";
    private Segmenter segmenter;

    public SegmentationHelper() {
        // Настройки сегментации
        SelfieSegmenterOptions options =
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .enableRawSizeMask() // Получаем маску в исходном размере
                        .build();

        segmenter = Segmentation.getClient(options);
        Log.d(TAG, "Segmenter инициализирован");
    }

    /**
     * Основной метод: накладывает 3D модель ЗА человеком
     *
     * @param originalPhoto Оригинальное фото
     * @param modelBitmap Отрисованная 3D модель (прозрачный фон)
     * @return Итоговое изображение с моделью за человеком
     */
    public Bitmap applyModelBehindPerson(Bitmap originalPhoto, Bitmap modelBitmap) {
        try {
            Log.d(TAG, "Начало сегментации...");

            // 1. Получаем маску человека
            Bitmap personMask = extractPersonMask(originalPhoto);

            if (personMask == null) {
                Log.e(TAG, "Не удалось создать маску");
                return originalPhoto;
            }

            Log.d(TAG, "Маска получена, создание итогового изображения...");

            // 2. Создаем итоговое изображение
            Bitmap result = Bitmap.createBitmap(
                    originalPhoto.getWidth(),
                    originalPhoto.getHeight(),
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(result);

            // 3. Рисуем оригинальное фото (фон)
            canvas.drawBitmap(originalPhoto, 0, 0, null);

            // 4. Рисуем 3D модель поверх
            Paint modelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(modelBitmap, 0, 0, modelPaint);

            // 5. Накладываем человека поверх модели
            Bitmap personLayer = extractPerson(originalPhoto, personMask);
            canvas.drawBitmap(personLayer, 0, 0, null);

            Log.d(TAG, "Композиция завершена успешно!");

            // Освобождаем ресурсы
            personMask.recycle();
            personLayer.recycle();

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при наложении модели", e);
            return originalPhoto;
        }
    }

    /**
     * Извлекает маску человека из изображения
     */
    private Bitmap extractPersonMask(Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            // Используем CountDownLatch для синхронного выполнения
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<SegmentationMask> maskRef = new AtomicReference<>();

            segmenter.process(image)
                    .addOnSuccessListener(segmentationMask -> {
                        maskRef.set(segmentationMask);
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Ошибка сегментации", e);
                        latch.countDown();
                    });

            // Ждем результат (максимум 10 секунд)
            if (!latch.await(10, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout при сегментации");
                return null;
            }

            SegmentationMask mask = maskRef.get();
            if (mask == null) {
                return null;
            }

            // Конвертируем маску в Bitmap
            return maskToBitmap(mask, bitmap.getWidth(), bitmap.getHeight());

        } catch (Exception e) {
            Log.e(TAG, "Ошибка извлечения маски", e);
            return null;
        }
    }

    /**
     * Конвертирует SegmentationMask в Bitmap
     */
    private Bitmap maskToBitmap(SegmentationMask mask, int width, int height) {
        try {
            ByteBuffer buffer = mask.getBuffer();
            int maskWidth = mask.getWidth();
            int maskHeight = mask.getHeight();

            Log.d(TAG, "Размер маски: " + maskWidth + "x" + maskHeight);
            Log.d(TAG, "Размер изображения: " + width + "x" + height);

            // Создаем bitmap для маски
            Bitmap maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888);

            // Заполняем маску
            for (int y = 0; y < maskHeight; y++) {
                for (int x = 0; x < maskWidth; x++) {
                    // Получаем значение вероятности (0.0 - 1.0)
                    float confidence = buffer.getFloat();

                    // Если вероятность > 0.5, это человек
                    if (confidence > 0.5f) {
                        maskBitmap.setPixel(x, y, Color.WHITE);
                    } else {
                        maskBitmap.setPixel(x, y, Color.TRANSPARENT);
                    }
                }
            }

            // Масштабируем маску до размера оригинального изображения
            if (maskWidth != width || maskHeight != height) {
                Bitmap scaledMask = Bitmap.createScaledBitmap(maskBitmap, width, height, true);
                maskBitmap.recycle();
                return scaledMask;
            }

            return maskBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка конвертации маски", e);
            return null;
        }
    }

    /**
     * Извлекает только человека из оригинального фото используя маску
     */
    private Bitmap extractPerson(Bitmap originalPhoto, Bitmap mask) {
        try {
            Bitmap personLayer = Bitmap.createBitmap(
                    originalPhoto.getWidth(),
                    originalPhoto.getHeight(),
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(personLayer);

            // Рисуем маску
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

            // Сначала рисуем оригинальное фото
            canvas.drawBitmap(originalPhoto, 0, 0, null);

            // Затем применяем маску (оставляем только человека)
            canvas.drawBitmap(mask, 0, 0, maskPaint);

            return personLayer;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка извлечения человека", e);
            return originalPhoto;
        }
    }

    /**
     * Очистка ресурсов f
     */
    public void cleanup() {
        if (segmenter != null) {
            segmenter.close();
            Log.d(TAG, "Segmenter закрыт");
        }
    }
}