#!/bin/sh
# Generate once, then replay the exact bytes through Python and public Elisp.
set -eu
cd "$(dirname "$0")/.."
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
spec_dir=${EBP_SPEC_DIR:-"$repositories_root/ebp"}
ebp_el_dir=${EBP_EL_DIR:-"$repositories_root/ebp.el"}

corpus_dir=$(mktemp -d)
trap 'rm -rf "$corpus_dir"' EXIT
corpus="$corpus_dir/text-input-corpus.jsonl"
python3 "$spec_dir/conformance/generate-text-input-corpus.py" "$corpus"
python3 "$spec_dir/conformance/check-text-input-corpus.py" "$corpus"
JETPACS_TEXT_INPUT_CORPUS="$corpus" \
  emacs -Q --batch --eval '(setq load-prefer-newer t)' \
    -L "$ebp_el_dir/lisp" -L emacs -L test \
    -l test/text-input-corpus-test.el \
    -f ert-run-tests-batch-and-exit
