package com.dualstack.lab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;

public class PreviewActivity extends LabActivity {
    private FileStore store;
    private FileStore.Node source;
    private File file;
    private VideoView player;
    private WebView web;
    private TextView status;
    private LinearLayout body;
    private Bitmap bitmap;
    private boolean destroyed;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new FileStore(this);
        body = Ui.column(this); body.addView(Ui.toolbar(this, getIntent().getStringExtra("name") == null ? "预览" : getIntent().getStringExtra("name"), this::finish));
        status = Ui.text(this, "读取中…", 13, Ui.MUTED); status.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        body.addView(status); Ui.install(this, body);
        String kind = getIntent().getStringExtra("kind");
        Tasks.run(this, () -> {
            FileStore.Node node = store.fromIntent(getIntent());
            File content = store.materialize(node);
            synchronized (this) {
                if (destroyed) { release(node, content); return null; }
                source = node; file = content;
            }
            return new Source(node, content);
        }, result -> {
            if (result == null) return;
            if ("image".equals(kind)) image();
            else if ("html".equals(kind) || "svg".equals(kind)) document(kind);
            else media("audio".equals(kind));
        }, error -> status.setText("无法预览：" + error.getMessage()));
    }
    private record Source(FileStore.Node node, File file) {}
    private void image() {
        Tasks.run(this, () -> {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0) throw new IOException("文件不是可识别图片");
            String dimensions = options.outWidth + " × " + options.outHeight;
            options.inJustDecodeBounds = false; options.inSampleSize = 1;
            // Zooming needs more detail than the screen; cap the decoded size by edge and by memory.
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 4096
                    || (long) (options.outWidth / options.inSampleSize) * (options.outHeight / options.inSampleSize) * 4 > 48L << 20)
                options.inSampleSize *= 2;
            Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (image == null) throw new IOException("图片解码失败");
            return new Picture(image, dimensions);
        }, picture -> {
            bitmap = picture.bitmap; status.setText(picture.dimensions + " · " + FileStore.size(file.length()));
            ZoomImageView image = new ZoomImageView(this); image.setImageBitmap(bitmap);
            image.setContentDescription("图片预览"); body.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout controls = Ui.row(this);
            String[] labels = {"缩小", "适屏", "放大"};
            for (int i = 0; i < labels.length; i++) {
                int action = i; TextView button = Ui.text(this, labels[i], 14, Ui.ACCENT);
                button.setGravity(android.view.Gravity.CENTER); button.setBackground(Ui.ripple(Ui.PANEL)); button.setFocusable(true);
                button.setContentDescription("图片" + labels[i]); button.setOnClickListener(v -> {
                    if (action == 1) image.reset(); else image.zoom(action == 0 ? 1f / 1.5f : 1.5f, image.getWidth() / 2f, image.getHeight() / 2f);
                }); controls.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
            }
            body.addView(controls);
        }, error -> status.setText("无法预览：" + error.getMessage()));
    }
    private record Picture(Bitmap bitmap, String dimensions) {}
    private void document(String kind) {
        Tasks.run(this, () -> {
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = FileStore.readBytes(in, 8 * 1024 * 1024 + 1);
                if (bytes.length > 8 * 1024 * 1024) throw new IOException("预览文件超过 8 MiB，请使用文本编辑器");
                return TextCodec.decode(bytes, null).text();
            }
        }, text -> {
            status.setText(source.name); web = new WebView(this); web.setContentDescription("文档预览");
            web.getSettings().setJavaScriptEnabled(false); web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false); web.getSettings().setBlockNetworkLoads(true);
            web.getSettings().setBuiltInZoomControls(true); web.getSettings().setDisplayZoomControls(false);
            web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) { return true; }
            });
            body.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            if (kind.equals("svg")) {
                String encoded = android.util.Base64.encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                web.loadData("<html><body style='margin:0'><img style='width:100%;height:90vh;object-fit:contain' src='data:image/svg+xml;base64," + encoded + "'></body></html>", "text/html", "UTF-8");
            } else web.loadDataWithBaseURL(null, text, "text/html", "UTF-8", null);
        }, error -> status.setText("无法预览：" + error.getMessage()));
    }
    private void media(boolean audio) {
        player = new VideoView(this); player.setBackgroundColor(Color.BLACK); player.setContentDescription(audio ? "音频播放器" : "视频播放器");
        body.addView(player, new LinearLayout.LayoutParams(-1, 0, 1));
        MediaController controls = new MediaController(this); controls.setAnchorView(player); player.setMediaController(controls);
        player.setOnPreparedListener(media -> { status.setText(source.name); player.start(); controls.show(0); });
        player.setOnErrorListener((media, what, extra) -> { status.setText("系统无法解码此媒体（" + what + ", " + extra + "）"); return true; });
        player.setVideoURI(Uri.fromFile(file));
    }
    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onDestroy() {
        if (player != null) player.stopPlayback();
        if (web != null) web.destroy();
        if (bitmap != null) bitmap.recycle();
        FileStore.Node owned; File staged;
        synchronized (this) { destroyed = true; owned = source; staged = file; }
        if (owned != null && staged != null) Tasks.IO.execute(() -> release(owned, staged));
        super.onDestroy();
    }
    private void release(FileStore.Node node, File content) {
        try { store.release(node, content); } catch (IOException ignored) { }
    }
}
