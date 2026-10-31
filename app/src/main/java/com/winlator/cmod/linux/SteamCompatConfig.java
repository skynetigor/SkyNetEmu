package com.winlator.cmod.linux;

import java.util.ArrayList;
import java.util.List;

/** Edits only a game's compatibility mapping, retaining Steam's other configuration verbatim. */
public final class SteamCompatConfig {
    private SteamCompatConfig() {}

    public static String selectNativeTool(String text, String appId) {
        if (!appId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid Steam app id");
        Parser parser = new Parser(text);
        Node root = parser.object(null);
        String[] path = {"InstallConfigStore", "Software", "Valve", "Steam", "CompatToolMapping", appId};
        Node parent = root;
        int index = 0;
        for (; index < path.length; index++) {
            Node child = parent.child(path[index]);
            if (child == null) break;
            if (child.children == null) throw new IllegalArgumentException("Steam mapping path is not an object");
            parent = child;
        }
        // An explicit choice (including Proton) belongs to the user.
        if (index == path.length) return text;
        String entry = "\"name\" \"skynet-linux\"\n\"config\" \"\"\n\"priority\" \"250\"\n";
        for (int i = path.length - 1; i >= index; i--) {
            entry = "\"" + path[i] + "\"\n{\n" + entry + "}\n";
        }
        return text.substring(0, parent.close) + "\n" + entry + text.substring(parent.close);
    }

    private static final class Node {
        final String key;
        final List<Node> children;
        final int close;

        Node(String key, List<Node> children, int close) {
            this.key = key;
            this.children = children;
            this.close = close;
        }

        Node child(String name) {
            Node found = null;
            for (Node node : children) {
                if (!name.equalsIgnoreCase(node.key)) continue;
                if (found != null) throw new IllegalArgumentException("Duplicate Steam configuration key: " + name);
                found = node;
            }
            return found;
        }
    }

    private static final class Parser {
        final String text;
        int position;

        Parser(String text) {
            this.text = text;
        }

        Node object(String key) {
            List<Node> children = new ArrayList<>();
            while (true) {
                skip();
                int start = position;
                if (position == text.length()) {
                    if (key != null) throw new IllegalArgumentException("Unclosed Steam configuration object");
                    return new Node(null, children, position);
                }
                if (text.charAt(position) == '}') {
                    if (key == null) throw new IllegalArgumentException("Unexpected Steam configuration brace");
                    position++;
                    return new Node(key, children, start);
                }
                String name = token();
                skip();
                if (position < text.length() && text.charAt(position) == '{') {
                    position++;
                    children.add(object(name));
                } else {
                    token();
                    children.add(new Node(name, null, -1));
                }
            }
        }

        void skip() {
            while (position < text.length()) {
                if (Character.isWhitespace(text.charAt(position))) position++;
                else if (text.startsWith("//", position)) {
                    while (position < text.length() && text.charAt(position) != '\n') position++;
                } else break;
            }
        }

        String token() {
            skip();
            if (position == text.length()) throw new IllegalArgumentException("Missing Steam configuration value");
            StringBuilder value = new StringBuilder();
            if (text.charAt(position) == '"') {
                position++;
                while (position < text.length()) {
                    char c = text.charAt(position++);
                    if (c == '"') return value.toString();
                    if (c == '\\' && position < text.length()
                            && (text.charAt(position) == '"' || text.charAt(position) == '\\')) {
                        c = text.charAt(position++);
                    }
                    value.append(c);
                }
                throw new IllegalArgumentException("Unclosed Steam configuration string");
            }
            while (position < text.length()) {
                char c = text.charAt(position);
                if (Character.isWhitespace(c) || c == '{' || c == '}') break;
                value.append(c);
                position++;
            }
            if (value.length() == 0) throw new IllegalArgumentException("Unexpected Steam configuration token");
            return value.toString();
        }
    }
}
