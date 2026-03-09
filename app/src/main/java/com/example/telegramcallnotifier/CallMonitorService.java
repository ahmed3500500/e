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
    // Removed heartbeat fields
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
        
        // Log Service Start (Local log only)
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
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable t) {
                Log.e("CallMonitorService", "Fatal error starting foreground", t);
            }
        }

        // Register Phone Listener
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        registerPhoneListener();
        
        // Register BroadcastReceiver
        registerCallReceiver();

        // Removed Heartbeat and Start Notification per user request
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
                    
                    // BroadcastReceiver doesn't give SIM info easily, passing -1
                    handleCallState(state, number, -1);
                }
            }
        };
        IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        registerReceiver(callReceiver, filter);
    }

    private void registerPhoneListener() {
        // Multi-SIM Support
        android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            java.util.List<android.telephony.SubscriptionInfo> subList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subList != null && !subList.isEmpty()) {
                for (android.telephony.SubscriptionInfo subInfo : subList) {
                    int subId = subInfo.getSubscriptionId();
                    int slotIndex = subInfo.getSimSlotIndex(); // 0 or 1
                    registerListenerForSub(subId, slotIndex + 1);
                }
            } else {
                // Fallback for single SIM or if list is empty
                if (Build.VERSION.SDK_INT >= 31) {
                    registerTelephonyCallback();
                } else {
                    registerLegacyPhoneListener();
                }
            }
        }
    }

    private void registerListenerForSub(int subId, int simSlot) {
        TelephonyManager subTm = telephonyManager.createForSubscriptionId(subId);
        
        try {
            PhoneStateListener listener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    handleCallState(state, phoneNumber, simSlot);
                }
            };
            subTm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error registering listener for SIM " + simSlot, e);
        }
    }

    private void registerLegacyPhoneListener() {
        try {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    handleCallState(state, phoneNumber, -1);
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
            handleCallState(state, null, -1);
        }
    }
    
    private void handleCallState(int state, String incomingNumber, int simSlot) {
        if (incomingNumber == null) incomingNumber = "Unknown";
        if (lastIncomingNumber.equals(incomingNumber) && state == TelephonyManager.CALL_STATE_RINGING) {
            return; // Duplicate
        }
        
        CustomExceptionHandler.log(this, "Call State: " + state + ", Number: " + incomingNumber + ", SIM: " + simSlot);

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                isRinging = true;
                callStartTime = System.currentTimeMillis();
                lastIncomingNumber = incomingNumber;
                
                String simInfo = (simSlot != -1) ? "SIM " + simSlot : "Unknown SIM";
                
                String msg = "📞 Incoming Call Detected!\n" +
                        "🔢 Number: " + incomingNumber + "\n" +
                        "📱 Line: " + simInfo + "\n" +
                        "⏰ Time: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                
                telegramSender.sendMessage(msg);
                
                // Auto Answer Logic
                attemptAutoAnswer();
                break;
                
            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (isRinging) {
                    // Call Answered
                    // Start 5 second timer to hang up
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            attemptHangUp();
                        }
                    }, 5000);
                }
                isRinging = false;
                break;
                
            case TelephonyManager.CALL_STATE_IDLE:
                isRinging = false;
                break;
        }
    }
    
    private void attemptAutoAnswer() {
        if (Build.VERSION.SDK_INT >= 26) {
             android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
             if (tm != null) {
                 if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     try {
                         tm.acceptRingingCall();
                         CustomExceptionHandler.log(this, "Auto-answered call via TelecomManager");
                     } catch (Exception e) {
                         Log.e("CallMonitorService", "Failed to answer call", e);
                         CustomExceptionHandler.logError(this, e);
                     }
                 }
             }
        }
    }

    private void attemptHangUp() {
        if (Build.VERSION.SDK_INT >= 28) {
             android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
             if (tm != null) {
                 if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     try {
                         tm.endCall();
                         CustomExceptionHandler.log(this, "Auto-ended call via TelecomManager");
                     } catch (Exception e) {
                         Log.e("CallMonitorService", "Failed to end call", e);
                         CustomExceptionHandler.logError(this, e);
                     }
                 }
             }
        }
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        // Removed stop notification
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}