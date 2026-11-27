#!/usr/bin/env python3
"""Validates the data repository of My Alarm English (see tutor/TUTOR.md).

Usage: python3 tutor/validate.py <data repo dir>

Checks tasks/, reviews/, answers/ and profile.json against the format the app reads
(app/src/main/java/io/github/juxtapose161/myalarm/course/DataModel.kt) and the way the
phone checks answers (LocalCheck.kt). Exit code 1 if anything is wrong.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CURRICULUM = os.path.join(HERE, "..", "app", "src", "main", "java", "io", "github", "juxtapose161",
                          "myalarm", "course", "Curriculum.kt")
LEVELS = ["A1", "A2", "B1", "B2", "C1", "C2"]
KINDS = {
    "DIALOG": "dialog", "RETELL": "main+items", "REPHRASE": "items", "ERRORS": "single",
    "BACK_TRANSLATE": "single", "CUE_CARD": "single", "DAY_PLAN": "single", "YESTERDAY": "single",
    "DICTATION": "dictation",
}
ANSWER_KINDS = set(KINDS) | {"TRANSLATE", "QUICK"}
NEUTRAL = "i like tea and coffee. my friend lives in a small town near the sea"
FORBIDDEN_REGEX = [r"\p{", r"(?>", r"*+", r"++", r"?+"]

TASK_FIELDS = {
    "id": str, "kind": str, "level": str, "createdAt": str, "title": str, "instructionsRu": str,
    "material": str, "hideMaterial": bool, "speakMaterial": bool, "prepSeconds": int, "minWords": int,
    "items": list, "words": list, "needWords": int, "grammar": list, "needGrammar": int,
    "points": list, "needPoints": int, "errors": list, "needErrors": int, "needLinkers": int,
    "minMinutes": int, "dictation": str,
}
REQUIRED_TASK = {"id", "kind", "level", "title", "instructionsRu"}


def normalize(s):
    s = s.lower().replace("’", "'").replace("‘", "'").replace("`", "'")
    return re.sub(r"\s+", " ", s).strip()


def grammar_ids():
    with open(CURRICULUM, encoding="utf-8") as f:
        return set(re.findall(r'g\("(\w+)"', f.read()))


class Report:
    def __init__(self):
        self.errors = []

    def err(self, where, msg):
        self.errors.append(f"{where}: {msg}")


def check_types(r, where, obj, fields, required):
    if not isinstance(obj, dict):
        r.err(where, "must be a JSON object")
        return False
    for k in required:
        if k not in obj:
            r.err(where, f"missing field '{k}'")
    for k, v in obj.items():
        if k not in fields:
            r.err(where, f"unknown field '{k}'")
        elif not isinstance(v, fields[k]) or (fields[k] is int and isinstance(v, bool)):
            r.err(where, f"field '{k}' must be {fields[k].__name__}")
    return True


def check_grammar(r, where, g, ids):
    if not check_types(r, where, g, {"id": str, "pattern": str, "hintRu": str, "examples": list}, {"id", "pattern"}):
        return
    if g.get("id") not in ids:
        r.err(where, f"unknown grammar id '{g.get('id')}' (see Curriculum.kt)")
    pattern = g.get("pattern", "")
    for bad in FORBIDDEN_REGEX:
        if bad in pattern:
            r.err(where, f"pattern uses non-portable syntax '{bad}'")
    try:
        rx = re.compile(pattern, re.IGNORECASE)
    except re.error as e:
        r.err(where, f"pattern does not compile: {e}")
        return
    if rx.search(""):
        r.err(where, "pattern matches an empty answer")
    if rx.search(NEUTRAL):
        r.err(where, "pattern matches a neutral sentence without the construction: " + NEUTRAL)
    examples = g.get("examples", [])
    if len(examples) < 2:
        r.err(where, "give at least 2 examples")
    for ex in examples:
        if not isinstance(ex, str) or not rx.search(normalize(ex)):
            r.err(where, f"example does not match the pattern: {ex!r}")


def check_word(r, where, w):
    check_types(r, where, w, {"word": str, "forms": list}, {"word"})
    if isinstance(w, dict) and not all(isinstance(x, str) for x in w.get("forms", [])):
        r.err(where, "forms must be strings")


def check_point(r, where, p):
    check_types(r, where, p, {"nameRu": str, "anyOf": list}, {"nameRu", "anyOf"})
    if isinstance(p, dict):
        alts = p.get("anyOf", [])
        if len(alts) < 2:
            r.err(where, "give several alternatives in anyOf (the check is literal)")
        if not all(isinstance(x, str) and x.strip() for x in alts):
            r.err(where, "anyOf must be non-empty strings")


def need(r, where, task, field, count_field):
    n = task.get(count_field, 0)
    lst = task.get(field, [])
    if n > len(lst):
        r.err(where, f"{count_field}={n} but only {len(lst)} {field}")


def check_task(r, path, task, ids):
    where = path
    if not check_types(r, where, task, TASK_FIELDS, REQUIRED_TASK):
        return
    stem = os.path.basename(path)[:-5]
    if task.get("id") != stem:
        r.err(where, f"id '{task.get('id')}' differs from the file name '{stem}'")
    kind = task.get("kind")
    if kind not in KINDS:
        r.err(where, f"unknown kind '{kind}'")
        return
    if task.get("level") not in LEVELS:
        r.err(where, f"level must be one of {LEVELS}")
    for i, w in enumerate(task.get("words", [])):
        check_word(r, f"{where} words[{i}]", w)
    for i, g in enumerate(task.get("grammar", [])):
        check_grammar(r, f"{where} grammar[{i}]", g, ids)
    for i, p in enumerate(task.get("points", [])):
        check_point(r, f"{where} points[{i}]", p)
    need(r, where, task, "words", "needWords")
    need(r, where, task, "grammar", "needGrammar")
    need(r, where, task, "points", "needPoints")
    need(r, where, task, "errors", "needErrors")
    items = task.get("items", [])
    for i, it in enumerate(items):
        w = f"{where} items[{i}]"
        if not check_types(r, w, it, {"text": str, "points": list, "needPoints": int, "grammar": (dict, type(None)), "minWords": int}, {"text"}):
            continue
        for j, p in enumerate(it.get("points", [])):
            check_point(r, f"{w} points[{j}]", p)
        if it.get("needPoints", 0) > len(it.get("points", [])):
            r.err(w, "needPoints is larger than the number of points")
        if it.get("grammar") is not None:
            check_grammar(r, f"{w} grammar", it["grammar"], ids)

    mode = KINDS[kind]
    material = normalize(task.get("material", ""))
    if mode == "dictation":
        text = task.get("dictation", "")
        gaps = re.findall(r"\[\[([^\]]+)\]\]", text)
        if len(gaps) < 3:
            r.err(where, "dictation needs at least 3 [[gaps]]")
        if any(" " in g.strip() for g in gaps):
            r.err(where, "each gap is a single word")
        if re.search(r"\]\]\s*\[\[", text):
            r.err(where, "two gaps next to each other")
        return
    if kind == "DIALOG":
        if len(items) < 3:
            r.err(where, "a dialog needs at least 3 partner lines in items")
    if kind == "RETELL":
        if not material or not task.get("hideMaterial"):
            r.err(where, "a retell needs material with hideMaterial true")
        if task.get("minWords", 0) <= 0:
            r.err(where, "a retell needs minWords")
    if kind == "REPHRASE":
        if not items or any(it.get("grammar") is None for it in items if isinstance(it, dict)):
            r.err(where, "every rephrase item needs a grammar check")
    if kind == "ERRORS":
        errs = task.get("errors", [])
        if not errs:
            r.err(where, "no planted errors")
        for i, e in enumerate(errs):
            w = f"{where} errors[{i}]"
            if not check_types(r, w, e, {"wrong": str, "right": list, "whyRu": str}, {"wrong", "right"}):
                continue
            if normalize(e.get("wrong", "")) not in material:
                r.err(w, "the wrong fragment is not in the material")
            for frag in e.get("right", []):
                if re.search(r"(?<![a-z'])" + re.escape(normalize(frag)) + r"(?![a-z])", material):
                    r.err(w, f"right fragment {frag!r} already appears in the material")
    if kind == "CUE_CARD" and task.get("minWords", 0) <= 0:
        r.err(where, "a cue card needs minWords")
    if kind in ("DAY_PLAN", "YESTERDAY") and task.get("needGrammar", 0) <= 0:
        r.err(where, "needs needGrammar")


def check_review(r, path, rev):
    fields = {"taskId": str, "kind": str, "title": str, "reviewedAt": str, "passed": bool, "criteria": list,
              "corrections": list, "feedbackRu": str, "modelAnswer": str}
    if not check_types(r, path, rev, fields, {"taskId", "passed", "feedbackRu"}):
        return
    for i, c in enumerate(rev.get("criteria", [])):
        check_types(r, f"{path} criteria[{i}]", c, {"name": str, "ok": bool, "comment": str}, {"name", "ok"})
    for i, c in enumerate(rev.get("corrections", [])):
        check_types(r, f"{path} corrections[{i}]", c, {"wrong": str, "right": str, "why": str}, {"wrong", "right"})


def check_profile(r, path, p, ids):
    fields = {"level": str, "updatedAt": str, "summary": str, "grammar": dict, "words": dict, "strengths": list,
              "weaknesses": list, "nextFocus": list, "history": list}
    if not check_types(r, path, p, fields, {"level"}):
        return
    if p.get("level") not in LEVELS:
        r.err(path, "bad level")
    for gid, s in p.get("grammar", {}).items():
        if gid not in ids:
            r.err(path, f"unknown grammar id '{gid}'")
        check_types(r, f"{path} grammar.{gid}", s, {"ok": int, "errors": int, "lastDay": str}, set())
    for w, s in p.get("words", {}).items():
        check_types(r, f"{path} words.{w}", s, {"used": int, "missed": int}, set())
    for i, h in enumerate(p.get("history", [])):
        check_types(r, f"{path} history[{i}]", h, {"day": str, "kind": str, "title": str, "passed": bool, "note": str},
                    {"day", "kind", "title", "passed"})
    if len(p.get("summary", "")) > 900:
        r.err(path, "summary is too long (keep it under 600 characters)")


def load(r, path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:  # noqa: BLE001
        r.err(path, f"not valid JSON: {e}")
        return None


def main(root):
    r = Report()
    ids = grammar_ids()
    for sub, fn in (("tasks", lambda p, d: check_task(r, p, d, ids)), ("reviews", lambda p, d: check_review(r, p, d))):
        d = os.path.join(root, sub)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if name.endswith(".json"):
                path = os.path.join(d, name)
                data = load(r, path)
                if data is not None:
                    fn(path, data)
    for sub in ("answers", os.path.join("archive", "answers")):
        d = os.path.join(root, sub)
        if os.path.isdir(d):
            for name in sorted(os.listdir(d)):
                if name.endswith(".json"):
                    data = load(r, os.path.join(d, name))
                    if data is not None and data.get("kind") not in ANSWER_KINDS:
                        r.err(os.path.join(d, name), f"unknown kind {data.get('kind')}")
    prof = os.path.join(root, "profile.json")
    if os.path.exists(prof):
        data = load(r, prof)
        if data is not None:
            check_profile(r, prof, data, ids)
    if r.errors:
        print("\n".join(r.errors))
        print(f"\n{len(r.errors)} problem(s).")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
