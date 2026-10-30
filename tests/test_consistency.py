"""Static checks over the pack's workflows: the mistakes no build step catches.

- Diagrams: every gateway decides cleanly (one default, conditions on every
  other flow, conditions the test walker and Flowable both evaluate the same
  way), every element is reachable and can reach an end. The 29 Oct 2026
  headless-abort bug was a path through the diagram nobody had walked.
- Forms: every form key a template uses exists and every form is used; every
  {{variable}} a form shows is set somewhere in that workflow.
- Scripts: every script under scripts/ is used by some template.
- Ids: the operating-model UUIDs agree between dxrModelIds(), Configure, the
  search form's pickers and tools/sync_classifications_standalone.py.
"""
from __future__ import annotations

import ast
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

import groovy_suite  # noqa: F401  (puts the harness on sys.path)
from lib.app_builder import _INCLUDE_RE, _PLACEHOLDER_RE, bpmn_template_path, expand_script
from lib.pack import load_pack

PACK = Path(__file__).resolve().parents[1]
BPMN = "{http://www.omg.org/spec/BPMN/20100524/MODEL}"
FLOWABLE = "{http://flowable.org/bpmn}"
PACK_INFO = load_pack(PACK)
VARIANTS = list(PACK_INFO.variants)
WORKFLOWS = PACK_INFO.workflows
CASES = [(wf, v) for wf in WORKFLOWS for v in VARIANTS]
CASE_IDS = [f"{wf}-{v}" for wf, v in CASES]
# What BpmnRunner (tests/groovy/fake/BpmnRunner.groovy) evaluates; anything richer must be added there first.
SUPPORTED_CONDITION = re.compile(r"^\$\{\s*\w+\s*(==|!=)\s*(true|false|'[^']*'|\"[^\"]*\"|-?\d+)\s*\}$")
SYSTEM_ID = re.compile(r"^00000000-0000-0000-0000-")


def process(wf: str, variant: str) -> ET.Element:
    root = ET.parse(bpmn_template_path(PACK_INFO.workflow_dir(wf), variant)).getroot()
    return root.find(f"{BPMN}process")


def local(tag: str) -> str:
    return tag.split("}", 1)[-1]


def forms_of(wf: str) -> dict[str, dict]:
    out = {}
    for f in sorted((PACK_INFO.workflow_dir(wf) / "forms").glob("*.form")):
        form = json.loads(f.read_text(encoding="utf-8"))
        out[form["metadata"]["key"]] = form
    return out


def expanded_scripts(wf: str, variant: str) -> str:
    template = bpmn_template_path(PACK_INFO.workflow_dir(wf), variant).read_text(encoding="utf-8")
    names = dict.fromkeys(m.strip() for m in _PLACEHOLDER_RE.findall(template))
    return "\n".join(expand_script(PACK_INFO.workflow_dir(wf), n, variant) for n in names)


# --- diagrams ---------------------------------------------------------------------------------

@pytest.mark.parametrize("wf,variant", CASES, ids=CASE_IDS)
def test_gateways_decide_cleanly(wf, variant):
    proc = process(wf, variant)
    flows = [f for f in proc if local(f.tag) == "sequenceFlow"]
    problems = []
    for gw in (e for e in proc if local(e.tag) == "exclusiveGateway"):
        gid, default = gw.get("id"), gw.get("default")
        out = [f for f in flows if f.get("sourceRef") == gid]
        if default and default not in {f.get("id") for f in out}:
            problems.append(f"{gid}: default '{default}' is not one of its outgoing flows")
        for f in out:
            cond = f.find(f"{BPMN}conditionExpression")
            text = (cond.text or "").strip() if cond is not None else ""
            if f.get("id") == default:
                if text:
                    problems.append(f"{gid}: default flow {f.get('id')} has a condition")
            elif not text:
                problems.append(f"{gid}: flow {f.get('id')} has no condition and is not the default")
            elif not SUPPORTED_CONDITION.match(text):
                problems.append(f"{gid}: condition {text!r} is outside the ${{var == literal}} form the test walker evaluates")
        if len(out) < 2:
            problems.append(f"{gid}: a gateway with {len(out)} outgoing flow(s)")
    assert not problems, "\n".join(problems)


