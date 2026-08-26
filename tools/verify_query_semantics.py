#!/usr/bin/env python3
"""Differential check of the Data X-Ray query semantics the workflows rely on.

The search / rerun scripts compose `/api/v1/files?q=` queries as:

    labels.name:"L" AND extractedMetadata.name:"E"
    AND annotators.name:"A" AND annotators.name:"B"           # every criterion AND-ed
    AND annotators: { (name:"A" OR name:"C") AND annotations.phrase:"*text*" }
                                                              # phrase OR-ed across its annotators

This script proves those semantics against a live instance by comparing the
fileId set of each composed query with the set algebra of its components
(intersection for AND, union for OR), and checks row-level evidence.

Credentials: DXR_BASE_URL + DXR_API_KEY env vars (or the harness .env).
Optional overrides: --annotator-a/--annotator-b/--annotator-c/--extractor/--phrase;
otherwise they are discovered from a bounded sample of file rows.

    python packs/dxr-workflows/tools/verify_query_semantics.py
"""
import argparse
import collections
import itertools
import json
import os
import sys

import requests

MAX_ROWS = 20_000        # safety cap per query; the run aborts if any query hits it
SAMPLE_ROWS = 400        # rows scanned to discover test classifications


def load_env():
    try:
        from dotenv import load_dotenv
        for p in ('.env', os.path.join(os.path.dirname(__file__), '..', '..', '..', '.env')):
            if os.path.exists(p):
                load_dotenv(p, override=False)
                break
    except ImportError:
        pass
    base, key = os.environ.get('DXR_BASE_URL', '').rstrip('/'), os.environ.get('DXR_API_KEY', '')
    if not base or not key:
        sys.exit('DXR_BASE_URL and DXR_API_KEY must be set')
    if not base.startswith('http'):
        base = 'https://' + base
    return base, key


class Dxr:
    def __init__(self, base, key):
        self.base, self.h = base, {'Authorization': f'Bearer {key}'}
        self.calls = 0

    def files(self, q, limit=MAX_ROWS):
        """Stream /api/v1/files?q=… → (rows, truncated)."""
        self.calls += 1
        r = requests.get(f'{self.base}/api/v1/files', headers=self.h, params={'q': q}, stream=True, timeout=120)
        if r.status_code != 200:
            raise RuntimeError(f'HTTP {r.status_code} for q={q!r}: {r.text[:300]}')
        rows = []
        for line in r.iter_lines():
            if line:
                rows.append(json.loads(line))
            if len(rows) >= limit:
                r.close()
                return rows, True
        return rows, False

    def ids(self, q):
        rows, trunc = self.files(q)
        if trunc:
            # A partial set makes every equality below meaningless — refuse to judge.
            sys.exit(f'ABORT: {q!r} returned more than {MAX_ROWS} rows; raise MAX_ROWS or pick narrower test classifications')
        s = {row['fileId'] for row in rows}
        print(f'  {len(s):>6}  {q}')
        return s, rows


def hit(a):
    return bool(a.get('uniquePhrases') or a.get('annotations'))


def phrases_of(a):
    for x in a.get('annotations') or []:
        p = x.get('phrase') if isinstance(x, dict) else x
        if p:
            yield str(p)


