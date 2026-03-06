package com.rorm.dataimport.naming;

import lombok.Getter;

import java.util.Arrays;
import java.util.regex.Pattern;

public enum NamingStyle {
    CAMEL_CASE(Pattern.compile("^[a-z][a-zA-Z0-9]*$"), ""),
    PASCAL_CASE(Pattern.compile("^[A-Z][a-zA-Z0-9]*$"), ""),
    SNAKE_CASE(Pattern.compile("^[a-z][a-z0-9_]*$"), "_"),
    KEBAB_CASE(Pattern.compile("^[a-z][a-z0-9-]*$"), "-");

    private final Pattern pattern;
    @Getter
    private final String separator;

    NamingStyle(Pattern pattern, String separator) {
        this.pattern = pattern;
        this.separator = separator;
    }

    public boolean matches(String name) {
        return pattern.matcher(name).matches();
    }

    public String[] split(String name) {
        return switch (this) {
            case CAMEL_CASE, PASCAL_CASE -> splitByCaseChange(name);
            case SNAKE_CASE, KEBAB_CASE -> name.split(Pattern.quote(separator));
        };
    }

    private String[] splitByCaseChange(String name) {
        return name.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    }

    public String join(String... parts) {
        return switch (this) {
            case CAMEL_CASE, PASCAL_CASE -> {
                var capitalizedParts = Arrays.stream(parts).map(NamingStyle::capitalize).toArray(String[]::new);
                capitalizedParts[0] = this == CAMEL_CASE ? capitalizedParts[0].toLowerCase() : capitalize(capitalizedParts[0]);
                yield String.join("", capitalizedParts);
            }
            case SNAKE_CASE, KEBAB_CASE -> String.join(separator, parts).toLowerCase();
        };
    }

    private static String capitalize(String str) {
        return str.isEmpty() ? str : Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public String forceAdjust(String s) {
        var matchingStyle = Arrays.stream(NamingStyle.values())
            .filter(style -> style.matches(s))
            .findFirst();
        return matchingStyle.map(style -> this.join(style.split(s))).orElse(s);
    }
}
