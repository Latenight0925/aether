package dev.aether.modules.pest.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PestTrapTabParser {
    private static final Pattern TRAP_ID_PATTERN = Pattern.compile("#(\\d+)");

    private PestTrapTabParser() {
    }

    static List<Integer> parseTrapIds(List<String> lines, Pattern headingPattern) {
        List<Integer> trapIds = new ArrayList<>();
        for (String line : lines) {
            Matcher heading = headingPattern.matcher(line);
            if (!heading.find()) {
                continue;
            }

            Matcher idMatcher = TRAP_ID_PATTERN.matcher(heading.group(1));
            while (idMatcher.find()) {
                trapIds.add(Integer.parseInt(idMatcher.group(1)));
            }
        }
        return trapIds;
    }
}
