package de.cgoit.faktura.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts the simple markup of header and footer texts to XHTML. Supported: {@code **bold**}, {@code *italic*},
 * {@code __underline__}, bullet lists ({@code - item}), numbered lists ({@code 1. item}), indentation by leading spaces
 * (two spaces per list level) and paragraphs separated by blank lines. A backslash prints the next markup character
 * literally. Everything else is plain text; HTML in the input is escaped, so the result is always well-formed and safe.
 * A marker without a matching partner is printed as it is.
 */
public final class RichText {

    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)(?:([-*•])|(\\d+[.)]))\\s+(.*)$");
    private static final int SPACES_PER_LEVEL = 2;
    private static final char NON_BREAKING_SPACE = ' ';

    private RichText() {
    }

    /**
     * Escapes the markup characters of a value so that it is printed as it is, e.g. a customer name with an asterisk.
     */
    public static String escape(String value) {
        if (value == null) {
            return null;
        }

        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_");
    }

    /**
     * Converts the text to XHTML block elements (paragraphs and lists). A {@code null} or blank text gives an empty
     * string.
     */
    public static String toXhtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        var html = new StringBuilder();
        var paragraph = new ArrayList<String>();
        var lists = new ArrayDeque<String>();

        for (var line : text.strip().split("\\R", -1)) {
            if (line.isBlank()) {
                flushParagraph(html, paragraph);
                closeLists(html, lists, 0);

                continue;
            }

            var item = LIST_ITEM.matcher(line.replace("\t", "  "));

            if (item.matches() && !startsWithBold(line)) {
                flushParagraph(html, paragraph);
                openListItem(html, lists, item.group(1).length() / SPACES_PER_LEVEL + 1,
                        item.group(3) != null ? "ol" : "ul");
                html.append("<li>").append(inline(item.group(4))).append("</li>");

                continue;
            }

            closeLists(html, lists, 0);
            paragraph.add(indentation(line) + inline(line.strip()));
        }

        flushParagraph(html, paragraph);
        closeLists(html, lists, 0);

        return html.toString();
    }

    /**
     * Converts the text to plain text for the text part of a mail: the markers of bold, italic and underline vanish,
     * escaped characters are printed as they are, list items keep their bullet or number, and paragraphs are
     * separated by one blank line. A {@code null} or blank text gives an empty string.
     */
    public static String toPlainText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        var lines = new ArrayList<String>();

        for (var line : text.strip().split("\\R", -1)) {
            if (line.isBlank()) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                    lines.add("");
                }

                continue;
            }

            var item = LIST_ITEM.matcher(line.replace("\t", "  "));

            if (item.matches() && !startsWithBold(line)) {
                var bullet = item.group(3) != null ? item.group(3) : "-";

                lines.add(item.group(1) + bullet + " " + plainInline(item.group(4)));
            } else {
                lines.add(indentation(line).replace(NON_BREAKING_SPACE, ' ') + plainInline(line.strip()));
            }
        }

        return String.join("\n", lines);
    }

    /**
     * Removes the markers of one line and resolves escaped characters.
     */
    static String plainInline(String text) {
        var tokens = tokenize(text);
        pair(tokens, text);

        var plain = new StringBuilder();

        for (var token : tokens) {
            if (token.kind == Kind.TEXT || !token.matched) {
                plain.append(token.text);
            }
        }

        return plain.toString();
    }

    private static boolean startsWithBold(String line) {
        return line.stripLeading().startsWith("**");
    }

    private static String indentation(String line) {
        var spaces = 0;

        while (spaces < line.length() && (line.charAt(spaces) == ' ' || line.charAt(spaces) == '\t')) {
            spaces += line.charAt(spaces) == '\t' ? SPACES_PER_LEVEL : 1;
        }

        return String.valueOf(NON_BREAKING_SPACE).repeat(spaces);
    }

    private static void flushParagraph(StringBuilder html, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }

        html.append("<p>").append(String.join("<br/>", paragraph)).append("</p>");
        paragraph.clear();
    }

    private static void openListItem(StringBuilder html, ArrayDeque<String> lists, int level, String type) {
        closeLists(html, lists, level);

        if (lists.size() == level && !lists.peek().equals(type)) {
            html.append("</").append(lists.pop()).append(">");
        }

        while (lists.size() < level) {
            lists.push(type);
            html.append("<").append(type).append(">");
        }
    }

    /**
     * Closes lists until at most {@code level} remain open.
     */
    private static void closeLists(StringBuilder html, ArrayDeque<String> lists, int level) {
        while (lists.size() > level) {
            html.append("</").append(lists.pop()).append(">");
        }
    }

    /**
     * Converts bold, italic and underline markers inside one line.
     */
    static String inline(String text) {
        var tokens = tokenize(text);
        pair(tokens, text);

        var html = new StringBuilder();

        for (var token : tokens) {
            switch (token.kind) {
                case TEXT -> html.append(escapeXml(token.text));
                case MARKER -> html.append(token.matched ? tag(token) : escapeXml(token.text));
            }
        }

        return html.toString();
    }

    private static String tag(Token token) {
        var name = switch (token.text) {
            case "**" -> "b";
            case "__" -> "u";
            default -> "i";
        };

        return token.opening ? "<" + name + ">" : "</" + name + ">";
    }

    private static List<Token> tokenize(String text) {
        var tokens = new ArrayList<Token>();
        var plain = new StringBuilder();
        var i = 0;

        while (i < text.length()) {
            var c = text.charAt(i);

            if (c == '\\' && i + 1 < text.length() && "\\*_".indexOf(text.charAt(i + 1)) >= 0) {
                plain.append(text.charAt(i + 1));
                i += 2;

                continue;
            }

            if (text.startsWith("***", i)) {
                // bold and italic at once: the order decides how the markers nest when opening and closing
                var closing = i > 0 && !Character.isWhitespace(text.charAt(i - 1));

                if (!plain.isEmpty()) {
                    tokens.add(Token.text(plain.toString()));
                    plain.setLength(0);
                }

                tokens.add(Token.marker(closing ? "*" : "**", i));
                tokens.add(Token.marker(closing ? "**" : "*", i + (closing ? 1 : 2)));
                i += 3;

                continue;
            }

            String marker = null;

            if (text.startsWith("**", i)) {
                marker = "**";
            } else if (text.startsWith("__", i)) {
                marker = "__";
            } else if (c == '*') {
                marker = "*";
            }

            if (marker == null) {
                plain.append(c);
                i++;

                continue;
            }

            if (!plain.isEmpty()) {
                tokens.add(Token.text(plain.toString()));
                plain.setLength(0);
            }

            tokens.add(Token.marker(marker, i));
            i += marker.length();
        }

        if (!plain.isEmpty()) {
            tokens.add(Token.text(plain.toString()));
        }

        return tokens;
    }

    /**
     * Matches opening and closing markers like brackets; crossing or unmatched markers stay literal. An opening
     * marker must be followed and a closing marker preceded by a non-blank character.
     */
    private static void pair(List<Token> tokens, String source) {
        var open = new ArrayDeque<Token>();

        for (var token : tokens) {
            if (token.kind != Kind.MARKER) {
                continue;
            }

            var end = token.position + token.text.length();
            var canOpen = end < source.length() && !Character.isWhitespace(source.charAt(end));
            var canClose = token.position > 0 && !Character.isWhitespace(source.charAt(token.position - 1));
            var top = open.peek();

            if (top != null && top.text.equals(token.text) && canClose) {
                top.matched = true;
                top.opening = true;
                token.matched = true;
                token.opening = false;
                open.pop();
            } else if (canOpen && open.stream().noneMatch(other -> other.text.equals(token.text))) {
                open.push(token);
            }
        }
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private enum Kind {
        TEXT, MARKER
    }

    private static final class Token {

        private final Kind kind;
        private final String text;
        private final int position;
        private boolean matched;
        private boolean opening;

        private Token(Kind kind, String text, int position) {
            this.kind = kind;
            this.text = text;
            this.position = position;
        }

        static Token text(String text) {
            return new Token(Kind.TEXT, text, -1);
        }

        static Token marker(String text, int position) {
            return new Token(Kind.MARKER, text, position);
        }
    }
}
