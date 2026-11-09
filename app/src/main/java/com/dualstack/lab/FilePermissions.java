package com.dualstack.lab;

import android.app.*;
import android.system.*;
import android.text.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;

final class FilePermissions {
    private static final int PATH_ONLY = 010000000; // Linux O_PATH pins an inode without opening a device or requiring read access.
    record State(int mode, long uid, long gid, boolean root, boolean link) { }
    static int parse(String value) throws IOException {
        if (!value.matches("[0-7]{3,4}")) throw new IOException("请输入 3 或 4 位八进制权限，仅允许 0–7，例如 600、755");
        return Integer.parseInt(value, 8);
    }
    static String octal(int mode) { return String.format(Locale.ROOT, (mode & 07000) == 0 ? "%03o" : "%04o", mode); }
    static String symbolic(int mode) {
        char[] result = "---------".toCharArray();
        for (int i = 0; i < 9; i++) if ((mode & (1 << (8 - i))) != 0) result[i] = "rwx".charAt(i % 3);
        if ((mode & 04000) != 0) result[2] = (mode & 0100) != 0 ? 's' : 'S';
        if ((mode & 02000) != 0) result[5] = (mode & 0010) != 0 ? 's' : 'S';
        if ((mode & 01000) != 0) result[8] = (mode & 0001) != 0 ? 't' : 'T';
        return new String(result);
    }
    static State read(FileStore.Node requested) throws IOException {
        FileStore.Node node = RootAccess.automatic(requested);
        if (node.file == null) throw new IOException("此来源没有可修改的 POSIX 文件权限");
        int full; long uid, gid;
        if (node.root) {
            String[] fields = RootDirectoryReader.stat(node.path(), "%f|%u|%g", false).split("\\|");
            try {
                full = Integer.parseInt(fields[0], 16); uid = Long.parseLong(fields[1]); gid = Long.parseLong(fields[2]);
            } catch (IndexOutOfBoundsException | NumberFormatException error) { throw new IOException("无法解析文件权限", error); }
        } else try {
            StructStat stat = Os.lstat(node.path()); full = stat.st_mode; uid = stat.st_uid; gid = stat.st_gid;
        } catch (ErrnoException error) { throw new IOException("读取权限失败：" + error.getMessage(), error); }
        return new State(full & 07777, uid, gid, node.root, OsConstants.S_ISLNK(full));
    }
    static State apply(android.content.Context context, FileStore.Node requested, int mode) throws IOException {
        if (mode < 0 || mode > 07777) throw new IOException("权限值超出 0000–7777");
        Tasks.Progress.check();
        FileStore.Node node = RootAccess.automatic(requested);
        State previous = read(node);
        if (previous.link()) throw new IOException("符号链接不支持直接修改权限，请打开目标文件属性");
        if (node.root) return RootMutations.chmod(context, node, mode);
        try {
            java.io.FileDescriptor raw = Os.open(node.file.getParent(), PATH_ONLY | OsConstants.O_NONBLOCK, 0);
            android.os.ParcelFileDescriptor parent;
            try { parent = android.os.ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
            try (android.os.ParcelFileDescriptor directory = parent) {
                StructStat parentStat = Os.fstat(directory.getFileDescriptor());
                if (!OsConstants.S_ISDIR(parentStat.st_mode) || node.parentIdentity != null
                        && !node.parentIdentity.equals(FileStore.fileIdentity(parentStat))) throw new IOException("父目录已变化，请刷新");
                String path = "/proc/self/fd/" + directory.getFd() + "/" + node.file.getName();
                raw = Os.open(path, PATH_ONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                android.os.ParcelFileDescriptor pinned;
                try { pinned = android.os.ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
                try (android.os.ParcelFileDescriptor leaf = pinned) {
                    StructStat selected = Os.fstat(leaf.getFileDescriptor());
                    if ((!OsConstants.S_ISREG(selected.st_mode) && !OsConstants.S_ISDIR(selected.st_mode))
                            || node.identity != null && !node.identity.equals(FileStore.fileIdentity(selected)))
                        throw new IOException("目标已变化或不是普通文件/目录，请刷新");
                    Tasks.Progress.step(node.name);
                    Os.chmod("/proc/self/fd/" + leaf.getFd(), mode);
                    StructStat actual = Os.fstat(leaf.getFileDescriptor());
                    if (!FileStore.fileIdentity(selected).equals(FileStore.fileIdentity(Os.lstat(path))))
                        throw new IOException("权限操作期间目录项已变化，请刷新核对");
                    if ((actual.st_mode & 07777) != mode) throw new IOException("文件系统没有应用此权限");
                    return new State(actual.st_mode & 07777, actual.st_uid, actual.st_gid, false, false);
                }
            }
        } catch (ErrnoException error) { throw new IOException("修改权限失败：" + error.getMessage(), error); }
    }
    static void show(Activity activity, FileStore.Node node, String modified, Runnable changed) {
        LinearLayout body = Ui.column(activity);
        int padding = Ui.dp(activity, 18); body.setPadding(padding, Ui.dp(activity, 6), padding, Ui.dp(activity, 10));
        TextView info = Ui.text(activity, node.name, 15, Ui.TEXT);
        info.setTextIsSelectable(true); info.setMinHeight(Ui.dp(activity, 48)); body.addView(info);
        TextView path = Ui.text(activity, node.path(), 13, Ui.ACCENT);
        path.setContentDescription("属性完整路径");
        path.setMinHeight(Ui.dp(activity, 48));
        path.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        path.setOnLongClickListener(v -> {
            ((android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("目录路径", node.path()));
            Tasks.toast(activity, "路径已复制"); return true;
        });
        body.addView(path);
        body.addView(Ui.text(activity, (node.directory ? "文件夹" : FileStore.size(node.size)) + "\n" + modified, 13, Ui.TEXT));
        TextView stateText = Ui.text(activity, "读取权限…", 12, Ui.MUTED);
        stateText.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 8)); body.addView(stateText);
        LinearLayout controls = Ui.column(activity); body.addView(controls);
        EditText mode = Ui.input(activity, "八进制权限，如 600、755");
        mode.setContentDescription("八进制权限"); mode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        controls.addView(mode);
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001, 04000, 02000, 01000};
        CheckBox[] checks = new CheckBox[bits.length];
        for (int row = 0; row < 3; row++) {
            LinearLayout line = Ui.row(activity);
            TextView owner = Ui.text(activity, new String[]{"所有者", "用户组", "其他人"}[row], 12, Ui.TEXT);
            line.addView(owner, new LinearLayout.LayoutParams(Ui.dp(activity, 56), -2));
            for (int column = 0; column < 3; column++) {
                int index = row * 3 + column;
                CheckBox check = new CheckBox(activity); checks[index] = check;
                check.setText(new String[]{"读", "写", "执行"}[column]); check.setTextColor(Ui.TEXT); check.setTextSize(12);
                check.setContentDescription(new String[]{"所有者", "用户组", "其他人"}[row] + new String[]{"读取", "写入", "执行"}[column]);
                check.setPadding(0, 0, 0, 0); check.setMinWidth(0); check.setMinHeight(Ui.dp(activity, 48));
                line.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            }
            controls.addView(line);
        }
        LinearLayout specials = Ui.row(activity);
        for (int i = 9; i < 12; i++) {
            CheckBox check = new CheckBox(activity); checks[i] = check;
            check.setText(new String[]{"Set UID", "Set GID", "Sticky"}[i - 9]); check.setTextColor(Ui.TEXT); check.setTextSize(11);
            check.setPadding(0, 0, 0, 0); specials.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
        }
        controls.addView(specials); controls.setVisibility(android.view.View.GONE);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("属性").setView(scroll)
                .setNegativeButton("关闭", null).setPositiveButton("保存权限", null).create();
        boolean[] syncing = {false}, writable = {false};
        Runnable validate = () -> {
            try {
                int value = parse(mode.getText().toString());
                stateText.setText(symbolic(value)); mode.setError(null);
                syncing[0] = true;
                for (int i = 0; i < checks.length; i++) checks[i].setChecked((value & bits[i]) != 0);
                syncing[0] = false;
                dialog.getButton(-1).setEnabled(writable[0]);
            } catch (IOException error) {
                mode.setError(error.getMessage()); dialog.getButton(-1).setEnabled(false);
            }
        };
        mode.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable text) { if (!syncing[0] && dialog.isShowing()) validate.run(); }
        });
        for (CheckBox check : checks) check.setOnCheckedChangeListener((button, checked) -> {
            if (syncing[0]) return;
            int value = 0; for (int i = 0; i < checks.length; i++) if (checks[i].isChecked()) value |= bits[i];
            mode.setText(octal(value));
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(-1).setEnabled(false);
            if (node.file == null) { stateText.setText("此来源没有可修改的 POSIX 文件权限"); return; }
            Tasks.run(activity, Tasks.SHELL, () -> read(node), state -> {
                if (!dialog.isShowing()) return;
                body.addView(Ui.text(activity, "UID " + state.uid() + "  GID " + state.gid()
                        + (state.root() ? "  ROOT" : ""), 12, Ui.MUTED));
                if (state.link()) { stateText.setText("符号链接：请打开目标文件的属性修改权限"); return; }
                writable[0] = true; controls.setVisibility(android.view.View.VISIBLE); mode.setText(octal(state.mode()));
            }, error -> stateText.setText(error.getMessage()));
            dialog.getButton(-1).setOnClickListener(v -> {
                final int value;
                try { value = parse(mode.getText().toString()); }
                catch (IOException error) { mode.setError(error.getMessage()); return; }
                writable[0] = false; dialog.getButton(-1).setEnabled(false);
                Tasks.run(activity, Tasks.SHELL, () -> apply(activity, node, value), actual -> {
                    changed.run(); dialog.dismiss(); Tasks.toast(activity, "权限已保存 · " + octal(actual.mode()));
                }, error -> {
                    writable[0] = true; validate.run(); Tasks.showError(activity, error);
                });
            });
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private FilePermissions() { }
}
