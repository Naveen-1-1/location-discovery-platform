package io.github.naveen11.placemap.ingest.release;

import io.github.naveen11.placemap.ingest.source.OvertureSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.apache.commons.text.StringSubstitutor;

/**
 * The files beside the data: {@code LICENSE} (ODbL 1.0), {@code ATTRIBUTION.md} from
 * {@code data/sources.yaml}, {@code METHOD.md}, the licence texts and notices the sources ask for, and
 * {@code SHA256SUMS} over everything.
 */
final class ReleaseDocs {

    static final String LICENSE = "LICENSE";
    static final String ATTRIBUTION = "ATTRIBUTION.md";
    static final String METHOD = "METHOD.md";
    static final String SUMS = "SHA256SUMS";
    static final String LICENCES = "licences";
    static final String ODBL = "ODbL-1.0.txt";

    /** Licence texts a provider's licence needs shipped with the data, by SPDX id. CC0 needs none. */
    private static final Map<String, String> LICENCE_TEXTS = Map.of(
            "CDLA-Permissive-2.0", "CDLA-Permissive-2.0.txt",
            "Apache-2.0", "Apache-2.0.txt");
    private static final Map<String, String> LICENCE_NAMES = Map.of(
            "CDLA-Permissive-2.0", "CDLA Permissive 2.0",
            "Apache-2.0", "Apache 2.0",
            "CC0-1.0", "CC0 1.0");

    private ReleaseDocs() {
    }

    static void write(Path folder, Map<String, SourceTerms> terms, Path licencesDir, LocalDate date,
            ReleaseExport.Summary summary) throws IOException {
        Files.copy(licencesDir.resolve(ODBL), folder.resolve(LICENSE));
        var providers = providers(terms, summary.overtureProviders());
        for (String file : shippedTexts(providers)) {
            Path licences = Files.createDirectories(folder.resolve(LICENCES));
            Files.copy(licencesDir.resolve(file), licences.resolve(file));
        }
        Files.writeString(folder.resolve(ATTRIBUTION), attribution(terms, summary, providers, date));
        Files.writeString(folder.resolve(METHOD), method(terms, summary, date));
        Files.writeString(folder.resolve(SUMS), sums(folder));
    }

