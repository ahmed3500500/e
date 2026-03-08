package com.example.telegramcallnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallMonitorService extends Service {

    private static final String CHANNEL_ID = "CallMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private TelegramSender telegramSender;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;
    private long callStartTime = 0;
    private boolean isRinging = false;
    private String lastIncomingNumber = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        telegramSender = new TelegramSender(this);

        // Start Foreground Service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Monitor Active")
                .setContentText("Listening for incoming calls...")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Register Phone Listener
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                handleCallState(state, phoneNumber);
            }
        };
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        // Start Heartbeat
        startHeartbeat();
        
        // Notify start
        telegramSender.sendMessage("✅ Call Monitor Service Started\n🔋 Battery: " + getBatteryLevel() + "%\n📶 Network: " + getNetworkType());
    }

    private void handleCallState(int state, String phoneNumber) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                isRinging = true;
                lastIncomingNumber = phoneNumber;
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                String msg = "📞 Incoming Call\nNumber: " + phoneNumber + "\nTime: " + time + "\n🔋 Battery: " + getBatteryLevel() + "%";
                telegramSender.sendMessage(msg);
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (isRinging) {
                    // Call answered
                    callStartTime = System.currentTimeMillis();
                    isRinging = false; // Reset ringing state
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                if (callStartTime > 0) {
                    long duration = (System.currentTimeMillis() - callStartTime) / 1000;
                    String durationStr = formatDuration(duration);
                    String endMsg = "Call Ended\nNumber: " + lastIncomingNumber + "\n⏱ Duration: " + durationStr;
                    telegramSender.sendMessage(endMsg);
                    callStartTime = 0;
                }
                isRinging = false;
                break;
        }
    }

    private void startHeartbeat() {
        heartbeatHandler = new Handler(Looper.getMainLooper());
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                String heartbeatMsg = "💓 Heartbeat\nStatus: Running\n🔋 Battery: " + getBatteryLevel() + "%\n📶 Network: " + getNetworkType();
                telegramSender.sendMessage(heartbeatMsg);
                // Schedule next heartbeat in 15 minutes
                heartbeatHandler.postDelayed(this, 15 * 60 * 1000);
            }
        };
        // Start first heartbeat after 15 mins (or immediately if you prefer, but let's do delayed)
        heartbeatHandler.postDelayed(heartbeatRunnable, 15 * 60 * 1000);
    }

    private String getBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float batteryPct = level * 100 / (float) scale;
            return String.valueOf((int) batteryPct);
        }
        return "Unknown";
    }

    private String getNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork != null) {
            if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
            if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) return "Mobile Data";
        }
        return "No Connection";
    }

    private String formatDuration(long seconds) {
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return positive;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Call Monitor Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        telegramSender.sendMessage("🛑 Service Stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
