#!/usr/bin/env bash
# Applies .github/rulesets/main.json to the repository on GitHub, so every change to main needs a
# pull request with a passing build (no exceptions, maintainers included), and main cannot be
# force-pushed or deleted. Run it once the repository is public (or on GitHub Pro); running it
# again updates the existing ruleset instead of adding a second one.
#
# Needs the GitHub CLI (gh), logged in with admin rights on the repository.
set -euo pipefail

cd "$(dirname "$0")/.."
repo="${1:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
file=".github/rulesets/main.json"
name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$file")"

id="$(gh api "repos/$repo/rulesets" -q ".[] | select(.name == \"$name\") | .id" | head -n1)"
if [ -n "$id" ]; then
    gh api --method PUT "repos/$repo/rulesets/$id" --input "$file" > /dev/null
    echo "Updated ruleset \"$name\" ($id) on $repo"
else
    gh api --method POST "repos/$repo/rulesets" --input "$file" -q '"Created ruleset \"" + .name + "\" (" + (.id|tostring) + ") on '"$repo"'"'
fi
