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

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.common.Util;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes carrier and OEM bloatware after QR-code provisioning.
 *
 * <p>Mirrors a {@code pm uninstall --user 0} batch script: every entry in {@link #UNINSTALL_ONLY}
 * is uninstalled, and entries in {@link #UNINSTALL_THEN_DISABLE} are uninstalled with a fallback
 * to {@link DevicePolicyManager#setApplicationHidden} if uninstall fails.
 */
public class BloatwareRemovalTask {
  private static final String TAG = "BloatwareRemovalTask";

  private static final String ACTION_UNINSTALL_RESULT =
      "com.afwsamples.testdpc.BLOATWARE_UNINSTALL_RESULT";
  private static final String EXTRA_FALLBACK_TO_HIDE =
      "com.afwsamples.testdpc.extra.FALLBACK_TO_HIDE";

  private static final List<String> UNINSTALL_THEN_DISABLE =
      Arrays.asList(
          "com.hmdglobal.app.setupwizardext",
          "com.verizon.poweronactivation",
          "com.hmdglobal.app.omadm",
          "com.hmdglobal.app.omadm.install");

  private static final List<String> UNINSTALL_ONLY =
      Arrays.asList(
          "com.vzw.hss.myverizon",
          "com.verizon.messaging.vzmsgs",
          "com.vcast.mediamanager",
          "com.verizon.services",
          "com.verizon.mips.services",
          "com.verizon.obdm",
          "com.verizon.obdm_permissions",
          "com.verizon.llkagent",
          "com.verizon.remoteSimlock",
          "com.securityandprivacy.android.verizon.vms",
          "com.customermobile.preload.vzw",
          "com.vzw.apnlib",
          "com.vzw.ecid",
          "com.techm.vzw.provider",
          "com.techm.vvm3",
          "com.LogiaGroup.LogiaDeck",
          "com.ontim.cit",
          "com.google.android.youtube",
          "com.google.android.apps.googleassistant",
          "com.android.nfc",
          "com.google.android.gm",
          "com.google.android.apps.tachyon",
          "com.google.android.apps.wellbeing",
          "com.google.android.apps.maps",
          "com.google.android.videos",
          "com.google.android.projection.gearhead",
          "com.google.android.apps.youtube.music");

  private final Context mContext;
  private final DevicePolicyManager mDpm;
  private final PackageManager mPm;
  private final ComponentName mAdmin;

  public BloatwareRemovalTask(Context context) {
    mContext = context.getApplicationContext();
    mDpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    mPm = mContext.getPackageManager();
    mAdmin = DeviceAdminReceiver.getComponentName(mContext);
  }

  /** Performs the uninstall/disable operations. Safe to invoke once per device. */
  public void run() {
    if (!mDpm.isDeviceOwnerApp(mContext.getPackageName())
        && !mDpm.isProfileOwnerApp(mContext.getPackageName())) {
      Log.w(TAG, "TestDPC is neither device nor profile owner; skipping bloatware removal");
      return;
    }

    registerUninstallResultReceiver();

    for (String pkg : UNINSTALL_THEN_DISABLE) {
      if (!isInstalled(pkg)) {
        Log.i(TAG, "Skipping (not installed): " + pkg);
        continue;
      }
      Log.i(TAG, "Uninstalling (with hide fallback): " + pkg);
      requestUninstall(pkg, /* fallbackToHide= */ true);
    }

    for (String pkg : UNINSTALL_ONLY) {
      if (!isInstalled(pkg)) {
        Log.i(TAG, "Skipping (not installed): " + pkg);
        continue;
      }
      Log.i(TAG, "Uninstalling: " + pkg);
      requestUninstall(pkg, /* fallbackToHide= */ false);
    }
  }

  private boolean isInstalled(String pkg) {
    try {
      mPm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  private void requestUninstall(String pkg, boolean fallbackToHide) {
    try {
      Intent intent = new Intent(ACTION_UNINSTALL_RESULT).setPackage(mContext.getPackageName());
      intent.putExtra(Intent.EXTRA_PACKAGE_NAME, pkg);
      intent.putExtra(EXTRA_FALLBACK_TO_HIDE, fallbackToHide);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Util.SDK_INT >= VERSION_CODES.M) {
        flags |= PendingIntent.FLAG_IMMUTABLE;
      }
      PendingIntent pi = PendingIntent.getBroadcast(mContext, pkg.hashCode(), intent, flags);
      IntentSender sender = pi.getIntentSender();
      PackageInstaller installer = mPm.getPackageInstaller();
      installer.uninstall(pkg, sender);
    } catch (Exception e) {
      Log.e(TAG, "uninstall(" + pkg + ") failed; attempting hide fallback", e);
      if (fallbackToHide) {
        hide(pkg);
      }
    }
  }

  private void hide(String pkg) {
    try {
      mDpm.setApplicationHidden(mAdmin, pkg, true);
    } catch (Exception e) {
      Log.e(TAG, "setApplicationHidden(" + pkg + ") failed", e);
    }
  }

  private void registerUninstallResultReceiver() {
    IntentFilter filter = new IntentFilter(ACTION_UNINSTALL_RESULT);
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            int status =
                intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            boolean fallback = intent.getBooleanExtra(EXTRA_FALLBACK_TO_HIDE, false);
            if (status == PackageInstaller.STATUS_SUCCESS) {
              Log.i(TAG, "Uninstalled " + pkg);
            } else {
              Log.w(TAG, "Uninstall failed for " + pkg + " status=" + status + " msg=" + message);
              if (fallback && pkg != null) {
                hide(pkg);
              }
            }
          }
        };
    if (Util.SDK_INT >= VERSION_CODES.TIRAMISU) {
      mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      mContext.registerReceiver(receiver, filter);
    }
  }

  /** Visible for testing. */
  static Set<String> allPackages() {
    Set<String> all = new HashSet<>();
    all.addAll(UNINSTALL_THEN_DISABLE);
    all.addAll(UNINSTALL_ONLY);
    return all;
  }
}
