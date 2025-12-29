#!/usr/bin/env python3
"""
Sync documentation script for jstall.

This script:
1. Extracts version from Version.java and updates pom.xml
2. Synchronizes configuration examples from YAML files into README.md
3. Builds the project and extracts CLI --help output
4. Updates README.md with current documentation

Usage:
    ./bin/sync-documentation.py           # Sync all documentation
    ./bin/sync-documentation.py --dry-run # Preview changes without modifying files
    ./bin/sync-documentation.py --install # Install as pre-commit hook
    ./bin/sync-documentation.py --help    # Show help
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


def get_repo_root() -> Path:
    """Get the repository root directory (parent of bin/)."""
    return Path(__file__).parent.parent.resolve()


def read_file(path: Path) -> str:
    """Read file contents."""
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write_file(path: Path, content: str) -> None:
    """Write content to file."""
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)


def extract_version_from_java() -> str:
    """Extract version from Version.java."""
    version_file = get_repo_root() / "src/main/java/me/bechberger/jstall/Version.java"
    if not version_file.exists():
        print(f"Error: Version.java not found at {version_file}", file=sys.stderr)
        sys.exit(1)

    content = read_file(version_file)
    match = re.search(r'VERSION\s*=\s*"([^"]+)"', content)
    if not match:
        print("Error: Could not extract VERSION from Version.java", file=sys.stderr)
        sys.exit(1)

    return match.group(1)


def update_pom_version(version: str, dry_run: bool) -> bool:
    """Update version in pom.xml. Returns True if changed."""
    pom_file = get_repo_root() / "pom.xml"
    if not pom_file.exists():
        print(f"Error: pom.xml not found at {pom_file}", file=sys.stderr)
        return False

    content = read_file(pom_file)

    # Update version in the <version> tag (first occurrence, which is the project version)
    # Pattern: <version>...</version> that follows <artifactId>jstall</artifactId>
    new_content = re.sub(
        r'(<artifactId>jstall</artifactId>\s*<version>)[^<]+(</version>)',
        rf'\g<1>{version}\g<2>',
        content,
        count=1
    )

    if new_content == content:
        return False

    if not dry_run:
        write_file(pom_file, new_content)
        print(f"Updated pom.xml version to {version}")
    else:
        print(f"Would update pom.xml version to {version}")

    return True


def read_yaml_skipping_lines(path: Path, skip_lines: int) -> str:
    """Read YAML file, skipping the first N lines."""
    if not path.exists():
        print(f"Warning: YAML file not found: {path}", file=sys.stderr)
        return ""

    content = read_file(path)
    lines = content.splitlines()
    return '\n'.join(lines[skip_lines:])


def build_project_and_get_help() -> dict[str, str]:
    """Build project and extract --help output for each command."""
    repo_root = get_repo_root()

    # Build the project
    print("Building project...")
    result = subprocess.run(
        ["mvn", "package", "-DskipTests", "-q"],
        cwd=repo_root,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        print(f"Warning: Maven build failed: {result.stderr}", file=sys.stderr)
        return {}

    jar_path = repo_root / "target/jstall.jar"
    if not jar_path.exists():
        print(f"Warning: JAR file not found at {jar_path}", file=sys.stderr)
        return {}

    help_outputs = {}
    commands = ["redact", "redact-text", "generate-config", "test", "generate-schema",
                "concat", "words", "words discover", "words redact"]

    for cmd in commands:
        # Split command for subcommands like "words discover"
        cmd_parts = cmd.split()
        result = subprocess.run(
            ["java", "-jar", str(jar_path)] + cmd_parts + ["--help"],
            cwd=repo_root,
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            help_outputs[cmd] = result.stdout.strip()
        else:
            # Try stderr as some CLI frameworks output help there
            if result.stderr.strip():
                help_outputs[cmd] = result.stderr.strip()

    return help_outputs


def update_readme_section(content: str, section_marker: str, new_content: str,
                          language: str = "yaml") -> str:
    """
    Update a section in README that's marked with special comments.

    Sections are marked with:
    <!-- BEGIN section_marker -->
    ```yaml
    content
    ```
    <!-- END section_marker -->
    """
    # Match the markers, capturing the opening and closing without their internal whitespace
    pattern = rf'(<!-- BEGIN {section_marker} -->\s*```{language})\s*(.*?)\s*(```\s*<!-- END {section_marker} -->)'

    if re.search(pattern, content, re.DOTALL):
        # Escape backslashes in new_content to prevent regex interpretation
        escaped_content = new_content.replace('\\', '\\\\')
        # Put content immediately after opening ``` with a newline, then content, then newline before closing ```
        return re.sub(
            pattern,
            rf'\g<1>\n{escaped_content}\n\g<3>',
            content,
            flags=re.DOTALL
        )

    return content


def update_readme_help_section(content: str, command: str, help_text: str) -> str:
    """
    Update a CLI help section in README.

    Sections are marked with:
    <!-- BEGIN help_command -->
    ```
    help output
    ```
    <!-- END help_command -->
    """
    # Convert "words discover" to "words_discover" and "redact-text" to "redact_text"
    section_marker = f"help_{command.replace('-', '_').replace(' ', '_')}"
    # Match the markers, capturing the opening and closing without their internal whitespace
    pattern = rf'(<!-- BEGIN {section_marker} -->\s*```)\s*(.*?)\s*(```\s*<!-- END {section_marker} -->)'

    if re.search(pattern, content, re.DOTALL):
        # Strip leading and trailing empty lines from help text
        help_lines = help_text.splitlines()
        # Remove leading empty lines
        while help_lines and not help_lines[0].strip():
            help_lines.pop(0)
        # Remove trailing empty lines
        while help_lines and not help_lines[-1].strip():
            help_lines.pop()

        cleaned_help = '\n'.join(help_lines)

        # Escape backslashes in help_text to prevent regex interpretation
        escaped_help = cleaned_help.replace('\\', '\\\\')
        # Put content immediately after opening ``` with a newline, then content, then newline before closing ```
        return re.sub(
            pattern,
            rf'\g<1>\n{escaped_help}\n\g<3>',
            content,
            flags=re.DOTALL
        )

    return content


def sync_config_template_to_readme(readme_content: str, dry_run: bool) -> str:
    """Sync config-template.yaml to README."""
    repo_root = get_repo_root()
    config_template = repo_root / "config-template.yaml"

    # Skip first 5 lines (version and usage comments)
    yaml_content = read_yaml_skipping_lines(config_template, 5)

    if yaml_content:
        readme_content = update_readme_section(
            readme_content,
            "config_template",
            yaml_content.strip()
        )
        print("Synced config-template.yaml to README")

    return readme_content


def sync_default_yaml_to_readme(readme_content: str, dry_run: bool) -> str:
    """Sync default.yaml preset to README."""
    repo_root = get_repo_root()
    default_yaml = repo_root / "src/main/resources/presets/default.yaml"

    # Skip first 2 lines (yaml-language-server and version comment)
    yaml_content = read_yaml_skipping_lines(default_yaml, 2)

    if yaml_content:
        readme_content = update_readme_section(
            readme_content,
            "default_yaml",
            yaml_content.strip()
        )
        print("Synced default.yaml to README")

    return readme_content


def sync_strict_yaml_to_readme(readme_content: str, dry_run: bool) -> str:
    """Sync strict.yaml preset to README."""
    repo_root = get_repo_root()
    strict_yaml = repo_root / "src/main/resources/presets/strict.yaml"

    # Skip first 2 lines (yaml-language-server and version comment)
    yaml_content = read_yaml_skipping_lines(strict_yaml, 2)

    if yaml_content:
        readme_content = update_readme_section(
            readme_content,
            "strict_yaml",
            yaml_content.strip()
        )
        print("Synced strict.yaml to README")

    return readme_content


def sync_help_to_readme(readme_content: str, help_outputs: dict[str, str]) -> str:
    """Sync CLI --help outputs to README."""
    for command, help_text in help_outputs.items():
        readme_content = update_readme_help_section(readme_content, command, help_text)
        print(f"Synced {command} --help to README")

    return readme_content


def install_hook() -> None:
    """Install this script as a pre-commit hook."""
    repo_root = get_repo_root()
    hooks_dir = repo_root / ".git/hooks"

    if not hooks_dir.exists():
        print("Error: .git/hooks directory not found. Is this a git repository?", file=sys.stderr)
        sys.exit(1)

    pre_commit_hook = hooks_dir / "pre-commit"
    script_path = Path(__file__).resolve()

    hook_content = f"""#!/bin/bash
