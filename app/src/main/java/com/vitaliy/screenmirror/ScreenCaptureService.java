package com.vitaliy.screenmirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID =
            "ScreenMirrorChannel";

    private static final int PORT = 8080;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private ServerSocket serverSocket;

    private HandlerThread imageThread;
    private Handler imageHandler;

    private volatile byte[] latestFrame;

    private volatile String lastError =
            "WAITING_FOR_PROJECTION";

    private volatile boolean running = true;
    private volatile boolean serverStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();

        running = true;

        createNotificationChannel();

        Notification notification =
                new Notification.Builder(
                        this,
                        CHANNEL_ID)
                        .setContentTitle(
                                "Screen Mirror")
                        .setContentText(
                                "Трансляция экрана запущена")
                        .setSmallIcon(
                                android.R.drawable.ic_menu_view)
                        .build();

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q) {

            startForeground(
                    1,
                    notification,
                    ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        } else {

            startForeground(
                    1,
                    notification);
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        if (intent == null) {

            lastError =
                    "DEBUG_INTENT_NULL";

            stopSelf();

            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(
                        "resultCode",
                        -1);

        Intent data;

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU) {

            data =
                    intent.getParcelableExtra(
                            "data",
                            Intent.class);

        } else {

            data =
                    intent.getParcelableExtra(
                            "data");
        }

        lastError =
                "DEBUG_INTENT_OK RESULT="
                + resultCode
                + " DATA="
                + (data != null);

        if (resultCode != -1 ||
                data == null) {

            lastError =
                    "NO_PROJECTION_DATA";

            return START_NOT_STICKY;
        }

        running = true;

        startWebServer();

        startProjection(
                resultCode,
                data);

        return START_NOT_STICKY;
    }

    private void startProjection(
            int resultCode,
            Intent data) {

        releaseProjectionResources();

        latestFrame = null;

        lastError =
                "PROJECTION_STARTED";

        try {

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(
                                    MEDIA_PROJECTION_SERVICE);

            if (manager == null) {

                lastError =
                        "MEDIA_PROJECTION_MANAGER_NULL";

                return;
            }

            mediaProjection =
                    manager.getMediaProjection(
                            resultCode,
                            data);

            if (mediaProjection == null) {

                lastError =
                        "MEDIA_PROJECTION_NULL";

                return;
            }

            lastError =
                    "MEDIA_PROJECTION_OK";

            DisplayManager displayManager =
                    (DisplayManager)
                            getSystemService(
                                    DISPLAY_SERVICE);

            if (displayManager == null) {

                lastError =
                        "DISPLAY_MANAGER_NULL";

                return;
            }

            android.view.Display display =
                    displayManager.getDisplay(
                            android.view.Display
                                    .DEFAULT_DISPLAY);

            if (display == null) {

                lastError =
                        "DISPLAY_NULL";

                return;
            }

            DisplayMetrics metrics =
        new DisplayMetrics();

display.getRealMetrics(metrics);

int width =
        metrics.widthPixels;

int height =
        metrics.heightPixels;

int density =
        metrics.densityDpi;

if (width > 720) {

    float scale =
            720f / width;

    width =
            Math.round(width * scale);

    height =
            Math.round(height * scale);
}

            if (width <= 0 ||
                    height <= 0) {

                lastError =
                        "INVALID_DISPLAY_SIZE "
                        + width
                        + "x"
                        + height;

                return;
            }

            lastError =
                    "DISPLAY_SIZE "
                    + width
                    + "x"
                    + height;

            imageReader =
                    ImageReader.newInstance(
                            width,
                            height,
                            PixelFormat.RGBA_8888,
                            3);

            imageThread =
                    new HandlerThread(
                            "ScreenMirrorCapture");

            imageThread.start();

            imageHandler =
                    new Handler(
                            imageThread.getLooper());

            imageReader
                    .setOnImageAvailableListener(
                            reader -> {

                                Image image =
                                        null;

                                try {

                                    image =
                                            reader
                                                    .acquireLatestImage();

                                    if (image == null) {
                                        return;
                                    }

                                    Image.Plane plane =
                                            image
                                                    .getPlanes()[0];

                                    ByteBuffer source =
                                            plane
                                                    .getBuffer()
                                                    .duplicate();

                                    int imageWidth =
                                            image.getWidth();

                                    int imageHeight =
                                            image.getHeight();

                                    int pixelStride =
                                            plane
                                                    .getPixelStride();

                                    int rowStride =
                                            plane
                                                    .getRowStride();

                                    if (pixelStride <= 0 ||
                                            rowStride <= 0) {

                                        lastError =
                                                "INVALID_STRIDE "
                                                + pixelStride
                                                + "/"
                                                + rowStride;

                                        return;
                                    }

                                    if (pixelStride != 4) {

                                        lastError =
                                                "UNEXPECTED_PIXEL_STRIDE "
                                                + pixelStride;

                                        return;
                                    }

                                    int rowBytes =
                                            imageWidth *
                                            pixelStride;

                                    long requiredBytes =
                                            (long)
                                                    (imageHeight - 1)
                                            * rowStride
                                            + rowBytes;

                                    if (requiredBytes >
                                            source.remaining()) {

                                        lastError =
                                                "BUFFER_TOO_SMALL "
                                                + source.remaining()
                                                + "/"
                                                + requiredBytes;

                                        return;
                                    }

                                    byte[] pixels =
                                            new byte[
                                                    rowBytes *
                                                    imageHeight];

                                    for (int y = 0;
                                         y < imageHeight;
                                         y++) {

                                        int sourcePosition =
                                                y *
                                                rowStride;

                                        int targetPosition =
                                                y *
                                                rowBytes;

                                        source.position(
                                                sourcePosition);

                                        source.get(
                                                pixels,
                                                targetPosition,
                                                rowBytes);
                                    }

                                    Bitmap bitmap =
                                            Bitmap.createBitmap(
                                                    imageWidth,
                                                    imageHeight,
                                                    Bitmap.Config
                                                            .ARGB_8888);

                                    ByteBuffer packedBuffer =
                                            ByteBuffer.wrap(
                                                    pixels);

                                    bitmap.copyPixelsFromBuffer(
                                            packedBuffer);

                                    ByteArrayOutputStream output =
                                            new ByteArrayOutputStream();

                                    boolean compressed =
                                            bitmap.compress(
                                                    Bitmap
                                                            .CompressFormat
                                                            .JPEG,
                                                    30,
                                                    output);

                                    bitmap.recycle();

                                    if (!compressed) {

                                        lastError =
                                                "JPEG_COMPRESS_FAILED";

                                        return;
                                    }

                                    byte[] frame =
                                            output.toByteArray();

                                    if (frame.length > 0) {

                                        latestFrame =
                                                frame;

                                        lastError =
                                                "FRAME_OK "
                                                + frame.length
                                                + " SIZE="
                                                + imageWidth
                                                + "x"
                                                + imageHeight;
                                    }

                                } catch (Exception e) {

                                    lastError =
                                            e.getClass()
                                                    .getSimpleName()
                                            + ": "
                                            + e.getMessage();

                                    android.util.Log.e(
                                            "ScreenMirror",
                                            "ОШИБКА КАДРА",
                                            e);

                                } finally {

                                    if (image != null) {
                                        image.close();
                                    }
                                }

                            },
                            imageHandler);

            mediaProjection.registerCallback(
                    new MediaProjection.Callback() {

                        @Override
                        public void onStop() {

                            lastError =
                                    "MEDIA_PROJECTION_STOPPED";

                            releaseProjectionResourcesWithoutStop();
                        }
                    },
                    null);

            virtualDisplay =
                    mediaProjection
                            .createVirtualDisplay(
                                    "ScreenMirror",
                                    width,
                                    height,
                                    density,
                                    DisplayManager
                                            .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                    imageReader
                                            .getSurface(),
                                    null,
                                    null);

            if (virtualDisplay == null) {

                lastError =
                        "VIRTUAL_DISPLAY_NULL";

                return;
            }

            lastError =
                    "VIRTUAL_DISPLAY_OK "
                    + width
                    + "x"
                    + height;

        } catch (Exception e) {

            lastError =
                    e.getClass()
                            .getSimpleName()
                    + ": "
                    + e.getMessage();

            android.util.Log.e(
                    "ScreenMirror",
                    "ОШИБКА PROJECTION",
                    e);
        }
    }

    private void startWebServer() {

        if (serverStarted) {
            return;
        }

        serverStarted = true;

        new Thread(
                () -> {

                    try {

                        serverSocket =
                                new ServerSocket(
                                        PORT);

                        while (running) {

                            Socket socket =
                                    serverSocket.accept();

                            new Thread(
                                    () -> handleClient(
                                            socket),
                                    "ScreenMirrorClient")
                                    .start();
                        }

                    } catch (Exception e) {

                        if (running) {

                            android.util.Log.e(
                                    "ScreenMirror",
                                    "ОШИБКА СЕРВЕРА",
                                    e);
                        }
                    }

                },
                "ScreenMirrorServer")
                .start();
    }

    private void handleClient(
            Socket socket) {

        try {

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream()));

            String requestLine =
                    reader.readLine();

            if (requestLine == null) {

                socket.close();

                return;
            }

            String line;

            while ((line =
                    reader.readLine()) != null) {

                if (line.isEmpty()) {
                    break;
                }
            }

            OutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream());

            if (requestLine.contains(
        "GET /frame")) {

    sendFrame(output);

} else if (requestLine.contains(
        "GET /status")) {

                sendStatus(output);

            } else {

                sendWebPage(output);
            }

        } catch (Exception e) {

            android.util.Log.e(
                    "ScreenMirror",
                    "ОШИБКА CLIENT",
                    e);

        } finally {

            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void sendWebPage(
        OutputStream output)
        throws Exception {

    String html =
            "<!DOCTYPE html>" +
            "<html>" +
            "<head>" +
            "<meta name='viewport' " +
            "content='width=device-width,initial-scale=1'>" +
            "<style>" +
            "html,body{" +
            "margin:0;" +
            "padding:0;" +
            "background:#000;" +
            "width:100%;" +
            "height:100%;" +
            "overflow:hidden;" +
            "}" +
            "#screen{" +
            "width:100%;" +
            "height:100%;" +
            "object-fit:contain;" +
            "}" +
            "#status{" +
            "position:fixed;" +
            "left:0;" +
            "top:0;" +
            "right:0;" +
            "padding:12px;" +
            "box-sizing:border-box;" +
            "color:white;" +
            "background:rgba(0,0,0,.75);" +
            "font-family:sans-serif;" +
            "font-size:14px;" +
            "z-index:10;" +
            "}" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div id='status'>" +
            "Подключение..." +
            "</div>" +
            "<img id='screen'>" +
            "<script>" +
            "function updateFrame(){" +
            "document.getElementById('screen').src=" +
            "'/frame?t='+Date.now();" +
            "}" +
            "function updateStatus(){" +
            "fetch('/status?t='+Date.now())" +
            ".then(function(r){" +
            "return r.text();" +
            "})" +
            ".then(function(t){" +
            "document.getElementById('status')" +
            ".textContent=t;" +
            "})" +
            ".catch(function(){" +
            "document.getElementById('status')" +
            ".textContent='SERVER_ERROR';" +
            "});" +
            "}" +
            "updateFrame();" +
            "updateStatus();" +
            "setInterval(updateFrame,100); +
            "setInterval(updateStatus,1000);" +
            "</script>" +
            "</body>" +
            "</html>";

    byte[] bytes =
            html.getBytes("UTF-8");

    String header =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; " +
            "charset=UTF-8\r\n" +
            "Content-Length: " +
            bytes.length +
            "\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "\r\n";

    output.write(
            header.getBytes("UTF-8"));

    output.write(bytes);

    output.flush();
}

    private void sendFrame(
            OutputStream output)
            throws Exception {

        byte[] frame =
                latestFrame;

        if (frame == null) {

            String message =
                    "WAITING_FOR_FRAME\n" +
                    "ERROR: " +
                    lastError;

            byte[] messageBytes =
                    message.getBytes("UTF-8");

            String header =
                    "HTTP/1.1 503 Service Unavailable\r\n" +
                    "Content-Type: text/plain; " +
                    "charset=UTF-8\r\n" +
                    "Content-Length: " +
                    messageBytes.length +
                    "\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";

            output.write(
                    header.getBytes("UTF-8"));

            output.write(
                    messageBytes);

            output.flush();

            return;
        }

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " +
                frame.length +
                "\r\n" +
                "Cache-Control: no-store, no-cache, " +
                "must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        output.write(
                header.getBytes("UTF-8"));

        output.write(frame);

        output.flush();
    }

    private void sendMjpegStream(
            OutputStream output)
            throws Exception {

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; " +
                "boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n";

        output.write(
                header.getBytes("UTF-8"));

        output.flush();

        byte[] lastSent = null;

        while (running) {

            byte[] frame =
                    latestFrame;

            if (frame != null &&
                    frame != lastSent) {

                String frameHeader =
                        "--frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " +
                        frame.length +
                        "\r\n" +
                        "\r\n";

                output.write(
                        frameHeader.getBytes("UTF-8"));

                output.write(frame);

                output.write(
                        "\r\n".getBytes("UTF-8"));

                output.flush();

                lastSent = frame;
            }

            Thread.sleep(15);
        }
    }

    private void sendStatus(
            OutputStream output)
            throws Exception {

        String status =
                lastError;

        byte[] bytes =
                status.getBytes("UTF-8");

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; " +
                "charset=UTF-8\r\n" +
                "Content-Length: " +
                bytes.length +
                "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        output.write(
                header.getBytes("UTF-8"));

        output.write(bytes);

        output.flush();
    }

    private void releaseProjectionResources() {

        releaseProjectionResourcesWithoutStop();

        if (mediaProjection != null) {

            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }

            mediaProjection = null;
        }

        latestFrame = null;
    }

    private void releaseProjectionResourcesWithoutStop() {

        if (virtualDisplay != null) {

            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }

            virtualDisplay = null;
        }

        if (imageReader != null) {

            try {
                imageReader.close();
            } catch (Exception ignored) {
            }

            imageReader = null;
        }

        if (imageThread != null) {

            try {
                imageThread.quitSafely();
            } catch (Exception ignored) {
            }

            imageThread = null;
            imageHandler = null;
        }
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Screen Mirror",
                            NotificationManager
                                    .IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class);

            if (manager != null) {

                manager.createNotificationChannel(
                        channel);
            }
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        releaseProjectionResources();

        serverStarted = false;

        try {

            if (serverSocket != null) {

                serverSocket.close();
                serverSocket = null;
            }

        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

        @Override
    public IBinder onBind(
            Intent intent) {

        return null;
    }
}
