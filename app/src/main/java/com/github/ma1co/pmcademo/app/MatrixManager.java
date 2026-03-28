package com.github.ma1co.pmcademo.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MatrixManager {
    private File matrixDir;
    private List<String> matrixNames = new ArrayList<String>();
    private List<int[]> matrixValues = new ArrayList<int[]>();
    private List<String> matrixNotes = new ArrayList<String>();

    public MatrixManager() {
        matrixDir = new File(Filepaths.getAppDir(), "MATRIX");
        if (!matrixDir.exists()) matrixDir.mkdirs();
    }

    public void scanMatrices() {
        matrixNames.clear();
        matrixValues.clear();
        matrixNotes.clear();

        File[] files = matrixDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            // Changed to look exclusively for our proprietary .mtx files
            if (f.getName().toLowerCase().endsWith(".mtx")) {
                loadMatrixFile(f);
            }
        }
    }

    private void loadMatrixFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();

            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            JSONArray arr = json.getJSONArray("advMatrix");
            
            int[] values = new int[9];
            for (int i = 0; i < 9; i++) values[i] = arr.getInt(i);

            // Strip the new .mtx extension for clean UI display
            matrixNames.add(file.getName().replace(".mtx", "").replace("_", " ").toUpperCase());
            matrixValues.add(values);
            matrixNotes.add(json.optString("note", "User defined matrix."));
        } catch (Exception e) {
            android.util.Log.e("JPEG.CAM", "Failed to load matrix: " + file.getName());
        }
    }

    ppublic void saveMatrix(String name, int[] values, String note) {
        try {
            // Bypass Sony's flaky org.json library entirely for writing.
            // Manually construct the JSON string to guarantee success on API 10.
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"advMatrix\": [");
            for (int i = 0; i < values.length; i++) {
                sb.append(values[i]);
                if (i < values.length - 1) sb.append(", ");
            }
            // Safely escape quotes in the note string just in case
            sb.append("],\n  \"note\": \"").append(note.replace("\"", "\\\"")).append("\"\n}");

            // Save as our custom format
            File file = new File(matrixDir, name.replace(" ", "_") + ".mtx");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            android.util.Log.e("JPEG.CAM", "Save failed: " + e.getMessage());
        }
    }

    public List<String> getNames() { return matrixNames; }
    public int[] getValues(int index) { return matrixValues.get(index); }
    public String getNote(int index) { return matrixNotes.get(index); }
    public int getCount() { return matrixNames.size(); }
}