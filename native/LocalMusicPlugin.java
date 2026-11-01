// Put in: android/app/src/main/java/<your/package/path>/LocalMusicPlugin.java
// Change the package line below to match your MainActivity's package.
package com.valiryan.app;

import android.Manifest;
import android.content.ContentResolver;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.media.MediaScannerConnection;
import android.util.Base64;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.app.KeyguardManager;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;

@CapacitorPlugin(
    name = "LocalMusic",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "images", strings = { "android.permission.READ_MEDIA_IMAGES" })
    }
)
public class LocalMusicPlugin extends Plugin {

    /** State shown on the lock-screen player (pushed from the web page). */
    public static class Now {
        public static volatile String title = "", artist = "", album = "", coverPath = "", coverKey = "";
        public static volatile long dur = 0, pos = 0, stamp = 0;
        public static volatile boolean playing = false, fav = false;
    }

    static LocalMusicPlugin inst;
    static volatile boolean lockEnabled = false;

    public static void fire(String action, double pos) {
        if (inst == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("pos", pos);
        inst.notifyListeners("lockAction", o);
    }

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!lockEnabled || !Now.playing) return;
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && !km.isKeyguardLocked()) return;
            try {
                Intent a = new Intent(c, LockScreenActivity.class);
                a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                c.startActivity(a);
            } catch (Exception ignored) {}
        }
    };

    @Override
    public void load() {
        inst = this;
        try { getContext().registerReceiver(screenRx, new IntentFilter(Intent.ACTION_SCREEN_ON)); } catch (Exception ignored) {}
    }

    @PluginMethod
    public void setLockEnabled(PluginCall call) {
        lockEnabled = call.getBoolean("enabled", false);
        call.resolve();
    }

    @PluginMethod
    public void canOverlay(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void setNowPlaying(PluginCall call) {
        try {
            lockEnabled = call.getBoolean("enabled", lockEnabled);
            Now.title = call.getString("title", "");
            Now.artist = call.getString("artist", "");
            Now.album = call.getString("album", "");
            Now.dur = (long) (call.getDouble("duration", 0.0) * 1000);
            Now.pos = (long) (call.getDouble("position", 0.0) * 1000);
            Now.stamp = SystemClock.elapsedRealtime();
            Now.playing = call.getBoolean("playing", false);
            Now.fav = call.getBoolean("fav", false);
            if (call.getBoolean("coverSet", false)) {
                String b64 = call.getString("cover", "");
                if (b64 == null || b64.isEmpty()) {
                    Now.coverPath = "";
                } else {
                    File f = new File(getContext().getCacheDir(), "lock_cover.jpg");
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write(Base64.decode(b64, Base64.DEFAULT));
                    }
                    Now.coverPath = f.getAbsolutePath();
                }
                Now.coverKey = String.valueOf(System.currentTimeMillis());
            }
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }


    private String alias() { return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage"; }

    @PluginMethod
    public void list(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "afterPermission");
            return;
        }
        scan(call);
    }

    @PermissionCallback
    private void afterPermission(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) scan(call);
        else call.reject("Permission denied");
    }

    private void scan(PluginCall call) {
        final int minMs = call.getInt("minSeconds", 30) * 1000;
        new Thread(() -> {
            try {
                JSArray songs = new JSArray();
                ContentResolver cr = getContext().getContentResolver();
                String[] cols = {
                    MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION
                };
                try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                        MediaStore.Audio.Media.DURATION + ">=?", new String[]{ String.valueOf(minMs) },
                        MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                    if (c != null) while (c.moveToNext()) {
                        String path = c.getString(0);
                        if (path == null) continue;
                        JSObject o = new JSObject();
                        o.put("path", path);
                        o.put("title", c.getString(1));
                        String artist = c.getString(2);
                        if (artist != null && !artist.equals("<unknown>")) o.put("artist", artist);
                        String album = c.getString(3);
                        if (album != null && !album.equals("<unknown>")) o.put("album", album);
                        o.put("name", c.getString(4));
                        o.put("duration", c.getLong(5) / 1000);
                        File parent = new File(path).getParentFile();
                        o.put("folder", parent == null ? "" : parent.getName());
                        songs.put(o);
                    }
                }
                JSObject res = new JSObject();
                res.put("songs", songs);
                call.resolve(res);
            } catch (Exception e) {
                call.reject("Scan failed: " + e.getMessage());
            }
        }).start();
    }

    // ---- open a link in the matching app (Pinterest, Google Photos, browser) ----
    @PluginMethod
    public void openUrl(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    // ---- newest image saved since a timestamp (Pinterest downloads) ----
    @PluginMethod
    public void latestImage(PluginCall call) {
        String a = Build.VERSION.SDK_INT >= 33 ? "images" : "storage";
        if (getPermissionState(a) != PermissionState.GRANTED) {
            requestPermissionForAlias(a, call, "afterImagePerm");
            return;
        }
        findImage(call);
    }

    @PermissionCallback
    private void afterImagePerm(PluginCall call) {
        String a = Build.VERSION.SDK_INT >= 33 ? "images" : "storage";
        if (getPermissionState(a) == PermissionState.GRANTED) findImage(call);
        else call.reject("Permission denied");
    }

    private void findImage(PluginCall call) {
        long since = call.getLong("since", 0L);
        try (Cursor c = getContext().getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Images.Media.DATA },
                MediaStore.Images.Media.DATE_ADDED + ">=?", new String[]{ String.valueOf(since) },
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            JSObject r = new JSObject();
            if (c != null && c.moveToFirst()) r.put("path", c.getString(0));
            call.resolve(r);
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    // ---- "All files access" so tags can be written into the song files ----
    @PluginMethod
    public void hasAllFiles(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager());
        call.resolve(r);
    }

    @PluginMethod
    public void requestAllFiles(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    // ---- replace a file's bytes but keep its modified date/time ----
    @PluginMethod
    public void writeFile(PluginCall call) {
        final String path = call.getString("path");
        final String data = call.getString("data");
        final boolean keep = call.getBoolean("keepTime", true);
        new Thread(() -> {
            try {
                File f = new File(path);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                long lm = f.lastModified();
                File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(Base64.decode(data, Base64.DEFAULT));
                }
                if (!tmp.renameTo(f)) {
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write(Base64.decode(data, Base64.DEFAULT));
                    }
                    tmp.delete();
                }
                if (keep) f.setLastModified(lm);
                MediaScannerConnection.scanFile(getContext(), new String[]{ path }, null, null);
                call.resolve();
            } catch (Exception e) { call.reject("Write failed: " + e.getMessage()); }
        }).start();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void listDir(PluginCall call) {
        File d = new File(call.getString("dir", ""));
        String ext = call.getString("ext", "");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String[] n = d.list();
        if (n != null) for (String x : n) if (x.endsWith(ext)) out.add(x);
        java.util.Collections.sort(out, java.util.Collections.reverseOrder());
        JSArray a = new JSArray();
        for (String x : out) a.put(x);
        JSObject r = new JSObject();
        r.put("names", a);
        call.resolve(r);
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        new File(call.getString("path", "")).delete();
        call.resolve();
    }
}
