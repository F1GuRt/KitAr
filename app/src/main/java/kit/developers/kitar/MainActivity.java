package kit.developers.kitar;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "QRScannerApp";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = getRequiredPermissions();

    private SegmentationHelper segmentationHelper; // Добавьте эту переменную

    private static String[] getRequiredPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10-12
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else {
            // Android 9 и ниже
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    // Целевая ссылка для сравнения
    private static final String TARGET_URL = "https://your-target-url.com";

    private PreviewView previewView;

    private WatermarkHelper watermarkHelper; // ДОБАВЬТЕ эту переменную
    private AROverlayView arOverlayView;
    private FloatingActionButton btnCapture;
    private FloatingActionButton btnFlipCamera;
    private TextView statusText;
    private ProgressBar progressBar;

    private ImageCapture imageCapture;
    private androidx.camera.core.ImageAnalysis imageAnalysis;
    private Camera camera;
    private CameraSelector cameraSelector;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ExecutorService cameraExecutor;

    private BarcodeScanner barcodeScanner;
    private Simple3DRenderer model3DRenderer;
    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initBarcodeScanner();
        initModel3DRenderer();
        initSegmentation();
        initWatermark(); // ДОБАВЬТЕ инициализацию

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        setupClickListeners();
        setupARScaleListener();
    }

    // ДОБАВЬТЕ этот новый метод:
    private void initWatermark() {
        try {
            watermarkHelper = new WatermarkHelper(this);

            if (watermarkHelper.isWatermarkLoaded()) {
                Log.d(TAG, "Водяной знак инициализирован успешно");
                Toast.makeText(this, "Водяной знак готов", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Водяной знак не загружен - файл не найден");
                Toast.makeText(this, "Водяной знак не найден в assets", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации водяного знака", e);
        }
    }
    private void initSegmentation() {
        try {
            segmentationHelper = new SegmentationHelper();
            Log.d(TAG, "Сегментация инициализирована");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации сегментации", e);
            Toast.makeText(this, "Сегментация недоступна", Toast.LENGTH_SHORT).show();
        }
    }


    private void initViews() {
        previewView = findViewById(R.id.previewView);
        arOverlayView = findViewById(R.id.arOverlay);
        btnCapture = findViewById(R.id.btnCapture);
        btnFlipCamera = findViewById(R.id.btnFlipCamera);
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
    }

    private void initBarcodeScanner() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);
    }

    private void initModel3DRenderer() {
        try {
            model3DRenderer = new Simple3DRenderer(this);

            if (model3DRenderer.isModelLoaded()) {
                arOverlayView.setModelGeometry(
                        model3DRenderer.getVertices(),
                        model3DRenderer.getFaces()
                );
                showStatus("AR режим активирован");
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации рендерера", e);
            showLongStatus("Ошибка инициализации 3D: " + e.getMessage());
        }
    }

    private void setupARScaleListener() {
        arOverlayView.setOnScaleChangeListener(new AROverlayView.OnScaleChangeListener() {
            @Override
            public void onScaleChanged(float scale) {
                // Обновляем текст статуса с информацией о масштабе
                runOnUiThread(() -> {
                    statusText.setText(String.format("Масштаб: %.1fx - Нажмите для фото", scale));
                });

                // Передаем масштаб в рендерер для последующего использования
                if (model3DRenderer != null) {
                    model3DRenderer.setUserScale(scale);
                }
            }
        });
    }

    private void setupClickListeners() {
        btnCapture.setOnClickListener(v -> {
            if (!isProcessing) {
                capturePhoto();
            }
        });

        btnFlipCamera.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        // Долгое нажатие на кнопку камеры - сброс масштаба
        btnCapture.setOnLongClickListener(v -> {
            arOverlayView.resetScale();
            showStatus("Масштаб сброшен");
            return true;
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка привязки камеры", e);
                showStatus("Ошибка запуска камеры");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                .build();

        imageAnalysis = new androidx.camera.core.ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(1280, 720))
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new QRAnalyzer());

        cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        cameraProvider.unbindAll();

        camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
        );

        preview.setSurfaceProvider(previewView.getSurfaceProvider());
    }

    private void capturePhoto() {
        if (imageCapture == null) {
            showStatus("Камера не готова");
            return;
        }

        isProcessing = true;
        showStatus("Захват фото...");
        progressBar.setVisibility(View.VISIBLE);
        btnCapture.setEnabled(false);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                runOnUiThread(() -> showStatus("Фото получено, обработка..."));
                processImage(imageProxy);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    showLongStatus("Ошибка съемки: " + exception.getMessage());
                    resetProcessing();
                });
                Log.e(TAG, "Ошибка захвата изображения", exception);
            }
        });
    }

    private void processImage(ImageProxy imageProxy) {
        runOnUiThread(() -> showStatus("Конвертация изображения..."));

        Bitmap bitmap = imageProxyToBitmap(imageProxy);

        if (bitmap == null) {
            runOnUiThread(() -> showLongStatus("Ошибка: не удалось получить изображение"));
            imageProxy.close();
            runOnUiThread(this::resetProcessing);
            return;
        }

        runOnUiThread(() -> showStatus("Поиск QR-кода..."));

        Bitmap enhancedBitmap = enhanceImageForQR(bitmap);

        InputImage image = InputImage.fromBitmap(enhancedBitmap, 0);

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    imageProxy.close();
                    runOnUiThread(() -> showStatus("Сканирование завершено"));
                    handleBarcodeResult(barcodes, bitmap);
                })
                .addOnFailureListener(e -> {
                    imageProxy.close();
                    Log.e(TAG, "Ошибка сканирования QR", e);
                    runOnUiThread(() -> {
                        showLongStatus("Ошибка сканирования: " + e.getMessage());
                        resetProcessing();
                    });
                });
    }

    private Bitmap enhanceImageForQR(Bitmap bitmap) {
        try {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.set(new float[]{
                    1.5f, 0, 0, 0, -50,
                    0, 1.5f, 0, 0, -50,
                    0, 0, 1.5f, 0, -50,
                    0, 0, 0, 1, 0
            });

            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));

            Bitmap enhanced = Bitmap.createBitmap(bitmap.getWidth(),
                    bitmap.getHeight(), bitmap.getConfig());
            Canvas canvas = new Canvas(enhanced);
            canvas.drawBitmap(bitmap, 0, 0, paint);

            return enhanced;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка улучшения изображения", e);
            return bitmap;
        }
    }

    private void handleBarcodeResult(java.util.List<Barcode> barcodes, Bitmap bitmap) {
        runOnUiThread(() -> showStatus("Найдено кодов: " + barcodes.size()));

        if (barcodes.isEmpty()) {
            runOnUiThread(() -> {
                showLongStatus("QR-код не обнаружен. Убедитесь что он в кадре и хорошо виден");
                resetProcessing();
            });
            return;
        }

        Barcode barcode = barcodes.get(0);
        String qrUrl = barcode.getRawValue();

        android.graphics.Rect qrBounds = barcode.getBoundingBox();

        if (qrBounds == null) {
            runOnUiThread(() -> {
                showLongStatus("Ошибка: не удалось получить координаты QR");
                resetProcessing();
            });
            return;
        }

        runOnUiThread(() -> {
            String msg = "QR найден!\nПозиция: (" + qrBounds.left + "," + qrBounds.top +
                    ") Размер: " + qrBounds.width() + "x" + qrBounds.height();
            showStatus(msg);
        });

        Log.d(TAG, "=== QR КОД НАЙДЕН ===");
        Log.d(TAG, "URL: " + qrUrl);
        Log.d(TAG, "Координаты: " + qrBounds.toString());
        Log.d(TAG, "Размер фото: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        Log.d(TAG, "Ожидаемый URL: " + TARGET_URL);

        if (qrUrl != null && qrUrl.trim().equals(TARGET_URL.trim())) {
            runOnUiThread(() -> showLongStatus("✓ QR подтвержден! Рендеринг на позицию QR..."));
            render3DModelAndSave(bitmap, qrBounds);
        } else {
            runOnUiThread(() -> {
                showLongStatus("✗ Неверный QR-код. Попробуйте другой");
                resetProcessing();
            });
        }
    }

    private void render3DModelAndSave(Bitmap photoBitmap, android.graphics.Rect qrBounds) {
        runOnUiThread(() -> showStatus("Запуск рендеринга..."));

        cameraExecutor.execute(() -> {
            try {
                float userScale = arOverlayView.getUserScale();

                runOnUiThread(() -> showStatus("Рендеринг 3D модели..."));

                // 1. Создаем прозрачный bitmap с 3D моделью
                Bitmap transparentModelBitmap = createTransparentModelBitmap(photoBitmap, qrBounds);

                if (transparentModelBitmap == null) {
                    runOnUiThread(() -> showLongStatus("✗ Ошибка создания 3D модели"));
                    runOnUiThread(this::resetProcessing);
                    return;
                }

                runOnUiThread(() -> showStatus("Применение сегментации..."));

                // 2. Применяем сегментацию - модель будет ЗА человеком
                Bitmap resultBitmap;
                if (segmentationHelper != null) {
                    resultBitmap = segmentationHelper.applyModelBehindPerson(
                            photoBitmap,
                            transparentModelBitmap
                    );
                } else {
                    Log.w(TAG, "Сегментация недоступна, используется обычное наложение");
                    resultBitmap = model3DRenderer.renderModelOnBitmap(photoBitmap, qrBounds);
                }

                // Освобождаем временный bitmap
                transparentModelBitmap.recycle();

                // 3. НОВОЕ: Накладываем водяной знак
                if (resultBitmap != null && watermarkHelper != null && watermarkHelper.isWatermarkLoaded()) {
                    runOnUiThread(() -> showStatus("Добавление водяного знака..."));

                    Bitmap withWatermark = watermarkHelper.applyWatermark(resultBitmap);

                    // Если водяной знак успешно наложен, освобождаем старый bitmap
                    if (withWatermark != resultBitmap) {
                        resultBitmap.recycle();
                        resultBitmap = withWatermark;
                    }
                }

                if (resultBitmap != null) {
                    runOnUiThread(() -> showStatus("Сохранение в галерею..."));

                    boolean saved = saveImageToGallery(resultBitmap);

                    if (saved) {
                        runOnUiThread(() -> {
                            showLongStatus("✓ Фото сохранено с водяным знаком!");
                            Toast.makeText(this, "Готово! Проверьте галерею", Toast.LENGTH_LONG).show();
                        });
                    } else {
                        runOnUiThread(() -> showLongStatus("✗ Ошибка сохранения в галерею"));
                    }
                } else {
                    runOnUiThread(() -> showLongStatus("✗ Ошибка обработки изображения"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка рендеринга 3D", e);
                runOnUiThread(() -> showLongStatus("✗ Ошибка: " + e.getMessage()));
            } finally {
                runOnUiThread(this::resetProcessing);
            }
        });
    }


    private Bitmap createTransparentModelBitmap(Bitmap photoBitmap, android.graphics.Rect qrBounds) {
        try {
            int width = photoBitmap.getWidth();
            int height = photoBitmap.getHeight();

            // Создаем прозрачный bitmap
            Bitmap transparentBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(transparentBitmap);

            // Canvas уже прозрачный, рисуем только модель
            float qrCenterX = qrBounds.centerX();
            float qrCenterY = qrBounds.centerY();
            float qrSize = Math.max(qrBounds.width(), qrBounds.height());

            // Получаем масштаб
            float userScale = arOverlayView.getUserScale();
            float scale = qrSize * ModelConfig.SCALE * userScale * 0.8f;

            float centerX = qrCenterX + (ModelConfig.OFFSET_X * qrSize);
            float centerY = qrCenterY + (ModelConfig.OFFSET_Y * qrSize);

            // Рендерим модель на прозрачный canvas
            model3DRenderer.render3DToCanvas(
                    canvas,
                    centerX,
                    centerY,
                    scale
            );

            return transparentBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка создания прозрачного bitmap", e);
            return null;
        }
    }
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes.length == 0) {
                runOnUiThread(() -> showLongStatus("Ошибка: нет данных изображения"));
                return null;
            }

            ByteBuffer buffer = planes[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

            if (bitmap == null) {
                runOnUiThread(() -> showLongStatus("Ошибка: не удалось декодировать изображение"));
                return null;
            }

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                        bitmap.getHeight(), matrix, true);
            }

            Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> showStatus("Изображение: " + finalBitmap.getWidth() + "x" + finalBitmap.getHeight()));

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка конвертации изображения", e);
            runOnUiThread(() -> showLongStatus("Ошибка конвертации: " + e.getMessage()));
            return null;
        }
    }

    private boolean saveImageToGallery(Bitmap bitmap) {
        try {
            String filename = "QR_3D_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.getDefault()).format(new Date()) + ".jpg";

            OutputStream fos;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

                Uri imageUri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                if (imageUri == null) {
                    runOnUiThread(() -> showLongStatus("Ошибка: не удалось создать URI"));
                    return false;
                }

                fos = getContentResolver().openOutputStream(imageUri);
                if (fos == null) {
                    runOnUiThread(() -> showLongStatus("Ошибка: не удалось открыть поток"));
                    return false;
                }

                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();

                runOnUiThread(() -> showStatus("Сохранено: " + filename));
                return true;

            } else {
                File imagesDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES);

                if (!imagesDir.exists()) {
                    imagesDir.mkdirs();
                }

                File image = new File(imagesDir, filename);
                fos = new FileOutputStream(image);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DATA, image.getAbsolutePath());
                getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                runOnUiThread(() -> showStatus("Сохранено: " + filename));
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения", e);
            runOnUiThread(() -> showLongStatus("Ошибка сохранения: " + e.getMessage()));
            return false;
        }
    }

    private void showStatus(String message) {
        statusText.setText(message);
        Log.d(TAG, message);
    }

    private void showLongStatus(String message) {
        statusText.setText(message);
        Log.d(TAG, message);
    }

    private void resetProcessing() {
        isProcessing = false;
        progressBar.setVisibility(View.GONE);
        btnCapture.setEnabled(true);
        showStatus("Наведите камеру на QR-код");
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
    }

    private boolean allPermissionsGranted() {
        String[] permissions = getRequiredPermissions();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Разрешение не выдано: " + permission);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Разрешение отклонено: " + permissions[i]);
                    allGranted = false;
                }
            }

            if (allGranted && allPermissionsGranted()) {
                startCamera();
                showStatus("Разрешения получены");
            } else {
                showLongStatus("Необходимы все разрешения для работы приложения.\n" +
                        "Пожалуйста, выдайте разрешения в настройках.");

                new android.os.Handler().postDelayed(() -> {
                    if (!allPermissionsGranted()) {
                        requestPermissions();
                    }
                }, 2000);
            }
        }
    }

    private class QRAnalyzer implements androidx.camera.core.ImageAnalysis.Analyzer {

        private long lastAnalyzedTimestamp = 0;
        private static final long ANALYSIS_INTERVAL_MS = 100;

        @OptIn(markerClass = ExperimentalGetImage.class)
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            long currentTimestamp = System.currentTimeMillis();

            if (currentTimestamp - lastAnalyzedTimestamp < ANALYSIS_INTERVAL_MS) {
                imageProxy.close();
                return;
            }

            lastAnalyzedTimestamp = currentTimestamp;

            @androidx.camera.core.ExperimentalGetImage
            android.media.Image mediaImage = imageProxy.getImage();

            if (mediaImage != null) {
                InputImage image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.getImageInfo().getRotationDegrees()
                );

                barcodeScanner.process(image)
                        .addOnSuccessListener(barcodes -> {
                            if (!barcodes.isEmpty()) {
                                Barcode barcode = barcodes.get(0);
                                String qrUrl = barcode.getRawValue();
                                android.graphics.Rect bounds = barcode.getBoundingBox();

                                if (qrUrl != null && qrUrl.trim().equals(TARGET_URL.trim()) && bounds != null) {
                                    Rect scaledBounds = scaleQRBounds(bounds, imageProxy, arOverlayView);

                                    runOnUiThread(() -> {
                                        arOverlayView.updateQRPosition(scaledBounds);
                                        float scale = arOverlayView.getUserScale();
                                        statusText.setText(String.format("Масштаб: %.1fx - Нажмите для фото", scale));
                                    });
                                } else {
                                    runOnUiThread(() -> {
                                        arOverlayView.clearQRPosition();
                                        if (qrUrl != null && !qrUrl.trim().equals(TARGET_URL.trim())) {
                                            statusText.setText("Неверный QR-код");
                                        }
                                    });
                                }
                            } else {
                                runOnUiThread(() -> {
                                    arOverlayView.clearQRPosition();
                                    statusText.setText("Наведите камеру на QR-код");
                                });
                            }
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Ошибка анализа", e);
                        })
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                imageProxy.close();
            }
        }

        private Rect scaleQRBounds(android.graphics.Rect imageBounds,
                                   ImageProxy imageProxy,
                                   View targetView) {

            int imageWidth = imageProxy.getWidth();
            int imageHeight = imageProxy.getHeight();
            int viewWidth = targetView.getWidth();
            int viewHeight = targetView.getHeight();

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation == 90 || rotation == 270) {
                int temp = imageWidth;
                imageWidth = imageHeight;
                imageHeight = temp;
            }

            float scaleX = (float) viewWidth / imageWidth;
            float scaleY = (float) viewHeight / imageHeight;

            int left = (int) (imageBounds.left * scaleX);
            int top = (int) (imageBounds.top * scaleY);
            int right = (int) (imageBounds.right * scaleX);
            int bottom = (int) (imageBounds.bottom * scaleY);

            return new Rect(left, top, right, bottom);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (model3DRenderer != null) {
            model3DRenderer.destroy();
        }
        if (arOverlayView != null) {
            arOverlayView.cleanup();
        }
        if (segmentationHelper != null) {
            segmentationHelper.cleanup();
        }
        if (watermarkHelper != null) {
            watermarkHelper.cleanup(); // ДОБАВЬТЕ очистку
        }
    }
}