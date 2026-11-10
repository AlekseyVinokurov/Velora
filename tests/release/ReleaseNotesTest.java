import app.nimarkogram.messenger.updater.VeloraReleaseNotes;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReleaseNotesTest {
    private static int checks;
    private static void equal(String expected, String body) {
        String actual = VeloraReleaseNotes.summary(body);
        if (!expected.equals(actual)) throw new AssertionError("Expected: " + expected + "; got: " + actual);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        equal("", null);
        equal("", "");
        equal("", "Full automatic changelog\n- Improve something\nSHA-256: deadbeef");
        equal("• Убраны лишние значки.", "## Что нового\r\n- Убраны лишние значки.\r\n## Проверки\n- Служебный отчёт.");
        equal("• A\n• B\n• C\n• D", "## What's new\n- A\n- B\n- C\n- D\n- E");
        equal("• Новая иконка.", "## Что нового\n```\n- Служебный отчёт.\n```\n- Новая иконка.");
        equal("• Новая иконка.", "<!--\n## Что нового\n- Скрытые данные.\n-->\n## Что нового\n- Новая иконка.\n<!-- velora-promotion: internal -->");
        equal("• Новая иконка.", "## Что нового\n- Новая <!-- hidden -->иконка.");
        equal("", "## Что нового\n<!-- secret\n- Hidden\n");
        for (String text : new String[]{"CI passed", "Actions #123", "SHA-256 0123456789abcdef",
                "Commit abcdef1", "Firebase config", "applicationId io.velora", "versionCode 100003",
                "Build verified", "Сборка проверена", "Хеш проверен", "https://example.org",
                "[Details](url)", "<b>hidden</b>", "`internal`", "git changelog"}) {
            equal("", "## Что нового\n- " + text);
        }
        equal("", "## Что нового\n- " + "x".repeat(32768));
        String longText = VeloraReleaseNotes.summary("## Что нового\n- " + "😀".repeat(300));
        if (longText.codePointCount(0, longText.length()) != 162 || !longText.endsWith("…"))
            throw new AssertionError("Long Unicode note not bounded correctly");
        checks++;
        String notes = VeloraReleaseNotes.summary(Files.readString(Path.of(args[0])));
        if (notes.lines().count() != 4 || !notes.contains("самолётик"))
            throw new AssertionError("0.1.3 notes not shown completely");
        checks++;
        System.out.println("Release notes: " + checks + " host assertions passed");
    }
}