@pytest.mark.parametrize("wf,variant", CASES, ids=CASE_IDS)
def test_every_element_is_on_a_path_from_start_to_end(wf, variant):
    proc = process(wf, variant)
    nodes = {e.get("id"): local(e.tag) for e in proc if local(e.tag) not in ("sequenceFlow", "documentation", "extensionElements")}
    edges = [(f.get("sourceRef"), f.get("targetRef")) for f in proc if local(f.tag) == "sequenceFlow"]
    succ: dict[str, set] = {n: set() for n in nodes}
    pred: dict[str, set] = {n: set() for n in nodes}
    for s, t in edges:
        assert s in nodes and t in nodes, f"flow {s} -> {t} names an element that does not exist"
        succ[s].add(t)
        pred[t].add(s)

    def reach(starts, graph):
        seen, todo = set(starts), list(starts)
        while todo:
            for n in graph[todo.pop()] - seen:
                seen.add(n)
                todo.append(n)
        return seen

    starts = [n for n, k in nodes.items() if k == "startEvent"]
    ends = [n for n, k in nodes.items() if k == "endEvent"]
    assert len(starts) == 1, f"{len(starts)} start events"
    unreachable = set(nodes) - reach(starts, succ)
    dead_ends = set(nodes) - reach(ends, pred)
    assert not unreachable, f"never reached from the start: {sorted(unreachable)}"
    assert not dead_ends, f"cannot reach an end event: {sorted(dead_ends)}"


@pytest.mark.parametrize("wf,variant", CASES, ids=CASE_IDS)
def test_external_api_tasks_follow_a_script_that_arms_them(wf, variant):
    """An External API task reads ${dxrMethod} etc.: whatever precedes it must be a script (which arms
    the request) or a gateway whose every route there passes through one. A script that runs but arms
    nothing (the 29 Oct 2026 headless abort) is beyond a static check: the walker tests cover that."""
    proc = process(wf, variant)
    kinds = {e.get("id"): local(e.tag) for e in proc}
    pred: dict[str, list] = {}
    for f in (e for e in proc if local(e.tag) == "sequenceFlow"):
        pred.setdefault(f.get("targetRef"), []).append(f.get("sourceRef"))
    http = [e.get("id") for e in proc if local(e.tag) == "serviceTask" and e.get(f"{FLOWABLE}type") == "http"]

    def armed(node: str, seen: frozenset) -> bool:
        if node in seen:
            return True  # a loop back through the same gateway: judged by its other entries
        kind = kinds[node]
        if kind == "scriptTask":
            return True
        if kind in ("startEvent", "serviceTask", "userTask"):
            return False
        return all(armed(p, seen | {node}) for p in pred.get(node, []))

    bad = [h for h in http if not all(armed(p, frozenset({h})) for p in pred.get(h, []))]
    assert not bad, f"External API task(s) reachable without a script arming the request first: {bad}"


# --- forms ----------------------------------------------------------------------------------------

@pytest.mark.parametrize("wf", WORKFLOWS)
def test_form_keys_and_forms_match(wf):
    used = set()
    for v in VARIANTS:
        for e in process(wf, v).iter():
            if e.get(f"{FLOWABLE}formKey"):
                used.add(e.get(f"{FLOWABLE}formKey"))
    forms = set(forms_of(wf))
    assert used <= forms, f"form keys with no form: {sorted(used - forms)}"
    assert forms <= used, f"forms no template uses: {sorted(forms - used)}"


@pytest.mark.parametrize("wf,variant", CASES, ids=CASE_IDS)
def test_form_variables_are_set_by_the_workflow(wf, variant):
    proc = process(wf, variant)
    keys = {e.get(f"{FLOWABLE}formKey") for e in proc.iter() if e.get(f"{FLOWABLE}formKey")}
    forms = forms_of(wf)
    scripts = expanded_scripts(wf, variant)
    literals = set(re.findall(r"""['"]([A-Za-z_]\w*)['"]""", scripts))
    declared = {fp.get("id") for fp in proc.iter(f"{FLOWABLE}formProperty")} | {"initiator"}
    field_ids = set()
    for form in forms.values():
        field_ids |= set(re.findall(r'"id"\s*:\s*"([A-Za-z_]\w*)"', json.dumps(form)))
    missing = {}
    for key in keys:
        text = json.dumps(forms.get(key, {}))
        for var in set(re.findall(r"\{\{([A-Za-z_]\w*)\}\}", text)):
            if var not in literals and var not in declared and var not in field_ids:
                missing.setdefault(key, []).append(var)
    assert not missing, f"form variables nothing in {wf} [{variant}] sets: {missing}"