    /** The Overture providers in the release, with their terms; one the registry does not list stops the release. */
    static Map<String, SourceTerms.Provider> providers(Map<String, SourceTerms> terms, Set<String> present) {
        Map<String, SourceTerms.Provider> known = terms.get(OvertureSource.ID).providers();
        var providers = new LinkedHashMap<String, SourceTerms.Provider>();
        for (String name : known.keySet()) { // registry order
            if (present.contains(name)) {
                providers.put(name, known.get(name));
            }
        }
        var unknown = new TreeSet<>(present);
        unknown.removeAll(known.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Overture records name providers that data/sources.yaml does not list "
                    + "under providers: " + unknown + ". Check their licence and add them before releasing.");
        }
        return providers;
    }

    /** The files of {@code data/licences} the release ships, besides the ODbL. */
    static List<String> shippedTexts(Map<String, SourceTerms.Provider> providers) {
        var files = new TreeSet<String>();
        for (SourceTerms.Provider provider : providers.values()) {
            if (provider.licence() != null && LICENCE_TEXTS.containsKey(provider.licence())) {
                files.add(LICENCE_TEXTS.get(provider.licence()));
            }
            if (provider.notice() != null) {
                files.add(provider.notice());
            }
        }
        return List.copyOf(files);
    }

    static String attribution(Map<String, SourceTerms> terms, ReleaseExport.Summary summary,
            Map<String, SourceTerms.Provider> providers, LocalDate date) {
        var out = new StringBuilder();
        out.append("# Attribution\n\n")
                .append("Boston place-discovery map: places, release ").append(date).append(".\n\n")
                .append("This database is made available under the Open Database License (ODbL) 1.0: the full text is ")
                .append("in `LICENSE`, and at https://opendatacommons.org/licenses/odbl/1-0/. It is built from the ")
                .append("sources below, whose credits follow. Every source's records were changed: matched and ")
                .append("merged into places, normalised and, for some, reprojected or geocoded, as `METHOD.md` ")
                .append("describes. No publisher listed here endorses this database or its use.\n\n")
                .append("## Sources\n");
        for (SourceTerms source : terms.values()) {
            Integer records = summary.bySource().get(source.id());
            if (records == null) {
                continue;
            }
            out.append("\n### ").append(source.name()).append("\n")
                    .append("- Source id: `").append(source.id()).append("`\n")
                    .append("- Publisher: ").append(source.publisher()).append("\n")
                    .append("- Licence: ").append(String.join(", ", source.licence()));
            if (source.licenceUrl() != null) {
                out.append(" (").append(source.licenceUrl()).append(")");
            }
            out.append("\n- Records in this release: ").append(String.format(Locale.ROOT, "%,d", records)).append("\n");
            if (!source.providers().isEmpty()) {
                out.append("- Credit: ").append(source.attribution().lines().findFirst().orElse("")).append("\n");
                providerCredits(out, providers);
            } else if (source.attribution() != null) {
                out.append("- Credit: ").append(source.attribution().strip()).append("\n");
            } else {
                out.append("- Credit (courtesy; the licence asks for none): ").append(source.publisher()).append("\n");
            }
        }
        return out.toString();
    }

    /** Overture's providers, grouped by licence in registry order: "Data from Meta. Data from Microsoft. (CDLA…)". */
    private static void providerCredits(StringBuilder out, Map<String, SourceTerms.Provider> providers) {
        var byLicence = new LinkedHashMap<String, List<SourceTerms.Provider>>();
        for (SourceTerms.Provider provider : providers.values()) {
            if (provider.credit() != null) {
                byLicence.computeIfAbsent(provider.licence(), k -> new ArrayList<>()).add(provider);
            }
        }
        byLicence.forEach((licence, group) -> {
            out.append("- ").append(group.stream().map(SourceTerms.Provider::credit).collect(Collectors.joining(" ")))
                    .append(" (").append(LICENCE_NAMES.getOrDefault(licence, licence));
            if (LICENCE_TEXTS.containsKey(licence)) {
                out.append("; text in `").append(LICENCES).append('/').append(LICENCE_TEXTS.get(licence)).append('`');
            }
            group.stream().map(SourceTerms.Provider::notice).filter(n -> n != null).distinct()
                    .forEach(notice -> out.append("; notice in `").append(LICENCES).append('/').append(notice)
                            .append('`'));
            out.append(")\n");
        });
    }

    static String method(Map<String, SourceTerms> terms, ReleaseExport.Summary summary, LocalDate date) {
        String template;
        try (InputStream in = ReleaseDocs.class.getResourceAsStream("/release/METHOD.md")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var categories = new StringBuilder("| Category | Places |\n|---|---:|\n");
        summary.byCategory().forEach((category, n) -> categories.append("| ").append(category).append(" | ")
                .append(String.format(Locale.ROOT, "%,d", n)).append(" |\n"));
        var sources = new StringBuilder("| Source | Records |\n|---|---:|\n");
        terms.values().stream().filter(s -> summary.bySource().containsKey(s.id()))
                .forEach(s -> sources.append("| ").append(s.name()).append(" (`").append(s.id()).append("`) | ")
                        .append(String.format(Locale.ROOT, "%,d", summary.bySource().get(s.id()))).append(" |\n"));
        return new StringSubstitutor(Map.of(
                "date", date.toString(),
                "places", String.format(Locale.ROOT, "%,d", summary.places()),
                "records", String.format(Locale.ROOT, "%,d", summary.records()),
                "by_category", categories.toString().strip(),
                "by_source", sources.toString().strip()), "{{", "}}").replace(template);
    }

    /** {@code sha256sum} format, over every other file, by path. */
    static String sums(Path folder) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals(SUMS))
                    .sorted().toList();
        }
        var out = new StringBuilder();
        for (Path file : files) {
            out.append(sha256(file)).append("  ").append(folder.relativize(file).toString().replace('\\', '/'))
                    .append('\n');
        }
        return out.toString();
    }

    static String sha256(Path file) throws IOException {
        return new DigestUtils(MessageDigestAlgorithms.SHA_256).digestAsHex(file);
    }
}
