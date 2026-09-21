import java.nio.file.*;
import org.eclipse.jgit.api.Git;

/** Disposable local fixture: verify whether the locked JGit status/diff runs a clean filter. */
public class GitFilterProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(root.resolve("a.txt"), "original\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("fixture").setAuthor("fixture", "fixture@example.test")
                .setCommitter("fixture", "fixture@example.test").call();
            Files.writeString(root.resolve("a.txt"), "original\nchanged\n");
            Files.writeString(root.resolve(".gitattributes"), "*.txt filter=hostile\n");
            git.getRepository().getConfig().setString("filter", "hostile", "clean", "echo invoked > executed; cat");
            git.getRepository().getConfig().save();
            git.status().call();
            git.diff().call();
            System.out.println("configuredCommandExecuted=" + Files.exists(root.resolve("executed")));
        }
    }
}
