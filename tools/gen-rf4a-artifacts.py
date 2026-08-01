#!/usr/bin/env python3
"""RF-4a artifact generator — the ONE source of the ebp.data goldens/fixtures.

The draft quotes this script's output; the dry-run applies it. Nothing is
hand-copied, so the draft text and the validated bytes cannot drift (review
finding P2 "the draft's ready artifacts are not the dry-run-tested bytes").

Schema hashes are REAL: sha256 over the RFC8785/JCS serialization of
{id, version, tables, mutations}, the optional member OMITTED when absent
(review findings P1 "placeholder hashes" and P2 "hash input undefined when
mutations is absent"). Our declarations contain only strings, safe integers,
booleans, objects and arrays — no floats — so sorted-key compact JSON is
exact JCS for this input.
"""
import hashlib, json, sys, shutil, subprocess, os

def jcs(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")

def schema_hash(decl):
    src = {k: decl[k] for k in ("id", "version", "tables", "mutations")
           if k in decl}
    return hashlib.sha256(jcs(src)).hexdigest()

NOTES_V1 = {
    "columns": {
        "body_hash": {"nullable": True, "type": "blob"},
        "file": {"type": "text"},
        "pos": {"type": "wide_integer"},
        "title": {"provenance": "db", "type": "text"},
    },
    "pk": ["pos"], "scope": "file",
}
LINKS = {"columns": {"dst": {"type": "text"}, "src": {"type": "text"}},
         "pk": ["src", "dst"]}
MUTATIONS = [{"action": "example.note-retitle", "dedupe": "row",
              "ttl_s": 604800, "when_offline": "queue"}]

DECL_V1 = {"id": "vault", "version": 1,
           "tables": {"links": LINKS, "notes": NOTES_V1},
           "mutations": MUTATIONS}

NOTES_V2 = json.loads(json.dumps(NOTES_V1))
NOTES_V2["columns"]["tags"] = {"nullable": True, "type": "text"}
DECL_V2 = {"id": "vault", "version": 2,
           "tables": {"links": LINKS, "notes": NOTES_V2},
           "mutations": MUTATIONS}

H1, H2 = schema_hash(DECL_V1), schema_hash(DECL_V2)

def params_schema(decl):
    return {"schema": {"hash": schema_hash(decl), "id": decl["id"],
                       "version": decl["version"]},
            "tables": decl["tables"], "mutations": decl["mutations"]}

def req(rid, method, params):
    return {"id": rid, "jsonrpc": "2.0", "method": method, "params": params}

def res(rid, result):
    return {"id": rid, "jsonrpc": "2.0", "result": result}

ROW = lambda f, p, t: {"body_hash": None, "file": f, "pos": p, "title": t}

# --- frames.golden lines 40-42 ---------------------------------------------
G40 = req("ds1", "data.schema", params_schema(DECL_V1))
G41 = req("dc1", "data.changeset", {
    "ops": [{"op": "put", "row": ROW("a.org", "9223372036854775807", "Hello"),
             "table": "notes"},
            {"op": "del", "key": {"dst": "b", "src": "a"}, "table": "links"},
            {"op": "replace", "rows": [ROW("b.org", "2", "Two")],
             "scope_value": "b.org", "table": "notes"}],
    "parent": 6, "revision": 7, "schema_hash": H1})
G42 = req("dc2", "data.changeset", {
    "ops": [{"op": "put", "row": ROW("a.org", "1", "One"), "table": "notes"}],
    "revision": 1, "schema_hash": H1, "snapshot": True})
GOLDENS = [(40, G40), (41, G41), (42, G42)]

# --- wire fixtures 23-25 ----------------------------------------------------
FIXTURES = [
    ("23-data-schema-accept.bin",
     "27.2: an exact-identity re-declaration resumes - the result carries the "
     "Companion's durably applied floor",
     [G40, res("ds1", {"revision": 6, "status": "accepted"})]),
    ("24-data-schema-drift.bin",
     "27.2: drift is a negotiated result, not an error; the prior projection "
     "is retained untouched",
     [req("ds2", "data.schema", params_schema(DECL_V2)),
      res("ds2", {"reason": "pinned consumer requires vault@1",
                  "status": "refused"})]),
    ("25-data-wide-int.bin",
     "27.1.1: the boundary values survive both decoders at all chunkings as "
     "strings - the canonical encoding round-trips",
     [req("dw1", "data.changeset", {
         "ops": [{"op": "put", "row": ROW("w.org", "9223372036854775807", "Max"),
                  "table": "notes"},
                 {"op": "put", "row": ROW("w.org", "-9223372036854775808", "Min"),
                  "table": "notes"}],
         "parent": 7, "revision": 8, "schema_hash": H1}),
      res("dw1", {"revision": 8, "status": "applied"})]),
]

def frame(msg):
    body = json.dumps(msg, separators=(",", ":")).encode("utf-8")
    return b"Content-Length: %d\r\n\r\n" % len(body) + body

def emit_draft_block():
    print(f"schema hash v1 = {H1}\nschema hash v2 = {H2}\n")
    print("--- frames.golden appended lines ---")
    for n, msg in GOLDENS:
        print(f"{n} " + json.dumps(msg, sort_keys=True, separators=(",", ":")))

def apply_to(root):
    with open(f"{root}/goldens/frames.golden", "a") as f:
        for n, msg in GOLDENS:
            f.write(f"{n} " + json.dumps(msg, sort_keys=True,
                                         separators=(",", ":")) + "\n")
    mpath = f"{root}/goldens/wire/manifest.json"
    m = json.load(open(mpath))
    for name, rule, msgs in FIXTURES:
        with open(f"{root}/goldens/wire/{name}", "wb") as f:
            for msg in msgs:
                f.write(frame(msg))
        m["fixtures"].append({"file": name, "profile": "android-loopback-tcp",
                              "kind": "positive", "rule": rule,
                              "expect_messages": msgs})
    json.dump(m, open(mpath, "w"), indent=2)

if __name__ == "__main__":
    if len(sys.argv) > 1:
        apply_to(sys.argv[1])
        print(f"applied to {sys.argv[1]}")
    else:
        emit_draft_block()
