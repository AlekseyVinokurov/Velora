package app.nimarkogram.messenger.updater;

import java.util.Locale;
import java.util.regex.Pattern;

/** UI-only projection of an explicit user-facing section; never displays a raw release body. */
public final class VeloraReleaseNotes {
    private static final int MAX_BODY = 32768;
    private static final int MAX_ITEM = 160;
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?iu)(https?://|www\\.|`|\\b(?:ci|sha(?:-?256)?|firebase|applicationid|versioncode|"
                    + "commit|commits|actions?|workflow|gradle|certificate|checksum|artifact|fcm|"
                    + "build|apk|sdk|ndk|cmake|git|run|sourcecommit)\\b|"
                    + "[a-f0-9]{7,}|#[0-9]+|коммит|сертификат|хеш|хэш|подпис|сборк|артефакт)");

    private VeloraReleaseNotes() {}

    public static String summary(String body) {
        if (body == null || body.length() > MAX_BODY) return "";
        StringBuilder result = new StringBuilder();
        boolean inNotes = false;
        boolean inCode = false;
        boolean inComment = false;
        int count = 0;
        for (String raw : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            // Promotion receipts and other hidden metadata are never user-facing.
            StringBuilder visible = new StringBuilder();
            for (int i = 0; i < raw.length();) {
                if (inComment) {
                    int end = raw.indexOf("-->", i);
                    if (end < 0) break;
                    inComment = false;
                    i = end + 3;
                } else {
                    int start = raw.indexOf("<!--", i);
                    if (start < 0) { visible.append(raw.substring(i)); break; }
                    visible.append(raw, i, start);
                    inComment = true;
                    i = start + 4;
                }
            }
            String line = visible.toString().trim();
            if (line.startsWith("```") || line.startsWith("~~~")) { inCode = !inCode; continue; }
            if (inCode) continue;
            if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#{1,6}\\s*", "").toLowerCase(Locale.ROOT);
                if (inNotes) break;
                inNotes = heading.equals("что нового") || heading.equals("what's new")
                        || heading.equals("what’s new");
                continue;
            }
            if (!inNotes || !line.matches("^[-*•]\\s+.+")) continue;
            String item = line.replaceFirst("^[-*•]\\s+", "").replaceAll("\\s+", " ").trim();
            if (TECHNICAL.matcher(item).find() || item.contains("<") || item.contains(">")
                    || item.contains("[") || item.contains("]")) continue;
            if (item.codePointCount(0, item.length()) > MAX_ITEM) {
                int end = item.offsetByCodePoints(0, MAX_ITEM - 1);
                int space = item.lastIndexOf(' ', end);
                if (space > end / 2) end = space;
                item = item.substring(0, end).trim() + "…";
            }
            if (result.length() > 0) result.append('\n');
            result.append("• ").append(item);
            if (++count == 4) break;
        }
        return result.toString();
    }
}
