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
import android.util.Log;
import com.afwsamples.testdpc.common.PackageInstallationUtils;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and silently installs the Blaster League APK from GitHub after QR provisioning.
 *
 * <p>Runs on a background thread because the device may not yet have a network connection at the
 * exact moment provisioning completes.
 */
public class BlasterLeagueInstallTask {
  private static final String TAG = "BlasterLeagueInstall";

  private static final String APK_URL =
      "https://raw.githubusercontent.com/LIFEGOESON321/android-testdpc/refs/heads/master/blasterleague.apk";
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
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        Log.i(TAG, "Downloading blasterleague.apk (attempt " + attempt + ")");
        try (InputStream in = openWithRedirects(APK_URL, MAX_REDIRECTS)) {
          PackageInstallationUtils.installPackage(mContext, new BufferedInputStream(in), null);
        }
        Log.i(TAG, "Install session committed");
        return;
      } catch (IOException e) {
        Log.w(TAG, "Attempt " + attempt + " failed: " + e.getMessage());
      } catch (Exception e) {
        Log.e(TAG, "Unexpected failure installing blasterleague.apk", e);
        return;
      }
      try {
        Thread.sleep(RETRY_BACKOFF_MS * attempt);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    Log.e(TAG, "Giving up on blasterleague.apk after " + MAX_RETRIES + " attempts");
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
