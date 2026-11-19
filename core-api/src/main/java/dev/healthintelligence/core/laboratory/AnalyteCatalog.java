package dev.healthintelligence.core.laboratory;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Maps printed test names to catalogue analytes (ADR 0007). The catalogue is small reference
 * data, loaded once. A name that is not recognised stays without analyte: it is still stored and
 * reviewed, it just does not join a series.
 */
@Component
class AnalyteCatalog {

    private final Map<UUID, Analyte> byId;
    private final Map<String, Analyte> byAlias;

    AnalyteCatalog(AnalyteCatalogRepository repository) {
        this.byId = repository.findAllAnalytes().stream().collect(Collectors.toUnmodifiableMap(Analyte::id, Function.identity()));
        this.byAlias = repository.findAllAliases().stream()
                .collect(Collectors.toUnmodifiableMap(AnalyteAlias::aliasNormalized, alias -> byId.get(alias.analyteId())));
    }

    /** "HEMOGLOBINA GLICADA - HbA1c" -> "hemoglobina glicada hba1c": no accents, case or punctuation. */
    static String normalize(String name) {
        String stripped = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    Optional<Analyte> recognise(String rawName) {
        return Optional.ofNullable(byAlias.get(normalize(rawName)));
    }

    Optional<Analyte> find(UUID id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    Collection<Analyte> all() {
        return byId.values();
    }

    /** Catalogue search for corrections: every word of the query must appear in a name or alias. */
    List<Analyte> search(String query, int limit) {
        List<String> words = List.of(normalize(query).split(" "));
        Map<Analyte, Boolean> matches = byAlias.entrySet().stream()
                .filter(e -> words.stream().allMatch(w -> e.getKey().contains(w)))
                .collect(Collectors.toMap(Map.Entry::getValue, e -> true, (a, b) -> a));
        byId.values().stream()
                .filter(a -> words.stream().allMatch(w -> normalize(a.displayName()).contains(w)))
                .forEach(a -> matches.put(a, true));
        return matches.keySet().stream().sorted(Comparator.comparing(Analyte::displayName)).limit(limit).toList();
    }
}
