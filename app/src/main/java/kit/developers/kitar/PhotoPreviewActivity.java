package kit.developers.kitar;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Activity для предпросмотра обработанного фото с поддержкой зума
 */
public class PhotoPreviewActivity extends AppCompatActivity {

    private static final String TAG = "PhotoPreviewActivity";
    public static final String EXTRA_PHOTO_PATH = "photo_path";

    private ImageView previewImage;
    private View btnSave;
    private View btnRetake;
    private View loadingOverlay;
    private ProgressBar progressBar;
    private TextView loadingText;
    private CardView successCard;
    private TextView zoomText;

    private Bitmap photoBitmap;
    private String photoPath;

    // Зум и перемещение
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;

    private float minScale = 1f;
    private float maxScale = 5f;
    private float currentScale = 1f;

    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_preview);

        initViews();
        loadPhoto();
        setupClickListeners();
        setupZoomControls();
    }

    private void initViews() {
        previewImage = findViewById(R.id.previewImage);
        btnSave = findViewById(R.id.btnSave);
        btnRetake = findViewById(R.id.btnRetake);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        progressBar = findViewById(R.id.progressBar);
        loadingText = findViewById(R.id.loadingText);
        successCard = findViewById(R.id.successCard);
        zoomText = findViewById(R.id.zoomText);
    }

    private void loadPhoto() {
        photoPath = getIntent().getStringExtra(EXTRA_PHOTO_PATH);

        if (photoPath == null || photoPath.isEmpty()) {
            Toast.makeText(this, "Ошибка загрузки фото", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        try {
            File file = new File(photoPath);
            if (!file.exists()) {
                Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            FileInputStream fis = new FileInputStream(file);
            photoBitmap = BitmapFactory.decodeStream(fis);
            fis.close();

            if (photoBitmap != null) {
                previewImage.setImageBitmap(photoBitmap);
                previewImage.setScaleType(ImageView.ScaleType.MATRIX);

                // Центрируем изображение
                previewImage.post(() -> {
                    centerImage();
                    animatePreviewIn();
                });
            } else {
                Toast.makeText(this, "Ошибка декодирования фото", Toast.LENGTH_SHORT).show();
                finish();
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки фото", e);
            Toast.makeText(this, "Ошибка загрузки фото", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupZoomControls() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        previewImage.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);

            PointF curr = new PointF(event.getX(), event.getY());

            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    start.set(event.getX(), event.getY());
                    mode = DRAG;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist = spacing(event);
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix);
                        midPoint(mid, event);
                        mode = ZOOM;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;

                    // Скрываем индикатор зума через 1 секунду
                    if (zoomText != null) {
                        zoomText.postDelayed(() -> {
                            if (zoomText != null && zoomText.getVisibility() == View.VISIBLE) {
                                zoomText.animate()
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction(() -> {
                                            if (zoomText != null) {
                                                zoomText.setVisibility(View.GONE);
                                            }
                                        })
                                        .start();
                            }
                        }, 1000);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG && currentScale > minScale) {
                        matrix.set(savedMatrix);
                        float dx = curr.x - start.x;
                        float dy = curr.y - start.y;
                        matrix.postTranslate(dx, dy);
                    } else if (mode == ZOOM) {
                        float newDist = spacing(event);
                        if (newDist > 10f) {
                            matrix.set(savedMatrix);
                            float scale = newDist / oldDist;
                            matrix.postScale(scale, scale, mid.x, mid.y);
                        }
                    }
                    break;
            }

            // Применяем трансформацию
            checkAndSetImageMatrix();
            return true;
        });
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float newScale = currentScale * scaleFactor;

            // Ограничиваем масштаб
            newScale = Math.max(minScale, Math.min(newScale, maxScale));

            float scaleChange = newScale / currentScale;
            currentScale = newScale;

            matrix.postScale(scaleChange, scaleChange,
                    detector.getFocusX(), detector.getFocusY());

            checkAndSetImageMatrix();
            updateZoomIndicator();

            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mode = ZOOM;
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mode = NONE;
        }
    }

    private void updateZoomIndicator() {
        if (zoomText != null) {
            zoomText.setText(String.format(Locale.getDefault(), "%.1fx", currentScale));

            if (zoomText.getVisibility() != View.VISIBLE) {
                zoomText.setAlpha(0f);
                zoomText.setVisibility(View.VISIBLE);
                zoomText.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start();
            }
        }
    }

    private void checkAndSetImageMatrix() {
        if (previewImage == null || photoBitmap == null) return;

        float[] values = new float[9];
        matrix.getValues(values);

        float x = values[Matrix.MTRANS_X];
        float y = values[Matrix.MTRANS_Y];
        float scaleX = values[Matrix.MSCALE_X];
        float scaleY = values[Matrix.MSCALE_Y];

        int viewWidth = previewImage.getWidth();
        int viewHeight = previewImage.getHeight();
        int imageWidth = photoBitmap.getWidth();
        int imageHeight = photoBitmap.getHeight();

        float scaledImageWidth = imageWidth * scaleX;
        float scaledImageHeight = imageHeight * scaleY;

        // Ограничиваем перемещение
        float maxX = 0;
        float minX = viewWidth - scaledImageWidth;
        float maxY = 0;
        float minY = viewHeight - scaledImageHeight;

        // Если изображение меньше view, центрируем его
        if (scaledImageWidth <= viewWidth) {
            x = (viewWidth - scaledImageWidth) / 2;
        } else {
            // Ограничиваем перемещение по X
            if (x > maxX) x = maxX;
            if (x < minX) x = minX;
        }

        if (scaledImageHeight <= viewHeight) {
            y = (viewHeight - scaledImageHeight) / 2;
        } else {
            // Ограничиваем перемещение по Y
            if (y > maxY) y = maxY;
            if (y < minY) y = minY;
        }

        // Обновляем значения в матрице
        values[Matrix.MTRANS_X] = x;
        values[Matrix.MTRANS_Y] = y;
        matrix.setValues(values);

        // Обновляем currentScale
        currentScale = scaleX;

        previewImage.setImageMatrix(matrix);
    }

    private void centerImage() {
        if (previewImage == null || photoBitmap == null) return;

        int viewWidth = previewImage.getWidth();
        int viewHeight = previewImage.getHeight();
        int imageWidth = photoBitmap.getWidth();
        int imageHeight = photoBitmap.getHeight();

        if (viewWidth == 0 || viewHeight == 0) return;

        // Вычисляем масштаб для fit center
        float scaleX = (float) viewWidth / imageWidth;
        float scaleY = (float) viewHeight / imageHeight;
        float scale = Math.min(scaleX, scaleY);

        // Центрируем
        float scaledWidth = imageWidth * scale;
        float scaledHeight = imageHeight * scale;
        float dx = (viewWidth - scaledWidth) / 2;
        float dy = (viewHeight - scaledHeight) / 2;

        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);

        currentScale = scale;
        minScale = scale;

        previewImage.setImageMatrix(matrix);
    }

    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF point, MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }

    private void animatePreviewIn() {
        previewImage.setAlpha(0f);

        previewImage.animate()
                .alpha(1f)
                .setDuration(300)
                .start();

        btnSave.setAlpha(0f);
        btnSave.setTranslationY(50f);
        btnSave.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay(150)
                .start();

        btnRetake.setAlpha(0f);
        btnRetake.setTranslationY(50f);
        btnRetake.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay(200)
                .start();
    }

    private void setupClickListeners() {
        btnSave.setOnClickListener(v -> {
            animateButton(btnSave);
            savePhotoToGallery();
        });

        btnRetake.setOnClickListener(v -> {
            animateButton(btnRetake);
            retakePhoto();
        });
    }

    private void animateButton(View button) {
        button.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction(() -> button.animate().scaleX(1f).scaleY(1f).setDuration(100).start())
                .start();
    }

    private void savePhotoToGallery() {
        if (photoBitmap == null) {
            Toast.makeText(this, "Нет фото для сохранения", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);

        new Thread(() -> {
            boolean success = saveImageToGallery(photoBitmap);

            runOnUiThread(() -> {
                showLoading(false);

                if (success) {
                    showSuccess();
                } else {
                    Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
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
                File imagesDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES);

                if (!imagesDir.exists()) {
                    imagesDir.mkdirs();
                }

                File image = new File(imagesDir, filename);
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

    private void showLoading(boolean show) {
        if (show) {
            loadingOverlay.setVisibility(View.VISIBLE);
            loadingOverlay.setAlpha(0f);
            loadingOverlay.animate().alpha(1f).setDuration(200).start();
        } else {
            loadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> loadingOverlay.setVisibility(View.GONE))
                    .start();
        }
    }

    private void showSuccess() {
        successCard.setAlpha(0f);
        successCard.setVisibility(View.VISIBLE);
        successCard.setScaleX(0.7f);
        successCard.setScaleY(0.7f);

        successCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .withEndAction(() -> {
                    new android.os.Handler().postDelayed(() -> {
                        // Удаляем временный файл
                        deleteTempFile();

                        // Возвращаемся на главный экран
                        Intent intent = new Intent();
                        intent.putExtra("photo_saved", true);
                        setResult(RESULT_OK, intent);
                        finish();
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }, 1500);
                })
                .start();
    }

    private void retakePhoto() {
        // Удаляем временный файл
        deleteTempFile();

        // Возвращаемся на главный экран
        setResult(RESULT_CANCELED);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void deleteTempFile() {
        try {
            if (photoPath != null && !photoPath.isEmpty()) {
                File file = new File(photoPath);
                if (file.exists()) {
                    file.delete();
                    Log.d(TAG, "Временный файл удален");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка удаления временного файла", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (photoBitmap != null && !photoBitmap.isRecycled()) {
            photoBitmap.recycle();
            photoBitmap = null;
        }
    }

    @Override
    public void onBackPressed() {
        retakePhoto();
    }
}