package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.media.ExifInterface;
import android.os.Environment;
import android.os.StatFs;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class HttpServer extends NanoHTTPD {
    public static final int PORT = 8080;
    private Context context;
    private File dcimDir;

    public HttpServer(Context context) {
        super(PORT);
        this.context = context;
        this.dcimDir = new File(Environment.getExternalStorageDirectory(), "DCIM");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        try {
            // Serve the Alpha OS Dashboard
            if (uri.equals("/")) {
                InputStream is = context.getAssets().open("index.html");
                return newChunkedResponse(Status.OK, "text/html", is);
            }

            // Hardware Telemetry
            if (uri.equals("/api/system")) {
                StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
                long bytesAvailable = (long)stat.getBlockSize() * (long)stat.getAvailableBlocks();
                double gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
                String json = String.format("{\"storage_gb\": \"%.1f\"}", gbAvailable);
                return newFixedLengthResponse(Status.OK, "application/json", json);
            }

            // Paginated files + Date metadata
            if (uri.startsWith("/api/files")) {
                Map<String, String> params = session.getParms();
                int offset = params.containsKey("offset") ? Integer.parseInt(params.get("offset")) : 0;
                int limit = params.containsKey("limit") ? Integer.parseInt(params.get("limit")) : 50;

                List<File> allFiles = getMediaFiles(dcimDir);
                int total = allFiles.size();
                
                int end = Math.min(offset + limit, total);
                List<File> pageFiles = offset < total ? allFiles.subList(offset, end) : new ArrayList<File>();

                StringBuilder json = new StringBuilder();
                json.append("{\"total\": ").append(total).append(", \"files\": [");
                for (int i = 0; i < pageFiles.size(); i++) {
                    File f = pageFiles.get(i);
                    json.append("{\"name\":\"").append(f.getName())
                        .append("\", \"date\":").append(f.lastModified())
                        .append(", \"size\":").append(f.length()).append("}");
                    if (i < pageFiles.size() - 1) json.append(",");
                }
                json.append("]}");
                return newFixedLengthResponse(Status.OK, "application/json", json.toString());
            }

            // Instant EXIF Thumbnails
            if (uri.startsWith("/thumb/")) {
                String fileName = uri.substring(7);
                File file = findFile(dcimDir, fileName);
                if (file != null && file.exists()) {
                    try {
                        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                        byte[] imageData = exif.getThumbnail();
                        if (imageData != null) {
                            return newFixedLengthResponse(Status.OK, "image/jpeg", new ByteArrayInputStream(imageData), imageData.length);
                        }
                    } catch (Exception e) {}
                }
                return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No thumbnail found");
            }

            // Full Resolution Media
            if (uri.startsWith("/full/")) {
                String fileName = uri.substring(6);
                File file = findFile(dcimDir, fileName);
                if (file != null && file.exists()) {
                    FileInputStream fis = new FileInputStream(file);
                    String mime = fileName.toLowerCase().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
                    return newFixedLengthResponse(Status.OK, mime, fis, file.length());
                }
            }

        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Server Error: " + e.getMessage());
        }

        return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

    private List<File> getMediaFiles(File dir) {
        List<File> result = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    result.addAll(getMediaFiles(f));
                } else {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".mp4")) {
                        result.add(f);
                    }
                }
            }
        }
        Collections.sort(result, new Comparator<File>() {
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
        return result;
    }

    private File findFile(File dir, String fileName) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findFile(f, fileName);
                    if (found != null) return found;
                } else if (f.getName().equals(fileName)) {
                    return f;
                }
            }
        }
        return null;
    }
}