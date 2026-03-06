package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.StatFs;
import java.io.*;
import java.util.*;
import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {
    public static final int PORT = 8080;
    private Context context;

    public HttpServer(Context context) {
        super(PORT);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        File root = Environment.getExternalStorageDirectory();

        try {
            // PHASE 10: RAW BINARY STREAM (Bypasses NanoHTTPD parser to prevent OS crash)
            if (Method.POST.equals(session.getMethod()) && uri.equals("/api/upload_lut")) {
                Map<String, String> headers = session.getHeaders();
                String fileName = headers.get("x-file-name");
                String lengthStr = headers.get("content-length");

                if (fileName != null && lengthStr != null) {
                    long size = Long.parseLong(lengthStr);
                    File lutDir = new File(root, "LUTS");
                    if (!lutDir.exists()) lutDir.mkdirs();

                    File destFile = new File(lutDir, fileName);
                    InputStream in = session.getInputStream();
                    FileOutputStream out = new FileOutputStream(destFile);

                    byte[] buffer = new byte[16384]; // 16KB high-speed buffer
                    long totalRead = 0;
                    int read;

                    // Pull raw bytes directly from the network socket to the SD card
                    while (totalRead < size) {
                        int toRead = (int) Math.min(buffer.length, size - totalRead);
                        read = in.read(buffer, 0, toRead);
                        if (read == -1) break;
                        out.write(buffer, 0, read);
                        totalRead += read;
                    }

                    out.flush();
                    out.getFD().sync(); // Force physical write to disk
                    out.close();

                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"success\"}");
                } else {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing headers\"}");
                }
            }

            if (uri.equals("/")) {
                InputStream is = context.getAssets().open("index.html");
                return newChunkedResponse(Response.Status.OK, "text/html", is);
            }

            if (uri.equals("/api/system")) {
                StatFs stat = new StatFs(root.getPath());
                long bytesAvailable = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
                double gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
                boolean hasGraded = new File(root, "GRADED").exists();
                String json = String.format("{\"storage_gb\": \"%.1f\", \"has_graded\": %b}", gbAvailable, hasGraded);
                return newFixedLengthResponse(Response.Status.OK, "application/json", json);
            }

            if (uri.startsWith("/api/files")) {
                String folderParam = session.getParms().get("folder");
                File targetDir = (folderParam != null && folderParam.equals("GRADED")) ? new File(root, "GRADED") : new File(root, "DCIM/100MSDCF");
                List<File> allFiles = getMediaFiles(targetDir);
                StringBuilder json = new StringBuilder("{\"files\": [");
                for (int i = 0; i < allFiles.size(); i++) {
                    File f = allFiles.get(i);
                    json.append("{\"name\":\"").append(f.getName()).append("\", \"date\":").append(f.lastModified()).append(", \"size\":").append(f.length()).append("}");
                    if (i < allFiles.size() - 1) json.append(",");
                }
                json.append("]}");
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
            }

            if (uri.startsWith("/thumb/")) {
                Map<String, String> p = session.getParms();
                File file = new File(root, (p.get("folder").equals("GRADED") ? "GRADED" : "DCIM/100MSDCF") + "/" + p.get("name"));
                if (file.exists()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 8;
                    Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                    byte[] data = baos.toByteArray();
                    bm.recycle();
                    return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(data), data.length);
                }
            }

            if (uri.startsWith("/full/")) {
                Map<String, String> p = session.getParms();
                File file = new File(root, (p.get("folder").equals("GRADED") ? "GRADED" : "DCIM/100MSDCF") + "/" + p.get("name"));
                if (file.exists()) return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(file), file.length());
            }

        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error: " + e.getMessage());
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
    }

    private List<File> getMediaFiles(File dir) {
        List<File> result = new ArrayList<File>();
        if (!dir.exists()) return result;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isDirectory() && f.getName().toLowerCase().endsWith(".jpg")) result.add(f);
            }
        }
        Collections.sort(result, new Comparator<File>() {
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
        return result;
    }
}