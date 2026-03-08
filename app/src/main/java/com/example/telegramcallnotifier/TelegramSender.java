package com.example.telegramcallnotifier;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TelegramSender {

    private static final String PREFS_NAME = "TelegramPrefs";
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_CHAT_ID = "chat_id";
    private static final String TAG = "TelegramSender";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public TelegramSender(Context context) {
        this.context = context;
    }

    public void saveConfig(String token, String chatId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_BOT_TOKEN, token)
                .putString(KEY_CHAT_ID, chatId)
                .apply();
    }

    public String getBotToken() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_BOT_TOKEN, "");
    }

    public String getChatId() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CHAT_ID, "");
    }

    public void sendMessage(String message) {
        String token = getBotToken();
        String chatId = getChatId();

        if (token.isEmpty() || chatId.isEmpty()) {
            Log.e(TAG, "Bot token or Chat ID not set");
            return;
        }

        executor.execute(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + token + "/sendMessage?chat_id=" + chatId + "&text=" + URLEncoder.encode(message, "UTF-8");
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String inputLine;
                    StringBuilder response = new StringBuilder();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();
                    Log.d(TAG, "Message sent: " + response.toString());
                } else {
                    Log.e(TAG, "Failed to send message. Response Code: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
            }
        });
    }
}
