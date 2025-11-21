package kit.developers.kitar;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION = 3000; // 3 секунды
    private ImageView logoImage;
    private TextView appName;
    private TextView loadingText;
    private ProgressBar progressBar;
    private View[] dots;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        initViews();
        startAnimations();

        // Переход на главный экран через 3 секунды
        new Handler().postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, SPLASH_DURATION);
    }

    private void initViews() {
        logoImage = findViewById(R.id.logoImage);
        appName = findViewById(R.id.appName);
        loadingText = findViewById(R.id.loadingText);
        progressBar = findViewById(R.id.progressBar);

        dots = new View[]{
                findViewById(R.id.dot1),
                findViewById(R.id.dot2),
                findViewById(R.id.dot3)
        };

        // Начальная видимость
        logoImage.setAlpha(0f);
        appName.setAlpha(0f);
        loadingText.setAlpha(0f);
        progressBar.setAlpha(0f);
    }

    private void startAnimations() {
        // Анимация логотипа - fade in + scale
        logoImage.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(800)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        // Анимация названия приложения
        new Handler().postDelayed(() -> {
            appName.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(600)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        }, 400);

        // Анимация текста загрузки
        new Handler().postDelayed(() -> {
            loadingText.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start();

            progressBar.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start();
        }, 800);

        // Анимация точек (pulsing effect)
        animateDots();
    }

    private void animateDots() {
        for (int i = 0; i < dots.length; i++) {
            final int index = i;
            new Handler().postDelayed(() -> {
                pulseDot(dots[index]);
            }, i * 200);
        }
    }

    private void pulseDot(View dot) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.3f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.3f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(dot, "alpha", 0.5f, 1f, 0.5f);

        scaleX.setDuration(1000);
        scaleY.setDuration(1000);
        alpha.setDuration(1000);

        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        alpha.setRepeatCount(ObjectAnimator.INFINITE);

        scaleX.start();
        scaleY.start();
        alpha.start();
    }
}