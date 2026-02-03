package com.rorm.dataimport.naming;

import lombok.Getter;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
            case CAMEL_CASE, PASCAL_CASE -> toCamelCase(parts);
            case SNAKE_CASE, KEBAB_CASE -> String.join(separator, parts).toLowerCase();
        };
    }

    public static String toCamelCase(String[] parts) {
        if (parts.length == 0) {
            return "";
        }

        return Stream.concat(
            Stream.of(parts[0].toLowerCase()),
            Arrays.stream(parts, 1, parts.length)
                .map(String::toLowerCase)
                .map(NamingStyle::capitalize)
        ).collect(Collectors.joining());
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
