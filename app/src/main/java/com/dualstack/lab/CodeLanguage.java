package com.dualstack.lab;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.*;
import io.github.rosemoe.sora.lang.brackets.PairedBracket;
import io.github.rosemoe.sora.lang.styling.*;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.*;
import java.util.regex.*;

/** Lightweight per-file-type highlighting; every line is tokenized only up to {@link #LINE_LIMIT} characters. */
final class CodeLanguage extends EmptyLanguage {
    static final int LINE_LIMIT = 4096;
    private static final int BRACKET_SCAN = 20000;

    enum Kind {
        GENERIC(Rules.GENERIC, "/*", "*/", "()[]{}\"\"''"),
        CODE(Rules.CODE, "/*", "*/", "()[]{}\"\"''"),
        SHELL(Rules.SHELL, null, null, "()[]{}\"\"''"),
        MARKUP(Rules.MARKUP, "<!--", "-->", "\"\"''"),
        JSON(Rules.JSON, null, null, "[]{}\"\""),
        SMALI(Rules.SMALI, null, null, "(){}\"\""),
        CONFIG(Rules.CONFIG, null, null, "[]\"\""),
        PLAIN(null, null, null, "");
        final Rules rules; final String blockOpen, blockClose, pairs;
        Kind(Rules rules, String blockOpen, String blockClose, String pairs) {
            this.rules = rules; this.blockOpen = blockOpen; this.blockClose = blockClose; this.pairs = pairs;
        }
    }

    /** Ordered alternatives; group N is painted with colors[N - 1]. */
    private record Rules(Pattern pattern, int[] colors) {
        static Rules of(Object... parts) {
            StringBuilder regex = new StringBuilder(); int[] colors = new int[parts.length / 2];
            for (int i = 0; i < parts.length; i += 2) {
                if (i > 0) regex.append('|');
                regex.append('(').append(parts[i]).append(')'); colors[i / 2] = (Integer) parts[i + 1];
            }
            return new Rules(Pattern.compile(regex.toString()), colors);
        }
        static final int C = EditorColorScheme.COMMENT, K = EditorColorScheme.KEYWORD, L = EditorColorScheme.LITERAL,
                S = EditorColorScheme.ATTRIBUTE_VALUE, T = EditorColorScheme.HTML_TAG, A = EditorColorScheme.ATTRIBUTE_NAME,
                V = EditorColorScheme.IDENTIFIER_VAR, F = EditorColorScheme.FUNCTION_NAME, N = EditorColorScheme.ANNOTATION;
        static final Rules GENERIC = of(
                "//.*|#.*|/\\*.*?(?:\\*/|$)|<!--.*?(?:-->|$)", C,
                "\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'", S,
                "\\b(?:class|public|private|static|void|return|if|else|new|true|false|null|fun|val|var|import|package|"
                        + "extends|implements|try|catch|const|let|def|for|while)\\b", K,
                "\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b", L,
                "</?[\\w:.-]+|\\b[\\w:-]+(?=\\s*=)", T);
        static final Rules CODE = of(
                "//.*|/\\*.*?(?:\\*/|$)", C,
                "\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'|`(?:\\\\.|[^`\\\\])*+`", S,
                "@[A-Za-z_][\\w.]*", N,
                "\\b(?:abstract|as|async|await|break|case|catch|class|const|continue|data|def|default|do|else|enum|export|"
                        + "extends|final|finally|for|from|fun|func|function|if|implements|import|in|interface|is|let|"
                        + "namespace|new|object|override|package|private|protected|public|return|sealed|static|struct|"
                        + "super|switch|this|throw|throws|try|typeof|val|var|when|while|yield)\\b", K,
                "\\b(?:true|false|null|nil|None|True|False|undefined)\\b", L,
                "\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)[LlFfDdUu]*\\b", L,
                "\\b[A-Za-z_]\\w*(?=\\s*\\()", F);
        static final Rules SHELL = of(
                "(?<![\\w$#{])#.*", C,
                "\"(?:\\\\.|[^\"\\\\])*+\"|'[^']*+'", S,
                "\\$\\{[^}\\s]*\\}|\\$[A-Za-z_]\\w*|\\$[0-9#?@*$!-]", V,
                "\\b(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|in|function|return|exit|local|export|"
                        + "readonly|shift|break|continue|source|set|unset|trap|eval|exec)\\b", K,
                "\\b\\d+\\b", L,
                "\\b[A-Za-z_][\\w-]*(?=\\s*\\(\\s*\\))", F);
        static final Rules MARKUP = of(
                "<!--.*?(?:-->|$)", C,
                "<!\\[CDATA\\[|\\]\\]>|<\\?[\\w:.-]*|\\?>|</?[A-Za-z_][\\w:.-]*|/?>", T,
                "\\b[A-Za-z_][\\w:.-]*(?=\\s*=)", A,
                "\"[^\"]*+\"|'[^']*+'", S,
                "&#?\\w+;", L);
        static final Rules JSON = of(
                "\"(?:\\\\.|[^\"\\\\])*+\"(?=\\s*:)", A,
                "\"(?:\\\\.|[^\"\\\\])*+\"", S,
                "\\b(?:true|false|null)\\b", K,
                "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b", L);
        static final Rules SMALI = of(
                "\"(?:\\\\.|[^\"\\\\])*+\"", S,
                "#.*", C,
                "\\.[a-z][\\w-]*", K,
                "\\b[vp]\\d+\\b", V,
                "\\[*L[\\w/$-]+;", T,
                ":[\\w$-]+", N,
                "-?\\b(?:0x[0-9a-fA-F]+|\\d+)[LlSsTt]?\\b", L,
                "\\b(?:public|private|protected|static|final|abstract|synthetic|constructor|bridge|varargs|native|"
                        + "interface|enum|annotation|volatile|transient)\\b", K,
                "(?<=^\\s{0,32})[a-z][a-z0-9-]*(?:/[a-z0-9-]+)*", F);
        static final Rules CONFIG = of(
                "(?<=^\\s{0,32})[#;!].*", C,
                "(?<=^\\s{0,32})\\[[^\\]]*\\]", K,
                "(?<=^\\s{0,32}-?\\s{0,8})[\\w.@/$-]+(?=\\s*[=:])", A,
                "\"(?:\\\\.|[^\"\\\\])*+\"|'[^']*+'", S,
                "(?i)\\b(?:true|false|yes|no|on|off|null|none)\\b", K,
                "-?\\b\\d+(?:\\.\\d+)?\\b", L);
    }

