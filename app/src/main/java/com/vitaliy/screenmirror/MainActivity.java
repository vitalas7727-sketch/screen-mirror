package com.vitaliy.screenmirror;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
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

    serviceIntent.putExtra(
            "projectionData",
            data);

    if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O) {

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
