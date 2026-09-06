package com.xplc.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CaptureService extends Service {

    public static final String ACTION_START = "com.xplc.capture.START";
    public static final String ACTION_STOP = "com.xplc.capture.STOP";
    public static final String ACTION_STATE = "com.xplc.capture.STATE";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_ELAPSED = "elapsed";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_URI = "uri";

    private static final String CHANNEL_ID = "xplc_capture";
    private static final int NOTIFICATION_ID = 1701;
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int FRAME_BYTES = CHANNELS * BYTES_PER_SAMPLE;
    private static final int SOUND_THRESHOLD = 160;
    private static final long START_PADDING_BYTES = (long) SAMPLE_RATE * FRAME_BYTES * 350L / 1000L;
    private static final long END_PADDING_BYTES = (long) SAMPLE_RATE * FRAME_BYTES * 700L / 1000L;

    public static volatile boolean isRunning = false;

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private File rawFile;
    private volatile boolean stopRequested = false;
    private long startedAt = 0L;
    private long totalBytes = 0L;
    private long firstSoundByte = -1L;
    private long lastSoundByte = -1L;
    private long lastUiBroadcast = 0L;
    private long lastNotificationUpdate = 0L;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop("Finishing recording…");
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action) || isRunning) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == 0 || resultData == null) {
            broadcast(false, 0, "Capture permission data was missing.", null);
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        startedAt = SystemClock.elapsedRealtime();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("Ready for playback", 0L),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        );

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection unavailable");

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    requestStop("Android ended the capture session.");
                }
            }, new Handler(Looper.getMainLooper()));

            startAudioCapture();
        } catch (Exception e) {
            fail("Couldn't start internal capture: " + safeMessage(e));
        }

        return START_NOT_STICKY;
    }

    private void startAudioCapture() throws IOException {
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) minBuffer = SAMPLE_RATE * FRAME_BYTES / 5;
        int bufferSize = Math.max(minBuffer * 2, SAMPLE_RATE * FRAME_BYTES / 4);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord failed to initialize");
        }

        rawFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".pcm");
        stopRequested = false;
        isRunning = true;
        broadcast(true, 0L, "Waiting for capturable audio…", null);

        final int finalBufferSize = bufferSize;
        recordingThread = new Thread(() -> recordLoop(finalBufferSize), "XPLC-Audio-Capture");
        recordingThread.start();
    }

    private void recordLoop(int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        String finalMessage = "Capture stopped.";
        Uri savedUri = null;

        try (BufferedOutputStream rawOut = new BufferedOutputStream(new FileOutputStream(rawFile))) {
            audioRecord.startRecording();

            while (!stopRequested) {
                int read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;

                long before = totalBytes;
                rawOut.write(buffer, 0, read);
                inspectSound(buffer, read, before);
                totalBytes += read;

                long now = SystemClock.elapsedRealtime();
                if (now - lastUiBroadcast >= 900L) {
                    lastUiBroadcast = now;
                    String status = firstSoundByte >= 0
                            ? "Capturing digital playback…"
                            : "Waiting for capturable audio…";
                    broadcast(true, now - startedAt, status, null);
                }
                if (now - lastNotificationUpdate >= 5000L) {
                    lastNotificationUpdate = now;
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID,
                                buildNotification(firstSoundByte >= 0 ? "Capturing internal audio" : "Waiting for audio",
                                        now - startedAt));
                    }
                }
            }

            rawOut.flush();
        } catch (Exception e) {
            finalMessage = "Capture error: " + safeMessage(e);
        } finally {
            try {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                if (audioRecord != null) audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;

            if (firstSoundByte >= 0 && lastSoundByte > firstSoundByte && rawFile != null && rawFile.exists()) {
                try {
                    savedUri = saveTrimmedWav();
                    if (savedUri != null) {
                        finalMessage = "Saved WAV to Music/XPLC Capture.";
                    }
                } catch (Exception e) {
                    finalMessage = "Couldn't save WAV: " + safeMessage(e);
                }
            } else if (!finalMessage.startsWith("Capture error")) {
                finalMessage = "No capturable audio detected. Try playing Suno in Chrome; the source app may block playback capture.";
            }

            if (rawFile != null && rawFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                rawFile.delete();
            }

            isRunning = false;
            stopRequested = true;
            broadcast(false, SystemClock.elapsedRealtime() - startedAt, finalMessage,
                    savedUri == null ? null : savedUri.toString());

            try {
                if (mediaProjection != null) mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;

            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void inspectSound(byte[] buffer, int read, long absoluteStart) {
        int first = -1;
        int last = -1;

        for (int i = 0; i + 1 < read; i += 2) {
            int sample = (short) (((buffer[i + 1] & 0xFF) << 8) | (buffer[i] & 0xFF));
            if (Math.abs(sample) >= SOUND_THRESHOLD) {
                if (first < 0) first = i;
                last = i + 2;
            }
        }

        if (first >= 0) {
            long firstAbs = absoluteStart + first;
            long lastAbs = absoluteStart + last;
            if (firstSoundByte < 0) firstSoundByte = firstAbs;
            lastSoundByte = lastAbs;
        }
    }

    private Uri saveTrimmedWav() throws IOException {
        long start = Math.max(0L, firstSoundByte - START_PADDING_BYTES);
        long end = Math.min(totalBytes, lastSoundByte + END_PADDING_BYTES);
        start -= start % FRAME_BYTES;
        end -= end % FRAME_BYTES;
        if (end <= start) return null;

        long dataLength = end - start;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String displayName = "XPLC_Capture_" + timestamp + ".wav";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/XPLC Capture");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");

        boolean success = false;
        try (OutputStream os = resolver.openOutputStream(uri, "w");
             BufferedOutputStream out = os == null ? null : new BufferedOutputStream(os);
             RandomAccessFile raw = new RandomAccessFile(rawFile, "r")) {

            if (out == null) throw new IOException("Couldn't open output file");
            writeWavHeader(out, dataLength);
            raw.seek(start);

            byte[] copy = new byte[64 * 1024];
            long remaining = dataLength;
            while (remaining > 0) {
                int wanted = (int) Math.min(copy.length, remaining);
                int read = raw.read(copy, 0, wanted);
                if (read < 0) break;
                out.write(copy, 0, read);
                remaining -= read;
            }
            out.flush();
            success = remaining == 0;
        } finally {
            if (success) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                resolver.delete(uri, null, null);
            }
        }

        return success ? uri : null;
    }

    private void writeWavHeader(OutputStream out, long dataLength) throws IOException {
        long byteRate = (long) SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;
        writeAscii(out, "RIFF");
        writeLeInt(out, 36L + dataLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLeInt(out, 16);
        writeLeShort(out, 1);
        writeLeShort(out, CHANNELS);
        writeLeInt(out, SAMPLE_RATE);
        writeLeInt(out, byteRate);
        writeLeShort(out, FRAME_BYTES);
        writeLeShort(out, BYTES_PER_SAMPLE * 8);
        writeAscii(out, "data");
        writeLeInt(out, dataLength);
    }

    private void writeAscii(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private void writeLeShort(OutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
    }

    private void writeLeInt(OutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }

    private void requestStop(String message) {
        if (!isRunning) {
            stopSelf();
            return;
        }
        stopRequested = true;
        broadcast(true, SystemClock.elapsedRealtime() - startedAt, message, null);
    }

    private void fail(String message) {
        isRunning = false;
        stopRequested = true;
        broadcast(false, 0L, message, null);
        try {
            if (mediaProjection != null) mediaProjection.stop();
        } catch (Exception ignored) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String text, long elapsed) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, CaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String elapsedText = formatElapsed(elapsed);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.xplc.capture.R.drawable.ic_capture)
                .setContentTitle("XPLC Capture  •  " + elapsedText)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(com.xplc.capture.R.drawable.ic_capture, "Stop & Save", stopPending)
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Internal audio capture",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows while XPLC Capture is recording Android playback audio.");
        manager.createNotificationChannel(channel);
    }

    private void broadcast(boolean running, long elapsed, String message, String uri) {
        Intent state = new Intent(ACTION_STATE);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_RUNNING, running);
        state.putExtra(EXTRA_ELAPSED, elapsed);
        if (message != null) state.putExtra(EXTRA_MESSAGE, message);
        if (uri != null) state.putExtra(EXTRA_URI, uri);
        sendBroadcast(state);
    }

    private String formatElapsed(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
