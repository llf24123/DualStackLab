package com.dualstack.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SelectionTools {
    record Result(Set<String> keys, boolean append) { }
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    /** MT-style "更多" menu: day, week, month, year, time, size, keyword — anchored above the selection bar. */
    private static final String[] LABELS = {"选择同一天文件", "选择同一周文件", "选择同一月文件", "选择同一年文件",
            "按文件时间选择…", "按文件大小选择…", "按关键词选择…"};

    static AlertDialog show(Activity activity, List<FileStore.Node> entries, FileStore.Node reference,
                            Consumer<Result> done) {
        List<FileStore.Node> visible = new ArrayList<>(entries);
        LinearLayout options = body(activity);
        CheckBox filesOnly = checkbox(activity, "仅选文件", true);
        CheckBox append = checkbox(activity, "追加选择", false);
        options.addView(filesOnly); options.addView(append);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("更多")
                .setItems(LABELS, (d, which) -> pick(activity, visible, reference, which,
                        filesOnly.isChecked(), append.isChecked(), done))
                .setView(options).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                Math.min(activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 48),
                        Ui.dp(activity, 440)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }

    private static void pick(Activity activity, List<FileStore.Node> visible, FileStore.Node reference, int which,
                             boolean filesOnly, boolean append, Consumer<Result> done) {
        if (which < 4) {
            if (reference == null || reference.modified <= 0) {
                Tasks.toast(activity, "参照项的修改时间未知，请先选择其他文件");
                return;
            }
            int period = which == 0 ? 0 : which == 1 ? 3 : which == 2 ? 1 : 2;
            long[] range = dateRange(reference.modified, period);
            choose(activity, visible, node -> (!filesOnly || !node.directory)
                    && node.modified > 0 && node.modified >= range[0] && node.modified < range[1], append, done);
        } else if (which == 4) time(activity, visible, filesOnly, append, done);
        else if (which == 5) size(activity, visible, append, done);
        else keyword(activity, visible, filesOnly, append, done);
    }

    static long[] dateRange(long reference, int period) {
        LocalDate date = Instant.ofEpochMilli(reference).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate start = period == 1 ? date.withDayOfMonth(1) : period == 2 ? date.withDayOfYear(1)
                : period == 3 ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
        LocalDate end = period == 1 ? start.plusMonths(1) : period == 2 ? start.plusYears(1)
                : period == 3 ? start.plusWeeks(1) : start.plusDays(1);
        ZoneId zone = ZoneId.systemDefault();
        return new long[]{start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli()};
    }

    static long bytes(String text, int unit) {
        try {
            BigDecimal value = new BigDecimal(text.trim());
            if (value.signum() < 0 || unit < 0 || unit > 4) throw new ArithmeticException();
            return value.multiply(BigDecimal.valueOf(1024).pow(unit)).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("大小必须是非负数，换算后为整数 B，且不能超过 8 EiB");
        }
    }

    static long timeBound(String text, boolean upper) {
        try {
            String value = text.trim();
            ZoneId zone = ZoneId.systemDefault();
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
                return (upper ? date.plusDays(1) : date).atStartOfDay(zone).toInstant().toEpochMilli() - (upper ? 1 : 0);
            }
            long minute = LocalDateTime.parse(value, MINUTE).atZone(zone).toInstant().toEpochMilli();
            return upper ? minute + 59999 : minute;
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("时间格式：2026-11-04 或 2026-11-04 15:30");
        }
    }

    private static void keyword(Activity activity, List<FileStore.Node> visible, boolean filesOnly, boolean append, Consumer<Result> done) {
        LinearLayout body = body(activity);
        EditText input = Ui.input(activity, "文件名关键词"); input.setContentDescription("选择关键词");
        CheckBox sensitive = checkbox(activity, "区分大小写", false);
        body.addView(input); body.addView(sensitive);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("按关键词选择").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("选择", null).create();
        TextView preview = Ui.text(activity, "输入关键词后预览匹配数量", 12, Ui.MUTED); body.addView(preview);
        Runnable update = () -> {
            String value = input.getText().toString();
            dialog.getButton(-1).setEnabled(!value.isEmpty());
            preview.setText("匹配 " + matching(visible, keywordRule(value, sensitive.isChecked(), filesOnly)).size() + " 项");
        };
        MainActivity.watch(input, () -> { if (dialog.isShowing()) update.run(); });
        sensitive.setOnCheckedChangeListener((button, checked) -> { if (dialog.isShowing()) update.run(); });
        dialog.setOnShowListener(ignored -> {
            update.run(); dialog.getButton(-1).setOnClickListener(v -> {
                choose(activity, visible, keywordRule(input.getText().toString(), sensitive.isChecked(), filesOnly), append, done);
                dialog.dismiss();
            });
        }); dialog.show();
    }

    static Predicate<FileStore.Node> keywordRule(String keyword, boolean sensitive, boolean filesOnly) {
        String needle = sensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
        return node -> (!filesOnly || !node.directory) && !needle.isEmpty()
                && (sensitive ? node.name : node.name.toLowerCase(Locale.ROOT)).contains(needle);
    }

    private static void size(Activity activity, List<FileStore.Node> visible, boolean append, Consumer<Result> done) {
        LinearLayout body = body(activity);
        EditText lower = Ui.input(activity, "最小大小（留空不限）"), upper = Ui.input(activity, "最大大小（留空不限）");
        lower.setContentDescription("最小文件大小"); upper.setContentDescription("最大文件大小");
        lower.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        upper.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner unit = new Spinner(activity); unit.setContentDescription("文件大小单位");
        unit.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"B", "KiB", "MiB", "GiB", "TiB"}));
        body.addView(lower); body.addView(upper); body.addView(unit);
        body.addView(Ui.text(activity, "包含边界值，仅选择文件；1 KiB = 1024 B", 12, Ui.MUTED));
        TextView error = Ui.text(activity, "", 12, Ui.MUTED); body.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("按文件大小选择").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("选择", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                String min = lower.getText().toString().trim(), max = upper.getText().toString().trim();
                if (min.isEmpty() && max.isEmpty()) throw new IllegalArgumentException("请至少填写一个大小边界");
                long a = min.isEmpty() ? 0 : bytes(min, unit.getSelectedItemPosition());
                long b = max.isEmpty() ? Long.MAX_VALUE : bytes(max, unit.getSelectedItemPosition());
                if (a > b) throw new IllegalArgumentException("最小大小不能大于最大大小");
                choose(activity, visible, node -> !node.directory && node.size >= a && node.size <= b, append, done);
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        })); dialog.show();
    }

    private static void time(Activity activity, List<FileStore.Node> visible, boolean filesOnly, boolean append, Consumer<Result> done) {
        LinearLayout body = body(activity);
        EditText lower = Ui.input(activity, "起始时间（留空不限）"), upper = Ui.input(activity, "结束时间（留空不限）");
        lower.setContentDescription("选择起始时间"); upper.setContentDescription("选择结束时间");
        body.addView(lower); body.addView(upper);
        body.addView(Ui.text(activity, "修改时间，本地时区；格式 2026-11-04 或 2026-11-04 15:30\n结束日期包含整天，结束分钟包含整分钟", 12, Ui.MUTED));
        TextView error = Ui.text(activity, "", 12, Ui.MUTED); body.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("按修改时间选择").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("选择", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                String min = lower.getText().toString().trim(), max = upper.getText().toString().trim();
                if (min.isEmpty() && max.isEmpty()) throw new IllegalArgumentException("请至少填写一个时间边界");
                long a = min.isEmpty() ? Long.MIN_VALUE : timeBound(min, false);
                long b = max.isEmpty() ? Long.MAX_VALUE : timeBound(max, true);
                if (a > b) throw new IllegalArgumentException("起始时间不能晚于结束时间");
                choose(activity, visible, node -> (!filesOnly || !node.directory)
                        && node.modified > 0 && node.modified >= a && node.modified <= b, append, done);
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        })); dialog.show();
    }

    static Set<String> matching(List<FileStore.Node> visible, Predicate<FileStore.Node> rule) {
        Set<String> keys = new HashSet<>();
        for (FileStore.Node node : visible) if (rule.test(node)) keys.add(node.key());
        return keys;
    }
    private static void choose(Activity activity, List<FileStore.Node> visible, Predicate<FileStore.Node> rule,
                               boolean append, Consumer<Result> done) {
        Set<String> keys = matching(visible, rule);
        if (keys.isEmpty()) { Tasks.toast(activity, "没有匹配项，保留当前选择"); return; }
        done.accept(new Result(keys, append));
        Tasks.toast(activity, "已匹配 " + keys.size() + " 项");
    }
    private static CheckBox checkbox(Activity activity, String label, boolean checked) {
        CheckBox check = new CheckBox(activity); check.setText(label); check.setTextColor(Ui.TEXT);
        check.setTextSize(12); check.setChecked(checked); check.setContentDescription(label); return check;
    }
    private static LinearLayout body(Activity activity) {
        LinearLayout body = Ui.column(activity); int padding = Ui.dp(activity, 16);
        body.setPadding(padding, Ui.dp(activity, 8), padding, Ui.dp(activity, 8)); return body;
    }
    private SelectionTools() { }
}
