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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import com.afwsamples.testdpc.common.PackageInstallationUtils;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and silently installs the Blaster League APK from GitHub.
 *
 * <p>Invoked from {@code PostProvisioningTask} after QR provisioning and from {@code BootReceiver}
 * on every boot. The task downloads the APK to cache, reads its {@code versionCode}, and skips the
 * install if the installed version is already current.
 */
public class BlasterLeagueInstallTask {
  private static final String TAG = "BlasterLeagueInstall";

  private static final String APK_URL =
      "https://raw.githubusercontent.com/LIFEGOESON321/android-testdpc/refs/heads/master/blasterleague.apk";
  private static final String CACHE_FILENAME = "blasterleague-pending.apk";
  private static final int CONNECT_TIMEOUT_MS = 30_000;
  private static final int READ_TIMEOUT_MS = 60_000;
  private static final int MAX_REDIRECTS = 5;
  private static final int MAX_RETRIES = 6;
  private static final long RETRY_BACKOFF_MS = 15_000L;

  private final Context mContext;

  public BlasterLeagueInstallTask(Context context) {
    mContext = context.getApplicationContext();
  }

  /** Kicks off the download/install on a background thread. */
  public void run() {
    Thread t = new Thread(this::doRun, "BlasterLeagueInstall");
    t.setDaemon(true);
    t.start();
  }

  private void doRun() {
    File apkFile = new File(mContext.getCacheDir(), CACHE_FILENAME);
    try {
      if (!downloadWithRetries(apkFile)) {
        return;
      }

      String pkgName = readApkPackageName(apkFile);
      if (pkgName == null) {
        Log.e(TAG, "Could not parse APK; aborting");
        return;
      }
      long downloadedVersion = readApkVersionCode(apkFile);
      long installedVersion = installedVersionCode(pkgName);
      Log.i(
          TAG,
          "blasterleague pkg=" + pkgName + " downloaded=" + downloadedVersion
              + " installed=" + installedVersion);

      if (installedVersion >= downloadedVersion && installedVersion >= 0) {
        Log.i(TAG, "Already up to date; skipping install");
        return;
      }

      try (InputStream in = new BufferedInputStream(new FileInputStream(apkFile))) {
        PackageInstallationUtils.installPackage(mContext, in, pkgName);
      }
      Log.i(TAG, "Install session committed for " + pkgName + " v" + downloadedVersion);
    } catch (Exception e) {
      Log.e(TAG, "Failed to install blasterleague.apk", e);
    } finally {
      if (apkFile.exists() && !apkFile.delete()) {
        Log.w(TAG, "Could not delete cached APK at " + apkFile);
      }
    }
  }

  private boolean downloadWithRetries(File dest) {
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        Log.i(TAG, "Downloading blasterleague.apk (attempt " + attempt + ")");
        downloadTo(dest);
        return true;
      } catch (IOException e) {
        Log.w(TAG, "Attempt " + attempt + " failed: " + e.getMessage());
      }
      try {
        Thread.sleep(RETRY_BACKOFF_MS * attempt);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    Log.e(TAG, "Giving up on blasterleague.apk after " + MAX_RETRIES + " attempts");
    return false;
  }

  private void downloadTo(File dest) throws IOException {
    try (InputStream in = openWithRedirects(APK_URL, MAX_REDIRECTS);
        OutputStream out = new FileOutputStream(dest)) {
      byte[] buffer = new byte[65536];
      int c;
      while ((c = in.read(buffer)) != -1) {
        out.write(buffer, 0, c);
      }
    }
  }

  private String readApkPackageName(File apk) {
    PackageInfo info =
        mContext.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
    return info == null ? null : info.packageName;
  }

  private long readApkVersionCode(File apk) {
    PackageInfo info =
        mContext.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
    if (info == null) {
      return -1;
    }
    return versionCodeOf(info);
  }

  private long installedVersionCode(String pkgName) {
    try {
      PackageInfo info = mContext.getPackageManager().getPackageInfo(pkgName, 0);
      return versionCodeOf(info);
    } catch (PackageManager.NameNotFoundException e) {
      return -1;
    }
  }

  @SuppressWarnings("deprecation")
  private static long versionCodeOf(PackageInfo info) {
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      return info.getLongVersionCode();
    }
    return info.versionCode;
  }

  private static InputStream openWithRedirects(String urlString, int remaining) throws IOException {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("User-Agent", "TestDPC");
    int code = conn.getResponseCode();
    if (code >= 300 && code < 400 && remaining > 0) {
      String next = conn.getHeaderField("Location");
      conn.disconnect();
      if (next == null) {
        throw new IOException("Redirect without Location header");
      }
      return openWithRedirects(next, remaining - 1);
    }
    if (code != HttpURLConnection.HTTP_OK) {
      conn.disconnect();
      throw new IOException("HTTP " + code + " for " + urlString);
    }
    return conn.getInputStream();
  }
}
