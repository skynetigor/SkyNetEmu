package com.winlator.cmod.linux;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * The user interface of a statically translated game that has none of its own: BeamNG's UI is a CEF page, and its engine can serve that page (and the
 * WebSocket the page talks to the engine over, the page's "external client mode") on a local port. The game starts the server (the shortcut's launch
 * options), and this view shows the page over the X server's view, transparent where the 3D scene must show through. The page is retried until the
 * server is up.
 */
public final class LinuxLiftedUiOverlay {
    private static final String TAG = "LinuxLiftedUi";
    public static final int PORT = 8084;
    public static final String URL = "http://127.0.0.1:" + PORT + "/ui/entrypoints/main/index.html";
    private static final long RETRY_MS = 1500;

    private LinuxLiftedUiOverlay() {}

    /** The page draws a developer strip (UI / Shortcuts / Covers / ... / To top) at the bottom, which is no part of the game: hidden now and whenever the page changes. */
    private static final String HIDE_DEV_STRIP = "(function(){if(window.__bngStripHider)return;"
            // the page ignores the loading screen unless it believes it runs inside the game (CEF); the engine's hooks reach it the same way
            + "var ig=setInterval(function(){if(window.beamng){window.beamng.ingame=true;clearInterval(ig);}},200);"
            // the pad's X, or A on a level that is already selected, starts the level ("Start Freeroam" answers it only while focused, which the pad cannot reach in the grid)
            + "setInterval(function(){var bus=window.vueEventBus;if(bus&&window.__bngPadBus!==bus){window.__bngPadBus=bus;bus.on('UINavigation',function(a,v){var b=document.querySelector('.play-button');if(a==='action_2'&&v===1){if(b)b.click();}else if(a==='ok'&&v===1&&b){var el=document.activeElement;if(el&&el===window.__bngSel){b.click();}else{setTimeout(function(){window.__bngSel=el&&el.classList&&el.classList.contains('is-current-selection')?el:null;},300);}}});}},500);"
            // the page paints the main menu's backdrop on the body; in CEF that layer is transparent, here it would hide the game
            + "var st=document.createElement('style');st.textContent='html,body{background:transparent!important;background-image:none!important}';document.head.appendChild(st);"
            + "var pending=0;function hide(){pending=0;document.querySelectorAll('div').forEach(function(e){"
            + "if(e.offsetHeight>0&&e.offsetHeight<60){var t=e.innerText||'';if(t.indexOf('Shortcuts')>=0&&t.indexOf('To top')>=0)e.style.display='none';}});}"
            + "window.__bngStripHider=new MutationObserver(function(){if(!pending)pending=setTimeout(hide,400);});window.__bngStripHider.observe(document.body,{childList:true,subtree:true});hide();})()";

    /**
     * The loading screen's progress comes from the engine's C++ (a CEF call), which the translated build has no CEF to receive, and the engine reports nothing else while it
     * loads (its taskbar progress fires in one burst at the very end): the screen sat unchanged for the 30 to 40 s of a level load. This feeds the page's own progress event
     * (UpdateLoadingProgressV2) from the page: the seconds since the loading screen appeared, and a bar that approaches 95 % in the time the last load took (kept in the
     * page's localStorage; 40 s before the first). It is an estimate, not the engine's count, and it ends when the engine hides the loading screen.
     */
    static final String LOADING_INDICATOR = "(function(){if(window.__skyLoadInd)return;window.__skyLoadInd=true;"
            + "var KEY='skyLoadMs',DEFAULT=40000,started=0,timer=0;"
            + "function expected(){try{var v=+localStorage.getItem(KEY);return v>5000&&v<600000?v:DEFAULT}catch(e){return DEFAULT}}"
            + "function tick(){var e=Date.now()-started,p=Math.min(95,Math.round(95*e/expected()));"
            + "window.__skyHooks([['UpdateLoadingProgressV2',[{currentEntries:[{name:'engine.loading.stage.Loading',progress:p,message:'Loading... '+Math.round(e/1000)+' s'}],historyEntries:[]}]]])}"
            + "function onHooks(d){try{for(var i=0;i<d.length;i++){if(d[i][0]!=='LoadingScreen')continue;var a=d[i][1]&&d[i][1][0];"
            + "if(a&&a.active){if(!started){started=Date.now();clearInterval(timer);timer=setInterval(tick,500)}}"
            + "else if(started){try{localStorage.setItem(KEY,String(Date.now()-started))}catch(e){}started=0;clearInterval(timer)}}}catch(e){}}"
            // the page defines multihookUpdate when it starts: wrap it once, and again if the page replaces it
            + "setInterval(function(){var f=window.multihookUpdate;if(typeof f!=='function'||f.__sky)return;"
            + "var w=function(d){onHooks(d);return f.apply(this,arguments)};w.__sky=true;window.__skyHooks=f;window.multihookUpdate=w},200)})()";

    /** Takes a touch only while the page shows something to touch (a menu); the HUD ("#/play") and the loading screens let it through to the game. */
    private static final class UiWebView extends WebView {
        volatile boolean interactive;
        private boolean capturing;

        UiWebView(Activity activity) { super(activity); }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) capturing = interactive;
            return capturing && super.dispatchTouchEvent(event);
        }
    }

    /** Routes of the page that are drawn over the game and have nothing to touch. */
    static boolean isHud(String url) {
        int hash = url == null ? -1 : url.indexOf('#');
        String route = hash < 0 ? "" : url.substring(hash + 1);
        return route.isEmpty() || route.equals("/") || route.startsWith("/play") || route.startsWith("/loading");
    }

    @SuppressLint("SetJavaScriptEnabled")
    /** @param controls the on-screen controls: the page goes just below it, so the virtual gamepad stays drawn (and touchable) over the page */
    public static WebView attach(Activity activity, FrameLayout root, View controls) {
        UiWebView web = new UiWebView(activity);
        Handler handler = new Handler(Looper.getMainLooper());
        web.setBackgroundColor(Color.TRANSPARENT);
        // the page tracks its gamepad focus through DOM focus events, which a page without window focus never gets (the pad's "ok" would do nothing)
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect: this app is a development build
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.i(TAG, "UI not reachable yet: " + error.getDescription());
                    handler.postDelayed(() -> view.loadUrl(URL), RETRY_MS);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "UI page loaded: " + url);
                web.interactive = !isHud(url);
                view.evaluateJavascript(HIDE_DEV_STRIP, null);
                view.evaluateJavascript(LOADING_INDICATOR, null);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                web.interactive = !isHud(url);
                Log.i(TAG, "UI route " + url + " interactive=" + web.interactive);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {   // the page's console: errors and warnings explain a page that hangs
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                    Log.i(TAG, "console " + m.messageLevel() + ": " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        int at = controls != null ? root.indexOfChild(controls) : -1;
        root.addView(web, at >= 0 ? at : root.getChildCount(), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web.loadUrl(URL);
        web.requestFocus();
        // the on-screen controls and other views ask for the focus too; the page needs it for the pad's "ok"
        Runnable keepFocus = new Runnable() {
            @Override public void run() {
                if (web.getParent() == null) return;
                if (!web.hasFocus()) web.requestFocus();
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(keepFocus, 500);
        return web;
    }

    public static void detach(FrameLayout root, WebView web) {
        if (web == null) return;
        if (root != null) root.removeView(web);
        web.destroy();
    }
}
