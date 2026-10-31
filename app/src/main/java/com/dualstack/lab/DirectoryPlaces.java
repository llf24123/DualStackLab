package com.dualstack.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import org.json.*;
import java.io.*;
import java.util.*;

final class DirectoryPlaces {
    static final int RECENT_LIMIT = 50;
    record Entry(String key, String label, String path, String uri, boolean root) {
        static Entry from(FileStore.Node node) {
            String label = node.file != null && node.path().equals(Environment.getExternalStorageDirectory().getAbsolutePath())
                    ? "内部存储" : node.name;
            return new Entry(node.key(), label, node.path(), node.uri == null ? "" : node.uri.toString(), node.root);
        }
        String displayPath() { return uri.isEmpty() ? path : uri; }
        FileStore.Node resolve(FileStore store) throws IOException {
            if (key.startsWith("zip:")) return store.fromKey(key);
            if (!uri.isEmpty()) return store.document(Uri.parse(uri));
            FileStore.Node directory = RootAccess.automatic(FileStore.directory(new File(path), root));
            if (!directory.root && !directory.file.isDirectory()) throw new IOException("目录不存在或无访问权限：" + path);
            return directory;
        }
    }
    private final SharedPreferences preferences;
    DirectoryPlaces(Context context) {
        preferences = context.getSharedPreferences("directory-places", Context.MODE_PRIVATE);
        if (!preferences.contains("bookmarks")) {
            List<Entry> defaults = new ArrayList<>();
            File base = Environment.getExternalStorageDirectory();
            for (String name : new String[]{"Download", "Documents"})
                defaults.add(Entry.from(FileStore.directory(new File(base, name), false)));
            save("bookmarks", defaults);
        }
    }
    List<Entry> entries(boolean bookmarks) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(bookmarks ? "bookmarks" : "recent", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i); if (item == null) continue;
                String key = item.optString("key"), path = item.optString("path");
                if (key.isEmpty() || path.isEmpty()) continue;
                entries.add(new Entry(key, item.optString("label", path), path,
                        item.optString("uri"), item.optBoolean("root")));
            }
        } catch (JSONException ignored) { }
        return entries;
    }
    void visit(FileStore.Node node) {
        if (!node.directory) return;
        List<Entry> items = entries(false); items.removeIf(e -> e.key.equals(node.key()));
        items.add(0, Entry.from(node));
        if (items.size() > RECENT_LIMIT) items.subList(RECENT_LIMIT, items.size()).clear();
        save("recent", items);
    }
    void bookmark(Entry entry) {
        List<Entry> items = entries(true);
        if (items.stream().anyMatch(e -> e.key.equals(entry.key))) return;
        items.add(entry); save("bookmarks", items);
    }
    void rename(String key, String label) {
        List<Entry> items = entries(true);
        for (int i = 0; i < items.size(); i++) {
            Entry old = items.get(i);
            if (old.key.equals(key)) items.set(i, new Entry(old.key, label, old.path, old.uri, old.root));
        }
        save("bookmarks", items);
    }
    void move(String key, int direction) {
        List<Entry> items = entries(true);
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) {
            int to = i + direction;
            if (to >= 0 && to < items.size()) Collections.swap(items, i, to);
            break;
        }
        save("bookmarks", items);
    }
    void remove(boolean bookmarks, String key) {
        List<Entry> items = entries(bookmarks); items.removeIf(e -> e.key.equals(key));
        save(bookmarks ? "bookmarks" : "recent", items);
    }
    void clear(boolean bookmarks) { save(bookmarks ? "bookmarks" : "recent", Collections.emptyList()); }
    private void save(String key, List<Entry> items) {
        JSONArray array = new JSONArray();
        for (Entry entry : items) {
            try {
                array.put(new JSONObject().put("key", entry.key).put("label", entry.label)
                        .put("path", entry.path).put("uri", entry.uri).put("root", entry.root));
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }
}
