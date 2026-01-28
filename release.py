#!/usr/bin/env python3
"""
Bump minor version and deploy jthreaddump library.

This script:
1. Reads the current version from pom.xml
2. Bumps the minor version (e.g., 0.0.0 -> 0.2.0)
3. Updates pom.xml and Main.java with new version
4. Runs tests
5. Builds the package
6. Optionally deploys to Maven Central
7. Creates a git tag and commits the changes

Additionally:
- build-minimal: creates a temporary copy of the project with Jackson annotations and
  JetBrains @Nullable removed (plus their dependencies), renames the artifactId to
  jthreaddump-minimal, and builds it.
"""

import re
import sys
import subprocess
import argparse
from pathlib import Path
from typing import Tuple, Optional


class VersionBumper:
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.pom_xml = project_root / "pom.xml"
        self.main_java = project_root / "src/main/java/me/bechberger/jthreaddump/Main.java"
        self.readme = project_root / "README.md"
        self.changelog = project_root / "CHANGELOG.md"
        self.backup_dir = project_root / ".release-backup"
        self.backups_created = False

    def get_current_version(self) -> str:
        """Extract current version from pom.xml"""
        pom_content = self.pom_xml.read_text()
        match = re.search(r'<version>([\d.]+)</version>', pom_content)
        if not match:
            raise ValueError("Could not find version in pom.xml")
        return match.group(1)

    def parse_version(self, version: str) -> Tuple[int, int, int]:
        """Parse version string into (major, minor, patch)"""
        parts = version.split('.')
        if len(parts) != 3:
            raise ValueError(f"Invalid version format: {version}")
        return tuple(map(int, parts))

    def bump_minor(self, version: str) -> str:
        """Bump minor version (e.g., 0.0.0 -> 0.2.0)"""
        major, minor, patch = self.parse_version(version)
        return f"{major}.{minor + 1}.0"

    def bump_major(self, version: str) -> str:
        """Bump major version (e.g., 0.0.0 -> 1.0.0)"""
        major, minor, patch = self.parse_version(version)
        return f"{major + 1}.0.0"

    def bump_patch(self, version: str) -> str:
        """Bump patch version (e.g., 0.0.0 -> 0.1.1)"""
        major, minor, patch = self.parse_version(version)
        return f"{major}.{minor}.{patch + 1}"

    def update_pom_xml(self, old_version: str, new_version: str):
        """Update version in pom.xml"""
        content = self.pom_xml.read_text()
        # Replace first occurrence (project version)
        content = content.replace(
            f'<version>{old_version}</version>',
            f'<version>{new_version}</version>',
            1
        )
        self.pom_xml.write_text(content)
        print(f"✓ Updated pom.xml: {old_version} -> {new_version}")

    def update_main_java(self, old_version: str, new_version: str):
        """Update version in Main.java"""
        content = self.main_java.read_text()
        content = content.replace(
            f'version = "{old_version}"',
            f'version = "{new_version}"'
        )
        self.main_java.write_text(content)
        print(f"✓ Updated Main.java: {old_version} -> {new_version}")

    def update_readme(self, old_version: str, new_version: str):
        """Update version in README.md"""
        content = self.readme.read_text()
        content = content.replace(
            f'<version>{old_version}</version>',
            f'<version>{new_version}</version>'
        )
        # Also update minimal artifact snippet if it appears explicitly
        # (safe even if absent; it's a no-op)
        content = content.replace(
            f'<artifactId>jthreaddump-minimal</artifactId>\n    <version>{old_version}</version>',
            f'<artifactId>jthreaddump-minimal</artifactId>\n    <version>{new_version}</version>'
        )
        self.readme.write_text(content)
        print(f"✓ Updated README.md: {old_version} -> {new_version}")

    def show_version_diff(self, old_version: str, new_version: str):
        """Show what would change in version files"""
        print("\n📝 File changes preview:")
        print(f"\n  pom.xml:")
        print(f"    - <version>{old_version}</version>")
        print(f"    + <version>{new_version}</version>")

        print(f"\n  Main.java:")
        print(f"    - version = \"{old_version}\"")
        print(f"    + version = \"{new_version}\"")

        print(f"\n  README.md:")
        print(f"    - <version>{old_version}</version>")
        print(f"    + <version>{new_version}</version>")
        print(f"    (also updates jthreaddump-minimal snippet if present)")

    def show_changelog_diff(self, version: str):
        """Show what would change in CHANGELOG.md"""
        if not self.changelog.exists():
            print("\n  CHANGELOG.md: (file does not exist)")
            return

        from datetime import datetime
        today = datetime.now().strftime('%Y-%m-%d')

        # Get the Unreleased entry
        entry = self.get_changelog_entry(version)

        print(f"\n  CHANGELOG.md:")
        print(f"    - ## [Unreleased]")
        print(f"    + ## [Unreleased]")
        print(f"    + ")
        print(f"    + ### Added")
        print(f"    + ### Changed")
        print(f"    + ")
        print(f"    + ## [{version}] - {today}")

        if entry:
            # Show first few lines of content that will move to new version
            lines = entry.split('\n')[:5]
            for line in lines:
                if line.strip():
                    truncated = line[:70] + ('...' if len(line) > 70 else '')
                    print(f"    + {truncated}")

    def get_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for Unreleased section"""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text()

        # Look for [Unreleased] section
        unreleased_match = re.search(
            r'## \[Unreleased\]\s*\n(.*?)(?=\n## \[|$)',
            content,
            re.DOTALL
        )

        if unreleased_match:
            entry = unreleased_match.group(1).strip()
            return entry

        return ""

    def get_version_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for a specific released version"""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text()

        # Look for specific version section
        version_pattern = rf'## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)'
        version_match = re.search(version_pattern, content, re.DOTALL)

        if version_match:
            entry = version_match.group(1).strip()
            # Remove empty section headers (headers with no content after them)
            lines = []
            header = None
            for line in entry.split('\n'):
                if line.startswith('###'):
                    header = line
                    continue
                if line.strip():
                    if header:
                        lines.append(header)
                        header = None
                    lines.append(line)

            return '\n'.join(lines) if lines else ""

        return ""

    def validate_changelog(self, version: str) -> bool:
        """Validate that changelog has entries for the version"""
        entry = self.get_changelog_entry(version)
        if not entry or len(entry) < 20:
            print("\n❌ ERROR: CHANGELOG.md must have content in [Unreleased] section")
            print("\nPlease add your changes to CHANGELOG.md under [Unreleased]:")
            print("  ### Added")
            print("  - New feature 1")
            print("  ### Changed")
            print("  - Change 1")
            print("  ### Fixed")
            print("  - Bug fix 1")
            return False
        return True

    def update_changelog(self, version: str):
        """Update CHANGELOG.md to release the Unreleased section"""
        if not self.changelog.exists():
            print("⚠ No CHANGELOG.md found, skipping")
            return

        content = self.changelog.read_text()

        # Get today's date
        from datetime import datetime
        today = datetime.now().strftime('%Y-%m-%d')

        # Replace [Unreleased] with version and add new Unreleased section
        unreleased_pattern = r'## \[Unreleased\]'
        version_section = f'## [Unreleased]\n\n### Added\n### Changed\n### Deprecated\n### Removed\n### Fixed\n### Security\n\n## [{version}] - {today}'

        content = re.sub(unreleased_pattern, version_section, content, count=1)

        # Update comparison links at bottom
        old_unreleased = re.search(r'\[Unreleased\]: (.+)/compare/v([\d.]+)\.\.\.HEAD', content)
        if old_unreleased:
            base_url = old_unreleased.group(1)
            old_version = old_unreleased.group(2)

            new_links = f'[Unreleased]: {base_url}/compare/v{version}...HEAD\n[{version}]: {base_url}/compare/v{old_version}...v{version}'
            content = re.sub(
                r'\[Unreleased\]: .+',
                new_links,
                content
            )

        self.changelog.write_text(content)
        print(f"✓ Updated CHANGELOG.md for version {version}")

    def create_github_release(self, version: str):
        """Create GitHub release using gh CLI and CHANGELOG.md"""
        tag = f'v{version}'

        # Check if gh CLI is available
        try:
            subprocess.run(['gh', '--version'], capture_output=True, check=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            print("⚠ GitHub CLI (gh) not found. Skipping GitHub release creation.")
            print("  Install with: brew install gh  (macOS)")
            print("  Or visit: https://cli.github.com/")
            return

        # Check authentication
        try:
            result = subprocess.run(['gh', 'auth', 'status'], capture_output=True, text=True)
            if result.returncode != 0:
                print("⚠ GitHub CLI not authenticated. Run: gh auth login")
                return
        except:
            print("⚠ Could not check GitHub CLI auth status")
            return

        # Get changelog entry for this specific version (after it's been released in CHANGELOG.md)
        changelog_entry = self.get_version_changelog_entry(version)
        if not changelog_entry:
            changelog_entry = f"Release {version}\n\nSee [CHANGELOG.md](https://github.com/parttimenerd/jthreaddump/blob/main/CHANGELOG.md) for details."

        # Format release notes
        release_notes = f"""
{changelog_entry}

## Installation

### Maven
```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jthreaddump</artifactId>
    <version>{version}</version>
</dependency>
```

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jthreaddump-minimal</artifactId>
    <version>{version}</version>
</dependency>
```

### Download JAR
Download `jthreaddump.jar` (full) and `jthreaddump-minimal.jar` (minimal) from the assets below.
"""

        # Create release notes file
        notes_file = self.project_root / '.release-notes.md'
        notes_file.write_text(release_notes)

        try:
            # Build jar paths
            jar_path = self.project_root / 'target' / 'jthreaddump.jar'
            minimal_jar_path = self.project_root / 'target' / 'jthreaddump-minimal.jar'

            assets = []
            if jar_path.exists():
                assets.append(str(jar_path) + '#jthreaddump.jar')
            else:
                print(f"⚠ JAR not found at {jar_path}")

            if minimal_jar_path.exists():
                assets.append(str(minimal_jar_path) + '#jthreaddump-minimal.jar')
            else:
                print(f"⚠ Minimal JAR not found at {minimal_jar_path}")

            create_cmd = ['gh', 'release', 'create', tag,
                          '--title', f'Release {version}',
                          '--notes-file', str(notes_file)] + assets
            if not assets:
                print("⚠ No JAR assets found, creating release without assets")

            # If the release already exists (e.g. rerun), fall back to uploading assets.
            try:
                self.run_command(create_cmd, f"Creating GitHub release {tag}")
            except SystemExit:
                # run_command calls sys.exit(1) on failure; detect already-exists situations via stderr.
                # We can't easily inspect stderr here, so we do a conservative retry: try uploading assets.
                if assets:
                    upload_cmd = ['gh', 'release', 'upload', tag, '--clobber'] + assets
                    self.run_command(upload_cmd, f"Uploading GitHub release assets for {tag}")
                else:
                    raise
        finally:
            # Clean up notes file
            if notes_file.exists():
                notes_file.unlink()

    def create_backups(self):
        """Create backups of files that will be modified"""
        import shutil

        self.backup_dir.mkdir(exist_ok=True)

        files_to_backup = [
            self.pom_xml,
            self.main_java,
            self.readme,
            self.changelog
        ]

        for file in files_to_backup:
            if file.exists():
                backup_file = self.backup_dir / file.name
                shutil.copy2(file, backup_file)

        self.backups_created = True
        print("✓ Created backups of files")

    def restore_backups(self):
        """Restore files from backup"""
        import shutil

        if not self.backups_created or not self.backup_dir.exists():
            return

        print("\n⚠️  Restoring files from backup...")

        files_to_restore = [
            (self.backup_dir / "pom.xml", self.pom_xml),
            (self.backup_dir / "Main.java", self.main_java),
            (self.backup_dir / "README.md", self.readme),
            (self.backup_dir / "CHANGELOG.md", self.changelog)
        ]

        for backup_file, original_file in files_to_restore:
            if backup_file.exists():
                shutil.copy2(backup_file, original_file)
                print(f"  ✓ Restored {original_file.name}")

        print("✓ All files restored from backup")

    def cleanup_backups(self):
        """Remove backup directory"""
        import shutil

        if self.backup_dir.exists():
            shutil.rmtree(self.backup_dir)
            print("✓ Cleaned up backups")

    def run_command(self, cmd: list, description: str, check=True, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
        """Run a shell command"""
        print(f"\n→ {description}...")
        print(f"  $ {' '.join(cmd)}")
        result = subprocess.run(cmd, cwd=(cwd or self.project_root), capture_output=True, text=True)

        if result.returncode != 0 and check:
            print(f"✗ Failed: {description}")
            print(f"  stdout: {result.stdout}")
            print(f"  stderr: {result.stderr}")

            # Restore backups on failure
            self.restore_backups()

            print("\n❌ Release failed. All changes have been reverted.")
            sys.exit(1)

        print(f"✓ {description}")
        return result

    def run_tests(self):
        """Run Maven tests"""
        self.run_command(
            ['mvn', 'clean', 'test'],
            "Running tests"
        )

    def build_package(self):
        """Build Maven package"""
        self.run_command(
            ['mvn', 'clean', 'package'],
            "Building package"
        )

    def deploy_release(self):
        """Deploy to Maven Central using release profile"""
        self.run_command(
            ['mvn', 'clean', 'deploy', '-P', 'release'],
            "Deploying to Maven Central"
        )

    def git_commit(self, version: str):
        """Commit version changes"""
        self.run_command(
            ['git', 'add', 'pom.xml', 'src/main/java/me/bechberger/jthreaddump/Main.java', 'README.md', 'CHANGELOG.md'],
            "Staging files"
        )
        self.run_command(
            ['git', 'commit', '-m', f'Bump version to {version}'],
            "Committing changes"
        )

    def git_tag(self, version: str):
        """Create git tag"""
        tag = f'v{version}'
        self.run_command(
            ['git', 'tag', '-a', tag, '-m', f'Release {version}'],
            f"Creating tag {tag}"
        )

    def git_push(self, push_tags: bool = True):
        """Push changes and tags"""
        self.run_command(
            ['git', 'push'],
            "Pushing commits"
        )
        if push_tags:
            self.run_command(
                ['git', 'push', '--tags'],
                "Pushing tags"
            )


def _strip_minimal_java_source(content: str) -> str:
    """Strip Jackson and Nullable annotations/imports from a Java source file."""
    out_lines = []
    for line in content.splitlines(keepends=True):
        # Remove Jackson annotation imports
        if re.match(r"^[ \t]*import[ \t]+com\.fasterxml\.jackson\.annotation\..*;[ \t]*\r?\n?$", line):
            continue

        # Remove JetBrains Nullable import (be forgiving with whitespace)
        if re.match(r"^[ \t]*import[ \t]+org\.jetbrains\.annotations\.Nullable\s*;\s*\r?\n?$", line):
            continue

        # Remove @Json* annotations (lines starting with @Json...)
        if re.match(r"^[ \t]*@Json\w*\b.*\r?\n?$", line):
            continue

        # Never drop whole lines for @Nullable: just remove the token wherever it appears.
        # This keeps lines like: "private static @Nullable Instant foo()" intact.
        line = re.sub(r"@Nullable", "", line)

        # Clean up whitespace introduced by removing the annotation.
        # Only normalize within the line; preserve indentation and newlines.
        line = re.sub(r"([ \t]{2,})", " ", line)
        line = re.sub(r"\s+([,;>)])", r"\1", line)  # no space before punctuation
        line = re.sub(r"([(<])\s+", r"\1", line)     # no space after opening bracket/paren

        out_lines.append(line)

    return "".join(out_lines)


def _make_minimal_pom(pom_content: str) -> str:
    """Return a modified pom.xml content for the minimal artifact."""
    # Rename artifactId (first occurrence is the project artifactId)
    pom_content = pom_content.replace(
        "<artifactId>jthreaddump</artifactId>",
        "<artifactId>jthreaddump-minimal</artifactId>",
        1,
    )

    # Remove dependencies: drop any <dependency>...</dependency> block containing both the groupId and artifactId.
    def _drop_dependency(xml: str, group_id: str, artifact_id: str) -> str:
        pattern = (
            r"\s*<dependency>"  # start
            r"(?:(?!</dependency>).)*"  # body
            + re.escape(f"<groupId>{group_id}</groupId>")
            + r"(?:(?!</dependency>).)*"
            + re.escape(f"<artifactId>{artifact_id}</artifactId>")
            + r"(?:(?!</dependency>).)*"
            r"</dependency>\s*"  # end
        )
        return re.sub(pattern, "\n", xml, flags=re.DOTALL)

    pom_content = _drop_dependency(pom_content, "org.jetbrains", "annotations")
    pom_content = _drop_dependency(pom_content, "com.fasterxml.jackson.core", "jackson-annotations")

    # The extra Javadoc CLI args are optional and easy to get wrong when we strip Jackson.
    # For the minimal build, drop the whole <additionalJOptions> block so javadoc can run cleanly.
    pom_content = re.sub(
        r"\s*<additionalJOptions>.*?</additionalJOptions>\s*",
        "\n",
        pom_content,
        flags=re.DOTALL,
    )

    # Add -g:none to the existing maven-compiler-plugin configuration (minimal build should be stripped)
    # We prefer to edit the existing compiler plugin rather than adding a second one.
    def _ensure_compiler_g_none(xml: str) -> str:
        plugin_re = re.compile(
            r"(<plugin>\s*"  # start plugin
            r"(?:(?!</plugin>).)*?"  # body
            r"<artifactId>maven-compiler-plugin</artifactId>"  # identify
            r"(?:(?!</plugin>).)*?"  # body
            r"</plugin>)",
            re.DOTALL,
        )

        m = plugin_re.search(xml)
        if not m:
            # No compiler plugin found; inject one early in <plugins>
            inject = (
                "<plugin>\n"
                "    <groupId>org.apache.maven.plugins</groupId>\n"
                "    <artifactId>maven-compiler-plugin</artifactId>\n"
                "    <version>3.13.0</version>\n"
                "    <configuration>\n"
                "        <compilerArgs>\n"
                "            <arg>-g:none</arg>\n"
                "        </compilerArgs>\n"
                "    </configuration>\n"
                "</plugin>\n"
            )
            # Put it right after the opening <plugins> tag
            return re.sub(r"(<plugins>\s*)", r"\\1\n" + inject, xml, count=1)

        plugin_block = m.group(1)

        # If it already contains -g:none, do nothing.
        if "-g:none" in plugin_block:
            return xml

        # Ensure a <configuration> exists in the compiler plugin block.
        if "<configuration>" not in plugin_block:
            # Insert configuration before </plugin>
            plugin_block_new = re.sub(
                r"</plugin>\s*$",
                "    <configuration>\n"
                "        <compilerArgs>\n"
                "            <arg>-g:none</arg>\n"
                "        </compilerArgs>\n"
                "    </configuration>\n"
                "</plugin>",
                plugin_block,
                flags=re.DOTALL,
            )
            return xml[: m.start(1)] + plugin_block_new + xml[m.end(1) :]

        # If compilerArgs exists, append our arg.
        if "<compilerArgs>" in plugin_block:
            plugin_block_new = re.sub(
                r"(<compilerArgs>\s*)",
                lambda m2: m2.group(1) + "\n            <arg>-g:none</arg>",
                plugin_block,
                count=1,
            )
            return xml[: m.start(1)] + plugin_block_new + xml[m.end(1) :]

        # Otherwise insert compilerArgs inside configuration.
        plugin_block_new = re.sub(
            r"(<configuration>\s*)",
            lambda m2: m2.group(1) + "\n        <compilerArgs>\n            <arg>-g:none</arg>\n        </compilerArgs>",
            plugin_block,
            count=1,
        )
        return xml[: m.start(1)] + plugin_block_new + xml[m.end(1) :]

    pom_content = _ensure_compiler_g_none(pom_content)
    return pom_content


def _prepare_minimal_workspace(project_root: Path, tmp: Optional[Path]) -> Path:
    """Create a rewritten workspace for the minimal artifact and return its root directory."""
    import shutil
    import tempfile

    src_root = project_root

    if tmp is None:
        tmp_dir = Path(tempfile.mkdtemp(prefix="jthreaddump-minimal-"))
    else:
        tmp_dir = tmp.expanduser().resolve()
        if tmp_dir.exists():
            shutil.rmtree(tmp_dir)
        tmp_dir.mkdir(parents=True, exist_ok=True)

    ignore = shutil.ignore_patterns(
        ".git", ".idea", "target", "archive-tmp", ".release-backup", "__pycache__", ".DS_Store"
    )
    shutil.copytree(src_root, tmp_dir, dirs_exist_ok=True, ignore=ignore)

    # Patch pom.xml
    pom_path = tmp_dir / "pom.xml"
    pom_path.write_text(_make_minimal_pom(pom_path.read_text()))

    # Patch Java sources (main + tests)
    for java_root in [tmp_dir / "src" / "main" / "java", tmp_dir / "src" / "test" / "java"]:
        if not java_root.exists():
            continue
        for jf in java_root.rglob("*.java"):
            jf.write_text(_strip_minimal_java_source(jf.read_text()))

    return tmp_dir


def build_minimal(project_root: Path, tmp: Optional[Path], keep_tmp: bool, skip_tests: bool) -> int:
    """Build the minimal variant and copy the resulting jar into the main project's target/ folder."""
    import shutil

    tmp_dir = None
    try:
        tmp_dir = _prepare_minimal_workspace(project_root, tmp)

        if not skip_tests:
            test = subprocess.run(["mvn", "clean", "test"], cwd=tmp_dir)
            if test.returncode != 0:
                return test.returncode

        pkg = subprocess.run(["mvn", "clean", "package"], cwd=tmp_dir)
        if pkg.returncode != 0:
            return pkg.returncode

        # Find the built minimal jar and copy it to the original target/
        built_jar = tmp_dir / "target" / "jthreaddump-minimal.jar"
        if not built_jar.exists():
            # Fallback: try to locate jar by name
            candidates = list((tmp_dir / "target").glob("*minimal*.jar"))
            if candidates:
                built_jar = candidates[0]

        if not built_jar.exists():
            print(f"❌ Minimal jar not found in {tmp_dir / 'target'}")
            return 1

        out_dir = project_root / "target"
        out_dir.mkdir(exist_ok=True)
        shutil.copy2(built_jar, out_dir / "jthreaddump-minimal.jar")
        print(f"✓ Copied minimal jar to {out_dir / 'jthreaddump-minimal.jar'}")

        return 0
    finally:
        if tmp_dir is None:
            return
        if keep_tmp:
            print(f"\nℹ Keeping temporary directory: {tmp_dir}")
        else:
            try:
                shutil.rmtree(tmp_dir)
            except Exception:
                pass


def test_minimal(project_root: Path, tmp: Optional[Path], keep_tmp: bool) -> int:
    """Run mvn test on the minimal variant in a temporary directory."""
    import shutil

    tmp_dir = None
    try:
        tmp_dir = _prepare_minimal_workspace(project_root, tmp)
        result = subprocess.run(["mvn", "clean", "test"], cwd=tmp_dir, capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        return result.returncode
    finally:
        if tmp_dir is None:
            return
        if keep_tmp:
            print(f"\nℹ Keeping temporary directory: {tmp_dir}")
        else:
            try:
                shutil.rmtree(tmp_dir)
            except Exception:
                pass


def main():
    parser = argparse.ArgumentParser(
        description='Bump version and deploy jthreaddump library',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  # Full release (default): bump minor, test, build, deploy, commit, tag, push, GitHub release
  ./release.py

  # Build minimal variant into a temp directory
  ./release.py build-minimal

  # Test minimal variant (runs mvn test in a rewritten workspace)
  ./release.py test-minimal

  # Build minimal variant into a specific directory and keep it for debugging
  ./release.py build-minimal --tmp /tmp/jtd-min --keep-tmp

  # Test minimal variant into a specific directory and keep it for debugging
  ./release.py test-minimal --tmp /tmp/jtd-min --keep-tmp

 Note: CHANGELOG.md must have content under [Unreleased] section before releasing.
        '''
    )

    parser.add_argument(
        'command',
        nargs='?',
        default='release',
        choices=['release', 'build-minimal', 'test-minimal'],
        help='Command to run: release (default) or build-minimal'
    )

    parser.add_argument(
        '--tmp',
        type=str,
        default=None,
        help='Directory to use for build-minimal/test-minimal temporary workspace (will be deleted unless --keep-tmp)'
    )
    parser.add_argument(
        '--keep-tmp',
        action='store_true',
        help='Keep the temporary workspace directory created/used by build-minimal/test-minimal'
    )

    parser.add_argument(
        '--major',
        action='store_true',
        help='Bump major version (x.0.0)'
    )
    parser.add_argument(
        '--minor',
        action='store_true',
        help='Bump minor version (0.x.0) [default]'
    )
    parser.add_argument(
        '--patch',
        action='store_true',
        help='Bump patch version (0.0.x)'
    )
    parser.add_argument(
        '--no-deploy',
        action='store_true',
        help='Skip deployment to Maven Central (deploy is default)'
    )
    parser.add_argument(
        '--no-github-release',
        action='store_true',
        help='Skip GitHub release creation (github-release is default)'
    )
    parser.add_argument(
        '--no-push',
        action='store_true',
        help='Skip pushing to git remote (push is default)'
    )
    parser.add_argument(
        '--skip-tests',
        action='store_true',
        help='Skip running tests'
    )
    parser.add_argument(
        '--dry-run',
        action='store_true',
        help='Show what would happen without making changes'
    )

    args = parser.parse_args()

    # Determine project root
    script_path = Path(__file__).resolve()
    project_root = script_path.parent

    if args.command == 'build-minimal':
        tmp_path = Path(args.tmp) if args.tmp else None
        return_code = build_minimal(project_root, tmp_path, args.keep_tmp, args.skip_tests)
        sys.exit(return_code)

    if args.command == 'test-minimal':
        tmp_path = Path(args.tmp) if args.tmp else None
        return_code = test_minimal(project_root, tmp_path, args.keep_tmp)
        sys.exit(return_code)

    bumper = VersionBumper(project_root)

    # Get current version
    current_version = bumper.get_current_version()
    print(f"Current version: {current_version}")

    # Determine bump type
    if args.major:
        new_version = bumper.bump_major(current_version)
        bump_type = "major"
    elif args.patch:
        new_version = bumper.bump_patch(current_version)
        bump_type = "patch"
    else:
        new_version = bumper.bump_minor(current_version)
        bump_type = "minor"

    print(f"New version ({bump_type}): {new_version}")

    # Set defaults (deploy and github-release are ON by default)
    do_deploy = not args.no_deploy
    do_github_release = not args.no_github_release
    do_push = not args.no_push

    # Validate changelog before proceeding (unless dry-run)
    if not args.dry_run:
        if not bumper.validate_changelog(new_version):
            sys.exit(1)

    if args.dry_run:
        print("\n=== DRY RUN MODE ===")

        # Show file diffs
        bumper.show_version_diff(current_version, new_version)
        bumper.show_changelog_diff(new_version)

        # Show actions that would be taken
        print("\n📋 Actions that would be performed:")
        if not args.skip_tests:
            print("  • mvn clean test")
        print("  • mvn clean package")
        if do_deploy:
            print("  • mvn clean deploy -P release")
        print(f"  • git add pom.xml Main.java README.md CHANGELOG.md")
        print(f"  • git commit -m 'Bump version to {new_version}'")
        print(f"  • git tag -a v{new_version} -m 'Release {new_version}'")
        if do_push:
            print("  • git push")
            print("  • git push --tags")
        if do_github_release:
            print(f"  • gh release create v{new_version} (with CHANGELOG entry + jthreaddump.jar + jthreaddump-minimal.jar)")

        print("\n✓ No changes made (dry run)")
        return

    # Confirm
    step = 1
    print("\nThis will:")
    print(f"  {step}. Update version: {current_version} -> {new_version}")
    step += 1
    print(f"  {step}. Update CHANGELOG.md")
    step += 1

    if not args.skip_tests:
        print(f"  {step}. Run tests")
        step += 1
        print(f"  {step}. Run minimal tests")
        step += 1

    print(f"  {step}. Build package")
    step += 1
    print(f"  {step}. Build minimal package")
    step += 1

    if do_deploy:
        print(f"  {step}. Deploy to Maven Central")
        step += 1
        print(f"  {step}. Deploy minimal to Maven Central")
        step += 1

    print(f"  {step}. Commit and tag")
    step += 1

    if do_push:
        print(f"  {step}. Push to remote")
        step += 1

    if do_github_release:
        print(f"  {step}. Create GitHub release")
        step += 1

    response = input("\nContinue? [y/N] ")
    if response.lower() not in ['y', 'yes']:
        print("Aborted.")
        sys.exit(0)

    try:
        # Create backups before making any changes
        print("\n=== Creating backups ===")
        bumper.create_backups()

        # Update version files
        print("\n=== Updating version files ===")
        bumper.update_pom_xml(current_version, new_version)
        bumper.update_main_java(current_version, new_version)
        bumper.update_readme(current_version, new_version)
        bumper.update_changelog(new_version)

        # Run tests
        if not args.skip_tests:
            print("\n=== Running tests ===")
            bumper.run_tests()

            print("\n=== Running minimal tests ===")
            test_rc = test_minimal(project_root, None, keep_tmp=False)
            if test_rc != 0:
                raise RuntimeError(f"Minimal tests failed with exit code {test_rc}")
        else:
            print("\n⚠ Skipping tests")

        # Build package
        print("\n=== Building package ===")
        bumper.build_package()

        print("\n=== Building minimal package ===")
        minimal_rc = build_minimal(project_root, None, keep_tmp=False, skip_tests=True)
        if minimal_rc != 0:
            raise RuntimeError(f"Minimal build failed with exit code {minimal_rc}")

        # Deploy
        if do_deploy:
            print("\n=== Deploying to Maven Central ===")
            print("⚠ Make sure you have configured:")
            print("  - GPG key for signing")
            print("  - Maven settings.xml with OSSRH credentials")
            response = input("\nReady to deploy? [y/N] ")
            if response.lower() not in ['y', 'yes']:
                print("Skipping deployment.")
                do_deploy = False
            else:
                bumper.deploy_release()

                print("\n=== Deploying minimal to Maven Central ===")
                # Deploy minimal artifact from a rewritten workspace
                tmp_dir = _prepare_minimal_workspace(project_root, None)
                try:
                    deploy_result = subprocess.run(
                        ['mvn', 'clean', 'deploy', '-P', 'release'],
                        cwd=tmp_dir,
                        capture_output=True,
                        text=True,
                    )
                    sys.stdout.write(deploy_result.stdout)
                    sys.stderr.write(deploy_result.stderr)
                    if deploy_result.returncode != 0:
                        raise RuntimeError(f"Minimal deploy failed with exit code {deploy_result.returncode}")
                finally:
                    import shutil
                    try:
                        shutil.rmtree(tmp_dir)
                    except Exception:
                        pass

        # Git operations
        print("\n=== Git operations ===")
        bumper.git_commit(new_version)
        bumper.git_tag(new_version)

        if do_push:
            bumper.git_push(push_tags=True)

        # GitHub release
        if do_github_release:
            print("\n=== Creating GitHub release ===")
            # Ensure both jars exist in target/ before creating the GitHub release.
            if not (project_root / 'target' / 'jthreaddump-minimal.jar').exists():
                minimal_rc = build_minimal(project_root, None, keep_tmp=False, skip_tests=True)
                if minimal_rc != 0:
                    raise RuntimeError(f"Minimal build failed with exit code {minimal_rc}")
            bumper.create_github_release(new_version)

        # Cleanup backups after successful release
        bumper.cleanup_backups()

    except KeyboardInterrupt:
        print("\n\n⚠️  Release interrupted by user")
        bumper.restore_backups()
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ Unexpected error: {e}")
        bumper.restore_backups()
        raise

    # Summary
    print("\n" + "="*60)
    print(f"✓ Successfully released version {new_version}")
    print("="*60)

    print("\nCompleted:")
    print(f"  ✓ Version bumped: {current_version} -> {new_version}")
    print(f"  ✓ CHANGELOG.md updated")
    print(f"  ✓ Tests passed" if not args.skip_tests else "  ⊘ Tests skipped")
    print(f"  ✓ Package built")
    print(f"  ✓ Deployed to Maven Central" if do_deploy else "  ⊘ Deployment skipped")
    print(f"  ✓ Git commit and tag created")
    print(f"  ✓ Pushed to remote" if do_push else "  ⊘ Push skipped")
    print(f"  ✓ GitHub release created" if do_github_release else "  ⊘ GitHub release skipped")

    print(f"\nArtifacts:")
    print(f"  • target/jthreaddump.jar")
    print(f"  • target/jthreaddump-minimal.jar")
    print(f"  • target/jthreaddump-{new_version}.jar")
    print(f"  • target/jthreaddump-{new_version}-sources.jar")
    print(f"  • target/jthreaddump-{new_version}-javadoc.jar")

    if do_github_release:
        print(f"\n📦 GitHub Release:")
        print(f"  https://github.com/parttimenerd/jthreaddump/releases/tag/v{new_version}")

    if do_deploy:
        print(f"\n📦 Maven Central:")
        print(f"  https://central.sonatype.com/artifact/me.bechberger/jthreaddump/{new_version}")


if __name__ == '__main__':
    main()