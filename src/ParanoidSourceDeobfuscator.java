import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static source-level deobfuscator for Paranoid and LSParanoid strings.
 *
 * Supported call shapes include:
 *   DeobfuscatorHelper.getString(123L, chunks)
 *   Deobfuscator.android.module.getString(123L)
 *   Deobfuscator$android$module.getString(123L)
 *
 * The tool scans Java source for literal String[] chunk tables, evaluates
 * constant long IDs, replaces resolvable calls with Java string literals,
 * copies the remaining source tree, and writes mapping/unresolved TSV reports.
 *
 * It does not hook a running process and does not rebuild APK or DEX files.
 *
 * Usage:
 *   java src/ParanoidSourceDeobfuscator.java <input-file-or-dir> <output-file-or-dir> [support-file-or-dir ...]
 */
public final class ParanoidSourceDeobfuscator {
    private static final String TOOL_VERSION = "1.0.0";
    private static final int MAX_CHUNK_LENGTH = 0x1fff;
    private static final int MAX_DECODED_LENGTH = 65535;

    private static final String NUMBER = "([+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+))[lL]";

    private static final Pattern DIRECT_CALL = Pattern.compile(
            "(?:(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*DeobfuscatorHelper)" +
            "\\s*\\.\\s*getString\\s*\\(\\s*" + NUMBER +
            "\\s*,\\s*([^)]*?)\\)",
            Pattern.DOTALL
    );

    private static final Pattern WRAPPER_CALL = Pattern.compile(
            "((?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*Deobfuscator" +
            "(?:(?:\\s*\\.\\s*|\\$)[A-Za-z_$][\\w$]*)*)" +
            "\\s*\\.\\s*getString\\s*\\(\\s*" + NUMBER + "\\s*\\)",
            Pattern.DOTALL
    );

    private final List<Table> tables;
    private final Map<MappingKey, Decoded> cache = new HashMap<>();
    private final Map<Long, Set<String>> seenPlaintexts = new LinkedHashMap<>();
    private final List<String> unresolvedRows = new ArrayList<>();
    private int filesScanned;
    private int filesChanged;
    private int directReplacements;
    private int wrapperReplacements;
    private int unresolved;
    private int ambiguous;

