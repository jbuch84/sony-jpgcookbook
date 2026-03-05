package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.hardware.Camera;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class ScalarWebAPIWrapper {
    private Object scalarInstance;
    private Method getIntMethod;
    private Method getFocusAreasMethod;

    public ScalarWebAPIWrapper(Context context) {
        try {
            Class<?> scalarClass = context.getClassLoader().loadClass("com.sony.scalar.sysutil.ScalarWebAPI");
            Constructor<?> ctor = scalarClass.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            scalarInstance = ctor.newInstance(context.getApplicationContext());

            getIntMethod = scalarClass.getMethod("getInt", String.class);
            getFocusAreasMethod = scalarClass.getMethod("getFocusAreas");
            Log.i("COOKBOOK_AF", "IPC Wrapper: Connected to Sony Daemon.");
        } catch (Exception e) {
            Log.e("COOKBOOK_AF", "IPC Wrapper: Daemon not reached. " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return scalarInstance != null;
    }

    public int getInt(String key) {
        if (scalarInstance == null || getIntMethod == null) return 0;
        try {
            return (Integer) getIntMethod.invoke(scalarInstance, key);
        } catch (Exception e) { return 0; }
    }

    public Camera.Area[] getFocusAreas() {
        if (scalarInstance == null || getFocusAreasMethod == null) return null;
        try {
            Object[] objArray = (Object[]) getFocusAreasMethod.invoke(scalarInstance);
            if (objArray == null || objArray.length == 0) return null;

            Camera.Area[] areas = new Camera.Area[objArray.length];
            for (int i = 0; i < objArray.length; i++) {
                Object areaObj = objArray[i];
                Object rectObj = areaObj.getClass().getField("rect").get(areaObj);
                
                int l = rectObj.getClass().getField("left").getInt(rectObj);
                int t = rectObj.getClass().getField("top").getInt(rectObj);
                int r = rectObj.getClass().getField("right").getInt(rectObj);
                int b = rectObj.getClass().getField("bottom").getInt(rectObj);
                
                areas[i] = new Camera.Area(new android.graphics.Rect(l, t, r, b), 1000);
            }
            return areas;
        } catch (Exception e) { return null; }
    }
}