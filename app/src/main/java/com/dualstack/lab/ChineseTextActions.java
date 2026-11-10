package com.dualstack.lab;

import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

final class ChineseTextActions {
    static void install(TextView... views) {
        ActionMode.Callback callback = new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { translate(menu); return true; }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { translate(menu); return true; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        };
        for (TextView view : views) {
            view.setCustomSelectionActionModeCallback(callback);
            view.setCustomInsertionActionModeCallback(callback);
        }
    }
    private static void translate(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            switch (item.getItemId()) {
                case android.R.id.copy -> item.setTitle("复制");
                case android.R.id.cut -> item.setTitle("剪切");
                case android.R.id.paste -> item.setTitle("粘贴");
                case android.R.id.selectAll -> item.setTitle("全选");
                case android.R.id.shareText -> item.setTitle("分享");
                case android.R.id.pasteAsPlainText -> item.setTitle("粘贴为纯文本");
                case android.R.id.undo -> item.setTitle("撤销");
                case android.R.id.redo -> item.setTitle("重做");
                case android.R.id.replaceText -> item.setTitle("替换");
                case android.R.id.autofill -> item.setTitle("自动填充");
                case android.R.id.textAssist -> item.setTitle("文本助手");
            }
        }
    }
    private ChineseTextActions() { }
}
