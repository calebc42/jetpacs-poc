#!/bin/sh
# Generate once, then replay the exact bytes through Python and public Elisp.
set -eu
cd "$(dirname "$0")/.."

corpus_dir=$(mktemp -d)
trap 'rm -rf "$corpus_dir"' EXIT
corpus="$corpus_dir/text-input-corpus.jsonl"
python3 test/generate-text-input-corpus.py "$corpus"
python3 test/check-text-input-corpus.py "$corpus"
JETPACS_TEXT_INPUT_CORPUS="$corpus" \
  emacs -Q --batch --eval '(setq load-prefer-newer t)' \
    -L emacs -L test -l test/text-input-corpus-test.el \
    -f ert-run-tests-batch-and-exit
