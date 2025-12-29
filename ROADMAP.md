# Roadmap: jthreaddump Library Transformation

## Goal
Transform this project into a focused, well-tested thread dump parser library with minimal CLI support. Remove all analysis and view generation code, keeping only the core parsing functionality and JStack utility.

## Current State (v0.2.0)
- ✅ Working thread dump parser for jstack/jcmd output
- ✅ Rich data model (ThreadInfo, StackFrame, LockInfo, DeadlockInfo, JniInfo)
- ✅ Multiple output formats (TEXT, JSON, YAML)
- ✅ JStackUtil for capturing thread dumps from live processes
- ✅ Comprehensive test suite
- ⚠️ Simple CLI with text/JSON/YAML output (mostly just formatting)
- ⚠️ CPU time stored as Long (milliseconds) - loses precision for decimal values

## Phase 1: Fix Data Precision Issues ✅
**Target: v0.3.0**

### 1.1 CPU Time Representation
- [ ] Change `cpuTimeMs` (Long) to `cpuTimeSec` (Double) in ThreadInfo
- [ ] Update parser to preserve decimal precision
- [ ] Update all tests to use seconds instead of milliseconds
- [ ] Update Main.java to display CPU time in seconds
- [ ] Update README examples

**Why:** Thread dumps show CPU time with decimal precision (e.g., `cpu=10429.07ms`). Storing as Long truncates this valuable information.

### 1.2 Elapsed Time Representation
- [ ] Consider changing `elapsedTimeMs` to `elapsedTimeSec` (Double) for consistency
- [ ] Same precision preservation as CPU time

**Estimated effort:** 2-3 hours

---

## Phase 2: Code Cleanup & Simplification
**Target: v0.3.0 (same release)**

### 2.1 Remove Analysis Code
The current CLI is already minimal - it just formats output. Keep this as-is.

**Status:** ✅ No complex analysis to remove - just basic formatting

### 2.2 Verify Core Components
Essential components to **keep**:
- ✅ `parser/ThreadDumpParser.java` - Core parser
- ✅ `util/JStackUtil.java` - JStack/jcmd integration
- ✅ `model/*` - All data model classes
- ✅ `Main.java` - Simple CLI for formatting output
- ✅ All test files

**Estimated effort:** 1 hour (verification only)

---

## Phase 3: Documentation Updates
**Target: v0.3.0**

### 3.1 README Updates
- [x] Already focuses on parsing (no analysis claims)
- [ ] Update CPU time examples to use seconds
- [ ] Clarify that CLI is just for format conversion
- [ ] Add note about decimal precision in timing data

### 3.2 Code Documentation
- [ ] Review JavaDoc for all public APIs
- [ ] Ensure parser robustness is documented
- [ ] Document supported jstack/jcmd formats

**Estimated effort:** 2-3 hours

---

## Phase 4: Enhanced Testing
**Target: v0.4.0**

### 4.1 Parser Edge Cases
- [ ] Test decimal CPU times (e.g., 10429.07ms)
- [ ] Test very large elapsed times (e.g., 47185.99s)
- [ ] Test missing fields gracefully
- [ ] Test malformed thread dumps

### 4.2 Cross-JVM Compatibility
- [ ] Test with different Java versions (8, 11, 17, 21, 23)
- [ ] Test jstack output variations
- [ ] Test jcmd Thread.print variations

**Estimated effort:** 4-5 hours

---

## Phase 5: API Stability & Release
**Target: v1.0.0**

### 5.1 API Freeze
- [ ] Mark all public APIs as stable
- [ ] Add `@since` tags to JavaDoc
- [ ] Document breaking changes policy
- [ ] Semantic versioning commitment

### 5.2 Performance
- [ ] Benchmark parser on large dumps (1000+ threads)
- [ ] Memory profiling
- [ ] Consider streaming parser if needed

### 5.3 Release v1.0.0
- [ ] Final test pass
- [ ] Update CHANGELOG
- [ ] Maven Central release
- [ ] GitHub release with notes

**Estimated effort:** 6-8 hours

---

## Future Enhancements (Post v1.0.0)

### Optional Features (by demand)
- [ ] Support for additional JVM tools (VisualVM format, etc.)
- [ ] Native thread stack parsing (if available in dump)
- [ ] GC thread identification
- [ ] Virtual thread support (Project Loom)
- [ ] Compressed dump format support

### Community Requests
- Track issues for feature requests
- Keep library focused on parsing
- Direct users to build their own analysis tools

---

## Non-Goals

These are explicitly **out of scope** for this library:

❌ Thread dump analysis (e.g., finding hot threads, blocking chains)  
❌ Visualization/UI components  
❌ Diff/comparison tools  
❌ Recommendations or suggestions  
❌ Web server or REST API  
❌ Database storage  
❌ Historical tracking  

**Philosophy:** This library provides a robust parser and data model. Users build their own analysis and tooling on top.

---

## Release Timeline

| Version | Focus | ETA |
|---------|-------|-----|
| v0.3.0 | Fix CPU/elapsed time precision + doc updates | Next release |
| v0.4.0 | Enhanced testing & edge cases | 2-3 weeks |
| v1.0.0 | API stability & performance | 1-2 months |

---

## Success Criteria

A successful transformation means:

1. ✅ **Zero analysis code** - Just parsing and data models
2. ✅ **Comprehensive tests** - High confidence in parser robustness
3. ✅ **Clean API** - Simple, documented, stable
4. ✅ **Accurate parsing** - Preserves all data with proper precision
5. ✅ **Utility support** - Easy integration with jstack/jcmd
6. ✅ **Multiple formats** - JSON/YAML for tool integration

---

## Questions & Decisions

### Q: Keep the CLI?
**A:** Yes, keep it simple. It's useful for:
- Quick verification of parsing
- Format conversion (dump.txt → JSON)
- Debugging and testing

### Q: Support older Java versions?
**A:** No. Java 21+ only. Use modern features (records, pattern matching).

### Q: Add streaming parser?
**A:** Only if performance testing shows it's needed. Start simple.

### Q: Publish to Maven Central?
**A:** Yes, essential for a library. Already configured in pom.xml.

---

## Implementation Notes

### CPU Time Fix Details
Current: `cpu=10429.07ms` → `cpuTimeMs=10429L` (loses .07)  
Fixed: `cpu=10429.07ms` → `cpuTimeSec=10.42907` (preserves precision)

Parser changes:
- `parseTimeToSeconds(String value, String unit)` → returns Double
- Store in seconds, not milliseconds
- More intuitive for consumers (matches jstack output units better)

### Testing Strategy
- Unit tests for parser edge cases
- Integration tests with real dumps from different JVMs
- Property-based testing for time conversions
- Regression tests for known issues

---

## Migration Guide (for v0.3.0 users)

Breaking changes from v0.2.0 to v0.3.0:

```java
// OLD (v0.2.0)
Long cpuMs = thread.cpuTimeMs();
Long elapsedMs = thread.elapsedTimeMs();

// NEW (v0.3.0)
Double cpuSec = thread.cpuTimeSec();
Double elapsedSec = thread.elapsedTimeSec();

// Migration
long cpuMs = (long)(cpuSec * 1000);  // if you need ms
```

This is a **breaking change** but necessary for correctness.