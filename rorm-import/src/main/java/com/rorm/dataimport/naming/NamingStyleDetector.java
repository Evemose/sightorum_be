package com.rorm.dataimport.naming;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NamingStyleDetector {

    public NamingStyle detect(Collection<String> propertyNames) {
        if (propertyNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot detect naming style from empty property list");
        }

        var matchesByStyle = Arrays.stream(NamingStyle.values())
            .collect(Collectors.toMap(
                style -> style,
                style -> propertyNames.stream().filter(style::matches).count(),
                (a, _) -> a,
                () -> new EnumMap<>(NamingStyle.class)
            ));

        if (!isUnanimousBestMatch(matchesByStyle)) {
            log.warn("Ambiguous naming style detection: {}. Will use best match, others will be adapted", matchesByStyle);
        }

        return matchesByStyle.entrySet().stream()
            .max(Comparator.comparingLong(Entry::getValue))
            .map(Entry::getKey)
            .orElseThrow(() -> new IllegalStateException("Failed to determine best naming style"));
    }

    private boolean isUnanimousBestMatch(EnumMap<NamingStyle, Long> matchesByStyle) {
        var maxCount = Collections.max(matchesByStyle.values());
        return matchesByStyle.values().stream()
                   .filter(count -> Objects.equals(count, maxCount))
                   .count() == 1;
    }
}
