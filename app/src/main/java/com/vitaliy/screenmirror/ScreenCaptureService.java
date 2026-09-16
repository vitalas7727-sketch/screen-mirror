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

    private static final String CHANNEL_ID = "ScreenMirrorChannel";
    private static final int PORT = 8080;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private ServerSocket serverSocket;

    private volatile byte[] latestFrame;
    private volatile String lastError = "";
    private volatile boolean running = true;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification notification =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Screen Mirror")
                        .setContentText("Трансляция экрана запущена")
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        startWebServer();

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra("resultCode", -1);

        Intent data;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data = intent.getParcelableExtra(
                    "data",
                    Intent.class
            );
        } else {
            data = intent.getParcelableExtra("data");
        }

        if (resultCode != -1 && data != null) {
            startProjection(resultCode, data);
        }

        return START_NOT_STICKY;
    }

    private void startProjection(
            int resultCode,
            Intent data) {

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE);

        mediaProjection =
                manager.getMediaProjection(
                        resultCode,
                        data);

        if (mediaProjection == null) {
            return;
        }

        mediaProjection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        running = false;

                        if (virtualDisplay != null) {
                            virtualDisplay.release();
                            virtualDisplay = null;
                        }
                    }
                },
                null
        );

        DisplayMetrics metrics =
                getResources().getDisplayMetrics();

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader =
                ImageReader.newInstance(
                        width,
                        height,
                        PixelFormat.RGBA_8888,
                        2);

        imageReader.setOnImageAvailableListener(
                reader -> {

                    Image image = null;

                    try {

                        image =
                                reader.acquireLatestImage();

                        if (image == null) {
                            return;
                        }

                        Image.Plane plane =
                                image.getPlanes()[0];

                        ByteBuffer buffer =
                                plane.getBuffer();

                        int pixelStride =
                                plane.getPixelStride();

                        int rowStride =
                                plane.getRowStride();

                        int rowPadding =
                                rowStride -
                                pixelStride * width;

                        int bitmapWidth =
                                width +
                                rowPadding / pixelStride;

                        Bitmap bitmap =
                                Bitmap.createBitmap(
                                        bitmapWidth,
                                        height,
                                        Bitmap.Config.ARGB_8888);

                        buffer.rewind();

                        bitmap.copyPixelsFromBuffer(
                                buffer);

                        Bitmap cropped =
                                Bitmap.createBitmap(
                                        bitmap,
                                        0,
                                        0,
                                        width,
                                        height);

                        bitmap.recycle();

                        ByteArrayOutputStream output =
                                new ByteArrayOutputStream();

                        cropped.compress(
                                Bitmap.CompressFormat.JPEG,
                                60,
                                output);

                        cropped.recycle();

                        latestFrame =
                                output.toByteArray();

                        android.util.Log.d(
                                "ScreenMirror",
                                "КАДР: " +
                                latestFrame.length);

                    } catch (Exception e) {

    lastError =
            e.getClass().getSimpleName()
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
                null);

        virtualDisplay =
                mediaProjection.createVirtualDisplay(
                        "ScreenMirror",
                        width,
                        height,
                        density,
                        DisplayManager
                                .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.getSurface(),
                        null,
                        null);
                if (virtualDisplay == null) {
            lastError = "VIRTUAL_DISPLAY_NULL";
        } else {
            lastError = "VIRTUAL_DISPLAY_CREATED";
                }
    }

    private void startWebServer() {

        new Thread(() -> {

            try {

                serverSocket =
                        new ServerSocket(PORT);

                while (running) {

                    Socket socket =
                            serverSocket.accept();

                    new Thread(() ->
                            handleClient(socket)
                    ).start();
                }

            } catch (Exception e) {

                android.util.Log.e(
                        "ScreenMirror",
                        "ОШИБКА СЕРВЕРА",
                        e);
            }

        }).start();
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

            while ((line = reader.readLine()) != null) {

                if (line.isEmpty()) {
                    break;
                }
            }

            OutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream());

            if (requestLine.contains("GET /frame.jpg")) {

                sendFrame(output);

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
                "content='width=device-width'>" +
                "<style>" +
                "html,body{" +
                "margin:0;" +
                "padding:0;" +
                "background:black;" +
                "width:100%;" +
                "height:100%;" +
                "overflow:hidden;" +
                "}" +
                "img{" +
                "width:100%;" +
                "height:100%;" +
                "object-fit:contain;" +
                "}" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<img src='/frame.jpg' " +
                "id='screen'>" +
                "<script>" +
                "setInterval(function(){" +
                "document.getElementById('screen')" +
                ".src='/frame.jpg?t=' + Date.now();" +
                "},500);" +
                "</script>" +
                "</body>" +
                "</html>";

        byte[] bytes =
                html.getBytes("UTF-8");

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " +
                bytes.length +
                "\r\n" +
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

    byte[] frame = latestFrame;

    if (frame == null) {

        String message =
                "WAITING_FOR_FRAME\nERROR: "
                + lastError;

        byte[] messageBytes =
                message.getBytes("UTF-8");

        String header =
                "HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Content-Length: " +
                messageBytes.length +
                "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        output.write(
                header.getBytes("UTF-8"));

        output.write(messageBytes);

        output.flush();

        return;
    }

    String header =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: " +
            frame.length +
            "\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n" +
            "\r\n";

    output.write(
            header.getBytes("UTF-8"));

    output.write(frame);

    output.flush();
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

            manager.createNotificationChannel(
                    channel);
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        try {

            if (serverSocket != null) {
                serverSocket.close();
            }

        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

    @Override
public IBinder onBind(Intent intent) {
    return null;
}
}       
