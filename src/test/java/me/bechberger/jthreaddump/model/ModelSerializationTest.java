package me.bechberger.jthreaddump.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test serialization of model classes to JSON and YAML
 */
class ModelSerializationTest {

    private final ObjectMapper jsonMapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    @Test
    void testThreadInfoJsonSerialization() throws Exception {
        ThreadInfo thread = ThreadInfoBuilder.create()
                .name("test-thread")
                .threadId(1L)
                .nativeId(0x2803L)
                .priority(5)
                .daemon(false)
                .state(Thread.State.RUNNABLE)
                .cpuTimeSec(100.0)
                .elapsedTimeSec(5000.0)
                // Use individual addX methods for readability when adding frames/locks
                .addStackFrame(new StackFrame("com.example.Test", "method", "Test.java", 42))
                .addLock(new LockInfo("0x123", "java.lang.Object", LockInfo.LockOperation.LOCKED))
                .build();

        String json = jsonMapper.writeValueAsString(thread);
        assertNotNull(json);
        assertTrue(json.contains("test-thread"));
        assertTrue(json.contains("RUNNABLE"));

        ThreadInfo deserialized = jsonMapper.readValue(json, ThreadInfo.class);
        assertEquals(thread.name(), deserialized.name());
        assertEquals(thread.state(), deserialized.state());
        assertEquals(thread.stackTrace().size(), deserialized.stackTrace().size());
    }

    @Test
    void testThreadDumpJsonSerialization() throws Exception {
        ThreadDump dump = new ThreadDump(
                Instant.now(),
                "Test JVM",
                List.of(
                        ThreadInfoBuilder.create()
                                .name("thread1")
                                .threadId(1L)
                                .nativeId(0x100L)
                                .priority(5)
                                .daemon(false)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(100.0)
                                .elapsedTimeSec(1000.0)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("thread2")
                                .threadId(2L)
                                .nativeId(0x200L)
                                .priority(5)
                                .daemon(true)
                                .state(Thread.State.WAITING)
                                .cpuTimeSec(50.0)
                                .elapsedTimeSec(2000.0)
                                .build()
                ),
                new JniInfo(100, 200, 1000L, 2000L),
                "jstack"
        );

        String json = jsonMapper.writeValueAsString(dump);
        assertNotNull(json);
        assertTrue(json.contains("thread1"));
        assertTrue(json.contains("thread2"));
        assertTrue(json.contains("jstack"));

        ThreadDump deserialized = jsonMapper.readValue(json, ThreadDump.class);
        assertEquals(2, deserialized.threads().size());
        assertEquals("jstack", deserialized.sourceType());
        assertNotNull(deserialized.jniInfo());
    }

    @Test
    void testThreadDumpYamlSerialization() throws Exception {
        ThreadDump dump = new ThreadDump(
                Instant.now(),
                "Test JVM",
                List.of(
                        ThreadInfoBuilder.create()
                                .name("thread1")
                                .threadId(1L)
                                .nativeId(0x100L)
                                .priority(5)
                                .daemon(false)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(100.0)
                                .elapsedTimeSec(1000.0)
                                .build()
                ),
                null,
                "jstack"
        );

        String yaml = yamlMapper.writeValueAsString(dump);
        assertNotNull(yaml);
        assertTrue(yaml.contains("thread1"));
        assertTrue(yaml.contains("RUNNABLE"));

        ThreadDump deserialized = yamlMapper.readValue(yaml, ThreadDump.class);
        assertEquals(1, deserialized.threads().size());
        assertEquals("thread1", deserialized.threads().get(0).name());
    }

    @Test
    void testStackFrameSerialization() throws Exception {
        StackFrame frame = new StackFrame("com.example.Main", "main", "Main.java", 10);

        String json = jsonMapper.writeValueAsString(frame);
        assertTrue(json.contains("com.example.Main"));
        assertTrue(json.contains("main"));
        assertTrue(json.contains("Main.java"));
        assertTrue(json.contains("10"));

        StackFrame deserialized = jsonMapper.readValue(json, StackFrame.class);
        assertEquals(frame.className(), deserialized.className());
        assertEquals(frame.methodName(), deserialized.methodName());
        assertEquals(frame.fileName(), deserialized.fileName());
        assertEquals(frame.lineNumber(), deserialized.lineNumber());
    }

    @Test
    void testLockInfoSerialization() throws Exception {
        LockInfo lock = new LockInfo("0x12345", "java.lang.Object", LockInfo.LockOperation.LOCKED);

        String json = jsonMapper.writeValueAsString(lock);
        assertTrue(json.contains("0x12345"));
        assertTrue(json.contains("java.lang.Object"));
        assertTrue(json.contains("LOCKED"));

        LockInfo deserialized = jsonMapper.readValue(json, LockInfo.class);
        assertEquals(lock.lockId(), deserialized.lockId());
        assertEquals(lock.className(), deserialized.className());
        assertEquals(lock.operation(), deserialized.operation());
    }

    @Test
    void testJniInfoSerialization() throws Exception {
        JniInfo jni = new JniInfo(100, 200, 1000L, 2000L);

        String json = jsonMapper.writeValueAsString(jni);
        assertTrue(json.contains("100"));
        assertTrue(json.contains("200"));

        JniInfo deserialized = jsonMapper.readValue(json, JniInfo.class);
        assertEquals(jni.globalRefs(), deserialized.globalRefs());
        assertEquals(jni.weakRefs(), deserialized.weakRefs());
    }

    @Test
    void testNullFieldsNotIncluded() throws Exception {
        ThreadInfo thread = ThreadInfoBuilder.create()
                .name("test")
                .threadId(null)
                .nativeId(null)
                .priority(null)
                .daemon(null)
                .state(Thread.State.RUNNABLE)
                .cpuTimeSec(null)
                .elapsedTimeSec(null)
                .build();

        String json = jsonMapper.writeValueAsString(thread);
        // JsonInclude.Include.NON_NULL should exclude null fields
        assertFalse(json.contains("\"threadId\""));
        assertFalse(json.contains("\"nativeId\""));
        assertFalse(json.contains("\"cpuTimeSec\""));
    }
}