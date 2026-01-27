# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.5.4] - 2026-01-27

### Fixed
- Missing timestamp

## [0.5.3] - 2026-01-27

### Changed
- Remove debug information for minimal build

## [0.5.1] - 2026-01-26

### Added
- minimal build (without `@Nullable` or `@Json` annotations)
  - 35K vs 141K for the normal build

## [0.5.0] - 2026-01-26

### Changed
- Simplified CLI

### Removed
- JSON and YAML output formats

## [0.4.0] - 2026-01-19

### Changed
- Rewrote LockInfo to align it more closely with the thread dump structure

### Removed
- Removed `ThreadInfo#waitingOnLock`, but only constructor changes

## [0.3.2] - 2026-01-19

### Added
- ThreadInfoBuilder
- Support for parsing carrier thread info

## [0.3.1] - 2026-01-12

### Added

- Add `ThreadInfo#getWaitedOnLock`

## [0.3.0] - 2025-12-29

### Changed
- Store CPU time and elapsed time as double instead of long

## [0.2.0] - 2025-12-29

### Changed
- Make ThreadDumpParser have only static methods

## [0.1.1] - 2025-12-29

### Fixed
- Add description in pom.xml

## [0.1.0] - 2025-12-29

### Added
- Initial implementation