#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
scanner_image=${GITLEAKS_IMAGE:-zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f}
temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT INT TERM

not_repository="$temporary_root/not-a-repository"
empty_repository="$temporary_root/empty-repository"
test_repository="$temporary_root/test-repository"
shallow_repository="$temporary_root/shallow-repository"
generic_repository="$temporary_root/generic-repository"
mkdir "$not_repository" "$empty_repository" "$test_repository" "$generic_repository"

if "$script_dir/verify-secret-history.sh" "$scanner_image" \
        "$not_repository" >/dev/null 2>&1; then
    echo "secret history test: non-Git directory was accepted" >&2
    exit 1
fi

git -C "$empty_repository" init --quiet
if "$script_dir/verify-secret-history.sh" "$scanner_image" \
        "$empty_repository" >/dev/null 2>&1; then
    echo "secret history test: empty Git history was accepted" >&2
    exit 1
fi

git -C "$test_repository" init --quiet
git -C "$test_repository" config user.name "Secret Scan Test"
git -C "$test_repository" config user.email "secret-scan@example.com"
printf '%s\n' 'clean history fixture' > "$test_repository/README.md"
git -C "$test_repository" add README.md
git -C "$test_repository" commit --quiet -m clean

printf '%s\n' 'second clean history fixture' > "$test_repository/CHANGELOG.md"
git -C "$test_repository" add CHANGELOG.md
git -C "$test_repository" commit --quiet -m clean-follow-up

git clone --quiet --depth 1 "file://$test_repository" "$shallow_repository"
if "$script_dir/verify-secret-history.sh" "$scanner_image" \
        "$shallow_repository" >/dev/null 2>&1; then
    echo "secret history test: shallow Git history was accepted" >&2
    exit 1
fi

"$script_dir/verify-secret-history.sh" "$scanner_image" \
    "$test_repository" >/dev/null

# Assemble the synthetic credential only in the disposable repository so this
# policy test never places a secret-shaped value in the real Git history.
printf '%s%s\n' 'AKIA' 'ABCDEFGHIJKLMNOP' \
    > "$test_repository/synthetic-leak.txt"
git -C "$test_repository" add synthetic-leak.txt
git -C "$test_repository" commit --quiet -m synthetic-leak

if "$script_dir/verify-secret-history.sh" "$scanner_image" \
        "$test_repository" >"$temporary_root/rejected.log" 2>&1; then
    echo "secret history test: controlled synthetic leak was accepted" >&2
    exit 1
fi
if grep -F 'AKIA' "$temporary_root/rejected.log" >/dev/null; then
    echo "secret history test: redacted output exposed the synthetic credential" >&2
    exit 1
fi

git -C "$generic_repository" init --quiet
git -C "$generic_repository" config user.name "Secret Scan Test"
git -C "$generic_repository" config user.email "secret-scan@example.com"
cp "$script_dir/../.gitleaks.toml" "$generic_repository/.gitleaks.toml"
printf '%s\n' 'generic detector fixture' > "$generic_repository/README.md"
git -C "$generic_repository" add .gitleaks.toml README.md
git -C "$generic_repository" commit --quiet -m clean

# Prove that the two bounded test-fixture allowlists do not disable the generic
# credential detector for any other file.
{
    printf '%s' 'api_key = "'
    printf '%s' 'aB3dE5gH7jK9mN2pQ4rS6tV8xZ0cF1hJ'
    printf '%s\n' '"'
} > "$generic_repository/synthetic-generic-leak.txt"
git -C "$generic_repository" add synthetic-generic-leak.txt
git -C "$generic_repository" commit --quiet -m synthetic-generic-leak

if "$script_dir/verify-secret-history.sh" "$scanner_image" \
        "$generic_repository" >"$temporary_root/generic-rejected.log" 2>&1; then
    echo "secret history test: generic synthetic leak was accepted" >&2
    exit 1
fi
generic_fixture_prefix=$(printf '%s%s' 'aB3dE5gH' '7jK9mN2p')
if grep -F "$generic_fixture_prefix" \
        "$temporary_root/generic-rejected.log" >/dev/null; then
    echo "secret history test: redacted output exposed generic fixture" >&2
    exit 1
fi

echo "secret history scan tests: passed"