# Pre-commit hook for jstall
# Installed by: ./bin/sync-documentation.py --install
#
# This hook:
# 1. Runs mvn test to ensure tests pass
# 2. Runs sync-documentation.py to update README.md

set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"

echo "=========================================="
echo "Running pre-commit hook..."
echo "=========================================="

# Run tests
echo ""
echo "Step 1/2: Running tests..."
echo "------------------------------------------"
if ! mvn test -q; then
    echo ""
    echo "❌ Tests failed! Commit aborted."
    echo "Fix the failing tests and try again."
    exit 1
fi
echo "✓ Tests passed"

# Sync documentation
echo ""
echo "Step 2/2: Syncing documentation..."
echo "------------------------------------------"
if ! python3 "{script_path}" --skip-build; then
    echo ""
    echo "❌ Documentation sync failed! Commit aborted."
    exit 1
fi
echo "✓ Documentation synced"

# Stage any changes to README.md and pom.xml
if git diff --name-only README.md pom.xml 2>/dev/null | grep -q .; then
    echo ""
    echo "Staging updated documentation files..."
    git add README.md pom.xml 2>/dev/null || true
    echo "✓ Staged README.md and pom.xml"
fi

echo ""
echo "=========================================="
echo "✓ Pre-commit hook completed successfully!"
echo "=========================================="
"""

    write_file(pre_commit_hook, hook_content)
    os.chmod(pre_commit_hook, 0o755)

    print(f"✓ Installed pre-commit hook at {pre_commit_hook}")
    print("")
    print("The pre-commit hook will now run automatically on every commit.")
    print("It will:")
    print("  1. Run 'mvn test' to ensure all tests pass")
    print("  2. Run 'sync-documentation.py' to keep README.md updated")
    print("")
    print("To skip the hook temporarily, use: git commit --no-verify")
    print(f"To uninstall, run: rm {pre_commit_hook}")


def main():
    parser = argparse.ArgumentParser(
        description="Sync documentation for jstall",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  ./bin/sync-documentation.py              # Sync all documentation
  ./bin/sync-documentation.py --dry-run    # Preview changes
  ./bin/sync-documentation.py --install    # Install as pre-commit hook
        """
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Preview changes without modifying files"
    )
    parser.add_argument(
        "--install",
        action="store_true",
        help="Install as a git pre-commit hook"
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Skip building project and updating CLI help sections"
    )

    args = parser.parse_args()

    if args.install:
        install_hook()
        return

    repo_root = get_repo_root()
    readme_file = repo_root / "README.md"

    if not readme_file.exists():
        print(f"Error: README.md not found at {readme_file}", file=sys.stderr)
        sys.exit(1)

    # 1. Extract and sync version
    version = extract_version_from_java()
    print(f"Extracted version: {version}")
    update_pom_version(version, args.dry_run)

    # 2. Read README
    readme_content = read_file(readme_file)
    original_content = readme_content

    # 3. Sync YAML files to README
    readme_content = sync_config_template_to_readme(readme_content, args.dry_run)
    readme_content = sync_default_yaml_to_readme(readme_content, args.dry_run)
    readme_content = sync_strict_yaml_to_readme(readme_content, args.dry_run)

    # 4. Build and sync CLI help (unless skipped)
    if not args.skip_build:
        help_outputs = build_project_and_get_help()
        if help_outputs:
            readme_content = sync_help_to_readme(readme_content, help_outputs)

    # 5. Write updated README
    if readme_content != original_content:
        if args.dry_run:
            print("\n--- DRY RUN: Updated README.md would be: ---")
            print(readme_content)
            print("--- END DRY RUN ---")
        else:
            write_file(readme_file, readme_content)
            print("Updated README.md")
    else:
        print("README.md is already up to date")

    print("Documentation sync complete!")


if __name__ == "__main__":
    main()