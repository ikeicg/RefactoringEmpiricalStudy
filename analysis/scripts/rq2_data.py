"""Section 6.2 data for RQ2 (refactoring motivations).

Usage: python3 rq2_data.py <datasets_dir> <messages_dir> <out_dir>
  datasets_dir: refactoring-extraction/datasets
  messages_dir: folder with <project>_messages.txt, made with
      git log --no-merges --format='%H%x1f%aN%x1f%aE%x1f%s%x1f%b%x1e' <snapshot>
Writes rq2_stats.json, rq2_labeling_sample.csv (100 commits per project, seed 6491) and
<project>_commit_messages.csv (every non-merge commit with its message data).
"""
import csv, json, re, sys, random, collections, statistics

PROJECTS = ["checkstyle", "mockito", "jabref"]
REPO = {"checkstyle": "checkstyle/checkstyle", "mockito": "mockito/mockito", "jabref": "JabRef/jabref"}
SEED, SAMPLE = 6491, 100

# --- references in commit messages -------------------------------------------
ISSUE_RX = re.compile(
    r"\bissues?\s*#?\d+"                                   # Issue #123, issue 391 (old Google Code)
    r"|\b(?:fix(?:e[sd])?|close[sd]?|resolve[sd]?)\s*:?\s*#\d+"  # Fixes #123
    r"|github\.com/[\w.-]+/[\w.-]+/issues/\d+"             # issue URL
    r"|[\w.-]+/[\w.-]+#\d+", re.I)                          # owner/repo#123
PR_RX = re.compile(r"\(#\d+\)|\bpull\s*(?:request)?\s*#\d+|github\.com/[\w.-]+/[\w.-]+/pull/\d+", re.I)
ANY_NUM_RX = re.compile(r"(?<![\w/&])#\d+")

# --- AlOmar et al. (IWoR 2019), Table III: 87 self-affirmed refactoring patterns ---
SAR_KEYWORDS = ["refactor", "move", "split", "fix", "introduce", "decompose", "reorganize", "extract",
                "merge", "rename", "change", "restructure", "reformat", "extend", "remove", "replace",
                "rewrite", "simplify", "create", "improve", "add", "modify", "enhance", "rework", "inline",
                "redesign", "cleanup", "reduce"]  # patterns 1-29 (29 repeats "modify")
SAR_PHRASES = ["removed poor coding practice", "improve naming consistency", "removing unused classes",
               "pull some code up", "use better name", "replace it with", "make maintenance easier",
               "code cleanup", "minor simplification", "reorganize project structures",
               "code maintenance for refactoring", "remove redundant code", "moved and gave clearer names to",
               "refactor bad designed code", "getting code out of", "deleting a lot of old stuff",
               "code revision", "fix technical debt", "fix quality issue", "antipattern bad for performances",
               "major/minor structural changes", "clean up unnecessary code", "code reformatting & reordering",
               "nicer code / formatted / structure", "simplify code redundancies",
               "added more checks for quality factors", "naming improvements", "renamed for consistency",
               "refactoring towards nicer name analysis", "change design", "modularize the code",
               "code cosmetics", "moved more code out of", "remove dependency", "enhanced code beauty",
               "simplify internal design", "change package structure", "use a safer method",
               "code improvements", "minor enhancement", "get rid of unused code", "fixing naming convention",
               "fix module structure", "code optimization", "fix a design flaw", "nonfunctional code cleanup",
               "improve code quality", "fix code smell", "use less code", "avoid future confusion",
               "more easily extended", "polishing code", "move unused file away", "many cosmetic changes",
               "inlined unnecessary classes", "code cleansing", "fix quality flaws", "simplify the code"]
# Operation-specific subset: keywords that name a refactoring operation rather than any change.
SAR_SPECIFIC = ["refactor", "move", "split", "decompose", "reorganize", "extract", "merge", "rename",
                "restructure", "reformat", "rewrite", "simplify", "rework", "inline", "redesign", "cleanup",
                "clean up", "clean-up", "pull up", "push down"]


