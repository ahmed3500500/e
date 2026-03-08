package com.example.telegramcallnotifier;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TelegramSender telegramSender;
    private EditText editBotToken;
    private EditText editChatId;
    private TextView textStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        telegramSender = new TelegramSender(this);

        editBotToken = findViewById(R.id.editBotToken);
        editChatId = findViewById(R.id.editChatId);
        textStatus = findViewById(R.id.textStatus);
        Button btnSave = findViewById(R.id.btnSave);

        // Load saved config
        editBotToken.setText(telegramSender.getBotToken());
        editChatId.setText(telegramSender.getChatId());

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String token = editBotToken.getText().toString().trim();
                String chatId = editChatId.getText().toString().trim();

                if (token.isEmpty() || chatId.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter both Token and Chat ID", Toast.LENGTH_SHORT).show();
                    return;
                }

                telegramSender.saveConfig(token, chatId);
                checkPermissionsAndStartService();
            }
        });

        checkPermissionsAndStartService();
    }

    private void checkPermissionsAndStartService() {
        String[] permissions = {
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.FOREGROUND_SERVICE
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             // Android 13+ Notification Permission
            permissions = new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            startService();
        }
    }

    private void startService() {
        if (!telegramSender.getBotToken().isEmpty()) {
            Intent serviceIntent = new Intent(this, CallMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            textStatus.setText(getString(R.string.status_label) + " " + getString(R.string.service_running));
        } else {
            textStatus.setText(getString(R.string.status_label) + " " + getString(R.string.service_stopped));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService();
            } else {
                Toast.makeText(this, "Permissions are required for the app to work", Toast.LENGTH_LONG).show();
            }
        }
    }
}
