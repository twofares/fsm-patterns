# FSM Patterns — common build, test, and export recipes.
# Requires: just (https://github.com/casey/just), JDK 17–21, ./gradlew
# List recipes: `just` or `just --list`

set shell := ["bash", "-euo", "pipefail", "-c"]

# Prefer the Gradle wrapper so the toolchain matches the repo.
gradle := "./gradlew"

export_md := "export.md"
visual_html := "docs/machine-examples-visual.html"

# Default: show available recipes
default:
    @just --list

# Compile main + test classes (no tests)
build:
    {{gradle}} classes testClasses

# Assemble jar (and other outputs)
assemble:
    {{gradle}} assemble

# Full Gradle build (assemble + test)
check:
    {{gradle}} build

# Run unit tests
test:
    {{gradle}} test

# Quiet test run (less Gradle noise)
test-q:
    {{gradle}} -q test

# Delete Gradle build outputs
clean:
    {{gradle}} clean

# DemoMain: dual-sink + VirtualClock dwell/watchdog
run:
    {{gradle}} run

# Product tables + Mermaid to stdout (preview; does not write export.md)
export-preview:
    {{gradle}} -q runExport

# Regenerate committed product tables + Mermaid diagrams → export.md
export:
    {{gradle}} -q runExport > {{export_md}}
    @echo "Wrote {{export_md}}"

# Alias: generate docs/diagrams artifact
docs: export

# Build, test, then regenerate export.md (local CI-style gate)
ci: clean check export

# Open structural diagrams in the default browser (macOS)
visual:
    open {{visual_html}}

# Print project coordinates
info:
    @echo "project:  fsm-patterns"
    @echo "package:  fsm.sample"
    @echo "export:   {{export_md}}"
    @echo "visual:   {{visual_html}}"
    @{{gradle}} -q properties 2>/dev/null | rg -n '^(name|version|group):' || true
