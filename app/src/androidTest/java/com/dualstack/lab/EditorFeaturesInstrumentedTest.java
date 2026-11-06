package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.brackets.PairedBracket;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorFeaturesInstrumentedTest {
    private Context context;
    private File work;
    private ActivityScenario<EditorActivity> scenario;
    private java.util.Set<String> draftsBefore;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "editor-features-").toFile();
        draftsBefore = drafts();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        EditorDrafts.WORKER.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        for (String name : drafts()) if (!draftsBefore.contains(name))
            Files.deleteIfExists(new File(context.getFilesDir(), "editor-drafts/" + name).toPath());
        new FileStore(context).delete(FileStore.local(work));
    }
    private java.util.Set<String> drafts() {
        String[] names = new File(context.getFilesDir(), "editor-drafts").list();
        return names == null ? new java.util.HashSet<>() : new java.util.HashSet<>(java.util.Arrays.asList(names));
    }

    @Test public void fileNamesSelectHighlightingKind() {
        for (CodeLanguage.Kind kind : CodeLanguage.Kind.values()) assertNotNull(new CodeLanguage(kind).getAnalyzeManager());
        assertEquals(CodeLanguage.Kind.SHELL, CodeLanguage.kindFor("install.sh"));
        assertEquals(CodeLanguage.Kind.MARKUP, CodeLanguage.kindFor("res/layout/main.xml"));
        assertEquals(CodeLanguage.Kind.JSON, CodeLanguage.kindFor("config.JSON"));
        assertEquals(CodeLanguage.Kind.SMALI, CodeLanguage.kindFor("smali/a/B.smali"));
        assertEquals(CodeLanguage.Kind.CODE, CodeLanguage.kindFor("Main.kt"));
        assertEquals(CodeLanguage.Kind.CONFIG, CodeLanguage.kindFor("gradle.properties"));
        assertEquals(CodeLanguage.Kind.PLAIN, CodeLanguage.kindFor("notes.txt"));
        assertEquals(CodeLanguage.Kind.GENERIC, CodeLanguage.kindFor("unknown.bin"));
    }
    @Test public void tokenizersColourEachLanguageDistinctly() {
        String shell = "if [ \"$HOME\" ]; then echo $# ${PATH} # note";
        assertEquals(EditorColorScheme.KEYWORD, color(CodeLanguage.Kind.SHELL, shell, 0));
        assertEquals(EditorColorScheme.ATTRIBUTE_VALUE, color(CodeLanguage.Kind.SHELL, shell, shell.indexOf('"')));
        assertEquals(EditorColorScheme.IDENTIFIER_VAR, color(CodeLanguage.Kind.SHELL, shell, shell.indexOf("$#")));
        assertEquals(EditorColorScheme.IDENTIFIER_VAR, color(CodeLanguage.Kind.SHELL, shell, shell.indexOf("${")));
        assertEquals(EditorColorScheme.COMMENT, color(CodeLanguage.Kind.SHELL, shell, shell.indexOf("# note")));

        String json = "{\"name\": \"value\", \"n\": 12, \"ok\": true}";
        assertEquals(EditorColorScheme.ATTRIBUTE_NAME, color(CodeLanguage.Kind.JSON, json, 1));
        assertEquals(EditorColorScheme.ATTRIBUTE_VALUE, color(CodeLanguage.Kind.JSON, json, json.indexOf("\"value\"")));
        assertEquals(EditorColorScheme.LITERAL, color(CodeLanguage.Kind.JSON, json, json.indexOf("12")));
        assertEquals(EditorColorScheme.KEYWORD, color(CodeLanguage.Kind.JSON, json, json.indexOf("true")));

        String xml = "<item android:name=\"a\">&amp;</item>";
        assertEquals(EditorColorScheme.HTML_TAG, color(CodeLanguage.Kind.MARKUP, xml, 0));
        assertEquals(EditorColorScheme.ATTRIBUTE_NAME, color(CodeLanguage.Kind.MARKUP, xml, xml.indexOf("android")));
        assertEquals(EditorColorScheme.ATTRIBUTE_VALUE, color(CodeLanguage.Kind.MARKUP, xml, xml.indexOf("\"a\"")));
        assertEquals(EditorColorScheme.LITERAL, color(CodeLanguage.Kind.MARKUP, xml, xml.indexOf("&amp;")));

        String smali = "    invoke-static {v0, p1}, Lfoo/Bar;->baz(I)V # call";
        assertEquals(EditorColorScheme.FUNCTION_NAME, color(CodeLanguage.Kind.SMALI, smali, smali.indexOf("invoke")));
        assertEquals(EditorColorScheme.IDENTIFIER_VAR, color(CodeLanguage.Kind.SMALI, smali, smali.indexOf("v0")));
        assertEquals(EditorColorScheme.HTML_TAG, color(CodeLanguage.Kind.SMALI, smali, smali.indexOf("Lfoo")));
        assertEquals(EditorColorScheme.COMMENT, color(CodeLanguage.Kind.SMALI, smali, smali.indexOf("# call")));

        String config = "[section]\nkey = \"text\"";
        assertEquals(EditorColorScheme.KEYWORD, color(CodeLanguage.Kind.CONFIG, "[section]", 0));
        assertEquals(EditorColorScheme.ATTRIBUTE_NAME, color(CodeLanguage.Kind.CONFIG, "key = \"text\"", 0));
        assertEquals(EditorColorScheme.COMMENT, color(CodeLanguage.Kind.CONFIG, "; disabled", 0));
        assertNotNull(config);

        String code = "@Override public int run() { return 0x1F; } // done";
        assertEquals(EditorColorScheme.ANNOTATION, color(CodeLanguage.Kind.CODE, code, 0));
        assertEquals(EditorColorScheme.KEYWORD, color(CodeLanguage.Kind.CODE, code, code.indexOf("public")));
        assertEquals(EditorColorScheme.FUNCTION_NAME, color(CodeLanguage.Kind.CODE, code, code.indexOf("run")));
        assertEquals(EditorColorScheme.LITERAL, color(CodeLanguage.Kind.CODE, code, code.indexOf("0x1F")));
        assertEquals(EditorColorScheme.COMMENT, color(CodeLanguage.Kind.CODE, code, code.indexOf("// done")));
        assertEquals(EditorColorScheme.TEXT_NORMAL, color(CodeLanguage.Kind.PLAIN, code, code.indexOf("public")));
    }
    @Test public void blockCommentsContinueAcrossLines() {
        IncrementalAnalyzeManager<Integer, Span> analyzer = analyzer(CodeLanguage.Kind.CODE);
        IncrementalAnalyzeManager.LineTokenizeResult<Integer, Span> first = analyzer.tokenizeLine("int a; /* start", 0, 0);
        assertEquals(1, (int) first.state);
        IncrementalAnalyzeManager.LineTokenizeResult<Integer, Span> second = analyzer.tokenizeLine("end */ return 1;", first.state, 1);
        assertEquals(0, (int) second.state);
        assertEquals(EditorColorScheme.COMMENT, colorIn(analyzer, second, 0));
        assertEquals(EditorColorScheme.KEYWORD, colorIn(analyzer, second, "end */ ".length()));
    }
    @Test public void bracketMatchingFindsPartnersAcrossLines() {
        Content text = new Content("f(a[1], {\n  b })");
        PairedBracket outer = CodeLanguage.pairAt(text, 2);
        assertNotNull(outer); assertEquals(1, outer.leftIndex); assertEquals(text.length() - 1, outer.rightIndex);
        PairedBracket brace = CodeLanguage.pairAt(text, text.toString().indexOf('{'));
        assertNotNull(brace); assertEquals(text.toString().lastIndexOf('}'), brace.rightIndex);
        PairedBracket square = CodeLanguage.pairAt(text, text.toString().indexOf(']') + 1);
        assertNotNull(square); assertEquals(text.toString().indexOf('['), square.leftIndex);
        assertNull(CodeLanguage.pairAt(new Content("plain words"), 3));
        assertNull(CodeLanguage.pairAt(new Content("(unclosed"), 1));
    }
    @Test public void codeFilesAutoCloseBracketsButPlainTextDoesNot() throws Exception {
        assertEquals("()", typeAfterLoad("Main.java", "("));
        assertEquals("{}", typeAfterLoad("data.json", "{"));
        assertEquals("(", typeAfterLoad("notes.txt", "("));
    }
    @Test public void symbolBarInsertsRawSymbolsOnlyWhenEditable() throws Exception {
        typeAfterLoad("Main.java", "");
        scenario.onActivity(activity -> {
            CodeEditor editor = (CodeEditor) get(activity, "editor");
            invoke(activity, "insertSymbol", "{"); invoke(activity, "insertSymbol", ";");
            assertEquals("Symbol keys insert exactly what they show", "{;", editor.getText().toString());
            editor.setEditable(false); invoke(activity, "insertSymbol", "(");
            assertEquals("{;", editor.getText().toString());
            LinearLayout symbols = (LinearLayout) get(activity, "symbols");
            assertEquals("Tab", ((TextView) symbols.getChildAt(0)).getText().toString());
            assertTrue(symbols.getChildCount() > 20);
        });
    }
    @Test public void inlineSearchCountsNavigatesReplacesAndClosesOnBack() throws Exception {
        File file = new File(work, "search.txt");
        Files.write(file.toPath(), "alpha beta\nalpha gamma\nALPHA\n".getBytes(StandardCharsets.UTF_8));
        open(file);
        scenario.onActivity(activity -> invoke(activity, "openSearch"));
        scenario.onActivity(activity -> ((EditText) get(activity, "findInput")).setText("alpha"));
        await(() -> text("matchCount").endsWith("/2"));
        scenario.onActivity(activity -> invoke(activity, "jumpMatch", true));
        await(() -> text("matchCount").equals("1/2"));
        scenario.onActivity(activity -> invoke(activity, "jumpMatch", true));
        await(() -> text("matchCount").equals("2/2"));
        scenario.onActivity(activity -> ((TextView) get(activity, "caseToggle")).performClick());
        await(() -> text("matchCount").endsWith("/3"));
        scenario.onActivity(activity -> ((TextView) get(activity, "regexToggle")).performClick());
        scenario.onActivity(activity -> ((EditText) get(activity, "findInput")).setText("al(pha"));
        await(() -> error("findInput") != null);
        scenario.onActivity(activity -> {
            ((EditText) get(activity, "findInput")).setText("^alpha");
            ((EditText) get(activity, "replaceInput")).setText("omega");
        });
        await(() -> text("matchCount").endsWith("/3"));
        scenario.onActivity(activity -> invoke(activity, "replaceAll"));
        await(() -> editorText().equals("omega beta\nomega gamma\nomega\n"));
        scenario.onActivity(activity -> invoke(activity, "handleBack"));
        scenario.onActivity(activity -> {
            assertEquals(View.GONE, ((View) get(activity, "searchBar")).getVisibility());
            assertFalse("Back closed search instead of leaving", activity.isFinishing());
        });
    }
    @Test public void readOnlyEditorHidesReplaceAndSymbols() throws Exception {
        File file = new File(work, "view.txt"); Files.write(file.toPath(), "abc abc".getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent); intent.putExtra("readOnly", true);
        scenario = ActivityScenario.launch(intent);
        await(() -> flag("loaded") && !flag("reading"));
        scenario.onActivity(activity -> {
            invoke(activity, "openSearch");
            assertEquals(View.GONE, ((View) get(activity, "replaceRow")).getVisibility());
            invoke(activity, "updateSymbolBar");
            assertEquals(View.GONE, ((View) ((View) get(activity, "symbols")).getParent()).getVisibility());
        });
    }
    @Test public void statusShowsCursorSelectionAndSizeAndJumpsToLine() throws Exception {
        File file = new File(work, "lines.ini");
        Files.write(file.toPath(), "a=1\nb=2\nc=3\n".getBytes(StandardCharsets.UTF_8));
        open(file);
        scenario.onActivity(activity -> ((CodeEditor) get(activity, "editor")).setSelection(1, 2));
        await(() -> text("position").equals("2:3"));
        scenario.onActivity(activity -> ((CodeEditor) get(activity, "editor")).setSelectionRegion(0, 0, 1, 1));
        await(() -> text("position").equals("选中 5"));
        assertTrue(text("info"), text("info").contains("12 B"));
        scenario.onActivity(activity -> invoke(activity, "gotoLine"));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 input = device.wait(Until.findObject(By.desc("目标行号")), 5000); assertNotNull(input);
        input.setText("3"); device.wait(Until.findObject(By.text("跳转")), 5000).click();
        await(() -> text("position").equals("3:1"));
    }
    @Test public void displayPreferencesPersistAcrossRecreation() throws Exception {
        context.getSharedPreferences("editor-display", 0).edit().clear().commit();
        try {
            File file = new File(work, "prefs.txt"); Files.write(file.toPath(), "text".getBytes(StandardCharsets.UTF_8));
            open(file);
            scenario.onActivity(activity -> {
                activity.getSharedPreferences("editor-display", 0).edit().putBoolean("wordwrap", true)
                        .putBoolean("monospace", true).commit();
                CodeEditor editor = (CodeEditor) get(activity, "editor");
                editor.setTextSize(20);
            });
            scenario.recreate();
            await(() -> flag("loaded") && !flag("reading"));
            scenario.onActivity(activity -> {
                CodeEditor editor = (CodeEditor) get(activity, "editor");
                assertTrue(editor.isWordwrap());
                assertEquals(android.graphics.Typeface.MONOSPACE, editor.getTypefaceText());
                float sp = editor.getTextSizePx() / (activity.getResources().getDisplayMetrics().density
                        * activity.getResources().getConfiguration().fontScale);
                assertEquals(20f, sp, .6f);
            });
        } finally { context.getSharedPreferences("editor-display", 0).edit().clear().commit(); }
    }
    @Test public void denseBinaryPageUsesPlainStylingAndNoWrap() throws Exception {
        context.getSharedPreferences("editor-display", 0).edit().putBoolean("wordwrap", true).commit();
        try {
            File file = new File(work, "payload.sh");
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 7 == 0 ? 'x' : i % 31);
            Files.write(file.toPath(), data);
            open(file);
            scenario.onActivity(activity -> {
                CodeEditor editor = (CodeEditor) get(activity, "editor");
                assertEquals(CodeLanguage.Kind.PLAIN, ((CodeLanguage) editor.getEditorLanguage()).kind);
                assertFalse(editor.isWordwrap());
            });
        } finally { context.getSharedPreferences("editor-display", 0).edit().clear().commit(); }
    }
    private void open(File file) {
        if (scenario != null) scenario.close();
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent);
        scenario = ActivityScenario.launch(intent);
        await(() -> flag("loaded") && !flag("reading") && editable());
    }
    private String text(String field) {
        String[] value = {""}; scenario.onActivity(activity -> value[0] = ((TextView) get(activity, field)).getText().toString()); return value[0];
    }
    private CharSequence error(String field) {
        CharSequence[] value = {null}; scenario.onActivity(activity -> value[0] = ((EditText) get(activity, field)).getError()); return value[0];
    }
    private String editorText() {
        String[] value = {""}; scenario.onActivity(activity -> value[0] = ((CodeEditor) get(activity, "editor")).getText().toString()); return value[0];
    }
    private static void invoke(Object target, String name, Object... arguments) {
        for (java.lang.reflect.Method method : target.getClass().getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try { method.setAccessible(true); method.invoke(target, arguments); return; }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
        throw new AssertionError("No method " + name);
    }
    private String typeAfterLoad(String name, String typed) throws Exception {
        if (scenario != null) scenario.close();
        File file = new File(work, name); Files.write(file.toPath(), new byte[0]);
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent);
        scenario = ActivityScenario.launch(intent);
        await(() -> flag("loaded") && !flag("reading") && editable());
        String[] result = {null};
        scenario.onActivity(activity -> {
            CodeEditor editor = (CodeEditor) get(activity, "editor");
            assertEquals(CodeLanguage.kindFor(name), ((CodeLanguage) editor.getEditorLanguage()).kind);
            editor.setSelection(0, 0); editor.commitText(typed); result[0] = editor.getText().toString();
        });
        return result[0];
    }
    private static int color(CodeLanguage.Kind kind, String line, int column) {
        IncrementalAnalyzeManager<Integer, Span> analyzer = analyzer(kind);
        return colorIn(analyzer, analyzer.tokenizeLine(line, 0, 0), column);
    }
    @SuppressWarnings("unchecked")
    private static IncrementalAnalyzeManager<Integer, Span> analyzer(CodeLanguage.Kind kind) {
        return (IncrementalAnalyzeManager<Integer, Span>) new CodeLanguage(kind).getAnalyzeManager();
    }
    private static int colorIn(IncrementalAnalyzeManager<Integer, Span> analyzer,
                               IncrementalAnalyzeManager.LineTokenizeResult<Integer, Span> result, int column) {
        List<Span> spans = analyzer.generateSpansForLine(result);
        long style = spans.get(0).getStyle();
        for (Span span : spans) if (span.getColumn() <= column) style = span.getStyle();
        return TextStyle.getForegroundColorId(style);
    }
    private boolean flag(String name) {
        boolean[] value = {false}; scenario.onActivity(activity -> value[0] = (boolean) get(activity, name)); return value[0];
    }
    private boolean editable() {
        boolean[] value = {false}; scenario.onActivity(activity -> value[0] = ((CodeEditor) get(activity, "editor")).isEditable()); return value[0];
    }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
        assertTrue("Timed out waiting for the editor", condition.getAsBoolean());
    }
}
