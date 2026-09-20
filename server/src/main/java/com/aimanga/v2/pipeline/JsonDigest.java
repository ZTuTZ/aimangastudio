package com.aimanga.v2.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Stable JSON digest independent of MySQL JSON object-key normalization. */
public final class JsonDigest {
    private JsonDigest() { }

    public static String sha256(JsonNode node) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(node).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                String name = names.get(i);
                out.append(JsonNodeFactoryHolder.quote(name)).append(':').append(canonical(node.get(name)));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(node.get(i)));
            }
            return out.append(']').toString();
        }
        return node.toString();
    }

    private static final class JsonNodeFactoryHolder {
        private static String quote(String value) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(value).toString();
        }
    }
}
