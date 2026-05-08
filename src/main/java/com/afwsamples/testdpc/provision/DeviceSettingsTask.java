/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.afwsamples.testdpc.provision;

import android.annotation.TargetApi;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build.VERSION_CODES;
import android.provider.Settings;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.common.Util;

/**
 * Applies device-wide setting tweaks after QR-code provisioning.
 *
 * <ul>
 *   <li>Adaptive brightness off ({@link Settings.System#SCREEN_BRIGHTNESS_MODE} = manual)
 *   <li>Brightness 100% ({@link Settings.System#SCREEN_BRIGHTNESS} = 255)
 *   <li>Screen timeout 30 minutes ({@link Settings.System#SCREEN_OFF_TIMEOUT})
 *   <li>Font scale "Large" ({@link Settings.System#FONT_SCALE} = 1.15)
 *   <li>Best-effort attempt to disable public-safety cell-broadcast alerts
 * </ul>
 *
 * Most of these go through {@link DevicePolicyManager#setSystemSetting} which requires API 28+ and
 * is allowlisted to a small set of keys. The public-safety toggle lives in CellBroadcastReceiver's
 * private SharedPreferences and has no DPC API; we try the secure-settings keys that some OEMs
 * surface but will silently fail on devices that store it elsewhere.
 */
public class DeviceSettingsTask {
  private static final String TAG = "DeviceSettingsTask";

  private static final int BRIGHTNESS_MAX = 255;
  private static final int SCREEN_OFF_TIMEOUT_MS = 30 * 60 * 1000;
  private static final float FONT_SCALE_LARGE = 1.15f;

  private final Context mContext;
  private final DevicePolicyManager mDpm;
  private final ComponentName mAdmin;

  public DeviceSettingsTask(Context context) {
    mContext = context.getApplicationContext();
    mDpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    mAdmin = DeviceAdminReceiver.getComponentName(mContext);
  }

  public void run() {
    if (!mDpm.isDeviceOwnerApp(mContext.getPackageName())) {
      Log.w(TAG, "Not device owner; skipping setting tweaks");
      return;
    }
    if (Util.SDK_INT < VERSION_CODES.P) {
      Log.w(TAG, "setSystemSetting requires API 28; skipping");
      return;
    }

    setSystem(Settings.System.SCREEN_BRIGHTNESS_MODE,
        String.valueOf(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL));
    setSystem(Settings.System.SCREEN_BRIGHTNESS, String.valueOf(BRIGHTNESS_MAX));
    setSystem(Settings.System.SCREEN_OFF_TIMEOUT, String.valueOf(SCREEN_OFF_TIMEOUT_MS));
    setFontScale(FONT_SCALE_LARGE);
    disablePublicSafetyAlerts();
  }

  @TargetApi(VERSION_CODES.P)
  private void setSystem(String key, String value) {
    try {
      mDpm.setSystemSetting(mAdmin, key, value);
      Log.i(TAG, "setSystemSetting(" + key + "=" + value + ")");
    } catch (Exception e) {
      Log.e(TAG, "setSystemSetting(" + key + ") failed: " + e.getMessage());
    }
  }

  @TargetApi(VERSION_CODES.P)
  private void setFontScale(float scale) {
    // FONT_SCALE is NOT on the DPC system-setting allowlist on stock Android, so this throws
    // SecurityException on most devices. Some OEM ROMs whitelist it; we try and log either way.
    try {
      mDpm.setSystemSetting(mAdmin, Settings.System.FONT_SCALE, String.valueOf(scale));
      Log.i(TAG, "setSystemSetting(font_scale=" + scale + ")");
    } catch (Exception e) {
      Log.w(
          TAG,
          "Font scale could not be set programmatically (no public DPC API);"
              + " set Settings > Display > Font size manually if needed. ("
              + e.getMessage()
              + ")");
    }
  }

  /**
   * Best-effort: try the secure-settings keys that AOSP and major OEM CellBroadcastReceivers have
   * been observed to read. None of these are guaranteed; on many devices the toggle is stored
   * inside CellBroadcastReceiver's private SharedPreferences and there is no public API for a DPC
   * to flip it. Any failure is logged and ignored.
   */
  @TargetApi(VERSION_CODES.P)
  private void disablePublicSafetyAlerts() {
    String[] candidateKeys = {
      "enable_public_safety_messages",
      "enable_public_safety_alerts",
      "show_cmas_public_safety_messages",
      "cell_broadcast_public_safety_alerts_enabled",
    };
    for (String key : candidateKeys) {
      try {
        mDpm.setSecureSetting(mAdmin, key, "0");
        Log.i(TAG, "setSecureSetting(" + key + "=0) accepted");
      } catch (Exception e) {
        Log.d(TAG, "setSecureSetting(" + key + ") rejected: " + e.getMessage());
      }
    }
    Log.w(
        TAG,
        "Public-safety alert toggle has no public DPC API; if alerts still appear,"
            + " disable manually in Settings > Notifications > Wireless emergency alerts.");
  }
}
