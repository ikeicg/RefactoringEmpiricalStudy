import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;

import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Single-jar refactoring extraction tool.
 *
 * Usage: java -jar extract.jar <github-url> [start-commit]
 *
 * Clones the repo, walks commits newest-first (no merges) from start-commit (default: HEAD)
 * back to the first commit, runs RefactoringMiner per commit, and writes:
 *   <repo-name>_output/<repo-name>_extraction.csv   one row per refactoring instance
 *   <repo-name>_output/<repo-name>_releases.csv     tag, date, URL
 *   <repo-name>_output/run_log.txt                  what ran, what failed, timing
 *
 * Resumable: rerunning the same command skips commits already recorded in run_log.txt.
 */
public class Main {

    static final int RM_TIMEOUT_SECONDS = 300;
    static final List<String> TEST_MARKERS = List.of(
            "/test/", "/tests/", "/testFixtures/", "/androidTest/", "/integrationTest/",
            "/src/it/", "/src/xdocs-examples/");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar extract.jar <github-url> [start-commit]");
            System.exit(1);
        }
        String url = args[0];
        String startRef = args.length > 1 ? args[1] : null;

        String repoName = url.replaceAll("\\.git$", "");
        repoName = repoName.substring(repoName.lastIndexOf('/') + 1);
        String ownerRepo = url.replaceAll("^https://github.com/", "").replaceAll("\\.git$", "");

        Path outDir = Path.of(repoName + "_output");
        Path repoDir = outDir.resolve("repo");
        Files.createDirectories(outDir);

        if (!Files.exists(repoDir.resolve(".git"))) {
            System.out.println("Cloning " + url + " into " + repoDir + " ...");
            Git.cloneRepository().setURI(url).setDirectory(repoDir.toFile()).call().close();
        } else {
            System.out.println("Repo already cloned at " + repoDir + ", reusing.");
        }

        Path extractionCsv = outDir.resolve(repoName + "_extraction.csv");
        Path releasesCsv = outDir.resolve(repoName + "_releases.csv");
        Path runLog = outDir.resolve("run_log.txt");
        Path doneMarker = outDir.resolve(".commits_done");

        Set<String> alreadyDone = new HashSet<>();
        if (Files.exists(doneMarker)) {
            alreadyDone.addAll(Files.readAllLines(doneMarker));
        }
        boolean freshStart = alreadyDone.isEmpty();

        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).build()) {

            // ---- 1. walk the commit list ----
            List<RevCommit> commits = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit start = walk.parseCommit(repo.resolve(startRef != null ? startRef : "HEAD"));
                walk.markStart(start);
                walk.setRevFilter(RevFilter.NO_MERGES);
                for (RevCommit c : walk) commits.add(c);
            }
            System.out.println(commits.size() + " non-merge commits to walk (from "
                    + (startRef != null ? startRef : "HEAD") + " back to the start of history).");

            try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(runLog,
                         freshStart ? StandardOpenOption.CREATE : StandardOpenOption.APPEND,
                         freshStart ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE));
                 BufferedWriter csv = Files.newBufferedWriter(extractionCsv,
                         Files.exists(extractionCsv) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
                 BufferedWriter doneOut = Files.newBufferedWriter(doneMarker, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                if (!Files.exists(extractionCsv) || Files.size(extractionCsv) == 0) {
                    csv.write("project,commit,commit_date,author_name,author_email,refactoring_type,file_path,code_kind,has_test_file_proxy\n");
                }

                log.println("=== run started " + Instant.now() + " ===");
                log.println("repo=" + url + " startRef=" + (startRef != null ? startRef : "HEAD")
                        + " commitsToWalk=" + commits.size() + " alreadyDone=" + alreadyDone.size());
                log.flush();

                int processed = 0, skipped = 0, timeouts = 0, errors = 0, withRef = 0;
                long t0 = System.currentTimeMillis();

                for (RevCommit c : commits) {
                    String hash = c.getName();
                    if (alreadyDone.contains(hash)) { skipped++; continue; }

                    String authorName = c.getAuthorIdent().getName();
                    String authorEmail = c.getAuthorIdent().getEmailAddress();
                    String date = DateTimeFormatter.ISO_INSTANT.format(
                            Instant.ofEpochSecond(c.getCommitTime())).substring(0, 10);

                    boolean[] answered = {false};
                    String[] error = {null};
                    List<String[]> rows = new ArrayList<>(); // [type, filePath]

                    try (Repository repo2 = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).build()) {
                        new GitHistoryRefactoringMinerImpl().detectAtCommit(repo2, hash, new RefactoringHandler() {
                            @Override public void handle(String id, List<Refactoring> found) {
                                answered[0] = true;
                                for (Refactoring r : found) {
                                    String type = r.getRefactoringType().getDisplayName();
                                    Set<String> files = new TreeSet<>();
                                    r.leftSide().forEach(cr -> files.add(cr.getFilePath()));
                                    for (String f : files) rows.add(new String[]{type, f});
                                }
                            }
                            @Override public void handleException(String id, Exception e) {
                                answered[0] = true;
                                error[0] = String.valueOf(e);
                            }
                        }, RM_TIMEOUT_SECONDS);
                    } catch (Exception e) {
                        error[0] = String.valueOf(e);
                    }

                    if (!answered[0]) {
                        timeouts++;
                        log.println(hash + " timeout after " + RM_TIMEOUT_SECONDS + "s");
                    } else if (error[0] != null) {
                        errors++;
                        log.println(hash + " error: " + error[0].replace('\n', ' '));
                    } else {
                        if (!rows.isEmpty()) withRef++;
                        for (String[] row : rows) {
                            String type = row[0], path = row[1];
                            String kind = isTestPath(path) ? "test" : "production";
                            String testProxy = hasTestFileProxy(repo, c, path) ? "yes" : "no";
                            csv.write(csvLine(repoName, hash, date, authorName, authorEmail, type, path, kind, testProxy));
                        }
                    }

                    doneOut.write(hash + "\n");
                    doneOut.flush();
                    processed++;
                    if (processed % 200 == 0) {
                        csv.flush();
                        log.flush();
                        double min = (System.currentTimeMillis() - t0) / 60000.0;
                        System.out.printf("[%d/%d] %.1f min elapsed, timeouts=%d errors=%d withRefactorings=%d%n",
                                processed, commits.size() - skipped, min, timeouts, errors, withRef);
                    }
                }

                csv.flush();
                log.println("=== run finished " + Instant.now() + " ===");
                log.println("processed=" + processed + " skippedAlreadyDone=" + skipped
                        + " timeouts=" + timeouts + " errors=" + errors + " commitsWithRefactorings=" + withRef);
                log.flush();
                System.out.println("Done. processed=" + processed + " timeouts=" + timeouts
                        + " errors=" + errors + " commitsWithRefactorings=" + withRef);
            }

            // ---- 2. releases: from Git tags (no GitHub API/auth needed) ----
            writeReleases(repo, releasesCsv, ownerRepo);
        }

        System.out.println("Output in " + outDir.toAbsolutePath());
    }

    static boolean isTestPath(String path) {
        String p = "/" + path;
        for (String m : TEST_MARKERS) if (p.contains(m)) return true;
        return false;
    }

    /** Does a correspondingly-named test file exist in this commit's tree? Cheap proxy for "is this testable". */
    static boolean hasTestFileProxy(Repository repo, RevCommit commit, String filePath) {
        String base = filePath.substring(filePath.lastIndexOf('/') + 1).replaceAll("\\.java$", "");
        String candidate1 = base + "Test.java";
        String candidate2 = "Test" + base + ".java";
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                String name = tw.getNameString();
                if (name.equals(candidate1) || name.equals(candidate2)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static void writeReleases(Repository repo, Path releasesCsv, String ownerRepo) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(releasesCsv))) {
            w.println("tag,date,url");
            Map<String, org.eclipse.jgit.lib.Ref> tags = repo.getTags();
            List<String> names = new ArrayList<>(tags.keySet());
            try (RevWalk walk = new RevWalk(repo)) {
                List<String[]> rows = new ArrayList<>();
                for (String name : names) {
                    try {
                        org.eclipse.jgit.lib.ObjectId oid = tags.get(name).getObjectId();
                        RevCommit c;
                        try {
                            c = walk.parseCommit(oid);
                        } catch (org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
                            org.eclipse.jgit.revwalk.RevTag tag = walk.parseTag(oid);
                            c = walk.parseCommit(tag.getObject());
                        }
                        String date = DateTimeFormatter.ISO_INSTANT.format(
                                Instant.ofEpochSecond(c.getCommitTime())).substring(0, 10);
                        rows.add(new String[]{name, date,
                                "https://github.com/" + ownerRepo + "/releases/tag/" + name});
                    } catch (Exception ignored) {
                        // tag that doesn't resolve to a commit cleanly; skip
                    }
                }
                rows.sort(Comparator.comparing(r -> r[1]));
                for (String[] r : rows) w.println(csvLine(r[0], r[1], r[2]));
            }
        }
        System.out.println("Wrote " + releasesCsv);
    }

    static String csvLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] == null ? "" : fields[i];
            if (f.contains(",") || f.contains("\"") || f.contains("\n")) {
                f = "\"" + f.replace("\"", "\"\"") + "\"";
            }
            sb.append(f);
        }
        sb.append('\n');
        return sb.toString();
    }
}
