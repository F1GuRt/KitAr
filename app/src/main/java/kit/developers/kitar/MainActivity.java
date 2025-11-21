package kit.developers.kitar;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
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
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

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
    private static final String TARGET_URL = "https://kitar.com";

    private ModelManager modelManager;
    private SegmentationHelper segmentationHelper;
    private WatermarkHelper watermarkHelper;

    private static String[] getRequiredPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    // UI Elements
    private PreviewView previewView;
    private AROverlayView arOverlayView;
    private CardView statusCard;
    private ImageView statusIcon;
    private TextView statusText;
    private TextView statusSubtext;
    private LinearLayout btnSelectModel;
    private TextView currentModelText;
    private View btnCapture;
    private ImageView captureIcon;
    private View btnFlipCamera;
    private View btnResetScale;
    private CardView scaleCard;
    private TextView scaleText;
    private View processingOverlay;
    private TextView processingTitle;
    private TextView processingText;
    private ProgressBar progressBar;
    private LinearLayout progressSteps;
    private CardView successCard;

    // Camera
    private ImageCapture imageCapture;
    private androidx.camera.core.ImageAnalysis imageAnalysis;
    private Camera camera;
    private CameraSelector cameraSelector;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ExecutorService cameraExecutor;

    // Other
    private BarcodeScanner barcodeScanner;
    private Simple3DRenderer model3DRenderer;
    private boolean isProcessing = false;
    private ProcessingStep currentStep = ProcessingStep.NONE;

    private enum ProcessingStep {
        NONE,
        CAPTURING,
        SCANNING,
        RENDERING,
        SEGMENTATION,
        WATERMARK,
        SAVING,
        DONE
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initManagers();
        initBarcodeScanner();
        initModel3DRenderer();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        setupClickListeners();
        setupARScaleListener();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        arOverlayView = findViewById(R.id.arOverlay);
        statusCard = findViewById(R.id.statusCard);
        statusIcon = findViewById(R.id.statusIcon);
        statusText = findViewById(R.id.statusText);
        statusSubtext = findViewById(R.id.statusSubtext);
        btnSelectModel = findViewById(R.id.btnSelectModel);
        currentModelText = findViewById(R.id.currentModelText);
        btnCapture = findViewById(R.id.btnCapture);
        captureIcon = findViewById(R.id.captureIcon);
        btnFlipCamera = findViewById(R.id.btnFlipCamera);
        btnResetScale = findViewById(R.id.btnResetScale);
        scaleCard = findViewById(R.id.scaleCard);
        scaleText = findViewById(R.id.scaleText);
        processingOverlay = findViewById(R.id.processingOverlay);
        processingTitle = findViewById(R.id.processingTitle);
        processingText = findViewById(R.id.processingText);
        progressBar = findViewById(R.id.progressBar);
        progressSteps = findViewById(R.id.progressSteps);
        successCard = findViewById(R.id.successCard);
    }

    private void initManagers() {
        try {
            modelManager = new ModelManager(this);
            int modelsCount = modelManager.getModelsCount();

            if (modelsCount > 0) {
                ModelManager.Model3DInfo currentModel = modelManager.getCurrentModel();
                updateCurrentModelText(currentModel.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации ModelManager", e);
        }

        try {
            watermarkHelper = new WatermarkHelper(this);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации водяного знака", e);
        }

        try {
            segmentationHelper = new SegmentationHelper();
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации сегментации", e);
        }
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

            if (modelManager != null) {
                ModelManager.Model3DInfo currentModel = modelManager.getCurrentModel();

                if (model3DRenderer.loadModel(currentModel.getPath())) {
                    arOverlayView.setModelGeometry(
                            model3DRenderer.getVertices(),
                            model3DRenderer.getFaces()
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации рендерера", e);
        }
    }

    private void setupARScaleListener() {
        arOverlayView.setOnScaleChangeListener(scale -> runOnUiThread(() -> {
            scaleText.setText(String.format(Locale.getDefault(), "%.1fx", scale));

            // Показываем карточку масштаба при изменении
            if (scaleCard.getVisibility() != View.VISIBLE) {
                scaleCard.setAlpha(0f);
                scaleCard.setVisibility(View.VISIBLE);
                scaleCard.animate().alpha(1f).setDuration(200).start();
            }

            // Автоматически скрываем через 2 секунды
            scaleCard.removeCallbacks(hideScaleCardRunnable);
            scaleCard.postDelayed(hideScaleCardRunnable, 2000);

            if (model3DRenderer != null) {
                model3DRenderer.setUserScale(scale);
            }
        }));
    }

    private final Runnable hideScaleCardRunnable = () -> {
        scaleCard.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> scaleCard.setVisibility(View.GONE))
                .start();
    };

    private void setupClickListeners() {
        btnCapture.setOnClickListener(v -> {
            if (!isProcessing) {
                animateButton(btnCapture);
                capturePhoto();
            }
        });

        btnFlipCamera.setOnClickListener(v -> {
            animateButton(btnFlipCamera);
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        btnResetScale.setOnClickListener(v -> {
            animateButton(btnResetScale);
            arOverlayView.resetScale();
            showTemporaryMessage("Масштаб сброшен");
        });

        btnSelectModel.setOnClickListener(v -> showModelSelectionDialog());
    }

    private void animateButton(View button) {
        button.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction(() -> button.animate().scaleX(1f).scaleY(1f).setDuration(100).start())
                .start();
    }

    private void showModelSelectionDialog() {
        if (modelManager == null) return;

        String[] modelNames = modelManager.getModelNames();
        int currentIndex = modelManager.getCurrentModelIndex();

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Выберите 3D модель")
                .setSingleChoiceItems(modelNames, currentIndex, (dialog, which) -> {
                    modelManager.setCurrentModel(which);
                    ModelManager.Model3DInfo selectedModel = modelManager.getCurrentModel();

                    showProcessingStep(ProcessingStep.RENDERING);

                    cameraExecutor.execute(() -> {
                        boolean success = model3DRenderer.loadModel(selectedModel.getPath());

                        runOnUiThread(() -> {
                            hideProcessing();

                            if (success) {
                                arOverlayView.setModelGeometry(
                                        model3DRenderer.getVertices(),
                                        model3DRenderer.getFaces()
                                );

                                updateCurrentModelText(selectedModel.getName());
                                showTemporaryMessage("Модель загружена: " + selectedModel.getName());
                            } else {
                                showTemporaryMessage("Ошибка загрузки модели");
                            }
                        });
                    });

                    dialog.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void updateCurrentModelText(String modelName) {
        if (currentModelText != null) {
            String displayName = modelName;
            if (displayName.length() > 15) {
                displayName = displayName.substring(0, 12) + "...";
            }
            currentModelText.setText(displayName);
        }
    }

    private void updateStatus(String title, String subtitle, int iconRes) {
        runOnUiThread(() -> {
            statusText.setText(title);
            statusSubtext.setText(subtitle);
            if (iconRes != 0) {
                statusIcon.setImageResource(iconRes);
            }

            // Анимация пульсации
            statusCard.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(150)
                    .withEndAction(() -> statusCard.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                    .start();
        });
    }

    private void showProcessingStep(ProcessingStep step) {
        currentStep = step;
        runOnUiThread(() -> {
            processingOverlay.setVisibility(View.VISIBLE);

            String title = "";
            String text = "";

            switch (step) {
                case CAPTURING:
                    title = "Захват изображения";
                    text = "Получение фото с камеры...";
                    break;
                case SCANNING:
                    title = "Поиск QR-кода";
                    text = "Анализ изображения...";
                    break;
                case RENDERING:
                    title = "Рендеринг 3D";
                    text = "Создание AR-объекта...";
                    break;
                case SEGMENTATION:
                    title = "Обработка глубины";
                    text = "Размещение объекта за человеком...";
                    break;
                case WATERMARK:
                    title = "Финальная обработка";
                    text = "Добавление водяного знака...";
                    break;
                case SAVING:
                    title = "Сохранение";
                    text = "Сохранение в галерею...";
                    break;
            }

            processingTitle.setText(title);
            processingText.setText(text);
        });
    }

    private void hideProcessing() {
        runOnUiThread(() -> processingOverlay.setVisibility(View.GONE));
    }

    private void showSuccess() {
        runOnUiThread(() -> {
            hideProcessing();
            successCard.setAlpha(0f);
            successCard.setVisibility(View.VISIBLE);
            successCard.setScaleX(0.7f);
            successCard.setScaleY(0.7f);

            successCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            new Handler().postDelayed(() -> {
                                successCard.animate()
                                        .alpha(0f)
                                        .scaleX(0.7f)
                                        .scaleY(0.7f)
                                        .setDuration(300)
                                        .withEndAction(() -> successCard.setVisibility(View.GONE))
                                        .start();
                            }, 2000);
                        }
                    })
                    .start();

            updateStatus("Готово!", "Фото сохранено в галерее", R.drawable.ic_check_circle);
        });
    }

    private void showTemporaryMessage(String message) {
        runOnUiThread(() -> {
            updateStatus(message, "Нажмите кнопку камеры для съемки", R.drawable.ic_qr_scan);
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
                updateStatus("Ошибка камеры", "Не удалось запустить камеру", R.drawable.ic_camera);
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
            updateStatus("Камера не готова", "Подождите инициализации", R.drawable.ic_camera);
            return;
        }

        isProcessing = true;
        showProcessingStep(ProcessingStep.CAPTURING);
        btnCapture.setEnabled(false);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                processImage(imageProxy);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    hideProcessing();
                    updateStatus("Ошибка съемки", "Попробуйте еще раз", R.drawable.ic_camera);
                    resetProcessing();
                });
                Log.e(TAG, "Ошибка захвата изображения", exception);
            }
        });
    }

    private void processImage(ImageProxy imageProxy) {
        showProcessingStep(ProcessingStep.CAPTURING);

        Bitmap bitmap = imageProxyToBitmap(imageProxy);

        if (bitmap == null) {
            runOnUiThread(() -> {
                hideProcessing();
                updateStatus("Ошибка обработки", "Не удалось получить изображение", R.drawable.ic_camera);
                resetProcessing();
            });
            imageProxy.close();
            return;
        }

        showProcessingStep(ProcessingStep.SCANNING);

        Bitmap enhancedBitmap = enhanceImageForQR(bitmap);
        InputImage image = InputImage.fromBitmap(enhancedBitmap, 0);

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    imageProxy.close();
                    handleBarcodeResult(barcodes, bitmap);
                })
                .addOnFailureListener(e -> {
                    imageProxy.close();
                    Log.e(TAG, "Ошибка сканирования QR", e);
                    runOnUiThread(() -> {
                        hideProcessing();
                        updateStatus("Ошибка сканирования", "Попробуйте еще раз", R.drawable.ic_qr_scan);
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
        if (barcodes.isEmpty()) {
            runOnUiThread(() -> {
                hideProcessing();
                updateStatus("QR не найден", "Убедитесь что QR-код виден на фото", R.drawable.ic_qr_scan);
                resetProcessing();
            });
            return;
        }

        Barcode barcode = barcodes.get(0);
        String qrUrl = barcode.getRawValue();
        android.graphics.Rect qrBounds = barcode.getBoundingBox();

        if (qrBounds == null) {
            runOnUiThread(() -> {
                hideProcessing();
                updateStatus("Ошибка QR", "Не удалось определить положение", R.drawable.ic_qr_scan);
                resetProcessing();
            });
            return;
        }

        if (qrUrl != null && qrUrl.trim().equals(TARGET_URL.trim())) {
            render3DModelAndSave(bitmap, qrBounds);
        } else {
            runOnUiThread(() -> {
                hideProcessing();
                updateStatus("Неверный QR-код", "Используйте правильный QR-код", R.drawable.ic_qr_scan);
                resetProcessing();
            });
        }
    }

    private void render3DModelAndSave(Bitmap photoBitmap, android.graphics.Rect qrBounds) {
        showProcessingStep(ProcessingStep.RENDERING);

        cameraExecutor.execute(() -> {
            try {
                float userScale = arOverlayView.getUserScale();
                Bitmap transparentModelBitmap = createTransparentModelBitmap(photoBitmap, qrBounds);

                if (transparentModelBitmap == null) {
                    runOnUiThread(() -> {
                        hideProcessing();
                        updateStatus("Ошибка рендеринга", "Не удалось создать 3D модель", R.drawable.ic_3d_model);
                        resetProcessing();
                    });
                    return;
                }

                showProcessingStep(ProcessingStep.SEGMENTATION);

                Bitmap resultBitmap;
                if (segmentationHelper != null) {
                    resultBitmap = segmentationHelper.applyModelBehindPerson(
                            photoBitmap,
                            transparentModelBitmap
                    );
                } else {
                    resultBitmap = model3DRenderer.renderModelOnBitmap(photoBitmap, qrBounds);
                }

                transparentModelBitmap.recycle();

                if (resultBitmap != null && watermarkHelper != null && watermarkHelper.isWatermarkLoaded()) {
                    showProcessingStep(ProcessingStep.WATERMARK);
                    Bitmap withWatermark = watermarkHelper.applyWatermark(resultBitmap);

                    if (withWatermark != resultBitmap) {
                        resultBitmap.recycle();
                        resultBitmap = withWatermark;
                    }
                }

                if (resultBitmap != null) {
                    showProcessingStep(ProcessingStep.SAVING);
                    boolean saved = saveImageToGallery(resultBitmap);

                    if (saved) {
                        runOnUiThread(() -> showSuccess());
                    } else {
                        runOnUiThread(() -> {
                            hideProcessing();
                            updateStatus("Ошибка сохранения", "Не удалось сохранить фото", R.drawable.ic_camera);
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        hideProcessing();
                        updateStatus("Ошибка обработки", "Попробуйте еще раз", R.drawable.ic_camera);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка рендеринга 3D", e);
                runOnUiThread(() -> {
                    hideProcessing();
                    updateStatus("Ошибка", "Что-то пошло не так", R.drawable.ic_camera);
                });
            } finally {
                runOnUiThread(this::resetProcessing);
            }
        });
    }

    private Bitmap createTransparentModelBitmap(Bitmap photoBitmap, android.graphics.Rect qrBounds) {
        try {
            int width = photoBitmap.getWidth();
            int height = photoBitmap.getHeight();

            Bitmap transparentBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(transparentBitmap);

            float qrCenterX = qrBounds.centerX();
            float qrCenterY = qrBounds.centerY();
            float qrSize = Math.max(qrBounds.width(), qrBounds.height());

            float userScale = arOverlayView.getUserScale();
            float scale = qrSize * ModelConfig.SCALE * userScale * 0.8f;

            float centerX = qrCenterX + (ModelConfig.OFFSET_X * qrSize);
            float centerY = qrCenterY + (ModelConfig.OFFSET_Y * qrSize);

            model3DRenderer.render3DToCanvas(canvas, centerX, centerY, scale);

            return transparentBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка создания прозрачного bitmap", e);
            return null;
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes.length == 0) return null;

            ByteBuffer buffer = planes[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                        bitmap.getHeight(), matrix, true);
            }

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Ошибка конвертации изображения", e);
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

                if (imageUri == null) return false;

                fos = getContentResolver().openOutputStream(imageUri);
                if (fos == null) return false;

                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();

                return true;

            } else {
                java.io.File imagesDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES);

                if (!imagesDir.exists()) {
                    imagesDir.mkdirs();
                }

                java.io.File image = new java.io.File(imagesDir, filename);
                fos = new java.io.FileOutputStream(image);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DATA, image.getAbsolutePath());
                getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения", e);
            return false;
        }
    }

    private void resetProcessing() {
        isProcessing = false;
        btnCapture.setEnabled(true);
        updateStatus("Найдите QR-код", "Наведите камеру на QR-код для AR", R.drawable.ic_qr_scan);
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
    }

    private boolean allPermissionsGranted() {
        String[] permissions = getRequiredPermissions();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
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
            if (allPermissionsGranted()) {
                startCamera();
                updateStatus("Готово к работе", "Наведите камеру на QR-код", R.drawable.ic_qr_scan);
            } else {
                updateStatus("Нужны разрешения", "Предоставьте доступ к камере", R.drawable.ic_camera);
                new Handler().postDelayed(() -> {
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
                                        updateStatus(
                                                "QR найден! " + String.format(Locale.getDefault(), "%.1fx", scale),
                                                "Нажмите кнопку камеры для съемки",
                                                R.drawable.ic_check_circle
                                        );
                                    });
                                } else {
                                    runOnUiThread(() -> {
                                        arOverlayView.clearQRPosition();
                                        if (qrUrl != null && !qrUrl.trim().equals(TARGET_URL.trim())) {
                                            updateStatus("Неверный QR-код", "Используйте правильный QR-код", R.drawable.ic_qr_scan);
                                        }
                                    });
                                }
                            } else {
                                runOnUiThread(() -> {
                                    arOverlayView.clearQRPosition();
                                    updateStatus("Найдите QR-код", "Наведите камеру на QR-код для AR", R.drawable.ic_qr_scan);
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

            boolean isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT;

            float scaleX = (float) viewWidth / imageWidth;
            float scaleY = (float) viewHeight / imageHeight;

            int left, right;

            if (isFrontCamera) {
                left = (int) ((imageWidth - imageBounds.right) * scaleX);
                right = (int) ((imageWidth - imageBounds.left) * scaleX);
            } else {
                left = (int) (imageBounds.left * scaleX);
                right = (int) (imageBounds.right * scaleX);
            }

            int top = (int) (imageBounds.top * scaleY);
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
            watermarkHelper.cleanup();
        }
    }
}