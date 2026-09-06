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
import android.media.AudioManager;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private static final int CHANNELS = 2;
    private static final int DEFAULT_SAMPLE_RATE = 48_000;

    // Very low thresholds so quiet digital playback is not mistaken for silence.
    private static final float FLOAT_SOUND_THRESHOLD = 0.00001f;
    private static final int PCM16_SOUND_THRESHOLD = 1;

    // -1 dBFS peak target. This restores playback captured at low Android media volume.
    private static final double TARGET_PEAK = 0.8912509381;
    private static final double MAX_GAIN = 251.18864315; // +48 dB safety ceiling.

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

    private int sampleRate = DEFAULT_SAMPLE_RATE;
    private int encoding = AudioFormat.ENCODING_PCM_FLOAT;
    private int bytesPerSample = 4;
    private int frameBytes = CHANNELS * bytesPerSample;
    private int captureBufferSize = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop("Finishing and restoring volume…");
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
            broadcast(false, 0L, "Capture permission data was missing.", null);
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        startedAt = SystemClock.elapsedRealtime();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("Preparing hi-res capture", 0L),
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
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build();

        sampleRate = resolveNativeOutputSampleRate();

        boolean floatReady = configureRecorder(config, AudioFormat.ENCODING_PCM_FLOAT);
        if (!floatReady) {
            boolean pcm16Ready = configureRecorder(config, AudioFormat.ENCODING_PCM_16BIT);
            if (!pcm16Ready) {
                throw new IllegalStateException("AudioRecord failed to initialize");
            }
        }

        rawFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".pcm");
        stopRequested = false;
        totalBytes = 0L;
        firstSoundByte = -1L;
        lastSoundByte = -1L;
        lastUiBroadcast = 0L;
        lastNotificationUpdate = 0L;
        isRunning = true;

        broadcast(true, 0L, captureModeLabel() + " — waiting for digital audio…", null);

        recordingThread = new Thread(() -> {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                recordLoopFloat();
            } else {
                recordLoopPcm16();
            }
        }, "XPLC-AutoGain-Capture");
        recordingThread.start();
    }

    private boolean configureRecorder(AudioPlaybackCaptureConfiguration config, int requestedEncoding) {
        try {
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Exception ignored) {}
                audioRecord = null;
            }

            int requestedBytes = requestedEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            int requestedFrameBytes = CHANNELS * requestedBytes;

            int minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    requestedEncoding
            );
            if (minBuffer <= 0) minBuffer = sampleRate * requestedFrameBytes / 4;

            int bufferSize = Math.max(minBuffer * 4, sampleRate * requestedFrameBytes / 2);
            bufferSize -= bufferSize % requestedFrameBytes;

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(requestedEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            AudioRecord candidate = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                candidate.release();
                return false;
            }

            audioRecord = candidate;
            encoding = requestedEncoding;
            bytesPerSample = requestedBytes;
            frameBytes = requestedFrameBytes;
            captureBufferSize = bufferSize;
            return true;
        } catch (Exception e) {
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Exception ignored) {}
                audioRecord = null;
            }
            return false;
        }
    }

    private int resolveNativeOutputSampleRate() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                String value = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                if (value != null) {
                    int nativeRate = Integer.parseInt(value.trim());
                    if (nativeRate >= 32_000 && nativeRate <= 48_000) return nativeRate;
                }
            }
        } catch (Exception ignored) {}
        return DEFAULT_SAMPLE_RATE;
    }

    private void recordLoopFloat() {
        int floatCapacity = Math.max(CHANNELS * 2048, captureBufferSize / 4);
        floatCapacity -= floatCapacity % CHANNELS;
        float[] samples = new float[floatCapacity];
        byte[] bytes = new byte[floatCapacity * 4];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        String finalMessage = "Capture stopped.";

        try (BufferedOutputStream rawOut = new BufferedOutputStream(new FileOutputStream(rawFile), 256 * 1024)) {
            audioRecord.startRecording();

            while (!stopRequested) {
                int read = audioRecord.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;

                int alignedRead = read - (read % CHANNELS);
                if (alignedRead <= 0) continue;

                byteBuffer.clear();
                for (int i = 0; i < alignedRead; i++) byteBuffer.putFloat(samples[i]);

                int byteCount = alignedRead * 4;
                long before = totalBytes;
                rawOut.write(bytes, 0, byteCount);
                inspectSoundFloat(samples, alignedRead, before);
                totalBytes += byteCount;
                updateProgress();
            }
            rawOut.flush();
        } catch (Exception e) {
            finalMessage = "Capture error: " + safeMessage(e);
        } finally {
            finishCapture(finalMessage);
        }
    }

    private void recordLoopPcm16() {
        byte[] buffer = new byte[captureBufferSize];
        String finalMessage = "Capture stopped.";

        try (BufferedOutputStream rawOut = new BufferedOutputStream(new FileOutputStream(rawFile), 256 * 1024)) {
            audioRecord.startRecording();

            while (!stopRequested) {
                int read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;

                int alignedRead = read - (read % frameBytes);
                if (alignedRead <= 0) continue;

                long before = totalBytes;
                rawOut.write(buffer, 0, alignedRead);
                inspectSoundPcm16(buffer, alignedRead, before);
                totalBytes += alignedRead;
                updateProgress();
            }
            rawOut.flush();
        } catch (Exception e) {
            finalMessage = "Capture error: " + safeMessage(e);
        } finally {
            finishCapture(finalMessage);
        }
    }

    private void updateProgress() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiBroadcast >= 900L) {
            lastUiBroadcast = now;
            String status = firstSoundByte >= 0
                    ? captureModeLabel() + " — capturing; volume will be restored on save…"
                    : captureModeLabel() + " — waiting for capturable audio…";
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

    private String captureModeLabel() {
        String depth = encoding == AudioFormat.ENCODING_PCM_FLOAT ? "32-bit float" : "16-bit PCM";
        String rate = sampleRate == 44_100 ? "44.1 kHz" : (sampleRate / 1000) + " kHz";
        return depth + " • " + rate + " • stereo";
    }

    private void inspectSoundFloat(float[] samples, int count, long absoluteStart) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < count; i++) {
            float sample = samples[i];
            if (!Float.isNaN(sample) && !Float.isInfinite(sample) && Math.abs(sample) >= FLOAT_SOUND_THRESHOLD) {
                if (first < 0) first = i;
                last = i + 1;
            }
        }
        if (first >= 0) {
            long firstAbs = absoluteStart + (long) first * 4L;
            long lastAbs = absoluteStart + (long) last * 4L;
            if (firstSoundByte < 0) firstSoundByte = firstAbs;
            lastSoundByte = lastAbs;
        }
    }

    private void inspectSoundPcm16(byte[] buffer, int read, long absoluteStart) {
        int first = -1;
        int last = -1;
        for (int i = 0; i + 1 < read; i += 2) {
            int sample = (short) (((buffer[i + 1] & 0xFF) << 8) | (buffer[i] & 0xFF));
            if (Math.abs(sample) >= PCM16_SOUND_THRESHOLD) {
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

    private void finishCapture(String initialMessage) {
        String finalMessage = initialMessage;
        Uri savedUri = null;

        try {
            if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        } catch (Exception ignored) {}
        try {
            if (audioRecord != null) audioRecord.release();
        } catch (Exception ignored) {}
        audioRecord = null;

        if (firstSoundByte >= 0 && lastSoundByte > firstSoundByte && rawFile != null && rawFile.exists()) {
            try {
                SaveResult result = saveNormalizedWav();
                savedUri = result.uri;
                if (savedUri != null) {
                    finalMessage = "Saved full-volume WAV • " + captureModeLabel()
                            + " • auto gain " + formatGainDb(result.gain) + ".";
                }
            } catch (Exception e) {
                finalMessage = "Couldn't save WAV: " + safeMessage(e);
            }
        } else if (!finalMessage.startsWith("Capture error")) {
            finalMessage = "No digital signal detected. At media volume 0 Android may be supplying literal silence; try one volume step above zero.";
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
        } catch (Exception ignored) {}
        mediaProjection = null;

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private SaveResult saveNormalizedWav() throws IOException {
        long startPaddingBytes = (long) sampleRate * frameBytes * 350L / 1000L;
        long endPaddingBytes = (long) sampleRate * frameBytes * 900L / 1000L;

        long start = Math.max(0L, firstSoundByte - startPaddingBytes);
        long end = Math.min(totalBytes, lastSoundByte + endPaddingBytes);
        start -= start % frameBytes;
        end -= end % frameBytes;
        if (end <= start) throw new IOException("No usable audio range");

        double peak = scanPeak(start, end);
        if (peak <= 0.0) throw new IOException("Captured stream contained only digital silence");

        double gain = TARGET_PEAK / peak;
        if (gain < 1.0) gain = 1.0;
        if (gain > MAX_GAIN) gain = MAX_GAIN;

        long dataLength = end - start;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String displayName = "XPLC_FullVolume_" + timestamp + ".wav";

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
             BufferedOutputStream out = os == null ? null : new BufferedOutputStream(os, 256 * 1024);
             RandomAccessFile raw = new RandomAccessFile(rawFile, "r")) {

            if (out == null) throw new IOException("Couldn't open output file");
            writeWavHeader(out, dataLength);
            raw.seek(start);

            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                writeNormalizedFloat(raw, out, end - start, gain);
            } else {
                writeNormalizedPcm16(raw, out, end - start, gain);
            }

            out.flush();
            success = true;
        } finally {
            if (success) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                resolver.delete(uri, null, null);
            }
        }

        return new SaveResult(uri, gain);
    }

    private double scanPeak(long start, long end) throws IOException {
        try (RandomAccessFile raw = new RandomAccessFile(rawFile, "r")) {
            raw.seek(start);
            long remaining = end - start;
            double peak = 0.0;

            byte[] buffer = new byte[256 * 1024];
            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                wanted -= wanted % bytesPerSample;
                if (wanted <= 0) break;
                int read = raw.read(buffer, 0, wanted);
                if (read <= 0) break;

                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    ByteBuffer bb = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN);
                    while (bb.remaining() >= 4) {
                        float value = bb.getFloat();
                        if (!Float.isNaN(value) && !Float.isInfinite(value)) {
                            double abs = Math.abs((double) value);
                            if (abs > peak) peak = abs;
                        }
                    }
                } else {
                    for (int i = 0; i + 1 < read; i += 2) {
                        short value = (short) (((buffer[i + 1] & 0xFF) << 8) | (buffer[i] & 0xFF));
                        double abs = Math.abs((double) value) / 32768.0;
                        if (abs > peak) peak = abs;
                    }
                }
                remaining -= read;
            }
            return peak;
        }
    }

    private void writeNormalizedFloat(RandomAccessFile raw, BufferedOutputStream out,
                                      long remaining, double gain) throws IOException {
        byte[] input = new byte[256 * 1024];
        byte[] output = new byte[256 * 1024];

        while (remaining > 0) {
            int wanted = (int) Math.min(input.length, remaining);
            wanted -= wanted % 4;
            if (wanted <= 0) break;
            int read = raw.read(input, 0, wanted);
            if (read <= 0) break;

            ByteBuffer in = ByteBuffer.wrap(input, 0, read).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
            outBuffer.clear();

            while (in.remaining() >= 4) {
                float sample = in.getFloat();
                if (Float.isNaN(sample) || Float.isInfinite(sample)) sample = 0f;
                double boosted = sample * gain;
                if (boosted > TARGET_PEAK) boosted = TARGET_PEAK;
                if (boosted < -TARGET_PEAK) boosted = -TARGET_PEAK;
                outBuffer.putFloat((float) boosted);
            }

            out.write(output, 0, outBuffer.position());
            remaining -= read;
        }
    }

    private void writeNormalizedPcm16(RandomAccessFile raw, BufferedOutputStream out,
                                      long remaining, double gain) throws IOException {
        byte[] input = new byte[256 * 1024];
        byte[] output = new byte[256 * 1024];
        int maxTarget = (int) Math.round(32767.0 * TARGET_PEAK);

        while (remaining > 0) {
            int wanted = (int) Math.min(input.length, remaining);
            wanted -= wanted % 2;
            if (wanted <= 0) break;
            int read = raw.read(input, 0, wanted);
            if (read <= 0) break;

            for (int i = 0; i + 1 < read; i += 2) {
                short sample = (short) (((input[i + 1] & 0xFF) << 8) | (input[i] & 0xFF));
                int boosted = (int) Math.round(sample * gain);
                if (boosted > maxTarget) boosted = maxTarget;
                if (boosted < -maxTarget) boosted = -maxTarget;
                output[i] = (byte) (boosted & 0xFF);
                output[i + 1] = (byte) ((boosted >> 8) & 0xFF);
            }

            out.write(output, 0, read);
            remaining -= read;
        }
    }

    private void writeWavHeader(OutputStream out, long dataLength) throws IOException {
        long byteRate = (long) sampleRate * CHANNELS * bytesPerSample;
        int formatCode = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 3 : 1;

        writeAscii(out, "RIFF");
        writeLeInt(out, 36L + dataLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLeInt(out, 16);
        writeLeShort(out, formatCode);
        writeLeShort(out, CHANNELS);
        writeLeInt(out, sampleRate);
        writeLeInt(out, byteRate);
        writeLeShort(out, frameBytes);
        writeLeShort(out, bytesPerSample * 8);
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

    private String formatGainDb(double gain) {
        double db = 20.0 * Math.log10(Math.max(1.0, gain));
        return String.format(Locale.US, "+%.1f dB", db);
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
        } catch (Exception ignored) {}
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

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_capture)
                .setContentTitle("XPLC Capture • " + formatElapsed(elapsed))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(R.drawable.ic_capture, "Stop & Save", stopPending)
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
        channel.setDescription("Captures Android playback and restores low playback volume on save.");
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

    private static class SaveResult {
        final Uri uri;
        final double gain;

        SaveResult(Uri uri, double gain) {
            this.uri = uri;
            this.gain = gain;
        }
    }
}
