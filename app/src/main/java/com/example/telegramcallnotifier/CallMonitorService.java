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
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

public class CallMonitorService extends Service {

    private static final String CHANNEL_ID = "CallMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private Object telephonyCallback; // For API 31+
    private TelegramSender telegramSender;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;
    private long callStartTime = 0;
    private boolean isRinging = false;
    private String lastIncomingNumber = "";
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver callReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        telegramSender = new TelegramSender(this);
        
        // Log Service Start
        CustomExceptionHandler.log(this, "Service onCreate. SDK: " + Build.VERSION.SDK_INT);

        // Acquire WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            try {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "CallMonitorService::WakeLock");
                wakeLock.acquire();
            } catch (Exception e) {
                Log.e("CallMonitorService", "Error acquiring WakeLock", e);
            }
        }

        // Start Foreground Service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Monitor Active")
                .setContentText("Listening for incoming calls...")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            Log.e("CallMonitorService", "Error starting foreground service", e);
            // Fallback: try standard start if specific type fails
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable t) {
                Log.e("CallMonitorService", "Fatal error starting foreground", t);
            }
        }

        // Register Phone Listener
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        registerPhoneListener();
        
        // Register BroadcastReceiver for Call State (Reliable number retrieval)
        registerCallReceiver();

        // Start Heartbeat
        startHeartbeat();
        
        // Notify start
        telegramSender.sendMessage("✅ Call Monitor Service Started\n🔋 Battery: " + getBatteryLevel() + "%\n📶 Network: " + getNetworkType());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void registerCallReceiver() {
        callReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
                    String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                    String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                    
                    int state = TelephonyManager.CALL_STATE_IDLE;
                    if (TelephonyManager.EXTRA_STATE_RINGING.equals(stateStr)) {
                        state = TelephonyManager.CALL_STATE_RINGING;
                    } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(stateStr)) {
                        state = TelephonyManager.CALL_STATE_OFFHOOK;
                    }
                    
                    handleCallState(state, number);
                }
            }
        };
        IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        registerReceiver(callReceiver, filter);
    }

    private void registerPhoneListener() {
        if (Build.VERSION.SDK_INT >= 31) {
            registerTelephonyCallback();
        } else {
            registerLegacyPhoneListener();
        }
    }

    private void registerLegacyPhoneListener() {
        try {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    handleCallState(state, phoneNumber);
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException e) {
            Log.e("CallMonitorService", "Permission missing for phone listener", e);
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error registering legacy listener", e);
        }
    }

    private void registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                telephonyCallback = new CallStateCallback();
                telephonyManager.registerTelephonyCallback(getMainExecutor(), (TelephonyCallback) telephonyCallback);
            } catch (SecurityException e) {
                 Log.e("CallMonitorService", "Permission missing for telephony callback", e);
            } catch (Exception e) {
                 Log.e("CallMonitorService", "Error registering telephony callback", e);
            }
        }
    }

    private class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            // Note: onCallStateChanged in API 31+ does not provide phone number directly here safely in all cases without permission check
            // However, we can try to get it if we have permission, or use a workaround.
            // Actually, the callback doesn't provide the number. We have to query it.
            // Wait, this is a limitation. Legacy listener provided it.
            // Let's use a workaround: check call state, if ringing, try to get call log or just say "Incoming Call"
            // But wait, the user wants the number.
            // Let's stick to legacy listener for now if it works, or use BroadcastReceiver for PHONE_STATE which contains incoming number.
            // But we can still use legacy listener on newer Android versions, it's just deprecated.
            // Let's use the legacy listener approach inside the callback if possible, or just call handleCallState with null number and fetch it differently.
            
            // Actually, let's keep it simple: use the legacy listener for now as it still works on Android 14 target 34,
            // just with a warning. But if it crashes, we need this.
            // But wait, CallStateListener in TelephonyCallback DOES NOT provide phone number.
            // We need to use BroadcastReceiver for that.
            handleCallState(state, null);
        }
    }
    
    private void handleCallState(int state, String incomingNumber) {
        if (incomingNumber == null) incomingNumber = "Unknown";
        if (lastIncomingNumber.equals(incomingNumber) && state == TelephonyManager.CALL_STATE_RINGING) {
            return; // Duplicate
        }
        
        CustomExceptionHandler.log(this, "Call State: " + state + ", Number: " + (incomingNumber != null ? incomingNumber : "NULL"));

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                isRinging = true;
                callStartTime = System.currentTimeMillis();
                lastIncomingNumber = incomingNumber;
                
                String msg = "📞 Incoming Call Detected!\n" +
                        "🔢 Number: " + (incomingNumber != null ? incomingNumber : "Unknown") + "\n" +
                        "⏰ Time: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                
                telegramSender.sendMessage(msg);
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (isRinging) {
                    telegramSender.sendMessage("✅ Call Answered");
                } else {
                    // Outgoing call
                    telegramSender.sendMessage("Outgoing Call Started");
                }
                isRinging = false;
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                if (isRinging) {
                     telegramSender.sendMessage("❌ Call Missed/Rejected");
                } else {
                     telegramSender.sendMessage("Call Ended");
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
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Unknown";
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
                if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) return "Mobile Data";
                return "Connected";
            }
            return "No Internet";
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error checking network", e);
            return "Unknown (Error)";
        }
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
        if (callReceiver != null) {
            unregisterReceiver(callReceiver);
        }
        if (telephonyManager != null) {
            if (Build.VERSION.SDK_INT >= 31 && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) telephonyCallback);
            } else if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        }
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        telegramSender.sendMessage("🛑 Service Stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
