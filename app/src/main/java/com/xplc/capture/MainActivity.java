package com.xplc.capture;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_RECORD_AUDIO = 40;
    private static final int REQ_MEDIA_PROJECTION = 41;

    private static final int BG = Color.rgb(6, 8, 10);
    private static final int CARD = Color.rgb(15, 19, 22);
    private static final int TEXT = Color.rgb(244, 247, 248);
    private static final int MUTED = Color.rgb(151, 160, 166);
    private static final int ACCENT = Color.rgb(124, 248, 214);
    private static final int DANGER = Color.rgb(255, 104, 104);

    private MediaProjectionManager projectionManager;
    private TextView statusText;
    private TextView timerText;
    private TextView detailText;
    private Button captureButton;
    private Button shareButton;
    private Uri latestUri;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CaptureService.ACTION_STATE.equals(intent.getAction())) return;
            boolean running = intent.getBooleanExtra(CaptureService.EXTRA_RUNNING, false);
            long elapsed = intent.getLongExtra(CaptureService.EXTRA_ELAPSED, 0L);
            String message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE);
            String uri = intent.getStringExtra(CaptureService.EXTRA_URI);

            setRunningUi(running);
            timerText.setText(formatElapsed(elapsed));
            if (message != null && !message.isEmpty()) {
                detailText.setText(message);
            }
            if (uri != null && !uri.isEmpty()) {
                latestUri = Uri.parse(uri);
                getPreferences(MODE_PRIVATE).edit().putString("latest_uri", uri).apply();
                shareButton.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        buildUi();

        String savedUri = getPreferences(MODE_PRIVATE).getString("latest_uri", null);
        if (savedUri != null) {
            latestUri = Uri.parse(savedUri);
            shareButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CaptureService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRunningUi(CaptureService.isRunning);
        if (CaptureService.isRunning) {
            detailText.setText("Capturing continues while you use Suno or Chrome.");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(28));
        root.setBackgroundColor(BG);

        TextView brand = text("XPLC", 13, ACCENT, Typeface.BOLD);
        brand.setLetterSpacing(0.35f);
        root.addView(brand, wrap());

        TextView title = text("CAPTURE", 40, TEXT, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = dp(8);
        root.addView(title, titleLp);

        TextView subtitle = text("Internal audio. Zero speaker recording.", 15, MUTED, Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(7);
        root.addView(subtitle, subLp);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setGravity(Gravity.CENTER);
        statusCard.setPadding(dp(18), dp(22), dp(18), dp(20));
        statusCard.setBackground(rounded(CARD, dp(24)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(34);
        root.addView(statusCard, cardLp);

        statusText = text("READY", 12, ACCENT, Typeface.BOLD);
        statusText.setLetterSpacing(0.24f);
        statusCard.addView(statusText, wrap());

        timerText = text("00:00", 46, TEXT, Typeface.BOLD);
        timerText.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams timerLp = wrap();
        timerLp.topMargin = dp(9);
        statusCard.addView(timerText, timerLp);

        detailText = text("Tap start, allow capture, then play your song.", 14, MUTED, Typeface.NORMAL);
        detailText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(9);
        statusCard.addView(detailText, detailLp);

        captureButton = button("START CAPTURE", ACCENT, BG);
        captureButton.setOnClickListener(v -> {
            if (CaptureService.isRunning) {
                Intent stop = new Intent(this, CaptureService.class);
                stop.setAction(CaptureService.ACTION_STOP);
                startService(stop);
                detailText.setText("Finishing WAV and trimming silence…");
            } else {
                beginPermissionFlow();
            }
        });
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(-1, dp(64));
        capLp.topMargin = dp(22);
        root.addView(captureButton, capLp);

        Button openSuno = button("OPEN SUNO", CARD, TEXT);
        openSuno.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://suno.com/create")));
            } catch (Exception e) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams sunoLp = new LinearLayout.LayoutParams(-1, dp(56));
        sunoLp.topMargin = dp(12);
        root.addView(openSuno, sunoLp);

        shareButton = button("SHARE LAST RECORDING", CARD, TEXT);
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> shareLatest());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-1, dp(56));
        shareLp.topMargin = dp(10);
        root.addView(shareButton, shareLp);

        Space spacer = new Space(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView note = text(
                "Android requires RECORD_AUDIO permission for playback capture, even though this app never opens the microphone. " +
                        "When the system asks what to share, choose the full screen/device so audio from another app can be captured. " +
                        "Files save to Music/XPLC Capture. Source apps can block playback capture.",
                12, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(0f, 1.15f);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void beginPermissionFlow() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Android 10 or newer is required", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        detailText.setText("Approve Android's capture prompt. Choose the full screen/device.");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestProjection();
            } else {
                detailText.setText("Playback capture needs Android's RECORD_AUDIO permission.");
                Toast.makeText(this, "Permission is required for internal audio capture", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MEDIA_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            detailText.setText("Capture permission was cancelled.");
            return;
        }

        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        setRunningUi(true);
        detailText.setText("Switch to Suno or Chrome and play the song.");
    }

    private void setRunningUi(boolean running) {
        if (captureButton == null) return;
        if (running) {
            statusText.setText("CAPTURING");
            statusText.setTextColor(DANGER);
            captureButton.setText("STOP & SAVE");
            captureButton.setTextColor(TEXT);
            captureButton.setBackground(rounded(Color.rgb(113, 34, 39), dp(22)));
        } else {
            statusText.setText("READY");
            statusText.setTextColor(ACCENT);
            captureButton.setText("START CAPTURE");
            captureButton.setTextColor(BG);
            captureButton.setBackground(rounded(ACCENT, dp(22)));
        }
    }

    private void shareLatest() {
        if (latestUri == null) return;
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("audio/wav");
            share.putExtra(Intent.EXTRA_STREAM, latestUri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share recording"));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't share the recording", Toast.LENGTH_LONG).show();
        }
    }

    private String formatElapsed(long millis) {
        long totalSeconds = Math.max(0, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setLetterSpacing(0.10f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(rounded(background, dp(20)));
        return button;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