    final Kind kind;
    private final Analyzer analyzer;

    CodeLanguage() { this(Kind.GENERIC); }
    CodeLanguage(Kind kind) { this.kind = kind; analyzer = new Analyzer(kind); }

    /** Chooses highlighting from the file name; names without a recognised extension keep the generic rules. */
    static Kind kindFor(String name) {
        if (name == null) return Kind.GENERIC;
        String lower = name.toLowerCase(Locale.ROOT);
        lower = lower.substring(Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('!')) + 1);
        if (lower.equals("makefile") || lower.equals("dockerfile")) return Kind.SHELL;
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "sh", "bash", "zsh", "ksh", "rc", "profile", "bashrc", "zshrc", "mk", "cmake" -> Kind.SHELL;
            case "xml", "html", "htm", "xhtml", "svg", "plist", "xsd", "xsl", "xslt", "vue" -> Kind.MARKUP;
            case "json", "jsonc", "json5", "arsc" -> Kind.JSON;
            case "smali" -> Kind.SMALI;
            case "java", "kt", "kts", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "cxx", "hpp",
                    "cs", "go", "rs", "swift", "dart", "gradle", "groovy", "scala", "php", "css", "scss", "less",
                    "aidl", "proto" -> Kind.CODE;
            case "ini", "cfg", "conf", "properties", "prop", "toml", "yaml", "yml", "env", "desktop", "rules" -> Kind.CONFIG;
            case "txt", "log", "md", "csv", "tsv", "srt", "lrc" -> Kind.PLAIN;
            default -> Kind.GENERIC;
        };
    }

    @Override public AnalyzeManager getAnalyzeManager() { return analyzer; }
    @Override public SymbolPairMatch getSymbolPairs() {
        if (kind.pairs.isEmpty()) return EMPTY_SYMBOL_PAIRS;
        SymbolPairMatch pairs = new SymbolPairMatch();
        for (int i = 0; i < kind.pairs.length(); i += 2) {
            char open = kind.pairs.charAt(i), close = kind.pairs.charAt(i + 1);
            pairs.putPair(open, new SymbolPairMatch.SymbolPair(String.valueOf(open), String.valueOf(close)));
        }
        return pairs;
    }

    /** Finds the bracket adjacent to {@code index} and its partner within a bounded scan; quotes are not tracked. */
    static PairedBracket pairAt(Content text, int index) {
        if (index > 0) { PairedBracket before = pairFrom(text, index - 1); if (before != null) return before; }
        return index < text.length() ? pairFrom(text, index) : null;
    }
    private static PairedBracket pairFrom(Content text, int index) {
        CharPosition at = text.getIndexer().getCharPosition(index);
        ContentLine line = text.getLine(at.line);
        if (at.column >= line.length()) return null;
        char c = line.charAt(at.column);
        int kindIndex = "([{)]}".indexOf(c);
        if (kindIndex < 0) return null;
        boolean forward = kindIndex < 3;
        char open = "([{".charAt(kindIndex % 3), close = ")]}".charAt(kindIndex % 3);
        int depth = 0, budget = BRACKET_SCAN, row = at.line, column = at.column;
        while (budget-- > 0) {
            ContentLine current = text.getLine(row);
            if (column >= 0 && column < current.length()) {
                char value = current.charAt(column);
                if (value == open) depth += forward ? 1 : -1;
                else if (value == close) depth += forward ? -1 : 1;
                if (depth == 0) {
                    int other = text.getCharIndex(row, column);
                    return forward ? new PairedBracket(index, other) : new PairedBracket(other, index);
                }
            }
            if (forward) {
                if (++column >= current.length()) { if (++row >= text.getLineCount()) return null; column = 0; budget--; }
            } else if (--column < 0) {
                if (--row < 0) return null;
                column = text.getLine(row).length() - 1; budget--;
            }
        }
        return null;
    }

    private static final class Analyzer extends AsyncIncrementalAnalyzeManager<Integer, Span> {
        private final Kind kind;
        Analyzer(Kind kind) { this.kind = kind; }
        @Override public void setReceiver(StyleReceiver receiver) { super.setReceiver(receiver); publishBrackets(); }
        @Override public void reset(ContentReference content, android.os.Bundle extras) { super.reset(content, extras); publishBrackets(); }
        private void publishBrackets() {
            StyleReceiver receiver = getReceiver();
            if (receiver != null && kind != Kind.PLAIN) receiver.updateBracketProvider(this, CodeLanguage::pairAt);
        }
        public Integer getInitialState() { return 0; }
        public boolean stateEquals(Integer a, Integer b) { return Objects.equals(a, b); }
        public LineTokenizeResult<Integer, Span> tokenizeLine(CharSequence line, Integer state, int index) {
            List<Span> spans = new ArrayList<>();
            spans.add(Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            if (kind.rules == null) return new LineTokenizeResult<>(0, spans, spans);
            String s = line.subSequence(0, Math.min(line.length(), LINE_LIMIT)).toString();
            int start = 0, next = 0;
            if (state != 0 && kind.blockClose != null) {
                int end = s.indexOf(kind.blockClose);
                spans.set(0, Span.obtain(0, comment()));
                if (end < 0) return new LineTokenizeResult<>(state, spans, spans);
                start = end + kind.blockClose.length();
                spans.add(Span.obtain(start, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            }
            Matcher m = kind.rules.pattern().matcher(s);
            m.useTransparentBounds(true).useAnchoringBounds(false).region(start, s.length());
            while (m.find()) {
                if (m.end() == m.start()) continue;
                int group = 1; while (m.group(group) == null) group++;
                int color = kind.rules.colors()[group - 1];
                spans.add(Span.obtain(m.start(), color == EditorColorScheme.COMMENT ? comment() : TextStyle.makeStyle(color)));
                spans.add(Span.obtain(m.end(), TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
                String token = m.group();
                if (kind.blockOpen != null && token.startsWith(kind.blockOpen) && !token.endsWith(kind.blockClose)) next = 1;
            }
            if (line.length() > s.length()) spans.add(Span.obtain(s.length(), TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)));
            return new LineTokenizeResult<>(next, spans, spans);
        }
        private static long comment() { return TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false); }
        public List<Span> generateSpansForLine(LineTokenizeResult<Integer, Span> line) { return line.tokens; }
        public List<CodeBlock> computeBlocks(Content text, CodeBlockAnalyzeDelegate delegate) { return Collections.emptyList(); }
    }
}
