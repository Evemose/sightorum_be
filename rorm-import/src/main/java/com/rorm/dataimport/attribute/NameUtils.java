package com.rorm.dataimport.attribute;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.experimental.UtilityClass;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

@UtilityClass
public class NameUtils {

    private static final Cache<String, String> singularCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .recordStats()
        .build();

    private static final Cache<String, String> pluralCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .recordStats()
        .build();

    private static final Map<String, String> IRREGULAR_PLURALS = new HashMap<>() {{
        // Common irregulars
        put("children", "child");
        put("men", "man");
        put("women", "woman");
        put("teeth", "tooth");
        put("feet", "foot");
        put("geese", "goose");
        put("mice", "mouse");
        put("lice", "louse");
        put("oxen", "ox");
        put("people", "person");
        put("dice", "die");

        // Latin/Greek
        put("corpora", "corpus");
        put("corpuses", "corpus");
        put("genera", "genus");
        put("testes", "testis");
        put("atlases", "atlas");
        put("atlantes", "atlas");

        // Various
        put("loaves", "loaf");
        put("hooves", "hoof");
        put("hoofs", "hoof");
        put("thieves", "thief");
        put("mongooses", "mongoose");
        put("kine", "cow");

        // Uninflected (same form)
        put("sheep", "sheep");
        put("deer", "deer");
        put("fish", "fish");
        put("moose", "moose");
        put("species", "species");
        put("series", "series");
        put("means", "means");
        put("offspring", "offspring");
        put("aircraft", "aircraft");
        put("spacecraft", "spacecraft");
        put("salmon", "salmon");
        put("trout", "trout");
    }};

    private static final SequencedSet<SingularizationRule> SINGULARIZATION_RULES = new LinkedHashSet<>() {{
        // Check irregulars first
        add(new SingularizationRule(
            s -> IRREGULAR_PLURALS.containsKey(s.toLowerCase()),
            s -> IRREGULAR_PLURALS.get(s.toLowerCase())
        ));

        // -oes -> -o (tomatoes -> tomato)
        add(new SingularizationRule(
            s -> s.endsWith("oes") && s.length() > 3,
            s -> s.substring(0, s.length() - 2)
        ));

        // Latin: -i -> -us (cacti -> cactus, fungi -> fungus)
        add(new SingularizationRule(
            s -> s.endsWith("i") && s.length() > 2 && !s.endsWith("ski"),
            s -> s.substring(0, s.length() - 1) + "us"
        ));

        // Latin: -a -> -um (data -> datum, criteria -> criterion)
        add(new SingularizationRule(
            s -> (s.endsWith("ta") || s.endsWith("ma")) && s.length() > 2,
            s -> s.substring(0, s.length() - 1) + "um"
        ));

        // Latin: -ae -> -a (alumnae -> alumna, formulae -> formula)
        add(new SingularizationRule(
            s -> s.endsWith("ae") && s.length() > 2,
            s -> s.substring(0, s.length() - 1)
        ));

        // Latin: -ices -> -ex/-ix (indices -> index, appendices -> appendix)
        add(new SingularizationRule(
            s -> s.endsWith("ices") && s.length() > 4,
            s -> s.substring(0, s.length() - 4) + "ex"
        ));

        // Latin: -es -> -is (analyses -> analysis, crises -> crisis)
        add(new SingularizationRule(
            s -> s.matches(".*(s|x|c)es") && s.length() > 3 &&
                 Set.of("analyses", "crises", "theses", "hypotheses", "axes").contains(s.toLowerCase()),
            s -> s.substring(0, s.length() - 2) + "is"
        ));

        // -ies -> -y after consonant (categories -> category)
        add(new SingularizationRule(
            s -> s.endsWith("ies") && s.length() > 3 && isConsonant(s.charAt(s.length() - 4)),
            s -> s.substring(0, s.length() - 3) + "y"
        ));

        // -ves -> -f or -fe (wolves -> wolf, knives -> knife, lives -> life)
        add(new SingularizationRule(
            s -> s.endsWith("ves") && s.length() > 3,
            s -> {
                String base = s.substring(0, s.length() - 3);
                // knife, wife, life
                if (base.endsWith("i") || base.endsWith("wi") || base.equals("li")) {
                    return base + "fe";
                }
                return base + "f";
            }
        ));

        // -xes -> -x (boxes -> box, axes -> ax)
        add(new SingularizationRule(
            s -> s.endsWith("xes") && s.length() > 3,
            s -> s.substring(0, s.length() - 2)
        ));

        // -zes -> -z (quizzes -> quiz)
        add(new SingularizationRule(
            s -> s.endsWith("zes") && s.length() > 3,
            s -> s.substring(0, s.length() - 2)
        ));

        // -uses -> -use, -ouses -> -ouse (courses -> course, houses -> house)
        add(new SingularizationRule(
            s -> (s.endsWith("uses") || s.matches(".+our?ses$")) && s.length() > 4,
            s -> s.substring(0, s.length() - 1)
        ));

        // -sses -> -ss (glasses -> glass, passes -> pass)
        add(new SingularizationRule(
            s -> s.endsWith("sses") && s.length() > 4,
            s -> s.substring(0, s.length() - 2)
        ));

        // -ses, -shes, -ches -> remove -es (buses -> bus, dishes -> dish, churches -> church)
        add(new SingularizationRule(
            s -> (s.endsWith("ses") || s.endsWith("shes") || s.endsWith("ches")) && s.length() > 3,
            s -> s.substring(0, s.length() - 2)
        ));

        // Words ending in -ss, -us should stay (glass -> glass, status -> status)
        add(new SingularizationRule(
            s -> (s.endsWith("ss") || (s.endsWith("us") && s.length() > 2)),
            s -> s
        ));

        // -men -> -man (firemen -> fireman)
        add(new SingularizationRule(
            s -> s.endsWith("men") && s.length() > 3,
            s -> s.substring(0, s.length() - 3) + "man"
        ));

        // Generic -s removal (cats -> cat)
        add(new SingularizationRule(
            s -> s.endsWith("s") && s.length() > 1,
            s -> s.substring(0, s.length() - 1)
        ));
    }};

    public static String singularize(String possiblyPlural) {
        return singularCache.get(possiblyPlural, _ -> {
            if (possiblyPlural.isBlank()) {
                return possiblyPlural;
            }

            for (var rule : SINGULARIZATION_RULES) {
                if (rule.condition().test(possiblyPlural)) {
                    return rule.singularizer().apply(possiblyPlural);
                }
            }

            return possiblyPlural;
        });
    }

    private static boolean isConsonant(char c) {
        char lowerC = Character.toLowerCase(c);
        return "aeiou".indexOf(lowerC) == -1 && Character.isLetter(c);
    }

    private record SingularizationRule(Predicate<String> condition, UnaryOperator<String> singularizer) {
    }
}