
/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Code modified by syaoran12 (Adam Fisch)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import com.android.internal.R;


/**
 * This widget display an analogic clock with two hands for hours and
 * minutes.
 */
public class CarrierLabelTop extends TextView {
    private boolean mAttached;
    
    private Handler mHandler;
    

    public CarrierLabelTop(Context context) {
        this(context, null);
    }

    public CarrierLabelTop(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarrierLabelTop(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        updateNetworkName(false, null, false, null);
        
        mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();

        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
            getContext().registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Telephony.Intents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN));
            }
        }
    };

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Slog.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }

        boolean mCustomCarrier = (Settings.System.getInt(mContext.getContentResolver(), Settings.System.TOP_CARRIER_LABEL, 0) == 2);
        if (mCustomCarrier) {
        	String customCarrier = null;
            customCarrier = Settings.System.getString(mContext.getContentResolver(), Settings.System.CUSTOM_CARRIER_TEXT);

            if (customCarrier == null) {
                StringBuilder str = new StringBuilder();
                boolean something = false;
                if (showPlmn && plmn != null) {
                    str.append(plmn);
                    something = true;
                }
                if (showSpn && spn != null) {
                    if (something) {
                        str.append('\n');
                    }
                    str.append(spn);
                    something = true;
                }
                if (something) {
                    setText(str.toString());
                } else {
                    setText(com.android.internal.R.string.lockscreen_carrier_default);
                }
            } else {
                setText(customCarrier);
            }
        } else {
        	StringBuilder str = new StringBuilder();
            boolean something = false;
            if (showPlmn && plmn != null) {
                str.append(plmn);
                something = true;
            }
            if (showSpn && spn != null) {
                if (something) {
                    str.append('\n');
                }
                str.append(spn);
                something = true;
            }
            if (something) {
                setText(str.toString());
            } else {
                setText(com.android.internal.R.string.lockscreen_carrier_default);
            }
        }
        
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.TOP_CARRIER_LABEL), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.TOP_CARRIER_LABEL_COLOR), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }
    
    private void updateSettings() {
    	ContentResolver resolver = mContext.getContentResolver();
    	
    	int mColorChanger = Settings.System.getInt(resolver,
                Settings.System.TOP_CARRIER_LABEL_COLOR, 0xFF33B5E5);

        setTextColor(mColorChanger);
        
        boolean mDontShow = (Settings.System.getInt(resolver, Settings.System.TOP_CARRIER_LABEL, 0) == 0);
        if (mDontShow) {
            setVisibility(View.GONE);
        } else {
            setVisibility(View.VISIBLE);
        }
    }
}


