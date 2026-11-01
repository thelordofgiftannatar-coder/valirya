package com.valiryan.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;

/** Full-screen player shown over the lock screen: the cover fills the whole screen. */
public class LockScreenActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private ImageView bg;
    private TextView title, artist, album, tCur, tTot, heart, play;
    private SeekBar seek;
    private boolean dragging = false;
    private String shownCover = "";

    private final BroadcastReceiver unlockRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 500); }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView tv(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView btn(String text, int sp, final String action) {
        TextView t = tv(text, sp, Color.WHITE, false);
        t.setPadding(dp(8), dp(12), dp(8), dp(12));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (action.equals("playpause")) {
                    LocalMusicPlugin.Now.pos = currentPos();
                    LocalMusicPlugin.Now.stamp = SystemClock.elapsedRealtime();
                    LocalMusicPlugin.Now.playing = !LocalMusicPlugin.Now.playing;
                }
                if (action.equals("fav")) LocalMusicPlugin.Now.fav = !LocalMusicPlugin.Now.fav;
                LocalMusicPlugin.fire(action, 0);
                refresh();
            }
        });
        return t;
    }

    private long currentPos() {
        long cur = LocalMusicPlugin.Now.pos;
        if (LocalMusicPlugin.Now.playing) cur += SystemClock.elapsedRealtime() - LocalMusicPlugin.Now.stamp;
        long d = LocalMusicPlugin.Now.dur;
        if (d > 0 && cur > d) cur = d;
        return Math.max(0, cur);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(56), dp(20), dp(28));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        clock.setTextColor(Color.WHITE);
        clock.setGravity(Gravity.CENTER);
        col.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("MMM d, EEE");
        date.setFormat24Hour("MMM d, EEE");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setTextColor(0xCCFFFFFF);
        date.setGravity(Gravity.CENTER);
        col.addView(date, new LinearLayout.LayoutParams(-1, -2));

        col.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        title = tv("", 24, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.addView(btn("«", 30, "back"), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        artist = tv("", 18, 0xE6FFFFFF, false);
        artist.setSingleLine(true);
        album = tv("", 15, 0x99FFFFFF, false);
        album.setSingleLine(true);
        names.addView(artist, new LinearLayout.LayoutParams(-1, -2));
        names.addView(album, new LinearLayout.LayoutParams(-1, -2));
        mid.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        mid.addView(btn("»", 30, "fwd"), new LinearLayout.LayoutParams(-2, -2));
        col.addView(mid, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, dp(16), 0, dp(8));
        tCur = tv("0:00", 14, 0xCCFFFFFF, false);
        tTot = tv("0:00", 14, 0xCCFFFFFF, false);
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) tCur.setText(fmt(p)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                LocalMusicPlugin.Now.pos = s.getProgress();
                LocalMusicPlugin.Now.stamp = SystemClock.elapsedRealtime();
                LocalMusicPlugin.fire("seek", s.getProgress() / 1000.0);
            }
        });
        sr.addView(tCur, new LinearLayout.LayoutParams(dp(48), -2));
        sr.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        sr.addView(tTot, new LinearLayout.LayoutParams(dp(48), -2));
        col.addView(sr, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        heart = btn("♡", 30, "fav");
        play = btn("▶", 38, "playpause");
        ctl.addView(heart, new LinearLayout.LayoutParams(0, -2, 1f));
        ctl.addView(btn("|◁", 30, "prev"), new LinearLayout.LayoutParams(0, -2, 1f));
        ctl.addView(play, new LinearLayout.LayoutParams(0, -2, 1f));
        ctl.addView(btn("▷|", 30, "next"), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(ctl, new LinearLayout.LayoutParams(-1, -2));

        TextView lock = tv("🔒", 28, Color.WHITE, false);
        lock.setPadding(dp(8), dp(24), dp(8), dp(8));
        lock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { unlock(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.START;
        col.addView(lock, lp);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        try { registerReceiver(unlockRx, new IntentFilter(Intent.ACTION_USER_PRESENT)); } catch (Exception ignored) {}
    }

    private void unlock() {
        if (Build.VERSION.SDK_INT >= 26) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void refresh() {
        title.setText(LocalMusicPlugin.Now.title);
        artist.setText(LocalMusicPlugin.Now.artist);
        album.setText(LocalMusicPlugin.Now.album);
        long cur = currentPos(), d = LocalMusicPlugin.Now.dur;
        if (!dragging) {
            seek.setMax((int) Math.max(1, d));
            seek.setProgress((int) cur);
            tCur.setText(fmt(cur));
        }
        tTot.setText(fmt(d));
        play.setText(LocalMusicPlugin.Now.playing ? "❚❚" : "▶");
        heart.setText(LocalMusicPlugin.Now.fav ? "♥" : "♡");
        String key = LocalMusicPlugin.Now.coverKey;
        if (!key.equals(shownCover)) { shownCover = key; loadCover(LocalMusicPlugin.Now.coverPath); }
    }

    private void loadCover(final String path) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    if (path != null && !path.isEmpty()) {
                        int w = getResources().getDisplayMetrics().widthPixels;
                        int hh = getResources().getDisplayMetrics().heightPixels;
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(path, o);
                        int s = 1;
                        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= hh) s *= 2;
                        BitmapFactory.Options o2 = new BitmapFactory.Options();
                        o2.inSampleSize = s;
                        bmp = BitmapFactory.decodeFile(path, o2);
                    }
                } catch (Throwable ignored) {}
                final Bitmap fb = bmp;
                h.post(new Runnable() { @Override public void run() { bg.setImageBitmap(fb); } });
            }
        }).start();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(unlockRx); } catch (Exception ignored) {}
    }
}
