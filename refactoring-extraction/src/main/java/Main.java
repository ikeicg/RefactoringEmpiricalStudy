import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Refactoring extraction tool.
 *
 * Usage: java -jar extract.jar <github-url> [start-commit]
 *
 * Clones the repo, lists every non-merge commit reachable from start-commit (default: HEAD) back to
 * the first commit, runs RefactoringMiner on each one, and writes into <repo-name>_output/:
 *   <repo-name>_extraction.csv   one row per refactoring instance
 *   <repo-name>_tags.csv         every Git tag with its tagged commit date and URL
 *   run_log.txt                  what ran, every timeout/error with its reason, and a final summary
 *
 * RefactoringMiner runs in a separate worker process, so a commit that hits the time limit is really
 * stopped (the worker is killed and restarted) instead of running on in the background.
 *
 * Resumable: rerunning the same command continues where the last run stopped. Commits that timed out
 * or failed are retried on the next run. A rerun with a different start commit in the same folder is
 * refused, so results from different runs can never be mixed.
 */
public class Main {

    static final String TOOL_VERSION = "2";
    static final int RM_TIMEOUT_SECONDS = Integer.getInteger("extract.timeoutSeconds", 300);
    static final int WORKER_START_TIMEOUT_SECONDS = 120;
    static final List<String> TEST_MARKERS = List.of(
            "/test/", "/tests/", "/testFixtures/", "/androidTest/", "/integrationTest/",
            "/src/it/", "/src/xdocs-examples/");
    static final String CSV_HEADER = "project,commit,commit_date,author_name,author_email,refactoring_type,"
            + "file_path,code_kind,has_test_file_proxy,refactoring_description";

