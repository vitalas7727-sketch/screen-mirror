package com.vitaliy.screenmirror;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    private static final int REQUEST_CAPTURE = 1001;

    private TextView statusText;
    private TextView urlText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout =
                new LinearLayout(this);

        layout.setOrientation(
                LinearLayout.VERTICAL);

        layout.setPadding(
                40,
                60,
                40,
                40);

        TextView title =
                new TextView(this);

        title.setText(
                "Screen Mirror");

        title.setTextSize(26);

        statusText =
                new TextView(this);

        statusText.setText(
                "Готов к запуску");

        statusText.setTextSize(18);

        urlText =
                new TextView(this);

        urlText.setText(
                "Телевизор пока не подключён");

        urlText.setTextSize(17);

        Button startButton =
                new Button(this);

        startButton.setText(
                "Начать трансляцию");

        layout.addView(title);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(startButton);

        setContentView(layout);

        startButton.setOnClickListener(
                v -> startScreenCapture());
    }

    private void startScreenCapture() {

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE);

        if (manager == null) {

            statusText.setText(
                    "ОШИБКА: MEDIA_PROJECTION_MANAGER");

            return;
        }

        Intent captureIntent =
                manager.createScreenCaptureIntent();

        startActivityForResult(
                captureIntent,
                REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data);

        if (requestCode != REQUEST_CAPTURE) {
            return;
        }

        if (resultCode != RESULT_OK) {

            statusText.setText(
                    "CAPTURE_CANCELLED");

            return;
        }

        if (data == null) {

            statusText.setText(
                    "CAPTURE_DATA_NULL");

            return;
        }

        statusText.setText(
                "CAPTURE_DATA_OK");

        Intent serviceIntent =
                new Intent(
                        MainActivity.this,
                        ScreenCaptureService.class);

        serviceIntent.putExtra(
                "resultCode",
                resultCode);

        serviceIntent.putExtra(
                "data",
                data);

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            startForegroundService(
                    serviceIntent);

        } else {

            startService(
                    serviceIntent);
        }

        String ip =
                getLocalIpAddress();

        if (ip != null) {

            urlText.setText(
                    "На телевизоре открой:\nhttp://"
                    + ip
                    + ":8080");

        } else {

            urlText.setText(
                    "IP-адрес не найден");
        }

        statusText.setText(
                "Трансляция запущена");
    }

    private String getLocalIpAddress() {

        try {

            for (NetworkInterface networkInterface :
                    Collections.list(
                            NetworkInterface
                                    .getNetworkInterfaces())) {

                for (java.net.InetAddress address :
                        Collections.list(
                                networkInterface
                                        .getInetAddresses())) {

                    if (!address
                            .isLoopbackAddress()
                            && address
                            instanceof Inet4Address) {

                        return address
                                .getHostAddress();
                    }
                }
            }

        } catch (Exception e) {

            statusText.setText(
                    "IP ERROR: "
                    + e.getMessage());
        }

        return null;
    }
    }
