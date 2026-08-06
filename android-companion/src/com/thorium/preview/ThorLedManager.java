package com.thorium.preview;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Static-color controller for the AYN Thor/Odin joystick rings.
 *
 * The stock firmware exposes its privileged LED writer through PServerBinder.
 * The command format and four segment nodes are also used by the GPLv3 Bifrost
 * project (https://github.com/Pollux-MoonBench/Bifrost). Lucent only submits a
 * color when the selected platform changes; it does not capture the screen or
 * run a continuous animation loop.
 */
final class ThorLedManager {
    private static final String TAG = "LucentLeds";
    private final IBinder vendorBinder;
    private String lastColor = "";
    private int lastBrightness = -1;

    ThorLedManager() {
        IBinder found = null;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            found = (IBinder) getService.invoke(null, "PServerBinder");
        } catch (Exception error) {
            Log.i(TAG, "AYN LED service is not available on this device");
        }
        vendorBinder = found;
    }

    boolean available() {
        return vendorBinder != null;
    }

    synchronized boolean setColor(String requested, int requestedBrightness) {
        if (vendorBinder == null) return false;
        String color = requested == null ? "" : requested.trim();
        if (color.startsWith("#")) color = color.substring(1);
        if (!color.matches("(?i)[0-9a-f]{6}")) return false;
        color = color.toLowerCase(Locale.US);
        int brightness = Math.max(0, Math.min(255, requestedBrightness));
        if (color.equals(lastColor) && brightness == lastBrightness) return true;

        int red = Integer.parseInt(color.substring(0, 2), 16);
        int green = Integer.parseInt(color.substring(2, 4), 16);
        int blue = Integer.parseInt(color.substring(4, 6), 16);
        String value = red + ":" + green + ":" + blue + ":" + brightness;
        String command = "echo 1-" + value + " > /sys/class/sn3112l/led/brightness" +
                " && echo 2-" + value + " > /sys/class/sn3112l/led/brightness" +
                " && echo 1-" + value + " > /sys/class/sn3112r/led/brightness" +
                " && echo 2-" + value + " > /sys/class/sn3112r/led/brightness";

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeStringArray(new String[]{command, "1"});
            vendorBinder.transact(0, data, reply, IBinder.FLAG_ONEWAY);
            lastColor = color;
            lastBrightness = brightness;
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Unable to update AYN joystick LEDs", error);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
