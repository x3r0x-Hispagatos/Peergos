package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import peergos.shared.social.SharedItem;
import peergos.shared.user.app.*;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;
import peergos.shared.util.Serialize;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** This is the trusted implementation of the API that will be presented to a sandboxed application in Peergos.
 *
 * An application without any privileges can be run without any arguments, equivalent to viewing a web page.
 * It can also be used to open a file selected by the user, in this case the app can save changes over the original file
 * after confirmation by the user. An app  cna only open files for which it has register a matching mimetype.
 *
 * An app granting the STORE_LOCAL_APP_DATA permission can store, and read files in a directory which is private to the
 * app.
 *
 * An app granted the SHARE_LOCAL_APP_DATA permission can request the user to share a file created by the app with a
 * friend, and receive incoming files share with the user from the same app and with matching mimetype. It can also
 * request a secret link be generated to a file generated by the app.
 *
 * When an app is installed a copy of its assets are stored in /$username/.apps/$appname/assets
 * The apps internal storage, if allowed, is in /$username/.apps/$appname/data
 * Any permissions granted by the user will be stored in /$username/.apps/$appname/permissions.cbor
 */
public class App implements StoreLocalAppData {
    public static final String APPS_DIR_NAME = ".apps";
    public static final String DATA_DIR_NAME = "data";

    private final UserContext ctx;
    private final Path appDataDirectory;
    private App(UserContext ctx, Path appDataDirectory) {
        this.ctx = ctx;
        this.appDataDirectory = appDataDirectory;
    }

    @JsMethod
    public static CompletableFuture<App> init(UserContext ctx, String appName) {
        App app = new App(ctx, Paths.get(ctx.username, APPS_DIR_NAME, appName, DATA_DIR_NAME));
        Path appPath = Paths.get(APPS_DIR_NAME, appName, DATA_DIR_NAME);
        return ctx.getUserRoot()
                .thenCompose(root -> root.getOrMkdirs(appPath, ctx.network, true, ctx.crypto))
                .thenApply(appDir -> app);
    }

    private Path fullPath(Path path) {
        String pathAsString = path.toString().trim();
        Path relativePath = pathAsString.startsWith("/") ? Paths.get(pathAsString.substring(1)) : Paths.get(pathAsString);
        return appDataDirectory.resolve(relativePath);
    }

    private CompletableFuture<Boolean> writeFileContents(Path path, byte[] data) {
        Path pathWithoutUsername = Paths.get(Stream.of(path.toString().split("/")).skip(1).collect(Collectors.joining("/")));
        return ctx.getByPath(ctx.username).thenCompose(userRoot -> userRoot.get().getOrMkdirs(pathWithoutUsername.getParent(), ctx.network, true, ctx.crypto)
                .thenCompose(dir -> dir.uploadOrReplaceFile(path.getFileName().toString(), AsyncReader.build(data),
                        data.length, ctx.network, ctx.crypto, x -> {
                        }, ctx.crypto.random.randomBytes(32))
                        .thenApply(fw -> true)
                ));
    }

    @JsMethod
    public CompletableFuture<byte[]> readInternal(Path relativePath) {
        return readFileContents(fullPath(relativePath));
    }

    private CompletableFuture<byte[]> readFileContents(Path path) {
        return ctx.getByPath(path).thenCompose(optFile -> {
            if(optFile.isEmpty()) {
                throw new IllegalStateException("File not found:" + path.toString());
            }
            long len = optFile.get().getSize();
            return optFile.get().getInputStream(ctx.network, ctx.crypto, len, l-> {})
                    .thenCompose(is -> Serialize.readFully(is, len)
                            .thenApply(bytes -> bytes));
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> writeInternal(Path relativePath, byte[] data) {
        return writeFileContents(fullPath(relativePath), data);
    }

    @JsMethod
    public CompletableFuture<Boolean> deleteInternal(Path relativePath) {
        Path path = fullPath(relativePath);
        return ctx.getByPath(path.getParent()).thenCompose(dirOpt -> {
            if(dirOpt.isEmpty()) {
                throw new IllegalStateException("File not found:" + path.toString());
            }
            FileWrapper dir = dirOpt.get();
            String filename = path.getFileName().toString();
            Path pathToFile = path.resolve(filename);
            return dir.getChild(filename, ctx.crypto.hasher, ctx.network).thenCompose(file ->
                    file.get().remove(dir, pathToFile, ctx).thenApply(fw -> true));
        });
    }

    @JsMethod
    public CompletableFuture<List<String>> dirInternal(Path relativePath) {
        Path path = fullPath(relativePath);
        return ctx.getByPath(path).thenCompose(dirOpt -> {
            if(dirOpt.isEmpty()) {
                return Futures.of(Collections.emptyList());
            }
            return dirOpt.get().getChildren(ctx.crypto.hasher, ctx.network).thenApply(files ->
                    files.stream().map(fw -> fw.getName()).collect(Collectors.toList()));
        });
    }
}