def kw_rx(words):
    return re.compile(r"\b(?:" + "|".join(re.escape(w) for w in words) + r")", re.I)


SAR_ALL_RX = kw_rx(SAR_KEYWORDS + SAR_PHRASES)
SAR_SPECIFIC_RX = kw_rx(SAR_SPECIFIC)
REFACTOR_RX = re.compile(r"\brefactor", re.I)
PREFIX_RX = re.compile(r"^\s*(?:(?:issue|pull|fix(?:e[sd])?)\s*#?\d+\s*[:.,-]?\s*|[a-z]+(?:\([^)]*\))?:\s*)", re.I)


TRAILER_RX = re.compile(r"^\s*(signed-off-by|co-authored-by|reviewed-by|reported-by|acked-by|tested-by|"
                        r"suggested-by|helped-by|cc)\s*:.*$", re.I | re.M)
EMAIL_RX = re.compile(r"[\w.+-]+@[\w-]+(\.[\w-]+)+")


def scrub(t):
    """Output only: drop sign-off/co-author trailer lines and mask e-mail addresses. Statistics use the raw text."""
    return EMAIL_RX.sub("[e-mail]", re.sub(r"\n{3,}", "\n\n", TRAILER_RX.sub("", t))).strip()


def is_bot(name, email):
    s = (name + " " + email).lower()
    return "[bot]" in s or "dependabot" in s or "github-actions" in s or "renovate" in s


def informative_words(subject):
    s = PREFIX_RX.sub("", subject)
    s = PR_RX.sub("", ANY_NUM_RX.sub("", s))
    return len(re.findall(r"[A-Za-z]{2,}", s))


def pct(a, b):
    return round(100 * a / b, 1) if b else 0.0