# --- scripts ----------------------------------------------------------------------------------------

@pytest.mark.parametrize("wf", WORKFLOWS)
def test_every_script_file_is_used(wf):
    wf_dir = PACK_INFO.workflow_dir(wf)
    referenced = set()
    for v in VARIANTS:
        template = bpmn_template_path(wf_dir, v).read_text(encoding="utf-8")
        for name in _PLACEHOLDER_RE.findall(template):
            referenced.add((v, name.strip()))
    files = sorted((wf_dir / "scripts").rglob("*.groovy"))
    unused = []
    for f in files:
        rel = f.relative_to(wf_dir / "scripts")
        if len(rel.parts) == 2:     # scripts/<variant>/NAME: used by that variant only
            ok = (rel.parts[0], rel.name) in referenced
        else:                       # scripts/NAME: used by any variant without its own copy
            ok = any(n == rel.name and not (wf_dir / "scripts" / v / n).exists() for v, n in referenced)
        included = any(rel.name in _INCLUDE_RE.findall(g.read_text(encoding="utf-8")) for g in files if g != f)
        if not ok and not included:
            unused.append(str(rel))
    assert not unused, f"script files no template uses: {unused}"


# --- operating-model ids ------------------------------------------------------------------------------

def model_ids() -> dict[str, str]:
    text = (PACK / "shared" / "dxr_model.groovy").read_text(encoding="utf-8")
    body = text.split("def dxrModelIds()", 1)[1].split("\n}\n", 1)[0]
    return dict(re.findall(r"(\w+)\s*:\s*string2Uuid\('([0-9a-f-]{36})'\)", body))


def test_configure_creates_every_model_id():
    configure = (PACK / "workflows" / "configure-data-xray-workflows" / "scripts" / "configure_data_xray.groovy").read_text(encoding="utf-8")
    custom = {k: v for k, v in model_ids().items() if not SYSTEM_ID.match(v)}
    missing = {k: v for k, v in custom.items() if v not in configure}
    assert len(custom) > 20
    assert not missing, f"dxrModelIds() ids Configure never mentions: {missing}"


def test_search_form_pickers_use_model_ids():
    form = (PACK / "workflows" / "search-data-xray" / "forms" / "searchDataXrayForm.form").read_text(encoding="utf-8")
    picker_ids = set(re.findall(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", form))
    assert picker_ids, "no asset type ids in searchDataXrayForm.form"
    assert picker_ids <= set(model_ids().values()), f"picker ids not in dxrModelIds(): {picker_ids - set(model_ids().values())}"


def test_standalone_classification_sync_uses_the_same_ids():
    tree = ast.parse((PACK / "tools" / "sync_classifications_standalone.py").read_text(encoding="utf-8"))
    consts = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            try:
                consts[node.targets[0].id] = ast.literal_eval(node.value)
            except ValueError:
                pass
    ids = model_ids()
    expected = {
        "CLASSIFICATIONS_DOMAIN_ID": ids["classificationsDomainId"],
        "ATTR_DXR_ID": ids["dataxrayIdAttrTypeId"],
        "ATTR_DXR_INDEX_ID": ids["dataxrayIndexIdAttrTypeId"],
        "ATTR_DESCRIPTION": ids["descriptionAttrTypeId"],
        "ATTR_LINK": ids["linkAttrTypeId"],
        "ATTR_SEARCH_LINK": ids["searchLinkAttrTypeId"],
        "ATTR_SUBTYPE": ids["subtypeAttrTypeId"],
        "STATUS_OBSOLETE": ids["obsoleteStatusId"],
        "STATUS_CANDIDATE": ids["candidateStatusId"],
        "ASSET_TYPE_BY_DXR_TYPE": {"CLASSIFICATION": ids["classificationTypeId"], "ANNOTATOR": ids["annotatorTypeId"],
                                   "EXTRACTOR": ids["extractorTypeId"], "LABEL": ids["labelTypeId"]},
    }
    assert {k: consts.get(k) for k in expected} == expected
    tag = re.search(r"def dxrClassificationSyncTag\(\) \{ return '([^']+)' \}",
                    (PACK / "shared" / "dxr_model.groovy").read_text(encoding="utf-8")).group(1)
    assert consts["SYNC_TAG"] == tag