    // statuses recorded per commit in .commits_done
    static final String OK = "ok", ROOT = "root", TIMEOUT = "timeout", ERROR = "error";

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--worker")) {
            runWorker(args[1]);
            return;
        }
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java -jar extract.jar <github-url> [start-commit]");
            System.exit(1);
        }
        new Main(args[0], args.length > 1 ? args[1] : null).run();
    }

    // ------------------------------------------------------------------------------------------
    // Controller
    // ------------------------------------------------------------------------------------------

    final String url, startRefGiven, repoName, ownerRepo;
    final Path outDir, repoDir, extractionCsv, tagsCsv, runLog, statusFile, metaFile, lockFile, rmMessages;
    PrintWriter log;
    final Object logLock = new Object();
    Worker worker;
    volatile boolean finished = false;

    // counters for the current run
    int processedThisRun = 0, okThisRun = 0, rootThisRun = 0, timeoutsThisRun = 0, errorsThisRun = 0;

    Main(String url, String startRef) {
        this.url = url;
        this.startRefGiven = startRef;
        String name = url.replaceAll("/+$", "").replaceAll("\\.git$", "");
        this.repoName = name.substring(name.lastIndexOf('/') + 1);
        this.ownerRepo = name.replaceAll("^https?://github\\.com/", "");
        this.outDir = Path.of(repoName + "_output");
        this.repoDir = outDir.resolve("repo");
        this.extractionCsv = outDir.resolve(repoName + "_extraction.csv");
        this.tagsCsv = outDir.resolve(repoName + "_tags.csv");
        this.runLog = outDir.resolve("run_log.txt");
        this.statusFile = outDir.resolve(".commits_done");
        this.metaFile = outDir.resolve(".run_meta");
        this.lockFile = outDir.resolve(".lock");
        this.rmMessages = outDir.resolve("refactoringminer_messages.log");
    }

    void run() throws Exception {
        Files.createDirectories(outDir);

        // One run per output folder at a time.
        try (FileChannel lockCh = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lockCh.tryLock()) {
            if (lock == null) {
                fail("Another run is already using " + outDir.toAbsolutePath() + ". Stop it first.");
            }
            runLocked();
        }
    }

    void runLocked() throws Exception {
        // Refuse to continue an output folder made by an older version of the tool.
        if (!Files.exists(metaFile) && (Files.exists(statusFile) || Files.exists(extractionCsv))) {
            fail("The folder " + outDir.toAbsolutePath() + " has results from an older version of this tool.\n"
                    + "Delete everything in it except the 'repo' folder, then run again.");
        }

        cloneIfNeeded();

        try (Repository repo = openRepo(repoDir.resolve(".git"))) {
            // ---- resolve the start commit and list the commits to mine ----
            String ref = startRefGiven != null ? startRefGiven : "HEAD";
            ObjectId startId = repo.resolve(ref + "^{commit}");
            if (startId == null) fail("Start commit '" + ref + "' was not found in the repository.");

            List<RevCommit> commits = new ArrayList<>();
            RevCommit start;
            try (RevWalk walk = new RevWalk(repo)) {
                start = walk.parseCommit(startId);
                walk.markStart(start);
                walk.setRevFilter(RevFilter.NO_MERGES);
                for (RevCommit c : walk) commits.add(c);
            }
            String startHash = start.getName();
            Set<String> walkSet = new HashSet<>();
            for (RevCommit c : commits) walkSet.add(c.getName());

            // ---- metadata guard: same start commit, or refuse ----
            boolean resuming = Files.exists(metaFile);
            if (resuming) {
                Properties meta = new Properties();
                try (Reader r = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) { meta.load(r); }
                String prevStart = meta.getProperty("start_commit", "");
                if (!prevStart.equals(startHash)) {
                    fail("This output folder was started from commit " + prevStart
                            + ",\nbut this run asks for " + startHash + " (" + ref + ").\n"
                            + "Use the same start commit to continue, or delete everything in "
                            + outDir.toAbsolutePath() + " except the 'repo' folder to start over.");
                }
            } else {
                Properties meta = new Properties();
                meta.setProperty("tool_version", TOOL_VERSION);
                meta.setProperty("url", url);
                meta.setProperty("start_ref_given", ref);
                meta.setProperty("start_commit", startHash);
                meta.setProperty("non_merge_commits", String.valueOf(commits.size()));
                meta.setProperty("created", Instant.now().toString());
                try (Writer w = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
                    meta.store(w, "refactoring-extraction run metadata - do not edit");
                }
            }

            log = new PrintWriter(Files.newBufferedWriter(runLog, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));

            // ---- load and clean previous progress so CSV and status always agree ----
            Map<String, String[]> status = loadStatus();          // hash -> [status, rows]
            int notInWalk = 0;
            for (Iterator<String> it = status.keySet().iterator(); it.hasNext(); ) {
                if (!walkSet.contains(it.next())) { it.remove(); notInWalk++; }
            }
            writeStatusCompacted(status);
            int droppedRows = cleanCsv(status);

            int alreadyFinal = 0, toRetry = 0;
            for (String[] s : status.values()) {
                if (s[0].equals(OK) || s[0].equals(ROOT)) alreadyFinal++; else toRetry++;
            }

            logLine("=== run started " + Instant.now() + " (tool version " + TOOL_VERSION + ") ===");
            logLine("repo=" + url);
            logLine("start commit given=" + ref + " resolved=" + startHash + " ("
                    + date(start) + ", \"" + start.getShortMessage().replace('"', '\'') + "\")");
            logLine("non-merge commits from start commit back to the first commit=" + commits.size());
            logLine(resuming
                    ? "resuming: " + alreadyFinal + " commits already finished, " + toRetry
                      + " earlier timeouts/errors to retry, " + (commits.size() - alreadyFinal - toRetry) + " never attempted"
                    : "fresh start");
            if (notInWalk > 0) logLine("ignored " + notInWalk + " recorded commits that are not in this history");
            if (droppedRows > 0) logLine("removed " + droppedRows
                    + " CSV rows from a commit that was being written when the last run stopped (it will be redone)");

            System.out.println(repoName + ": " + commits.size() + " non-merge commits from " + startHash.substring(0, 10)
                    + " back to the first commit; " + alreadyFinal + " already finished, "
                    + (commits.size() - alreadyFinal) + " to do.");

            // ---- tags first, so they exist even if mining is interrupted ----
            int tagCount = writeTags(repo);
            logLine("tags csv written: " + tagCount + " tags");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (worker != null) worker.kill();
                if (!finished && log != null) {
                    logLine("=== run interrupted " + Instant.now() + " after " + processedThisRun
                            + " commits this run; rerun the same command to continue ===");
                }
            }));

            // ---- mine ----
            try (FileOutputStream csvFos = new FileOutputStream(extractionCsv.toFile(), true);
                 BufferedWriter csv = new BufferedWriter(new OutputStreamWriter(csvFos, StandardCharsets.UTF_8));
                 BufferedWriter statusOut = Files.newBufferedWriter(statusFile, StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                if (Files.size(extractionCsv) == 0) { csv.write(CSV_HEADER + "\n"); csv.flush(); }

                worker = new Worker();
                long t0 = System.currentTimeMillis(), lastPrint = t0;
                int todo = commits.size() - alreadyFinal;

                for (RevCommit c : commits) {
                    String hash = c.getName();
                    String[] prev = status.get(hash);
                    if (prev != null && (prev[0].equals(OK) || prev[0].equals(ROOT))) continue;
                    if (prev != null) logLine(hash + " retrying (previous result: " + prev[0] + ")");

                    Result res = worker.mine(hash);
                    int rowCount = 0;
                    switch (res.status) {
                        case OK -> {
                            okThisRun++;
                            Set<String> names = res.rows.isEmpty() ? null : fileNames(repo, c);
                            for (String[] row : res.rows) {
                                String type = row[0], path = row[1], desc = row[2];
                                csv.write(csvLine(repoName, hash, date(c), c.getAuthorIdent().getName(),
                                        c.getAuthorIdent().getEmailAddress(), type, path,
                                        isTestPath(path) ? "test" : "production",
                                        hasTestFileProxy(names, path) ? "yes" : "no", desc));
                            }
                            rowCount = res.rows.size();
                            if (rowCount > 0) {
                                csv.flush();
                                csvFos.getFD().sync(); // rows are on disk before the commit is marked done
                            }
                        }
                        case ROOT -> { rootThisRun++; logLine(hash + " first commit (no parent): " + res.detail); }
                        case TIMEOUT -> { timeoutsThisRun++; logLine(hash + " timeout after " + RM_TIMEOUT_SECONDS + "s (will be retried on the next run)"); }
                        default -> { errorsThisRun++; logLine(hash + " error: " + res.detail + " (will be retried on the next run)"); }
                    }
                    statusOut.write(hash + "\t" + res.status + "\t" + rowCount + "\n");
                    statusOut.flush();
                    status.put(hash, new String[]{res.status, String.valueOf(rowCount)});
                    processedThisRun++;

                    long now = System.currentTimeMillis();
                    if (processedThisRun % 100 == 0 || now - lastPrint > 60_000 || processedThisRun == todo) {
                        lastPrint = now;
                        double min = (now - t0) / 60000.0;
                        double perCommit = (now - t0) / (double) processedThisRun;
                        double etaMin = (todo - processedThisRun) * perCommit / 60000.0;
                        System.out.printf("[%d/%d] %.1f min elapsed, ~%.0f min left, timeouts=%d errors=%d%n",
                                processedThisRun, todo, min, etaMin, timeoutsThisRun, errorsThisRun);
                    }
                }
                worker.kill();
            }

            // ---- final, verifiable summary ----
            finished = true;
            summarize(commits, status);
        } finally {
            synchronized (logLock) {
                if (log != null) { log.close(); log = null; }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Summary and integrity check
    // ------------------------------------------------------------------------------------------

    void summarize(List<RevCommit> commits, Map<String, String[]> status) throws IOException {
        int ok = 0, root = 0, timeouts = 0, errors = 0, missing = 0, withRefs = 0;
        long expectedRows = 0;
        Map<String, Long> expectedPerCommit = new HashMap<>();
        for (RevCommit c : commits) {
            String[] s = status.get(c.getName());
            if (s == null) { missing++; continue; }
            switch (s[0]) {
                case OK -> {
                    ok++;
                    long n = Long.parseLong(s[1]);
                    if (n > 0) { withRefs++; expectedPerCommit.put(c.getName(), n); }
                    expectedRows += n;
                }
                case ROOT -> root++;
                case TIMEOUT -> timeouts++;
                default -> errors++;
            }
        }

        // Count what is really in the CSV, per commit, and compare.
        Map<String, Long> actualPerCommit = new HashMap<>();
        long actualRows = 0;
        try (BufferedReader r = Files.newBufferedReader(extractionCsv, StandardCharsets.UTF_8)) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                actualPerCommit.merge(commitOf(line), 1L, Long::sum);
                actualRows++;
            }
        }
        boolean csvMatches = actualPerCommit.equals(expectedPerCommit);

        int accounted = ok + root + timeouts + errors;
        boolean complete = missing == 0 && timeouts == 0 && errors == 0;

        List<String> lines = new ArrayList<>();
        lines.add("=== run finished " + Instant.now() + " ===");
        lines.add("this run: " + processedThisRun + " commits processed (ok=" + okThisRun + ", first commit=" + rootThisRun
                + ", timeouts=" + timeoutsThisRun + ", errors=" + errorsThisRun + ")");
        lines.add("SUMMARY for all runs in this folder:");
        lines.add("  non-merge commits in history:          " + commits.size());
        lines.add("  analyzed by RefactoringMiner:          " + ok + "  (with at least one refactoring: " + withRefs + ")");
        lines.add("  first commit (no parent to compare):   " + root);
        lines.add("  timed out:                             " + timeouts);
        lines.add("  errors:                                " + errors);
        lines.add("  not attempted yet:                     " + missing);
        lines.add("  accounted for:                         " + accounted + " of " + commits.size());
        lines.add("  refactoring rows in CSV:               " + actualRows
                + (csvMatches ? "  (matches the per-commit counts exactly)" : "  (MISMATCH: expected " + expectedRows + ")"));
        if (complete && csvMatches) {
            lines.add("  STATUS: COMPLETE - every commit in the history was mined.");
        } else if (missing == 0 && csvMatches) {
            lines.add("  STATUS: FINISHED - every commit was attempted; " + (timeouts + errors)
                    + " could not be analyzed (each is listed in run_log.txt with its reason).");
            lines.add("          Rerunning the same command retries only those.");
        } else if (!csvMatches) {
            lines.add("  STATUS: CSV CHECK FAILED - rerun the same command; it repairs the CSV before continuing.");
        } else {
            lines.add("  STATUS: INCOMPLETE - rerun the same command to continue.");
        }
        for (String l : lines) { logLine(l); System.out.println(l); }
        System.out.println("Output in " + outDir.toAbsolutePath());
    }

    // ------------------------------------------------------------------------------------------
    // Persistence helpers
    // ------------------------------------------------------------------------------------------

    /** Reads .commits_done; the last valid line for a commit wins. Partial or malformed lines are ignored. */
    Map<String, String[]> loadStatus() throws IOException {
        Map<String, String[]> status = new LinkedHashMap<>();
        if (!Files.exists(statusFile)) return status;
        for (String line : Files.readAllLines(statusFile, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length != 3 || !p[0].matches("[0-9a-f]{40}") || !p[2].matches("\\d+")) continue;
            if (!Set.of(OK, ROOT, TIMEOUT, ERROR).contains(p[1])) continue;
            status.put(p[0], new String[]{p[1], p[2]});
        }
        return status;
    }

    void writeStatusCompacted(Map<String, String[]> status) throws IOException {
        Path tmp = statusFile.resolveSibling(".commits_done.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String[]> e : status.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
            }
        }
        Files.move(tmp, statusFile, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Keeps only CSV rows that belong to commits recorded as finished. Rows from a commit that was
     * being written when a previous run stopped are removed (that commit is mined again).
     */
    int cleanCsv(Map<String, String[]> status) throws IOException {
        if (!Files.exists(extractionCsv) || Files.size(extractionCsv) == 0) return 0;
        Path tmp = extractionCsv.resolveSibling(extractionCsv.getFileName() + ".tmp");
        int dropped = 0;
        try (BufferedReader r = Files.newBufferedReader(extractionCsv, StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            w.write(CSV_HEADER + "\n");
            if (header != null && !header.equals(CSV_HEADER)) {
                fail("The CSV in " + outDir.toAbsolutePath() + " has an unexpected header. Delete the folder (except 'repo') and start over.");
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] s = status.get(commitOf(line));
                if (s != null && s[0].equals(OK)) { w.write(line); w.write('\n'); }
                else dropped++;
            }
        }
        Files.move(tmp, extractionCsv, StandardCopyOption.REPLACE_EXISTING);
        return dropped;
    }

    /** The commit column (2nd). The project column never contains commas, so a plain split is exact. */
    static String commitOf(String csvLine) {
        String[] p = csvLine.split(",", 3);
        return p.length > 1 ? p[1] : "";
    }

    void cloneIfNeeded() throws Exception {
        if (Files.exists(repoDir.resolve(".git"))) {
            System.out.println("Repo already cloned at " + repoDir + ", reusing.");
            return;
        }
        // Clone into a temporary folder and rename at the end, so an interrupted clone is never reused.
        Path tmp = outDir.resolve("repo.partial");
        if (Files.exists(tmp)) deleteRecursively(tmp);
        System.out.println("Cloning " + url + " into " + repoDir + " ...");
        // No working-tree checkout: RefactoringMiner reads file contents straight from Git history.
        Git.cloneRepository().setURI(url).setDirectory(tmp.toFile()).setNoCheckout(true).call().close();
        Files.move(tmp, repoDir);
    }

    static void deleteRecursively(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                x.toFile().setWritable(true);
                Files.deleteIfExists(x);
            }
        }
    }

    void logLine(String s) {
        synchronized (logLock) {
            if (log == null) return;
            log.println(s);
            log.flush();
        }
    }

    static void fail(String message) {
        System.err.println("ERROR: " + message);
        System.exit(2);
    }

    static Repository openRepo(Path gitDir) throws IOException {
        return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).build();
    }

    static String date(RevCommit c) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(c.getCommitTime())).substring(0, 10);
    }

    // ------------------------------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------------------------------

    static boolean isTestPath(String path) {
        String p = "/" + path;
        for (String m : TEST_MARKERS) if (p.contains(m)) return true;
        return false;
    }

    /** All file names (without directories) in a commit's tree. */
    static Set<String> fileNames(Repository repo, RevCommit commit) throws IOException {
        Set<String> names = new HashSet<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while (tw.next()) names.add(tw.getNameString());
        }
        return names;
    }

    /** Does a correspondingly-named test file (Foo.java -> FooTest.java or TestFoo.java) exist at this commit? */
    static boolean hasTestFileProxy(Set<String> namesInCommit, String filePath) {
        if (namesInCommit == null || filePath.isEmpty()) return false;
        String base = filePath.substring(filePath.lastIndexOf('/') + 1).replaceAll("\\.java$", "");
        return namesInCommit.contains(base + "Test.java") || namesInCommit.contains("Test" + base + ".java");
    }

    // ------------------------------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------------------------------

    int writeTags(Repository repo) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/tags/")) {
                String name = ref.getName().substring("refs/tags/".length());
                try {
                    RevObject obj = walk.parseAny(ref.getObjectId());
                    while (obj instanceof RevTag t) obj = walk.parseAny(t.getObject().getId());
                    if (!(obj instanceof RevCommit c)) continue; // tag on a tree/blob, not a commit
                    walk.parseHeaders(c);
                    rows.add(new String[]{name, date(c), "https://github.com/" + ownerRepo + "/releases/tag/" + name});
                } catch (Exception e) {
                    logLine("tag " + name + " skipped: " + e);
                }
            }
        }
        rows.sort(Comparator.<String[], String>comparing(r -> r[1]).thenComparing(r -> r[0]));
        try (BufferedWriter w = Files.newBufferedWriter(tagsCsv, StandardCharsets.UTF_8)) {
            w.write("tag,tagged_commit_date,url\n");
            for (String[] r : rows) w.write(csvLine(r));
        }
        return rows.size();
    }

    static String csvLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] == null ? "" : fields[i].replace('\r', ' ').replace('\n', ' ');
            if (f.contains(",") || f.contains("\"")) {
                f = "\"" + f.replace("\"", "\"\"") + "\"";
            }
            sb.append(f);
        }
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Worker process: runs RefactoringMiner, one commit at a time, on request from the controller.
    // ------------------------------------------------------------------------------------------

    record Result(String status, String detail, List<String[]> rows) {}

    /** Controller-side handle on the worker process. Killed and restarted after a timeout or crash. */
    class Worker {
        Process proc;
        BufferedWriter toWorker;
        LinkedBlockingQueue<String> fromWorker;
        int starts = 0;

        void start() throws IOException, InterruptedException {
            String javaHome = System.getProperty("java.home");
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            String java = Path.of(javaHome, "bin", windows ? "java.exe" : "java").toString();
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("-Xm") || a.startsWith("-Xss") || a.startsWith("-XX:")) cmd.add(a);
            }
            cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "Main", "--worker",
                    repoDir.resolve(".git").toAbsolutePath().toString()));
            proc = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.appendTo(rmMessages.toFile()))
                    .start();
            starts++;
            toWorker = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
            fromWorker = q;
            InputStream in = proc.getInputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) q.add(l);
                } catch (IOException ignored) {
                }
                q.add("\u0000EOF");
            }, "worker-reader");
            reader.setDaemon(true);
            reader.start();
            String first = q.poll(WORKER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!"READY".equals(first)) {
                kill();
                throw new IOException("RefactoringMiner worker did not start (got: " + first
                        + "). See " + rmMessages.toAbsolutePath());
            }
        }

        Result mine(String hash) throws IOException, InterruptedException {
            if (proc == null || !proc.isAlive()) start();
            toWorker.write(hash + "\n");
            toWorker.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RM_TIMEOUT_SECONDS);
            List<String[]> rows = new ArrayList<>();
            while (true) {
                long left = deadline - System.nanoTime();
                String line = left > 0 ? fromWorker.poll(left, TimeUnit.NANOSECONDS) : null;
                if (line == null) {
                    kill(); // really stops RefactoringMiner; a fresh worker starts for the next commit
                    return new Result(TIMEOUT, "", List.of());
                }
                if (line.equals("\u0000EOF")) {
                    int code = waitExit();
                    proc = null;
                    return new Result(ERROR, "RefactoringMiner worker exited unexpectedly (exit code " + code
                            + ", often out of memory; try java -Xmx4g -jar ...)", List.of());
                }
                String[] p = line.split("\t", -1);
                if (p[0].equals("ROW") && p.length == 4) {
                    rows.add(new String[]{p[1], p[2], p[3]});
                } else if (p[0].equals("DONE") && p.length == 4 && p[1].equals(hash)) {
                    if (p[2].equals(VirtualMachineErrorExit)) { kill(); return new Result(ERROR, p[3], List.of()); }
                    if (p[2].equals(OK) && !p[3].equals(String.valueOf(rows.size()))) {
                        return new Result(ERROR, "incomplete result from worker (" + rows.size() + " of " + p[3] + " rows)", List.of());
                    }
                    return new Result(p[2], p[3], p[2].equals(OK) ? rows : List.of());
                }
            }
        }

        int waitExit() {
            try { return proc.waitFor(10, TimeUnit.SECONDS) ? proc.exitValue() : -1; }
            catch (InterruptedException e) { return -1; }
        }

        void kill() {
            Process p = proc;
            if (p == null) return;
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            try { p.waitFor(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            proc = null;
        }
    }

    static final String VirtualMachineErrorExit = "vmerror";

    static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    static void runWorker(String gitDir) throws Exception {
        // The protocol uses the real stdout; anything libraries print goes to stderr (a log file).
        PrintStream proto = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        try (Repository repo = openRepo(Path.of(gitDir))) {
            GitHistoryRefactoringMinerImpl miner = new GitHistoryRefactoringMinerImpl();
            proto.println("READY");
            String hash;
            while ((hash = in.readLine()) != null) {
                hash = hash.trim();
                if (hash.isEmpty()) continue;

                try (RevWalk w = new RevWalk(repo)) {
                    if (w.parseCommit(repo.resolve(hash)).getParentCount() == 0) {
                        proto.println("DONE\t" + hash + "\t" + ROOT + "\tno parent to compare against");
                        continue;
                    }
                } catch (Exception e) {
                    proto.println("DONE\t" + hash + "\t" + ERROR + "\t" + clean(e.toString()));
                    continue;
                }

                List<String> rows = new ArrayList<>();
                boolean[] answered = {false};
                String[] error = {null};
                try {
                    miner.detectAtCommit(repo, hash, new RefactoringHandler() {
                        @Override public void handle(String id, List<Refactoring> found) {
                            answered[0] = true;
                            for (Refactoring r : found) {
                                String file = !r.leftSide().isEmpty() ? r.leftSide().get(0).getFilePath()
                                        : !r.rightSide().isEmpty() ? r.rightSide().get(0).getFilePath() : "";
                                rows.add("ROW\t" + clean(r.getRefactoringType().getDisplayName()) + "\t"
                                        + clean(file) + "\t" + clean(r.toString()));
                            }
                        }
                        @Override public void handleException(String id, Exception e) {
                            answered[0] = true;
                            error[0] = String.valueOf(e);
                        }
                    });
                } catch (VirtualMachineError e) {
                    proto.println("DONE\t" + hash + "\t" + VirtualMachineErrorExit + "\t" + clean(e.toString()));
                    System.exit(3); // out of memory / stack: start a clean worker
                } catch (Throwable t) {
                    error[0] = String.valueOf(t);
                }

                if (error[0] != null) {
                    proto.println("DONE\t" + hash + "\t" + ERROR + "\t" + clean(error[0]));
                } else if (!answered[0]) {
                    proto.println("DONE\t" + hash + "\t" + ERROR + "\tRefactoringMiner returned no result (see refactoringminer_messages.log)");
                } else {
                    for (String r : rows) proto.println(r);
                    proto.println("DONE\t" + hash + "\t" + OK + "\t" + rows.size());
                }
            }
        }
    }
}
