package com.example.telegramcallnotifier;

import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

public class MyInCallService extends InCallService {
    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.d("MyInCallService", "Call added: " + call);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.d("MyInCallService", "Call removed: " + call);
    }
}
