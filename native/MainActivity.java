// Replace android/app/src/main/java/<your/package/path>/MainActivity.java
// Keep YOUR existing package line.
package com.valiryan.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LocalMusicPlugin.class);   // must come BEFORE super.onCreate
        super.onCreate(savedInstanceState);
        // no rubber-band / glow when dragging the lists
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        }
    }

    // Keep the WebView (and its audio) alive when the screen turns off / app is minimized
    @Override
    public void onPause() {
        super.onPause();
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        }
    }
}