def discover(dxr, args):
    rows, _ = dxr.files('', SAMPLE_ROWS)
    per_file = []
    ann_phr = collections.defaultdict(collections.Counter)
    ext = collections.Counter()
    for row in rows:
        names = {a['name'] for a in row.get('annotators') or [] if hit(a)}
        per_file.append(names)
        for a in row.get('annotators') or []:
            if hit(a):
                for p in itertools.islice(phrases_of(a), 5):
                    ann_phr[a['name']][p.lower()] += 1
        for e in row.get('extractedMetadata') or []:
            ext[e['name']] += 1
    pair = collections.Counter()
    for names in per_file:
        for a, b in itertools.combinations(sorted(names), 2):
            pair[(a, b)] += 1
    if not pair:
        sys.exit('no file in the sample carries two annotators — pass --annotator-a/-b explicitly')
    (a, b), _ = pair.most_common(1)[0]
    a = args.annotator_a or a
    b = args.annotator_b or b

    def usable(p):
        return 2 <= len(p) <= 24 and p.replace(' ', '').isalnum()

    # Prefer a phrase that A shares with some third annotator C, so the OR test
    # (A OR C) AND T is non-trivial: both sides of the union contribute files.
    c, phrase = args.annotator_c, args.phrase
    if not (c and phrase):
        shared = [(p, n, ann_phr[a][p] + ann_phr[n][p])
                  for p in ann_phr[a] if usable(p)
                  for n in ann_phr if n not in (a, b) and p in ann_phr[n]]
        if shared:
            p, n, _ = max(shared, key=lambda x: x[2])
            c, phrase = c or n, phrase or p
    if not c:
        others = collections.Counter(n for names in per_file for n in names if n not in (a, b))
        c = others.most_common(1)[0][0] if others else b
    if not phrase:
        phrase = next((p for p, _ in ann_phr[a].most_common() if usable(p)), None) or next(iter(ann_phr[a]), None)
    if not phrase:
        sys.exit(f'no annotation phrase found for annotator {a!r} — pass --phrase')

    e = args.extractor or (ext.most_common(1)[0][0] if ext else None)
    if not e:
        # Fall back to the catalog: any extractor at all (may match few/zero files).
        cat = requests.get(f'{dxr.base}/api/v1/classifications', headers=dxr.h, timeout=60).json()
        e = next((x['name'] for x in cat.get('data', []) if x.get('type') == 'EXTRACTOR'), None)
    return a, b, c, e, phrase


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--annotator-a')
    ap.add_argument('--annotator-b')
    ap.add_argument('--annotator-c')
    ap.add_argument('--extractor')
    ap.add_argument('--phrase')
    args = ap.parse_args()
    dxr = Dxr(*load_env())

    print('Discovering test classifications from a sample of file rows…')
    A, B, C, E, T = discover(dxr, args)
    print(f'  annotators A={A!r} B={B!r} C={C!r}\n  extractor E={E!r}\n  phrase    T={T!r}\n')

    def nm(field, v):
        # Same escaping as quoteTerm() in the Groovy scripts: \ and " inside a term.
        v = str(v).replace('\\', '\\\\').replace('"', '\\"')
        return f'{field}:"{v}"'

    def nested(names, phrase):
        term = nm('annotations.phrase', f'*{phrase}*')
        if not names:
            return f'annotators: {{ {term} }}'
        nc = nm('name', names[0]) if len(names) == 1 else '(' + ' OR '.join(nm('name', n) for n in names) + ')'
        return f'annotators: {{ {nc} AND {term} }}'

    results = []

    def check(label, ok, detail=''):
        results.append((label, ok))
        print(f'{"PASS" if ok else "FAIL"}  {label}{("  — " + detail) if detail else ""}')

    print('Fetching component sets…')
    sA, rA = dxr.ids(nm('annotators.name', A))
    sB, _ = dxr.ids(nm('annotators.name', B))
    sE, _ = (dxr.ids(nm('extractedMetadata.name', E)) if E else (None, None))
    sAT, rAT = dxr.ids(nested([A], T))
    sCT, _ = dxr.ids(nested([C], T))
    sT, rT = dxr.ids(nested([], T))
    print()

    # 1. Values within a field are AND-ed
    sAB, rAB = dxr.ids(f'{nm("annotators.name", A)} AND {nm("annotators.name", B)}')
    check('AND within a field: files(A AND B) == files(A) ∩ files(B)', sAB == sA & sB,
          f'{len(sAB)} vs {len(sA & sB)}')
    check('…and is strictly narrower than OR when A≠B', A == B or sAB < (sA | sB),
          f'{len(sAB)} vs union {len(sA | sB)}')
    check('row evidence: every A∧B row shows hits for both A and B',
          all({A, B} <= {a['name'] for a in row.get('annotators') or [] if hit(a)} for row in rAB))

    # 2. Fields are AND-ed
    if E:
        sEA, _ = dxr.ids(f'{nm("extractedMetadata.name", E)} AND {nm("annotators.name", A)}')
        check('AND across fields: files(E AND A) == files(E) ∩ files(A)', sEA == sE & sA,
              f'{len(sEA)} vs {len(sE & sA)}')

    # 3. Nested phrase clause is scoped to the same annotator
    check('nested phrase ⊆ annotator: files(A:{T}) ⊆ files(A)', sAT <= sA, f'{len(sAT)} ⊆ {len(sA)}')
    check('row evidence: every A:{T} row has an A annotation containing T',
          all(any(a['name'] == A and any(T.lower() in p.lower() for p in phrases_of(a))
                  for a in row.get('annotators') or []) for row in rAT) if rAT else False,
          'no rows returned' if not rAT else '')

    # 4. OR across the phrase's annotators == union of per-annotator phrase sets
    sACT, _ = dxr.ids(nested([A, C], T))
    check('OR in phrase clause: files({(A OR C) AND T}) == files(A:{T}) ∪ files(C:{T})',
          sACT == sAT | sCT, f'{len(sACT)} vs {len(sAT | sCT)}')

    # 5. Unscoped phrase == phrase in any annotator (superset of the scoped ones)
    check('unscoped phrase ⊇ scoped: files({T}) ⊇ files(A:{T}) ∪ files(C:{T})', sT >= (sAT | sCT),
          f'{len(sT)} ⊇ {len(sAT | sCT)}')

    # 6. Why the rerun/search never AND two names inside one nested block
    sAnB, _ = dxr.ids(f'annotators: {{ {nm("name", A)} AND {nm("name", B)} }}')
    check('single nested block cannot hold two names: files({A AND B}) == ∅', A == B or not sAnB, f'{len(sAnB)}')

    # 7. The full agreed shape (Colin\'s example): E AND A AND B AND ((A OR C) AND T)
    if E:
        full = ' AND '.join([nm('extractedMetadata.name', E), nm('annotators.name', A),
                             nm('annotators.name', B), nested([A, C], T)])
        sFull, _ = dxr.ids(full)
        expect = sE & sA & sB & (sAT | sCT)
        check('full composed query == E ∩ A ∩ B ∩ (A:T ∪ C:T)', sFull == expect, f'{len(sFull)} vs {len(expect)}')

    # 7b. Same shape without the extractor — on most instances this is non-empty,
    #     so the equality is exercised on a real result set, not ∅ == ∅.
    sABT, rABT = dxr.ids(' AND '.join([nm('annotators.name', A), nm('annotators.name', B), nested([A, C], T)]))
    check('A AND B AND ((A OR C) AND T) == A ∩ B ∩ (A:T ∪ C:T)', sABT == sA & sB & (sAT | sCT),
          f'{len(sABT)} vs {len(sA & sB & (sAT | sCT))}' + ('' if sABT else '  [empty — weak]'))
    check('row evidence: every such row has hits for A and B, and T in an A or C annotation',
          all({A, B} <= {a['name'] for a in row.get('annotators') or [] if hit(a)}
              and any(a['name'] in (A, C) and any(T.lower() in x.lower() for x in phrases_of(a))
                      for a in row.get('annotators') or []) for row in rABT))

    # 8. Reversed clause order is irrelevant (guards against parser precedence surprises)
    sBA, _ = dxr.ids(f'{nm("annotators.name", B)} AND {nm("annotators.name", A)}')
    check('clause order irrelevant: files(B AND A) == files(A AND B)', sBA == sAB)

    failed = [l for l, ok in results if not ok]
    print(f'\n{len(results) - len(failed)}/{len(results)} checks passed, {dxr.calls} API calls')
    if failed:
        print('FAILED:\n  ' + '\n  '.join(failed))
        sys.exit(1)


if __name__ == '__main__':
    main()
