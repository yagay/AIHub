package com.yagay.aihub.web;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

/** Shared download path for every provider WebView. */
public final class DownloadHandler {
    public interface Reporter {
        void onMessage(String message);
    }

    private final Context context;
    private final Reporter reporter;

    public DownloadHandler(Context context, Reporter reporter) {
        this.context = context;
        this.reporter = reporter;
    }

    public void enqueue(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");

            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Downloaded by AIHub");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            if (mimeType != null && !mimeType.isBlank()) request.setMimeType(mimeType);
            if (userAgent != null && !userAgent.isBlank()) request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isBlank()) request.addRequestHeader("Cookie", cookies);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName);
            }
            manager.enqueue(request);
            reporter.onMessage("Download started: " + fileName);
        } catch (Exception error) {
            reporter.onMessage("Download failed");
        }
    }
}
