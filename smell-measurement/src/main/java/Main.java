import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Records code smells before and after refactoring commits.
 *
 * Input: the refactoring CSV written by the refactoring-extraction tool, and the same repository.
 * It draws refactoring commits at random (fixed seed) and, for each one, runs DesigniteJava and PMD
 * on the whole production code of the commit (after) and of its parent (before).
 *
 * For each sampled commit it writes:
 *   - the project-wide totals before and after, for both tools, and per smell / rule type;
 *   - one pair of files: a refactored file and a control file (changed in the same commit, not
 *     refactored, closest in churn), with each file's counts before and after.
 *
 * A tool that failed must never look like a tool that found nothing. Every count is backed by
 * evidence that the tool ran to completion and looked at that file; otherwise the commit is skipped
 * and the reason is logged.
 */
public class Main {

    record Config(Path csv, String repo, long seed, Path out, Path pmdHome, List<String> designiteCommand,
                  int commits, int threads) {}

    static final String USAGE = """
            Usage: java -jar smells.jar --csv <project>_extraction.csv --repo <url-or-local-clone>
                       --pmd <pmd-home> --designite <DesigniteJava.jar>
                       [--seed 6491] [--commits 100] [--threads 1] [--out <dir>]

              --csv        refactoring CSV from the refactoring-extraction tool
              --repo       the same repository: a clone URL, or a local clone with full history
              --pmd        PMD 7 installation directory (contains bin/pmd)
              --designite  DesigniteJava jar
              --seed       random seed that fixes which commits are sampled (default 6491)
              --commits    refactoring commits to record (default 100)
              --threads    commits analysed at the same time (default 1)
              --out        output directory (default <project>_smells_output)
            """;

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        if (args.length % 2 != 0) die("Every option needs a value.\n" + USAGE);
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) die("Unexpected argument: " + args[i] + "\n" + USAGE);
            a.put(args[i].substring(2), args[i + 1]);
        }
        for (String required : List.of("csv", "repo", "pmd", "designite")) {
            if (!a.containsKey(required)) die("Missing --" + required + "\n" + USAGE);
        }
        Path csv = Path.of(a.get("csv")).toAbsolutePath();
        if (!Files.isRegularFile(csv)) die("CSV not found: " + csv);
        Path out = a.containsKey("out") ? Path.of(a.get("out")).toAbsolutePath() : null;
        Config cfg = new Config(csv, a.get("repo"), Long.parseLong(a.getOrDefault("seed", "6491")), out,
                Path.of(a.get("pmd")).toAbsolutePath(),
                List.of("java", "-jar", Path.of(a.get("designite")).toAbsolutePath().toString()),
                Integer.parseInt(a.getOrDefault("commits", "100")),
                Integer.parseInt(a.getOrDefault("threads", "1")));
        System.exit(run(cfg));
    }

    // ------------------------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------------------------

    static int run(Config cfg0) throws Exception {
        Refactorings refs = readRefactorings(cfg0.csv());
        String project = refs.project();
        Config cfg = cfg0.out() != null ? cfg0 : new Config(cfg0.csv(), cfg0.repo(), cfg0.seed(),
                Path.of(project + "_smells_output").toAbsolutePath(), cfg0.pmdHome(), cfg0.designiteCommand(),
                cfg0.commits(), cfg0.threads());
        Files.createDirectories(cfg.out());
        checkToolsExist(cfg);

        try (RandomAccessFile lockFile = new RandomAccessFile(cfg.out().resolve(".lock").toFile(), "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) die("Another run is already using " + cfg.out());

            Log log = new Log(cfg.out().resolve("run_log.txt"));
            log.line("started: " + Instant.now());
            log.line("project: " + project);
            log.line("refactoring CSV: " + cfg.csv() + " (" + refs.rows() + " rows, " + refs.byCommit().size() + " commits)");
            log.line("repository: " + cfg.repo());
            log.line("seed: " + cfg.seed() + ", commits to record: " + cfg.commits() + ", threads: " + cfg.threads());
            log.line("PMD: " + pmdVersion(cfg) + ", ruleset rulesets/java/quickstart.xml (bundled, unmodified)");
            log.line("DesigniteJava: " + String.join(" ", cfg.designiteCommand()) + " (default thresholds)");
            log.line("test code: left out of every snapshot, never a refactored or control file");

            Path repoDir = cloneIfNeeded(cfg.repo(), cfg.out().resolve("repo"));

            // The sampling frame: commits with at least one production-code refactoring, in a seeded order.
            List<String> frame = refs.byCommit().values().stream()
                    .filter(c -> c.rows().stream().anyMatch(r -> r.codeKind().equals("production")))
                    .map(CommitRefs::commit).sorted().collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(frame, new Random(cfg.seed()));
            log.line("commits with a production-code refactoring (sampling frame): " + frame.size());

            Path rawDir = cfg.out().resolve("raw");
            Files.createDirectories(rawDir);
            Map<String, CompletableFuture<SnapshotResult>> cache = new ConcurrentHashMap<>();

            Walk walk = walk(frame, cfg, c -> evaluateCommit(refs.byCommit().get(c), cfg, repoDir, rawDir, cache, project));

            String p = project + "_";
            writeCommitTable(cfg.out().resolve(p + "commit_smells.csv"), walk.accepted(), refs);
            writeCommitDetections(cfg.out().resolve(p + "commit_detections.csv"), walk.accepted());
            writeMasterTable(cfg.out().resolve(p + "master_table.csv"), walk.accepted());
            writePairDetections(cfg.out().resolve(p + "pair_detections.csv"), walk.accepted());
            writeSample(cfg.out().resolve(p + "sample.csv"), walk);
            boolean complete = summary(log, frame, walk, cache, cfg.commits());
            log.line("finished: " + Instant.now());
            return complete ? 0 : 2;
        }
    }

    // ------------------------------------------------------------------------------------
    // The refactoring CSV
    // ------------------------------------------------------------------------------------

    record CsvRef(String type, String filePath, String codeKind, String description) {}

    record CommitRefs(String commit, String date, List<CsvRef> rows) {}

    record Refactorings(String project, int rows, Map<String, CommitRefs> byCommit) {}

    static final List<String> CSV_COLUMNS = List.of("project", "commit", "commit_date", "author_name", "author_email",
            "refactoring_type", "file_path", "code_kind", "has_test_file_proxy", "refactoring_description");

    static Refactorings readRefactorings(Path csv) throws IOException {
        List<List<String>> rows = readCsv(Files.readString(csv, StandardCharsets.UTF_8));
        if (rows.isEmpty() || !rows.get(0).equals(CSV_COLUMNS))
            die("Unexpected header in " + csv + "; expected: " + String.join(",", CSV_COLUMNS));
        String project = null;
        Map<String, CommitRefs> byCommit = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() != CSV_COLUMNS.size()) die("Malformed row " + i + " in " + csv);
            if (project == null) project = r.get(0);
            else if (!project.equals(r.get(0))) die("The CSV holds more than one project: " + project + ", " + r.get(0));
            byCommit.computeIfAbsent(r.get(1), c -> new CommitRefs(c, r.get(2), new ArrayList<>()))
                    .rows().add(new CsvRef(r.get(5), r.get(6), r.get(7), r.get(9)));
        }
        if (project == null) die("The CSV has no rows: " + csv);
        return new Refactorings(project, rows.size() - 1, byCommit);
    }

    /** Fully qualified names (a.b.C) mentioned in a commit's refactoring descriptions. */
    static final Pattern QUALIFIED = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");

    static Set<String> namesMentioned(CommitRefs c) {
        Set<String> names = new HashSet<>();
        for (CsvRef r : c.rows()) {
            Matcher m = QUALIFIED.matcher(r.description());
            while (m.find()) names.add(m.group());
        }
        return names;
    }

    /** True if the class a.b.C, or a type nested in it (a.b.C.D), is named in the descriptions. */
    static boolean mentioned(Set<String> names, String pkg, String unit) {
        String fqn = pkg == null ? unit : pkg + "." + unit;
        if (names.contains(fqn)) return true;
        for (String n : names) if (n.startsWith(fqn + ".")) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------
    // Walking the sample
    // ------------------------------------------------------------------------------------

    record Walk(List<Accepted> accepted, Map<String, String> outcome, Map<String, Integer> fileExclusions) {}

    /**
     * Takes commits in the seeded order until `commits` are accepted. Several commits may be analysed at once,
     * but results are consumed strictly in order, so the sample never depends on timing.
     */
    static Walk walk(List<String> frame, Config cfg, java.util.function.Function<String, Evaluation> evaluate) throws Exception {
        List<Accepted> accepted = new ArrayList<>();
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<String, Integer> fileExclusions = new TreeMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());
        try {
            Deque<Future<Evaluation>> inFlight = new ArrayDeque<>();
            Deque<String> inFlightCommits = new ArrayDeque<>();
            int next = 0;
            while (accepted.size() < cfg.commits() && (next < frame.size() || !inFlight.isEmpty())) {
                while (next < frame.size() && inFlight.size() < cfg.threads()) {
                    String c = frame.get(next++);
                    inFlight.add(pool.submit(() -> evaluate.apply(c)));
                    inFlightCommits.add(c);
                }
                Evaluation e = inFlight.poll().get();
                String c = inFlightCommits.poll();
                e.fileExclusions().forEach((k, v) -> fileExclusions.merge(k, v, Integer::sum));
                if (e.accepted() != null) {
                    String id = String.format("c%03d", accepted.size() + 1);
                    accepted.add(e.accepted().withId(id));
                    outcome.put(c, "accepted " + id);
                } else {
                    outcome.put(c, "skipped: " + e.rejection());
                }
                System.err.printf("[%s] %d/%d recorded, %d commits examined (%s: %s)%n", Instant.now(), accepted.size(),
                        cfg.commits(), outcome.size(), c.substring(0, 10), outcome.get(c));
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
        return new Walk(accepted, outcome, fileExclusions);
    }

    /** One file of a pair, with its counts. */
    record Row(String filePath, String unitName, String group, String refactoringTypes, int linesAdded, int linesDeleted,
               int pmdBefore, int pmdAfter, int djBefore, int djAfter,
               Map<String, Integer> pmdRulesBefore, Map<String, Integer> pmdRulesAfter,
               Map<String, Integer> djSmellsBefore, Map<String, Integer> djSmellsAfter) {}

    /** A recorded commit: its project-wide totals and its file pair. */
    record Accepted(String id, String project, String commit, String parent, String date,
                    SnapshotResult before, SnapshotResult after, Row treated, Row control) {
        Accepted withId(String newId) { return new Accepted(newId, project, commit, parent, date, before, after, treated, control); }
    }

    record Evaluation(Accepted accepted, String rejection, Map<String, Integer> fileExclusions) {}

    static Evaluation evaluateCommit(CommitRefs c, Config cfg, Path repoDir, Path rawDir,
                                     Map<String, CompletableFuture<SnapshotResult>> cache, String project) {
        Map<String, Integer> exclusions = new TreeMap<>();
        try {
            if (gitOk(repoDir, "cat-file", "-e", c.commit() + "^{commit}") != 0)
                return new Evaluation(null, "commit not found in the repository", exclusions);
            String parent = parentOf(repoDir, c.commit());
            if (parent == null) return new Evaluation(null, "root commit (no parent)", exclusions);

            // Changed .java files, with churn and the file checks.
            List<ChangedFile> changed = changedFiles(repoDir, parent, c.commit());
            for (ChangedFile f : changed) if (f.exclusion() != null) exclusions.merge(f.exclusion(), 1, Integer::sum);

            // Refactored: a production file the CSV names in this commit. Control: changed, named in no refactoring.
            Map<String, Set<String>> typesByFile = new TreeMap<>();
            Set<String> csvFiles = new HashSet<>();
            for (CsvRef r : c.rows()) {
                csvFiles.add(r.filePath());
                if (r.codeKind().equals("production")) typesByFile.computeIfAbsent(r.filePath(), k -> new TreeSet<>()).add(r.type());
            }
            Set<String> names = namesMentioned(c);
            List<ChangedFile> treated = new ArrayList<>(), controls = new ArrayList<>();
            for (ChangedFile f : changed) {
                if (f.exclusion() != null) continue;
                if (typesByFile.containsKey(f.path())) treated.add(f);
                else if (!csvFiles.contains(f.path()) && !mentioned(names, f.packageName(), f.unitName())) controls.add(f);
            }
            if (treated.isEmpty()) return new Evaluation(null, "no refactored production file passes the file checks", exclusions);
            if (controls.isEmpty()) return new Evaluation(null, "no control file passes the file checks", exclusions);

            SnapshotResult before = analyseSnapshot(repoDir, parent, rawDir, cfg, cache);
            if (!before.ok()) return new Evaluation(null, "before snapshot: " + before.failure(), exclusions);
            SnapshotResult after = analyseSnapshot(repoDir, c.commit(), rawDir, cfg, cache);
            if (!after.ok()) return new Evaluation(null, "after snapshot: " + after.failure(), exclusions);

            // One refactored file per commit, chosen in a seeded order; the first that both tools can vouch for.
            treated.sort(Comparator.comparing(ChangedFile::path));
            Collections.shuffle(treated, new Random(cfg.seed() ^ Long.parseUnsignedLong(c.commit().substring(0, 15), 16)));
            String why = null;
            for (ChangedFile t : treated) {
                ChangedFile k = closestChurnControl(t, controls);
                why = cannotVouchForAny(List.of(t, k), before, after);
                if (why != null) continue;
                Row tr = row(t, "treated", String.join(";", typesByFile.get(t.path())), before, after);
                Row kr = row(k, "control", "", before, after);
                return new Evaluation(new Accepted(null, project, c.commit(), parent, c.date(), before, after, tr, kr), null, exclusions);
            }
            return new Evaluation(null, why, exclusions);
        } catch (Exception e) {
            return new Evaluation(null, "error: " + e, exclusions);
        }
    }

    static String cannotVouchForAny(List<ChangedFile> files, SnapshotResult before, SnapshotResult after) {
        for (ChangedFile f : files) {
            for (SnapshotResult s : List.of(before, after)) {
                String why = s.cannotVouchFor(f.path(), f.packageName(), f.unitName());
                if (why != null) return (s == before ? "before" : "after") + " snapshot, " + f.path() + ": " + why;
            }
        }
        return null;
    }

    static Row row(ChangedFile f, String group, String types, SnapshotResult b, SnapshotResult a) {
        String key = djKey(f.packageName(), f.unitName());
        return new Row(f.path(), f.unitName(), group, types, f.added(), f.deleted(),
                b.pmdCount(f.path()), a.pmdCount(f.path()), b.djCount(key), a.djCount(key),
                b.pmdRules(f.path()), a.pmdRules(f.path()), b.djSmells(key), a.djSmells(key));
    }

    /** The control whose churn is closest to the refactored file's. Ties go to the alphabetically first path. */
    static ChangedFile closestChurnControl(ChangedFile treated, List<ChangedFile> controls) {
        return controls.stream()
                .min(Comparator.comparingInt((ChangedFile c) -> Math.abs(c.churn() - treated.churn()))
                        .thenComparing(ChangedFile::path))
                .orElseThrow();
    }

    // ------------------------------------------------------------------------------------
    // Repository and changed files
    // ------------------------------------------------------------------------------------

    static Path cloneIfNeeded(String repo, Path cloneDir) throws IOException {
        Path local = Path.of(repo);
        if (Files.isDirectory(local.resolve(".git"))) return local.toAbsolutePath();
        if (!Files.isDirectory(cloneDir.resolve(".git"))) {
            System.err.println("Cloning " + repo + " (full history) ...");
            exec(List.of("git", "clone", "--no-checkout", "--no-single-branch", repo, cloneDir.toString()),
                    cloneDir.getParent(), 7200).requireOk("git clone");
        }
        return cloneDir;
    }

    /** One changed .java file in a commit, with churn and the reason it is excluded (null if it is usable). */
    record ChangedFile(String status, String path, int added, int deleted,
                       String packageName, String unitName, String exclusion) {
        int churn() { return added + deleted; }
    }

    static String parentOf(Path repoDir, String commit) throws IOException {
        ProcResult r = exec(List.of("git", "rev-parse", "--verify", "--quiet", commit + "^1"), repoDir, 60);
        return r.exit() == 0 ? r.stdout().trim() : null;
    }

    static List<ChangedFile> changedFiles(Path repoDir, String parent, String commit) throws IOException {
        // --numstat -z with -M: "added\tdeleted\tpath\0", or for a rename "added\tdeleted\t\0old\0new\0".
        String[] f = git(repoDir, "diff", "--numstat", "-z", "-M", parent, commit, "--", "*.java").split("\0", -1);
        String[] ns = git(repoDir, "diff", "--name-status", "-z", "-M", parent, commit, "--", "*.java").split("\0", -1);
        Map<String, String> statusByPath = new HashMap<>();
        for (int i = 0; i < ns.length && !ns[i].isEmpty(); ) {
            String st = ns[i].substring(0, 1);
            if (st.equals("R") || st.equals("C")) { statusByPath.put(ns[i + 2], st); i += 3; }
            else { statusByPath.put(ns[i + 1], st); i += 2; }
        }
        List<ChangedFile> out = new ArrayList<>();
        for (int i = 0; i < f.length && !f[i].isEmpty(); ) {
            String[] p = f[i].split("\t", 3);
            String path;
            if (p[2].isEmpty()) { path = f[i + 2]; i += 3; }
            else { path = p[2]; i += 1; }
            boolean binary = p[0].equals("-");
            int added = binary ? 0 : Integer.parseInt(p[0]);
            int deleted = binary ? 0 : Integer.parseInt(p[1]);
            String status = statusByPath.getOrDefault(path, "?");
            String exclusion = null;
            String pkg = null, unit = baseName(path);
            if (status.equals("A")) exclusion = "file added in the commit (nothing before)";
            else if (status.equals("D")) exclusion = "file deleted in the commit (nothing after)";
            else if (status.equals("R") || status.equals("C")) exclusion = "file renamed or moved in the commit";
            else if (!status.equals("M")) exclusion = "unexpected git status " + status;
            else if (binary) exclusion = "git reports the file as binary";
            else if (isTestPath(path)) exclusion = "test file";
            else {
                SourceSummary b = scanSource(git(repoDir, "show", parent + ":" + path));
                SourceSummary a = scanSource(git(repoDir, "show", commit + ":" + path));
                exclusion = fileCheck(b, unit);
                if (exclusion == null) exclusion = fileCheck(a, unit);
                if (exclusion == null && !Objects.equals(b.packageName(), a.packageName()))
                    exclusion = "package declaration changed in the commit";
                pkg = a.packageName();
            }
            out.add(new ChangedFile(status, path, added, deleted, pkg, unit, exclusion));
        }
        return out;
    }

    /** Exactly one top-level type, of a kind DesigniteJava models, named like the file. */
    static String fileCheck(SourceSummary s, String unitName) {
        if (s.topLevelTypes().size() != 1)
            return "declares " + s.topLevelTypes().size() + " top-level types (exactly one required)";
        String[] t = s.topLevelTypes().get(0);
        if (!t[0].equals("class") && !t[0].equals("interface"))
            return "top-level type is " + (t[0].equals("@interface") ? "an annotation type" : "a" + (t[0].equals("enum") ? "n " : " ") + t[0])
                    + " (DesigniteJava does not model it)";
        if (!t[1].equals(unitName)) return "top-level type name differs from the file name";
        return null;
    }

    static boolean isTestPath(String path) {
        String p = "/" + path;
        return p.contains("/test/") || p.contains("/tests/") || p.contains("/testFixtures/")
                || p.contains("/androidTest/") || p.contains("/integrationTest/")
                || p.contains("/src/it/") || p.contains("/src/xdocs-examples/");
    }

    static String baseName(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.endsWith(".java") ? n.substring(0, n.length() - 5) : n;
    }

    // ------------------------------------------------------------------------------------
    // Snapshots and the two tools
    // ------------------------------------------------------------------------------------

    /**
     * What both tools reported for one snapshot. ok=false means a tool failed for the whole snapshot.
     * djTypesSeen: how many types DesigniteJava analysed under each package/Type key, which is the proof
     * that it looked at a file. The *Totals maps are project-wide, per rule or smell.
     */
    record SnapshotResult(String key, boolean ok, String failure,
                          Map<String, Map<String, Integer>> pmdByFile, Set<String> pmdErrorFiles,
                          Map<String, Map<String, Integer>> djByType, Map<String, Integer> djTypesSeen,
                          Set<String> javaFiles, int djRowsNotAttributed,
                          Map<String, Integer> pmdTotals, Map<String, Integer> djTotals) {

        static SnapshotResult failed(String key, String why) {
            return new SnapshotResult(key, false, why, Map.of(), Set.of(), Map.of(), Map.of(), Set.of(), 0, Map.of(), Map.of());
        }

        String cannotVouchFor(String path, String pkg, String unit) {
            if (!ok) return failure;
            if (!javaFiles.contains(path)) return "file is not in the snapshot";
            if (pmdErrorFiles.contains(path)) return "PMD could not process the file";
            int seen = djTypesSeen.getOrDefault(djKey(pkg, unit), 0);
            if (seen == 0) return "DesigniteJava did not analyse type " + djKey(pkg, unit);
            if (seen > 1) return "DesigniteJava reports " + seen + " types named " + djKey(pkg, unit) + " (cannot attribute)";
            return null;
        }

        int pmdCount(String path) { return sum(pmdByFile.getOrDefault(path, Map.of())); }
        int djCount(String key) { return sum(djByType.getOrDefault(key, Map.of())); }
        Map<String, Integer> pmdRules(String path) { return pmdByFile.getOrDefault(path, Map.of()); }
        Map<String, Integer> djSmells(String key) { return djByType.getOrDefault(key, Map.of()); }
        int pmdTotal() { return sum(pmdTotals); }
        int djTotal() { return sum(djTotals); }
    }

    static String djKey(String pkg, String unit) {
        return (pkg == null ? "(default package)" : pkg) + "/" + unit;
    }

    /**
     * Exports the commit's production .java files, runs both tools, checks both runs and keeps the raw output.
     * Cached on disk under a hash of the snapshot's .java files; a snapshot whose run died half-way has no
     * status.txt and is redone. Two commits needing the same snapshot at once share one run.
     */
    static SnapshotResult analyseSnapshot(Path repoDir, String commit, Path rawDir, Config cfg,
                                          Map<String, CompletableFuture<SnapshotResult>> cache) throws Exception {
        String key = javaStateKey(repoDir, commit);
        CompletableFuture<SnapshotResult> mine = new CompletableFuture<>();
        CompletableFuture<SnapshotResult> existing = cache.putIfAbsent(key, mine);
        if (existing != null) return existing.get();
        try {
            Path dir = rawDir.resolve(key);
            Path status = dir.resolve("status.txt");
            if (!Files.exists(status)) {
                Files.createDirectories(dir);
                // Named after the key: DesigniteJava writes the directory name into its Project Name column.
                Path tmp = Files.createTempDirectory("smells-");
                Path snap = tmp.resolve("snap-" + key.substring(0, 12));
                try {
                    exportJavaFiles(repoDir, commit, snap);
                    String failure = runTools(snap, dir, cfg);
                    Files.writeString(status, failure == null ? "ok\n" : "failed: " + failure + "\n");
                } finally {
                    deleteTree(tmp);
                }
            }
            SnapshotResult r = loadSnapshot(key, dir);
            mine.complete(r);
            return r;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            cache.remove(key, mine);
            throw e;
        }
    }

    /** SHA-1 over (path, blob id) of every production .java file in the commit's tree. */
    static String javaStateKey(Path repoDir, String commit) throws IOException {
        StringBuilder sb = new StringBuilder();
        forEachJavaBlob(repoDir, commit, (path, id, repo) -> sb.append(path).append(' ').append(id.name()).append('\n'));
        return sha1(sb.toString());
    }

    interface BlobVisitor { void visit(String path, ObjectId id, Repository repo) throws IOException; }

    static void forEachJavaBlob(Path repoDir, String commit, BlobVisitor v) throws IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).build();
             RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            RevCommit rc = rw.parseCommit(repo.resolve(commit));
            tw.addTree(rc.getTree());
            tw.setRecursive(true);
            tw.setFilter(PathSuffixFilter.create(".java"));
            while (tw.next()) {
                if (isTestPath(tw.getPathString())) continue;   // the tools see production code only
                FileMode m = tw.getFileMode(0);
                if (m == FileMode.REGULAR_FILE || m == FileMode.EXECUTABLE_FILE) v.visit(tw.getPathString(), tw.getObjectId(0), repo);
            }
        }
    }

    static void exportJavaFiles(Path repoDir, String commit, Path dest) throws IOException {
        forEachJavaBlob(repoDir, commit, (path, id, repo) -> {
            Path target = dest.resolve(path).normalize();
            if (!target.startsWith(dest)) return;
            Files.createDirectories(target.getParent());
            try (OutputStream o = Files.newOutputStream(target)) { repo.open(id).copyTo(o); }
        });
    }

    /** Runs PMD and DesigniteJava on a snapshot. Returns null on success, else why the snapshot is unusable. */
    static String runTools(Path snap, Path raw, Config cfg) throws IOException {
        // File list with each file's package, needed to attribute DesigniteJava rows (which carry no path).
        List<String> lines = new ArrayList<>();
        try (Stream<Path> s = Files.walk(snap)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = snap.relativize(p).toString().replace(File.separatorChar, '/');
                String pkg = scanSource(Files.readString(p, StandardCharsets.ISO_8859_1)).packageName();
                lines.add(rel + "\t" + (pkg == null ? "" : pkg));
            }
        }
        Files.write(raw.resolve("java_files.tsv"), lines);

        // PMD, bundled quickstart ruleset, unmodified. With both --no-fail-on-* flags exit 0 is the only success.
        Path pmdCsv = raw.resolve("pmd.csv");
        ProcResult pmd = exec(pmdCommand(cfg, snap, "csv", pmdCsv), snap, 3600);
        if (pmd.exit() != 0) return "PMD exited with status " + pmd.exit() + ": " + pmd.tail();
        if (!Files.exists(pmdCsv)) return "PMD wrote no CSV report";
        List<List<String>> csv = readCsv(Files.readString(pmdCsv));
        if (csv.isEmpty() || !csv.get(0).equals(PMD_HEADER)) return "PMD CSV report has an unexpected header";

        // A second PMD run in XML, because only the XML report names the files PMD failed to parse; the CSV
        // silently leaves them out, which would read as "zero violations". Its counts must equal the CSV's.
        Path pmdXml = Files.createTempFile("smells-pmd-", ".xml");
        try {
            ProcResult px = exec(pmdCommand(cfg, snap, "xml", pmdXml), snap, 3600);
            if (px.exit() != 0) return "PMD (XML run) exited with status " + px.exit() + ": " + px.tail();
            Map<String, Integer> xmlCounts = new TreeMap<>();
            Set<String> errors = new TreeSet<>();
            parsePmdXml(pmdXml, xmlCounts, errors);
            Map<String, Integer> csvCounts = new TreeMap<>();
            pmdRowsByFile(csv).forEach((f, rules) -> csvCounts.put(f, sum(rules)));
            if (!csvCounts.equals(xmlCounts)) return "PMD CSV and XML runs disagree on violation counts";
            Files.write(raw.resolve("pmd_errors.txt"), errors);
        } finally {
            Files.deleteIfExists(pmdXml);
        }

        // DesigniteJava into a fresh, empty directory (the tool deletes what it finds there).
        Path djOut = Files.createTempDirectory("smells-dj-");
        try {
            List<String> cmd = new ArrayList<>(cfg.designiteCommand());
            cmd.addAll(List.of("-i", snap.toString(), "-o", djOut.toString()));
            ProcResult dj = exec(cmd, snap, 3600);
            if (dj.exit() != 0) return "DesigniteJava exited with status " + dj.exit() + ": " + dj.tail();
            List<String> outLines = dj.stdout().lines().filter(l -> !l.isBlank()).toList();
            if (outLines.isEmpty() || !outLines.get(outLines.size() - 1).trim().equals("Done."))
                return "DesigniteJava did not report completion (no final \"Done.\")";
            if (dj.stdout().contains("Exception") || dj.stderr().contains("Exception"))
                return "DesigniteJava reported an exception: " + dj.tail();
            for (String name : List.of("designCodeSmells.csv", "typeMetrics.csv")) {
                Path src = djOut.resolve(name);
                if (!Files.exists(src)) return "DesigniteJava wrote no " + name;
                Files.copy(src, raw.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteTree(djOut);
        }
        try {
            readDesignite(raw.resolve("designCodeSmells.csv"), DJ_SMELL_HEADER);
            readDesignite(raw.resolve("typeMetrics.csv"), DJ_TYPE_HEADER);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return null;
    }

    static List<String> pmdCommand(Config cfg, Path snap, String format, Path report) {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "pmd.bat" : "pmd";
        return List.of(cfg.pmdHome().resolve("bin").resolve(exe).toString(), "check",
                "-d", snap.toString(), "-z", snap.toString(),
                "-R", "rulesets/java/quickstart.xml", "-f", format, "-r", report.toString(),
                "--no-fail-on-violation", "--no-fail-on-error", "--no-progress", "--no-cache");
    }

    static final List<String> PMD_HEADER = List.of("Problem", "Package", "File", "Priority", "Line", "Description", "Rule set", "Rule");
    static final List<String> DJ_SMELL_HEADER = List.of("Project Name", "Package Name", "Type Name", "Code Smell");
    static final List<String> DJ_TYPE_HEADER = List.of("Project Name", "Package Name", "Type Name", "NOF", "NOPF", "NOM",
            "NOPM", "LOC", "WMC", "NC", "DIT", "LCOM", "FANIN", "FANOUT");

    /** Rebuilds a SnapshotResult from the raw files on disk, so fresh and cached runs go through one code path. */
    static SnapshotResult loadSnapshot(String key, Path dir) throws IOException {
        String status = Files.readString(dir.resolve("status.txt")).trim();
        if (!status.equals("ok")) return SnapshotResult.failed(key, status.substring("failed: ".length()));

        Map<String, Map<String, Integer>> pmd = pmdRowsByFile(readCsv(Files.readString(dir.resolve("pmd.csv"))));
        Map<String, Integer> pmdTotals = new TreeMap<>();
        pmd.values().forEach(m -> m.forEach((rule, n) -> pmdTotals.merge(rule, n, Integer::sum)));
        Set<String> pmdErrors = new TreeSet<>(Files.readAllLines(dir.resolve("pmd_errors.txt")));

        Set<String> javaFiles = new TreeSet<>();
        Set<String> fileKeys = new HashSet<>();
        for (String l : Files.readAllLines(dir.resolve("java_files.tsv"))) {
            String[] p = l.split("\t", -1);
            javaFiles.add(p[0]);
            fileKeys.add(djKey(p[1].isEmpty() ? null : p[1], baseName(p[0])));
        }

        // Project totals count every design-smell row. Per-file counts keep only rows whose Type Name is a
        // file's base name in the same package; rows for nested types cannot be attributed to a file.
        Map<String, Map<String, Integer>> dj = new TreeMap<>();
        Map<String, Integer> djTotals = new TreeMap<>();
        int notAttributed = 0;
        for (String[] r : readDesignite(dir.resolve("designCodeSmells.csv"), DJ_SMELL_HEADER)) {
            djTotals.merge(r[3], 1, Integer::sum);
            String k = r[1] + "/" + r[2];
            if (fileKeys.contains(k)) dj.computeIfAbsent(k, x -> new TreeMap<>()).merge(r[3], 1, Integer::sum);
            else notAttributed++;
        }
        Map<String, Integer> seen = new TreeMap<>();
        for (String[] r : readDesignite(dir.resolve("typeMetrics.csv"), DJ_TYPE_HEADER)) seen.merge(r[1] + "/" + r[2], 1, Integer::sum);
        return new SnapshotResult(key, true, null, pmd, pmdErrors, dj, seen, javaFiles, notAttributed, pmdTotals, djTotals);
    }

    /** PMD CSV rows grouped by the File column, then by Rule: file -> rule -> count. */
    static Map<String, Map<String, Integer>> pmdRowsByFile(List<List<String>> csv) {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        for (int i = 1; i < csv.size(); i++) {
            List<String> r = csv.get(i);
            if (r.size() != PMD_HEADER.size()) throw new IllegalStateException("malformed PMD CSV row " + i);
            out.computeIfAbsent(r.get(2).replace('\\', '/'), k -> new TreeMap<>()).merge(r.get(7), 1, Integer::sum);
        }
        return out;
    }

    static void parsePmdXml(Path xml, Map<String, Integer> counts, Set<String> errors) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document d = f.newDocumentBuilder().parse(xml.toFile());
            NodeList files = d.getElementsByTagName("file");
            for (int i = 0; i < files.getLength(); i++) {
                Element e = (Element) files.item(i);
                int n = e.getElementsByTagName("violation").getLength();
                if (n > 0) counts.merge(e.getAttribute("name").replace('\\', '/'), n, Integer::sum);
            }
            NodeList errs = d.getElementsByTagName("error");
            for (int i = 0; i < errs.getLength(); i++) errors.add(((Element) errs.item(i)).getAttribute("filename").replace('\\', '/'));
        } catch (Exception e) {
            throw new IOException("unreadable PMD XML report: " + e.getMessage(), e);
        }
    }

    /** DesigniteJava CSVs are unquoted comma-separated lines. Any row of the wrong width is a failure, not a skip. */
    static List<String[]> readDesignite(Path csv, List<String> header) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty() || !Arrays.asList(lines.get(0).split(",", -1)).equals(header))
            throw new IllegalStateException("DesigniteJava " + csv.getFileName() + " has an unexpected header");
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) continue;
            String[] r = lines.get(i).split(",", -1);
            if (r.length != header.size()) throw new IllegalStateException("DesigniteJava " + csv.getFileName() + " row " + i + " is malformed");
            rows.add(r);
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------
    // Outputs
    // ------------------------------------------------------------------------------------

    static void writeCommitTable(Path out, List<Accepted> acc, Refactorings refs) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("sample_id", "project", "commit", "parent_commit", "commit_date", "production_refactorings",
                "refactoring_types", "java_files_before", "java_files_after", "designite_before", "designite_after",
                "pmd_before", "pmd_after", "pmd_unparsed_files_before", "pmd_unparsed_files_after"));
        for (Accepted a : acc) {
            List<CsvRef> prod = refs.byCommit().get(a.commit()).rows().stream().filter(r -> r.codeKind().equals("production")).toList();
            String types = prod.stream().map(CsvRef::type).collect(Collectors.toCollection(TreeSet::new)).stream().collect(Collectors.joining(";"));
            rows.add(List.of(a.id(), a.project(), a.commit(), a.parent(), a.date(), str(prod.size()), types,
                    str(a.before().javaFiles().size()), str(a.after().javaFiles().size()),
                    str(a.before().djTotal()), str(a.after().djTotal()),
                    str(a.before().pmdTotal()), str(a.after().pmdTotal()),
                    str(a.before().pmdErrorFiles().size()), str(a.after().pmdErrorFiles().size())));
        }
        writeCsv(out, rows);
    }

    static void writeCommitDetections(Path out, List<Accepted> acc) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("sample_id", "commit", "snapshot", "detector", "detection", "count"));
        for (Accepted a : acc) {
            a.before().djTotals().forEach((k, v) -> rows.add(List.of(a.id(), a.commit(), "before", "designite", k, str(v))));
            a.after().djTotals().forEach((k, v) -> rows.add(List.of(a.id(), a.commit(), "after", "designite", k, str(v))));
            a.before().pmdTotals().forEach((k, v) -> rows.add(List.of(a.id(), a.commit(), "before", "pmd", k, str(v))));
            a.after().pmdTotals().forEach((k, v) -> rows.add(List.of(a.id(), a.commit(), "after", "pmd", k, str(v))));
        }
        writeCsv(out, rows);
    }

    static final List<String> MASTER_COLUMNS = List.of("pair_id", "project", "commit", "parent_commit", "file_path",
            "unit_name", "group", "refactoring_types", "lines_added", "lines_deleted",
            "pmd_before", "pmd_after", "dj_before", "dj_after", "snapshot_before", "snapshot_after");

    static void writeMasterTable(Path out, List<Accepted> acc) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        rows.add(MASTER_COLUMNS);
        for (Accepted a : acc) {
            for (Row r : List.of(a.treated(), a.control())) {
                rows.add(List.of(a.id(), a.project(), a.commit(), a.parent(), r.filePath(), r.unitName(), r.group(),
                        r.refactoringTypes(), str(r.linesAdded()), str(r.linesDeleted()),
                        str(r.pmdBefore()), str(r.pmdAfter()), str(r.djBefore()), str(r.djAfter()),
                        a.before().key(), a.after().key()));
            }
        }
        writeCsv(out, rows);
    }

    static void writePairDetections(Path out, List<Accepted> acc) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("pair_id", "group", "file_path", "snapshot", "detector", "detection", "count"));
        for (Accepted a : acc) {
            for (Row r : List.of(a.treated(), a.control())) {
                r.pmdRulesBefore().forEach((k, v) -> rows.add(List.of(a.id(), r.group(), r.filePath(), "before", "pmd", k, str(v))));
                r.pmdRulesAfter().forEach((k, v) -> rows.add(List.of(a.id(), r.group(), r.filePath(), "after", "pmd", k, str(v))));
                r.djSmellsBefore().forEach((k, v) -> rows.add(List.of(a.id(), r.group(), r.filePath(), "before", "designite", k, str(v))));
                r.djSmellsAfter().forEach((k, v) -> rows.add(List.of(a.id(), r.group(), r.filePath(), "after", "designite", k, str(v))));
            }
        }
        writeCsv(out, rows);
    }

    static void writeSample(Path out, Walk w) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("order", "commit", "outcome"));
        int i = 0;
        for (Map.Entry<String, String> e : w.outcome().entrySet()) rows.add(List.of(str(++i), e.getKey(), e.getValue()));
        writeCsv(out, rows);
    }

    static boolean summary(Log log, List<String> frame, Walk w, Map<String, CompletableFuture<SnapshotResult>> cache, int target) throws IOException {
        log.line("");
        log.line("== Commits ==");
        log.line("in the sampling frame: " + frame.size());
        log.line("examined, in seeded order: " + w.outcome().size());
        Map<String, Integer> outcomes = new TreeMap<>();
        for (String o : w.outcome().values()) outcomes.merge(o.startsWith("accepted") ? "recorded" : o.replaceAll("\\S+\\.java", "<file>").replaceAll("(type|named) \\S+", "$1 <type>"), 1, Integer::sum);
        outcomes.forEach((k, v) -> log.line("  " + k + ": " + v));
        require(outcomes.values().stream().mapToInt(Integer::intValue).sum() == w.outcome().size(), "outcomes do not sum to commits examined");
        require(outcomes.getOrDefault("recorded", 0) == w.accepted().size(), "recorded count mismatch");
        log.line("recorded: " + w.accepted().size());

        log.line("");
        log.line("== Changed files left out by the file checks (in examined commits) ==");
        w.fileExclusions().forEach((k, v) -> log.line("  " + k + ": " + v));

        log.line("");
        log.line("== Snapshots ==");
        List<SnapshotResult> snaps = new ArrayList<>();
        for (CompletableFuture<SnapshotResult> f : cache.values()) if (f.isDone() && !f.isCompletedExceptionally()) snaps.add(f.join());
        long ok = snaps.stream().filter(SnapshotResult::ok).count();
        log.line("analysed: " + snaps.size() + " (ok " + ok + ", failed " + (snaps.size() - ok) + ")");
        snaps.stream().filter(s -> !s.ok()).forEach(s -> log.line("  failed " + s.key() + ": " + s.failure()));

        boolean complete = w.accepted().size() >= target;
        log.line("");
        log.line(complete ? "RESULT: COMPLETE - " + target + " commits recorded."
                : "RESULT: INCOMPLETE - only " + w.accepted().size() + " of " + target + " commits could be recorded; see the reasons above.");
        return complete;
    }

    // ------------------------------------------------------------------------------------
    // Source scanner (no parse, just enough to read the package and count top-level types)
    // ------------------------------------------------------------------------------------

    record SourceSummary(String packageName, List<String[]> topLevelTypes) {}

    static SourceSummary scanSource(String src) {
        List<String> tokens = new ArrayList<>();       // depth-0 tokens only
        int depth = 0, i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') { while (i < n && src.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') { int e = src.indexOf("*/", i + 2); i = e < 0 ? n : e + 2; continue; }
            if (src.startsWith("\"\"\"", i)) { int e = i + 3; while (e < n && !src.startsWith("\"\"\"", e)) e += src.charAt(e) == '\\' ? 2 : 1; i = Math.min(n, e + 3); continue; }
            if (c == '"' || c == '\'') { int e = i + 1; while (e < n && src.charAt(e) != c && src.charAt(e) != '\n') e += src.charAt(e) == '\\' ? 2 : 1; i = e + 1; continue; }
            if (c == '{') { depth++; i++; continue; }
            if (c == '}') { depth = Math.max(0, depth - 1); i++; continue; }
            if (depth == 0) {
                if (Character.isJavaIdentifierStart(c)) {
                    int e = i; while (e < n && Character.isJavaIdentifierPart(src.charAt(e))) e++;
                    tokens.add(src.substring(i, e)); i = e; continue;
                }
                if (!Character.isWhitespace(c)) tokens.add(String.valueOf(c));
            }
            i++;
        }
        String pkg = null;
        List<String[]> types = new ArrayList<>();
        for (int t = 0; t < tokens.size(); t++) {
            String tok = tokens.get(t);
            String prev = t > 0 ? tokens.get(t - 1) : "";
            String next = t + 1 < tokens.size() ? tokens.get(t + 1) : "";
            if (tok.equals("package") && pkg == null && types.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int k = t + 1; k < tokens.size() && !tokens.get(k).equals(";"); k++) sb.append(tokens.get(k));
                pkg = sb.toString();
            } else if (Set.of("class", "interface", "enum", "record").contains(tok) && !prev.equals(".")
                    && !next.isEmpty() && Character.isJavaIdentifierStart(next.charAt(0))) {
                types.add(new String[]{prev.equals("@") && tok.equals("interface") ? "@interface" : tok, next});
            }
        }
        return new SourceSummary(pkg, types);
    }

    // ------------------------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------------------------

    record ProcResult(int exit, String stdout, String stderr) {
        String tail() {
            List<String> l = (stderr + "\n" + stdout).lines().filter(s -> !s.isBlank()).toList();
            return String.join(" | ", l.subList(Math.max(0, l.size() - 3), l.size()));
        }
        ProcResult requireOk(String what) {
            if (exit != 0) throw new IllegalStateException(what + " failed (" + exit + "): " + tail());
            return this;
        }
    }

    /** Runs a command to completion, output captured via temp files. Exit -1 on timeout. Stops the process if interrupted. */
    static ProcResult exec(List<String> cmd, Path cwd, int timeoutSeconds) throws IOException {
        Path out = Files.createTempFile("smells-out-", ".txt"), err = Files.createTempFile("smells-err-", ".txt");
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); p.waitFor(); }
            return new ProcResult(done ? p.exitValue() : -1, Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    static String git(Path repoDir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=off"));
        cmd.addAll(List.of(args));
        return exec(cmd, repoDir, 600).requireOk("git " + args[0]).stdout();
    }

    static int gitOk(Path repoDir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return exec(cmd, repoDir, 60).exit();
    }

    static void checkToolsExist(Config cfg) {
        if (!Files.isDirectory(cfg.pmdHome().resolve("bin"))) die("PMD not found: " + cfg.pmdHome().resolve("bin"));
        String jar = cfg.designiteCommand().get(cfg.designiteCommand().size() - 1);
        if (!Files.isRegularFile(Path.of(jar))) die("DesigniteJava jar not found: " + jar);
    }

    static String pmdVersion(Config cfg) {
        try {
            String exe = cfg.pmdHome().resolve("bin").resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "pmd.bat" : "pmd").toString();
            return exec(List.of(exe, "--version"), cfg.out(), 120).stdout().lines().filter(l -> l.startsWith("PMD")).findFirst().orElse("?");
        } catch (IOException e) {
            return "?";
        }
    }

    /** Minimal RFC 4180 reader: quoted fields, doubled quotes, commas and newlines inside quotes. */
    static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') { quoted = true; any = true; }
            else if (c == ',') { row.add(field.toString()); field.setLength(0); any = true; }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || field.length() > 0) { row.add(field.toString()); rows.add(row); }
                row = new ArrayList<>(); field.setLength(0); any = false;
            } else { field.append(c); any = true; }
        }
        if (any || field.length() > 0) { row.add(field.toString()); rows.add(row); }
        return rows;
    }

    static void writeCsv(Path out, List<List<String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (List<String> r : rows) sb.append(r.stream().map(Main::csvField).collect(Collectors.joining(","))).append('\n');
        Path tmp = Files.createTempFile(out.getParent(), ".tmp-", ".csv");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    static String csvField(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static int sum(Map<String, Integer> m) { return m.values().stream().mapToInt(Integer::intValue).sum(); }

    static String str(int n) { return Integer.toString(n); }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    static void require(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("Summary does not add up: " + what);
    }

    static void die(String msg) {
        System.err.println(msg);
        System.exit(1);
    }

    /** The run log, written to disk as it grows and printed at the end of each line. */
    static final class Log {
        private final Path file;
        Log(Path file) throws IOException { this.file = file; Files.writeString(file, ""); }
        void line(String s) {
            System.out.println(s);
            try {
                Files.writeString(file, s + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