def main(ds, md, out):
    stats, sample_rows = {}, []
    for p in PROJECTS:
        rows = list(csv.DictReader(open(f"{ds}/{p}/{p}_extraction.csv", newline="", encoding="utf-8")))
        ref_types = collections.defaultdict(collections.Counter)
        ref_date = {}  # filled below
        for x in rows:
            ref_types[x["commit"]][x["refactoring_type"]] += 1
            ref_date[x["commit"]] = x["commit_date"]
        msgs = {}
        for rec in open(f"{md}/{p}_messages.txt", encoding="utf-8", errors="replace").read().split("\x1e"):
            f = rec.strip("\n").split("\x1f")
            if len(f) >= 5:
                msgs[f[0]] = {"name": f[1], "email": f[2], "subject": f[3], "body": f[4].strip()}
        bot = {h for h, m in msgs.items() if is_bot(m["name"], m["email"])}
        refc = [h for h in ref_types if h not in bot]                  # refactoring commits, humans only
        nonref = [h for h in msgs if h not in ref_types and h not in bot]  # no refactoring detected
        s = {"ref_commits_human": len(refc), "ref_commits_bot": len(ref_types) - len(refc),
             "nonref_commits_human": len(nonref)}
        text = lambda h: msgs[h]["subject"] + "\n" + msgs[h]["body"]
        # 1. links to issues / pull requests
        has_issue = {h for h in refc if ISSUE_RX.search(text(h))}
        has_pr = {h for h in refc if PR_RX.search(text(h))}
        s["issue_ref"] = len(has_issue); s["issue_ref_pct"] = pct(len(has_issue), len(refc))
        s["pr_only"] = len(has_pr - has_issue); s["pr_only_pct"] = pct(len(has_pr - has_issue), len(refc))
        has_bare = {h for h in refc if ANY_NUM_RX.search(text(h))} - has_issue - has_pr
        s["bare_only"] = len(has_bare); s["bare_only_pct"] = pct(len(has_bare), len(refc))
        anyref = has_issue | has_pr | has_bare
        s["any_ref"] = len(anyref); s["any_ref_pct"] = pct(len(anyref), len(refc))
        s["no_ref"] = len(refc) - len(anyref); s["no_ref_pct"] = pct(s["no_ref"], len(refc))
        for since in (2015, 2022):
            era = [h for h in refc if int(ref_date[h][:4]) >= since]
            s[f"any_ref_since_{since}"] = len(era)
            s[f"any_ref_since_{since}_pct"] = pct(sum(1 for h in era if h in anyref), len(era))
        early = [h for h in refc if int(ref_date[h][:4]) < 2015]
        s["any_ref_before_2015"] = len(early)
        s["any_ref_before_2015_pct"] = pct(sum(1 for h in early if h in anyref), len(early))
        # 2. message informativeness
        words = [informative_words(msgs[h]["subject"]) for h in refc]
        s["subject_words_median"] = statistics.median(words)
        s["short_subject"] = sum(1 for w in words if w <= 3); s["short_subject_pct"] = pct(s["short_subject"], len(refc))
        s["has_body"] = sum(1 for h in refc if msgs[h]["body"]); s["has_body_pct"] = pct(s["has_body"], len(refc))
        # 3. self-affirmed refactoring (SAR) patterns
        s["refactor_word"] = sum(1 for h in refc if REFACTOR_RX.search(text(h)))
        s["refactor_word_pct"] = pct(s["refactor_word"], len(refc))
        s["sar_specific"] = sum(1 for h in refc if SAR_SPECIFIC_RX.search(text(h)))
        s["sar_specific_pct"] = pct(s["sar_specific"], len(refc))
        s["sar_any"] = sum(1 for h in refc if SAR_ALL_RX.search(text(h)))
        s["sar_any_pct"] = pct(s["sar_any"], len(refc))
        s["nonref_sar_any_pct"] = pct(sum(1 for h in nonref if SAR_ALL_RX.search(text(h))), len(nonref))
        s["nonref_sar_specific_pct"] = pct(sum(1 for h in nonref if SAR_SPECIFIC_RX.search(text(h))), len(nonref))
        # 4. the word "refactor" vs what RefactoringMiner found
        mention = [h for h in msgs if h not in bot and REFACTOR_RX.search(text(h))]
        detected = [h for h in mention if h in ref_types]
        s["mention_refactor"] = len(mention)
        s["mention_refactor_detected"] = len(detected)
        s["mention_refactor_detected_pct"] = pct(len(detected), len(mention))
        # 5. tangling proxy: refactoring commits whose message also describes a fix or a feature
        fix_rx = re.compile(r"\b(fix(e[sd]|ing)?|bugs?|bugfix|errors?|crash(es)?|npe|exceptions?|regression)\b", re.I)
        feat_rx = re.compile(r"\b(add(s|ed)?|new|support|implement(s|ed)?|feature|introduc(e|es|ed))\b", re.I)
        clean = {h: PR_RX.sub("", ISSUE_RX.sub("", PREFIX_RX.sub("", msgs[h]["subject"]))) for h in refc}
        s["mentions_fix_pct"] = pct(sum(1 for h in refc if fix_rx.search(clean[h])), len(refc))
        s["mentions_feature_pct"] = pct(sum(1 for h in refc if feat_rx.search(clean[h])), len(refc))
        # per-commit message data (every non-merge commit, bots flagged; no author names or e-mails)
        fix_c = lambda h: bool(fix_rx.search(PR_RX.sub("", ISSUE_RX.sub("", PREFIX_RX.sub("", msgs[h]["subject"])))))
        feat_c = lambda h: bool(feat_rx.search(PR_RX.sub("", ISSUE_RX.sub("", PREFIX_RX.sub("", msgs[h]["subject"])))))
        refs = lambda rx, t: " ".join(sorted({"#" + n for mm in rx.finditer(t) for n in re.findall(r"\d+", mm.group(0))[-1:]}))
        with open(f"{out}/{p}_commit_messages.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["project", "commit", "is_bot", "refactorings", "issue_refs", "pr_refs", "other_refs",
                        "has_any_ref", "refactor_word", "sar_any", "sar_specific", "mentions_fix", "mentions_feature",
                        "subject_informative_words", "has_body", "subject", "body"])
            for h, m in msgs.items():
                t = text(h)
                ir, pr = refs(ISSUE_RX, t), refs(PR_RX, t)
                other = " ".join(sorted(set(re.findall(r"(?<![\w/&])#\d+", t)) - set((ir + " " + pr).split())))
                yn = lambda b: "yes" if b else "no"
                w.writerow([p, h, yn(h in bot), sum(ref_types[h].values()) if h in ref_types else 0, ir, pr, other,
                            yn(ir or pr or other), yn(REFACTOR_RX.search(t)), yn(SAR_ALL_RX.search(t)),
                            yn(SAR_SPECIFIC_RX.search(t)), yn(fix_c(h)), yn(feat_c(h)),
                            informative_words(m["subject"]), yn(m["body"]), scrub(m["subject"]), scrub(m["body"])])
        stats[p] = s
        # 6. labeling sample: refactoring commits with production-code refactorings, humans only
        prod = {x["commit"] for x in rows if x["code_kind"] == "production"
                and "/src/testinputs/" not in "/" + x["file_path"] and "/src/databaseTest/" not in "/" + x["file_path"]}
        frame = sorted(h for h in refc if h in prod)
        rnd = random.Random(SEED)
        rnd.shuffle(frame)
        s["sample_frame"] = len(frame)
        for i, h in enumerate(frame[:SAMPLE], 1):
            m = msgs[h]
            sample_rows.append({
                "id": f"{p[0]}{i:03d}", "project": p, "commit": h, "date": ref_date[h],
                "url": f"https://github.com/{REPO[p]}/commit/{h}",
                "refactorings": sum(ref_types[h].values()),
                "main_types": "; ".join(f"{t} ({n})" for t, n in ref_types[h].most_common(3)),
                "issue_refs": " ".join(sorted({"#" + n for mm in ISSUE_RX.finditer(text(h)) for n in re.findall(r"\d+", mm.group(0))[-1:]})),
                "pr_refs": " ".join(sorted({"#" + n for mm in PR_RX.finditer(text(h)) for n in re.findall(r"\d+", mm.group(0))[-1:]})),
                "other_refs": " ".join(sorted(set(re.findall(r"(?<![\w/&])#\d+", text(h)))
                                              - {"#" + n for mm in list(ISSUE_RX.finditer(text(h))) + list(PR_RX.finditer(text(h))) for n in re.findall(r"\d+", mm.group(0))[-1:]})),
                "subject": scrub(m["subject"]), "body": scrub(m["body"])[:500].replace("\n", " "),
                "motivation_rater1": "", "motivation_rater2": "", "agreed_motivation": "", "notes": ""})
        smp = [r for r in sample_rows if r["project"] == p]
        s["sample_issue_pct"] = pct(sum(1 for r in smp if r["issue_refs"]), len(smp))
        s["sample_pr_pct"] = pct(sum(1 for r in smp if r["pr_refs"]), len(smp))
        s["sample_any_pct"] = pct(sum(1 for r in smp if r["issue_refs"] or r["pr_refs"] or r["other_refs"]), len(smp))
    json.dump(stats, open(f"{out}/rq2_stats.json", "w"), indent=1)
    with open(f"{out}/rq2_labeling_sample.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(sample_rows[0]))
        w.writeheader(); w.writerows(sample_rows)
    return stats


if __name__ == "__main__":
    for p, s in main(*sys.argv[1:4]).items():
        print("=====", p)
        for k, v in s.items():
            print(" ", k, v)
