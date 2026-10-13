package us.cubk;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConfigEnvironmentTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void environmentApiKeysApplyWhenConfigIsMissingOrInvalid(boolean invalidConfig) throws Exception {
        if (invalidConfig) Files.writeString(directory.resolve("config.yaml"), "app: [");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("QODER_PAT", "test-pat");
        builder.environment().put("APP_API_KEYS", "test-key-a,test-key-b");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Config probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            if (!Config.qoder().pat().equals(List.of("test-pat"))) {
                throw new IllegalStateException("Environment PAT was not loaded");
            }
            if (!Config.app().apiKeys().equals(List.of("test-key-a", "test-key-b"))) {
                throw new IllegalStateException("Environment API keys were not loaded");
            }
        }
    }
}