    private ParanoidSourceDeobfuscator(List<Table> tables) {
        this.tables = tables;
    }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            usage();
            return;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("ParanoidSourceDeobfuscator " + TOOL_VERSION);
            return;
        }
        if (args.length < 2) {
            usage();
            System.exit(2);
        }

        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        List<Path> extras = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            extras.add(Path.of(args[i]).toAbsolutePath().normalize());
        }

        try {
            requireReadable(input, "input");
            for (Path extra : extras) requireReadable(extra, "support source");

            List<Path> sourceFiles = collectJavaFiles(input);
            List<Path> tableFiles = new ArrayList<>(sourceFiles);
            for (Path extra : extras) {
                tableFiles.addAll(collectJavaFiles(extra));
            }
            tableFiles = tableFiles.stream().distinct().toList();

            List<Table> tables = extractTables(tableFiles);
            if (tables.isEmpty()) {
                throw new IllegalArgumentException(
                        "No literal String[] table was found. " +
                        "Add a file or directory containing decoder/chunk tables as an additional argument."
                );
            }

            System.out.println("[+] ParanoidSourceDeobfuscator v" + TOOL_VERSION);
            System.out.printf("[+] Found %,d String[] table(s) in %,d Java file(s)%n",
                    tables.size(), tableFiles.size());
            for (Table table : tables) {
                System.out.printf("    - %s :: %s (%d chunk, %,d UTF-16 units)%n",
                        table.source, table.fieldName, table.chunks.length, totalLength(table.chunks));
                if (!table.ownerAliases.isEmpty()) {
                    System.out.println("      owners: " + String.join(", ", table.ownerAliases));
                }
            }

            ParanoidSourceDeobfuscator tool = new ParanoidSourceDeobfuscator(tables);
            if (Files.isDirectory(input)) {
                tool.processDirectory(input, output);
            } else {
                tool.processFile(input, output);
            }

            Path reportRoot = Files.isDirectory(output) ? output : output.getParent();
            if (reportRoot == null) reportRoot = Path.of(".").toAbsolutePath();
            tool.writeReports(reportRoot);

            System.out.printf("[+] Scanned %,d Java file(s)%n", tool.filesScanned);
            System.out.printf("[+] Changed %,d file(s)%n", tool.filesChanged);
            System.out.printf("[+] Direct helper replacements: %,d%n", tool.directReplacements);
            System.out.printf("[+] One-argument wrapper replacements: %,d%n", tool.wrapperReplacements);
            System.out.printf("[+] Unresolved calls: %,d%n", tool.unresolved);
            System.out.printf("[+] Ambiguous calls: %,d%n", tool.ambiguous);
            System.out.println("[+] Output: " + output);
            System.out.println("[+] Mapping: " + reportRoot.resolve("decoded_strings.tsv"));
            System.out.println("[+] Unresolved: " + reportRoot.resolve("unresolved_calls.tsv"));
        } catch (Exception e) {
            System.err.println("[-] " + e.getMessage());
            if (Boolean.getBoolean("paranoid.debug")) e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void processDirectory(Path inputRoot, Path outputRoot) throws IOException {
        if (inputRoot.equals(outputRoot)) {
            throw new IllegalArgumentException("Input and output must be different paths.");
        }
        Files.createDirectories(outputRoot);
        try (Stream<Path> stream = Files.walk(inputRoot)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                Path relative = inputRoot.relativize(source);
                Path target = outputRoot.resolve(relative);
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                if (source.getFileName().toString().endsWith(".java")) {
                    processJava(source, target, relative.toString());
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void processFile(Path input, Path output) throws IOException {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        processJava(input, output, input.getFileName().toString());
    }

    private void processJava(Path input, Path output, String displayPath) throws IOException {
        filesScanned++;
        String source = Files.readString(input, StandardCharsets.UTF_8);
        RewriteResult direct = rewrite(source, DIRECT_CALL, CallKind.DIRECT, displayPath);
        RewriteResult wrapper = rewrite(direct.text, WRAPPER_CALL, CallKind.WRAPPER, displayPath);
        String rewritten = wrapper.text;

        Files.writeString(output, rewritten, StandardCharsets.UTF_8);
        int changed = direct.replacements + wrapper.replacements;
        if (changed > 0) {
            filesChanged++;
            System.out.printf("[+] %s: replaced %d (%d direct, %d wrapper)%n",
                    displayPath, changed, direct.replacements, wrapper.replacements);
        }
    }

    private RewriteResult rewrite(String source, Pattern pattern, CallKind kind, String path) {
        Matcher matcher = pattern.matcher(source);
        StringBuffer out = new StringBuffer(source.length());
        int replaced = 0;

        while (matcher.find()) {
            String idText = kind == CallKind.DIRECT ? matcher.group(1) : matcher.group(2);
            long id;
            try {
                id = parseJavaLongLiteral(idText);
            } catch (RuntimeException e) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            String qualifier = kind == CallKind.WRAPPER ? normalizeDots(matcher.group(1)) : "DeobfuscatorHelper";
            String arrayExpr = kind == CallKind.DIRECT ? matcher.group(2).trim() : "";
            Resolution resolution = resolve(id, qualifier, arrayExpr, kind);
            if (resolution.decoded != null) {
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(toJavaStringLiteral(resolution.decoded.plaintext)));
                replaced++;
                if (kind == CallKind.DIRECT) directReplacements++; else wrapperReplacements++;
                seenPlaintexts.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                        .add(resolution.decoded.plaintext);
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                unresolved++;
                if (resolution.ambiguous) ambiguous++;
                int line = lineNumber(source, matcher.start());
                unresolvedRows.add(tsv(path) + "\t" + line + "\t" + kind + "\t" + id + "\t" +
                        tsv(qualifier) + "\t" + tsv(arrayExpr) + "\t" + tsv(resolution.reason) + "\t" +
                        tsv(singleLine(matcher.group())));
            }
        }
        matcher.appendTail(out);
        return new RewriteResult(out.toString(), replaced);
    }

    private Resolution resolve(long id, String qualifier, String arrayExpr, CallKind kind) {
        List<Candidate> candidates = new ArrayList<>();
        for (Table table : tables) {
            Decoded decoded = decodeCached(id, table);
            if (decoded == null) continue;
            double score = decoded.score;

            if (!arrayExpr.isBlank()) {
                String expr = normalizeDots(arrayExpr);
                if (expr.endsWith("." + table.fieldName) || expr.equals(table.fieldName)) score += 25.0;
                String simpleFile = simpleName(table.source);
                if (expr.contains(simpleFile + "." + table.fieldName)) score += 35.0;
                for (String owner : table.ownerAliases) {
                    if (expr.equals(owner + "." + table.fieldName) ||
                            expr.endsWith("." + owner + "." + table.fieldName)) {
                        score += 1000.0;
                    } else if (expr.contains(owner + "." + table.fieldName)) {
                        score += 300.0;
                    }
                }
            }
            if (kind == CallKind.WRAPPER) {
                String normalizedQualifier = normalizeOwnerName(qualifier);
                String simpleFile = normalizeOwnerName(simpleName(table.source));
                if (normalizedQualifier.contains(simpleFile)) score += 15.0;
                if (table.source.toLowerCase(Locale.ROOT).contains("deobfuscator")) score += 10.0;
                for (String owner : table.ownerAliases) {
                    String normalizedOwner = normalizeOwnerName(owner);
                    if (normalizedQualifier.equals(normalizedOwner) ||
                            normalizedQualifier.endsWith("." + normalizedOwner)) {
                        score += 1000.0;
                    } else if (normalizedQualifier.contains(normalizedOwner)) {
                        score += 250.0;
                    }
                }
            }
            candidates.add(new Candidate(table, decoded, score));
        }

        if (candidates.isEmpty()) {
            return new Resolution(null, false, "no table could decode this ID");
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        Candidate best = candidates.get(0);
        Candidate second = candidates.size() > 1 ? candidates.get(1) : null;

        // A valid decoded string can be empty. Reject only clearly binary/garbage output.
        if (best.decoded.score < -20.0) {
            return new Resolution(null, false,
                    "best candidate is non-text: " + best.table.source + "::" + best.table.fieldName);
        }

        if (second != null && Math.abs(best.score - second.score) < 3.0 &&
                !best.decoded.plaintext.equals(second.decoded.plaintext)) {
            return new Resolution(null, true,
                    "multiple tables decode plausibly: " +
                    best.table.source + "::" + best.table.fieldName + " vs " +
                    second.table.source + "::" + second.table.fieldName);
        }
        return new Resolution(best.decoded, false,
                best.table.source + "::" + best.table.fieldName);
    }

    private Decoded decodeCached(long id, Table table) {
        MappingKey key = new MappingKey(id, table.index);
        if (cache.containsKey(key)) return cache.get(key);
        Decoded value;
        try {
            String plaintext = decode(id, table.chunks);
            value = new Decoded(plaintext, scoreText(plaintext), table);
        } catch (RuntimeException e) {
            value = null;
        }
        cache.put(key, value);
        return value;
    }

    private void writeReports(Path root) throws IOException {
        Files.createDirectories(root);
        StringBuilder mapping = new StringBuilder(
                "id\tjava_literal\tplaintext\ttable_source\ttable_field\tscore\n");
        Set<String> rows = new LinkedHashSet<>();
        for (Map.Entry<MappingKey, Decoded> entry : cache.entrySet()) {
            Decoded decoded = entry.getValue();
            if (decoded == null) continue;
            long id = entry.getKey().id;
            Set<String> used = seenPlaintexts.get(id);
            if (used == null || !used.contains(decoded.plaintext)) continue;
            String row = id + "\t" + toJavaStringLiteral(decoded.plaintext) + "\t" +
                    tsv(decoded.plaintext) + "\t" + tsv(decoded.table.source) + "\t" +
                    tsv(decoded.table.fieldName) + "\t" + String.format(Locale.ROOT, "%.2f", decoded.score);
            rows.add(row);
        }
        for (String row : rows) mapping.append(row).append('\n');
        Files.writeString(root.resolve("decoded_strings.tsv"), mapping.toString(), StandardCharsets.UTF_8);

        StringBuilder unresolvedText = new StringBuilder(
                "path\tline\tkind\tid\tqualifier\tarray_expr\treason\tcall\n");
        for (String row : unresolvedRows) unresolvedText.append(row).append('\n');
        Files.writeString(root.resolve("unresolved_calls.tsv"), unresolvedText.toString(), StandardCharsets.UTF_8);
    }

    private static List<Table> extractTables(List<Path> files) throws IOException {
        List<Table> result = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        int index = 0;
        for (Path file : files) {
            if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".java")) continue;
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Set<String> ownerAliases = deriveOwnerAliases(file, source);
            for (ParsedArray array : JavaStringArrayParser.extractAll(source)) {
                if (array.values.length == 0) continue;
                String fingerprint = array.fieldName + "\u0000" + String.join("\u0001", array.values);
                if (!dedupe.add(fingerprint)) continue;
                result.add(new Table(index++, file.toString(), array.fieldName, array.values, ownerAliases));
            }
        }
        return result;
    }

    private static List<Path> collectJavaFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) return List.of(input);
        try (Stream<Path> stream = Files.walk(input)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }


    private static Set<String> deriveOwnerAliases(Path file, String source) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String fileBase = simpleName(file.toString());
        addOwnerAlias(aliases, fileBase);

        Matcher packageMatcher = Pattern.compile(
                "\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
        ).matcher(source);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";

        Matcher classMatcher = Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
        ).matcher(source);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            addOwnerAlias(aliases, className);
            if (!packageName.isBlank()) {
                addOwnerAlias(aliases, packageName + "." + className);
            }
        }

        if (!packageName.isBlank()) {
            addOwnerAlias(aliases, packageName + "." + fileBase);
        }
        return Set.copyOf(aliases);
    }

    private static void addOwnerAlias(Set<String> aliases, String raw) {
        if (raw == null || raw.isBlank()) return;
        String normalized = normalizeOwnerName(raw);
        aliases.add(normalized);

        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < normalized.length()) {
            aliases.add(normalized.substring(dot + 1));
        }

        int deobfuscator = normalized.indexOf("Deobfuscator.");
        if (deobfuscator >= 0) {
            aliases.add(normalized.substring(deobfuscator));
        }
    }

    private static String normalizeOwnerName(String text) {
        return normalizeDots(text).replace('$', '.');
    }

    private static String decode(long id, String[] chunks) {
        long state = RandomHelper.seed(id & 0xffffffffL);
        state = RandomHelper.next(state);
        long low = (state >>> 32) & 0xffffL;
        state = RandomHelper.next(state);
        long high = (state >>> 16) & 0xffff0000L;
        int index = (int) ((id >>> 32) ^ low ^ high);
        if (index < 0) throw new IllegalArgumentException("negative index");

        state = getCharAt(index, chunks, state);
        int length = (int) ((state >>> 32) & 0xffffL);
        if (length < 0 || length > MAX_DECODED_LENGTH) {
            throw new IllegalArgumentException("invalid length");
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            state = getCharAt(index + i + 1, chunks, state);
            chars[i] = (char) ((state >>> 32) & 0xffffL);
        }
        return new String(chars);
    }

    private static long getCharAt(int charIndex, String[] chunks, long state) {
        if (charIndex < 0) throw new IllegalArgumentException("negative char index");
        int chunkIndex = charIndex / MAX_CHUNK_LENGTH;
        int offset = charIndex % MAX_CHUNK_LENGTH;
        if (chunkIndex < 0 || chunkIndex >= chunks.length) throw new IllegalArgumentException("chunk OOB");
        String chunk = chunks[chunkIndex];
        if (offset < 0 || offset >= chunk.length()) throw new IllegalArgumentException("offset OOB");
        long nextState = RandomHelper.next(state);
        return nextState ^ ((long) chunk.charAt(offset) << 32);
    }

    private static double scoreText(String text) {
        if (text.isEmpty()) return 12.0;
        double score = 0.0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\ufffd' || Character.isSurrogate(c)) {
                score -= 30.0;
            } else if (c == '\n' || c == '\r' || c == '\t') {
                score += 0.5;
            } else if (Character.isISOControl(c)) {
                score -= 15.0;
            } else if (c >= 0x20 && c <= 0x7e) {
                ascii++;
                score += Character.isLetterOrDigit(c) ? 2.0 : 1.0;
            } else if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) {
                score += 1.0;
            } else {
                score += 0.2;
            }
        }
        score += 8.0 * ascii / Math.max(1, text.length());
        if (text.length() > 4096) score -= 20.0;
        return score;
    }

    private static final class RandomHelper {
        private static long seed(long x) {
            long z = (x ^ (x >>> 33)) * 0x62a9d9ed799705f5L;
            return ((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32;
        }
        private static long next(long state) {
            short s0 = (short) (state & 0xffffL);
            short s1 = (short) ((state >>> 16) & 0xffffL);
            short next = s0;
            next += s1;
            next = rotl(next, 9);
            next += s0;
            s1 ^= s0;
            s0 = rotl(s0, 13);
            s0 ^= s1;
            s0 ^= (s1 << 5);
            s1 = rotl(s1, 10);
            long result = next;
            result <<= 16;
            result |= s1;
            result <<= 16;
            result |= s0;
            return result;
        }
        private static short rotl(short x, int k) {
            return (short) ((x << k) | (x >>> (32 - k)));
        }
    }

    private static final class JavaStringArrayParser {
        private static final Pattern DECL = Pattern.compile(
                "\\bString\\s*(?:\\[\\s*]\\s*([A-Za-z_$][A-Za-z0-9_$]*)|" +
                "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[\\s*])\\s*="
        );

        private static List<ParsedArray> extractAll(String source) {
            List<ParsedArray> arrays = new ArrayList<>();
            Matcher matcher = DECL.matcher(source);
            while (matcher.find()) {
                String field = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                try {
                    int open = findOutsideTrivia(source, '{', matcher.end());
                    if (open < 0) continue;
                    ParsedArrayAt parsed = parseArray(source, open, field);
                    arrays.add(new ParsedArray(field, parsed.values.toArray(String[]::new)));
                } catch (RuntimeException ignored) {
                    // Ignore non-literal initializers and unrelated/unsupported Java syntax.
                    // A malformed unrelated String[] must not abort the whole scan.
                }
            }
            return arrays;
        }

        private static ParsedArrayAt parseArray(String source, int open, String field) {
            List<String> values = new ArrayList<>();
            int i = open + 1;
            while (i < source.length()) {
                i = skipTrivia(source, i);
                if (i >= source.length()) break;
                char c = source.charAt(i);
                if (c == '}') return new ParsedArrayAt(values, i + 1);
                if (c == ',') { i++; continue; }
                if (c != '"') throw new IllegalArgumentException("non literal array");
                ParsedString parsed = parseStringLiteral(source, i);
                String value = parsed.value;
                i = skipTrivia(source, parsed.next);
                while (i < source.length() && source.charAt(i) == '+') {
                    i = skipTrivia(source, i + 1);
                    if (i >= source.length() || source.charAt(i) != '"') {
                        throw new IllegalArgumentException("non literal concat");
                    }
                    ParsedString next = parseStringLiteral(source, i);
                    value += next.value;
                    i = skipTrivia(source, next.next);
                }
                values.add(value);
            }
            throw new IllegalArgumentException("unterminated array");
        }

        private static int findOutsideTrivia(String source, char target, int start) {
            int i = start;
            while (i < source.length()) {
                i = skipTrivia(source, i);
                if (i >= source.length()) return -1;
                char c = source.charAt(i);
                if (c == target) return i;
                if (c == '"') i = parseStringLiteral(source, i).next;
                else if (c == '\'') i = skipCharLiteral(source, i);
                else if (c == ';') return -1;
                else i++;
            }
            return -1;
        }

        private static int skipTrivia(String source, int start) {
            int i = start;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '/' && i + 1 < source.length()) {
                    char n = source.charAt(i + 1);
                    if (n == '/') {
                        i += 2;
                        while (i < source.length() && source.charAt(i) != '\n') i++;
                        continue;
                    }
                    if (n == '*') {
                        int end = source.indexOf("*/", i + 2);
                        if (end < 0) throw new IllegalArgumentException("unterminated comment");
                        i = end + 2;
                        continue;
                    }
                }
                break;
            }
            return i;
        }

        private static ParsedString parseStringLiteral(String source, int quote) {
            StringBuilder raw = new StringBuilder();
            int i = quote + 1;
            while (i < source.length()) {
                char c = source.charAt(i++);
                if (c == '"') return new ParsedString(decodeEscapes(raw.toString()), i);
                if (c == '\\') {
                    if (i >= source.length()) throw new IllegalArgumentException("bad escape");
                    raw.append(c).append(source.charAt(i++));
                } else raw.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private static int skipCharLiteral(String source, int quote) {
            int i = quote + 1;
            while (i < source.length()) {
                char c = source.charAt(i++);
                if (c == '\\' && i < source.length()) i++;
                else if (c == '\'') return i;
            }
            throw new IllegalArgumentException("unterminated char");
        }

        private static String decodeEscapes(String raw) {
            StringBuilder unicode = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length();) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == 'u') {
                    int j = i + 1;
                    while (j < raw.length() && raw.charAt(j) == 'u') j++;
                    if (j + 4 > raw.length()) throw new IllegalArgumentException("bad unicode");
                    int value = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit(raw.charAt(j + k), 16);
                        if (d < 0) throw new IllegalArgumentException("bad unicode");
                        value = (value << 4) | d;
                    }
                    unicode.append((char) value);
                    i = j + 4;
                } else { unicode.append(c); i++; }
            }

            StringBuilder out = new StringBuilder(unicode.length());
            for (int i = 0; i < unicode.length();) {
                char c = unicode.charAt(i++);
                if (c != '\\') { out.append(c); continue; }
                if (i >= unicode.length()) throw new IllegalArgumentException("bad escape");
                char e = unicode.charAt(i++);
                switch (e) {
                    case 'b' -> out.append('\b');
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'f' -> out.append('\f');
                    case 'r' -> out.append('\r');
                    case 's' -> out.append(' ');
                    case '"' -> out.append('"');
                    case '\'' -> out.append('\'');
                    case '\\' -> out.append('\\');
                    case '\n' -> { }
                    case '\r' -> { if (i < unicode.length() && unicode.charAt(i) == '\n') i++; }
                    default -> {
                        if (e >= '0' && e <= '7') {
                            int value = e - '0';
                            int consumed = 1;
                            while (consumed < 3 && i < unicode.length()) {
                                char d = unicode.charAt(i);
                                if (d < '0' || d > '7') break;
                                if (consumed == 2 && e > '3') break;
                                value = (value << 3) | (d - '0');
                                consumed++; i++;
                            }
                            out.append((char) value);
                        } else throw new IllegalArgumentException("unsupported escape");
                    }
                }
            }
            return out.toString();
        }
    }

    private static String toJavaStringLiteral(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static long parseJavaLongLiteral(String text) {
        String value = text.trim();
        boolean negative = value.startsWith("-");
        boolean positive = value.startsWith("+");
        if (negative || positive) value = value.substring(1);
        BigInteger number = value.startsWith("0x") || value.startsWith("0X")
                ? new BigInteger(value.substring(2), 16)
                : new BigInteger(value, 10);
        if (negative) number = number.negate();
        return number.longValue();
    }

    private static String normalizeDots(String text) {
        return text.replaceAll("\\s*\\.\\s*", ".").trim();
    }
    private static int lineNumber(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) if (source.charAt(i) == '\n') line++;
        return line;
    }
    private static String singleLine(String text) { return text.replace('\r', ' ').replace('\n', ' ').trim(); }
    private static String tsv(String text) { return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n"); }
    private static String simpleName(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
    private static long totalLength(String[] chunks) { long n = 0; for (String s : chunks) n += s.length(); return n; }
    private static void requireReadable(Path path, String label) {
        if (!Files.exists(path)) throw new IllegalArgumentException(label + " does not exist: " + path);
        if (!Files.isReadable(path)) throw new IllegalArgumentException(label + " is not readable: " + path);
    }
    private static void usage() {
        System.out.println("Paranoid Source Deobfuscator " + TOOL_VERSION);
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java src/ParanoidSourceDeobfuscator.java <input-file-or-dir> <output-file-or-dir> [support-file-or-dir ...]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  input     Decompiled Java source to patch.");
        System.out.println("  output    Destination for patched source and TSV reports.");
        System.out.println("  support   Optional Java source trees containing additional decoder/chunk tables.");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java src/ParanoidSourceDeobfuscator.java app_jadx patched_source secondary_dex_jadx");
        System.out.println();
        System.out.println("Use -Dparanoid.debug=true to print stack traces for fatal errors.");
    }

    private enum CallKind { DIRECT, WRAPPER }
    private record Table(int index, String source, String fieldName, String[] chunks, Set<String> ownerAliases) { }
    private record MappingKey(long id, int tableIndex) { }
    private record Decoded(String plaintext, double score, Table table) { }
    private record Candidate(Table table, Decoded decoded, double score) { }
    private record Resolution(Decoded decoded, boolean ambiguous, String reason) { }
    private record RewriteResult(String text, int replacements) { }
    private record ParsedArray(String fieldName, String[] values) { }
    private record ParsedArrayAt(List<String> values, int next) { }
    private record ParsedString(String value, int next) { }
}
