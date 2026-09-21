import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the candidate's compiled store against an owned temporary fixture only. */
class OAuthAttemptBoundaryProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "attempt-boundary-");
        Path attempts = Files.createDirectory(root.resolve("attempts"));
        Path victim = root.resolve("unrelated.json");
        Files.writeString(victim, "{\"fixture\":true}");
        Class<?> storeType = Class.forName("com.helix.app.mcp.oauth.McpOAuthAttemptStore");
        Class<?> secretType = Class.forName("com.helix.core.storage.SecretStore");
        Object secrets = java.lang.reflect.Proxy.newProxyInstance(secretType.getClassLoader(),
                new Class<?>[]{secretType}, (proxy, method, values) -> {
                    throw new AssertionError("Invalid state must not access secrets");
                });
        Object store = storeType.getConstructor(java.io.File.class, secretType).newInstance(attempts.toFile(), secrets);
        Object result = storeType.getMethod("consumeAttempt", String.class, long.class)
                .invoke(store, "../unrelated", 1L);
        System.out.println("candidate=" + args[1]);
        System.out.println("consumeReturnedNull=" + (result == null));
        System.out.println("unrelatedJsonSurvived=" + Files.exists(victim));
        if (!Files.exists(victim)) {
            throw new AssertionError("Callback state deleted unrelated JSON outside attempt directory");
        }
    }
}
