# Git Hook Setup

## Purpose

The `bin/sync-documentation.py` script is a git pre-commit hook that automatically:
- Extracts version from `Version.java` and updates `pom.xml`
- Synchronizes configuration examples from YAML files into README.md
- Builds the project and extracts CLI `--help` output
- Updates README.md with current documentation

This ensures single source of truth for version and documentation.

## Installation

### Automatic Installation

Run this command from the repository root:

```bash
./bin/sync-documentation.py --install
```

### Verify Installation

```bash
ls -la .git/hooks/pre-commit
```

You should see the executable pre-commit hook.

## Usage

### Sync All Documentation

```bash
./bin/sync-documentation.py
```

This will:
1. Extract version from `src/main/java/me/bechberger/jfrredact/Version.java`
2. Update `pom.xml` with the current version
3. Extract configurations from YAML files
4. Build project and extract `--help` output
5. Update README.md with all synced content

### Preview Changes (Dry Run)

```bash
./bin/sync-documentation.py --dry-run
```

This will output the complete final README.md content without modifying any files.
Useful for reviewing what will be changed before applying.

### Install as Git Hook

```bash
./bin/sync-documentation.py --install
```

Installs the script as a pre-commit hook that runs automatically on every commit.

### Show Help

```bash
./bin/sync-documentation.py --help
```

Display usage information and available options.

## How It Works

On every `git commit`:

1. The hook runs `mvn test` to ensure all tests pass

2. The hook extracts configuration from:
   - `src/main/resources/presets/default.yaml` (skips first 2 lines with version comment)
   - `config-template.yaml` (skips first 5 lines with version and usage comments)

3. Updates these sections in README.md (marked with `<!-- BEGIN section_name -->` comments):
   - `default_yaml` - inserts default.yaml content
   - `config_template` - inserts config-template.yaml content

4. Automatically stages the updated README.md and pom.xml

## Disable Hook

To temporarily disable the hook:

```bash
git commit --no-verify
```

To permanently remove:

```bash
rm .git/hooks/pre-commit
```

## What Gets Synced

### From `default.yaml`
- Complete default preset configuration
- Automatically appears in README sections marked with `<!-- BEGIN default_yaml -->`
- Version comment (first 2 lines) is skipped

### From `config-template.yaml`
- Template configuration with all options and comments
- Automatically appears in README sections marked with `<!-- BEGIN config_template -->`
- Version and usage comments (first 5 lines) are skipped

## Troubleshooting

**Hook doesn't run:**
- Check if `.git/hooks/pre-commit` exists and is executable
- Run: `chmod +x .git/hooks/pre-commit`
- Verify it's a bash script: `head -1 .git/hooks/pre-commit` should show `#!/bin/bash`

**Sync errors:**
- Ensure `config-template.yaml` and `default.yaml` exist
- Check YAML syntax is valid
- Run script manually to see detailed errors: `./bin/sync-documentation.py`

**Want to preview changes:**
- Run: `./bin/sync-documentation.py --dry-run`
- This shows the final README content without modifying files

**Want to update README manually:**
- Make changes to the source YAML files
- Run `./bin/sync-documentation.py`
- The README will be updated automatically

## Requirements

- Python 3.6 or later
- Git repository (for hook installation)
- Maven (for running tests in the hook)