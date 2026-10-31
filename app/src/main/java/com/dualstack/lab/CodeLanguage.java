package com.dualstack.lab;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.*;
import io.github.rosemoe.sora.lang.styling.*;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.*;
import java.util.regex.*;

final class CodeLanguage extends EmptyLanguage {
    private final Analyzer analyzer = new Analyzer();
    @Override public AnalyzeManager getAnalyzeManager() { return analyzer; }
    private static final class Analyzer extends AsyncIncrementalAnalyzeManager<Integer, Span> {
        private final Pattern tokens = Pattern.compile(
                "(//.*|#.*|/\\*.*?(?:\\*/|$)|<!--.*?(?:-->|$))"
                + "|(\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+')"
                + "|(\\b(?:class|public|private|static|void|return|if|else|new|true|false|null|"
                + "fun|val|var|import|package|extends|implements|try|catch|const|let|def|for|while)\\b)"
                + "|(\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b)"
                + "|(</?[\\w:.-]+|\\.[a-z][\\w-]*|\\b[vp]\\d+\\b|\\b[\\w:-]+(?=\\s*=))");
        public Integer getInitialState() { return 0; }
        public boolean stateEquals(Integer a, Integer b) { return Objects.equals(a, b); }
        public LineTokenizeResult<Integer, Span> tokenizeLine(CharSequence line, Integer state, int index) {
            String s = line.subSequence(0, Math.min(line.length(), 4096)).toString();
            List<Span> spans = new ArrayList<>(); int start = 0, next = 0;
            spans.add(Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            if (state != 0) {
                String close = state == 1 ? "*/" : "-->"; int end = s.indexOf(close);
                spans.set(0, Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.COMMENT)));
                if (end < 0) return new LineTokenizeResult<>(state, spans, spans);
                start = end + close.length(); spans.add(Span.obtain(start, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            }
            Matcher m = tokens.matcher(s); m.region(start, s.length());
            while (m.find()) {
                int color = m.group(1) != null ? EditorColorScheme.COMMENT : m.group(2) != null ?
                        EditorColorScheme.LITERAL : m.group(3) != null ? EditorColorScheme.KEYWORD :
                        m.group(4) != null ? EditorColorScheme.LITERAL : EditorColorScheme.HTML_TAG;
                spans.add(Span.obtain(m.start(), TextStyle.makeStyle(color)));
                spans.add(Span.obtain(m.end(), TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
                String token = m.group();
                if (token.startsWith("/*") && !token.endsWith("*/")) next = 1;
                if (token.startsWith("<!--") && !token.endsWith("-->")) next = 2;
            }
            if (line.length() > s.length()) spans.add(Span.obtain(s.length(), TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            return new LineTokenizeResult<>(next, spans, spans);
        }
        public List<Span> generateSpansForLine(LineTokenizeResult<Integer, Span> line) { return line.tokens; }
        public List<CodeBlock> computeBlocks(Content text, CodeBlockAnalyzeDelegate delegate) { return Collections.emptyList(); }
    }
}
