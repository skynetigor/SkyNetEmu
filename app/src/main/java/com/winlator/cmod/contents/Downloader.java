package com.winlator.cmod.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import com.winlator.cmod.core.Callback;
import java.net.URL;
import java.net.URLConnection;

public class Downloader {

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, Callback<Integer> progressCallback) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            long contentLength = connection.getContentLengthLong();
            long downloadedSize = 0;
            int lastProgress = -1;

            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(file.getAbsolutePath())) {
                byte[] data = new byte[8192];
                int count;
                while ((count = input.read(data)) != -1) {
                    output.write(data, 0, count);
                    if (progressCallback != null && contentLength > 0) {
                        downloadedSize += count;
                        int progress = Math.min(100, (int)(downloadedSize * 100 / contentLength));
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            progressCallback.call(progress);
                        }
                    }
                }
                output.flush();
            }
            if (progressCallback != null) progressCallback.call(100);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            com.winlator.cmod.telemetry.ErrorReporter.reportDownload(address, e);
            return false;
        }
    }

    /** Small text files (the registry): short timeouts, so a dead connection never holds a caller for long. */
    public static String downloadString(String address) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            com.winlator.cmod.telemetry.ErrorReporter.reportDownload(address, e);
            return null;
        }
    }
}
