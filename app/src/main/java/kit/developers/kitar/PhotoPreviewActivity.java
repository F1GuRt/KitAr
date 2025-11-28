package kit.developers.kitar;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
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
 * Activity для предпросмотра обработанного фото
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

    private Bitmap photoBitmap;
    private String photoPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_preview);

        initViews();
        loadPhoto();
        setupClickListeners();
    }

    private void initViews() {
        previewImage = findViewById(R.id.previewImage);
        btnSave = findViewById(R.id.btnSave);
        btnRetake = findViewById(R.id.btnRetake);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        progressBar = findViewById(R.id.progressBar);
        loadingText = findViewById(R.id.loadingText);
        successCard = findViewById(R.id.successCard);
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
                animatePreviewIn();
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

    private void animatePreviewIn() {
        previewImage.setAlpha(0f);
        previewImage.setScaleX(0.9f);
        previewImage.setScaleY(0.9f);

        previewImage.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
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