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
                                45,
                                output);

                        cropped.recycle();

                        latestFrame =
                                output.toByteArray();

                    } catch (Exception ignored) {

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
    }

    private void startWebServer() {

        new Thread(() -> {

            try {

                if (serverSocket != null &&
                        !serverSocket.isClosed()) {
                    return;
                }

                serverSocket =
                        new ServerSocket(PORT);

                while (running) {

                    Socket socket =
                            serverSocket.accept();

                    new Thread(() ->
                            handleClient(socket)
                    ).start();
                }

            } catch (Exception ignored) {
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

            if (requestLine.contains("GET /stream")) {

                sendStream(output);

            } else {

                sendWebPage(output);
            }

        } catch (Exception ignored) {

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
                "<img src='/stream'>" +
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

    private void sendStream(
            OutputStream output)
            throws Exception {

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "\r\n";

        output.write(
                header.getBytes("UTF-8"));

        output.flush();

        while (running) {

            byte[] frame =
                    latestFrame;

            if (frame != null) {

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
            }

            Thread.sleep(100);
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

            manager.createNotificationChannel(
                    channel);
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (imageReader != null) {
            imageReader.close();
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
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
