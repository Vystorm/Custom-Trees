package at.esoren.customtrees;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict, bounded persistence for registered CustomTrees gardens. Never restores blocks. */
final class GardenState {
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int MAX_TREES = 128;
    static final int MAX_LEAF_ROWS = 512;
    private static final int SCHEMA = 1;

    record Pos(int x, int y, int z) {
        Pos {
            if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000
                    || y < -2048 || y > 2047) {
                throw new IllegalArgumentException("Position is outside supported world bounds");
            }
        }
    }

    record Leaf(Pos pos, String blockData, long dueMillis) {
        Leaf {
            Objects.requireNonNull(pos, "pos");
            if (blockData == null || blockData.isBlank() || blockData.length() > 512) {
                throw new IllegalArgumentException("Invalid leaf block data");
            }
            if (dueMillis < 0) throw new IllegalArgumentException("Negative due time");
        }
    }

    record Tree(UUID id, UUID owner, UUID world, Pos root, String rootMaterial, List<Leaf> leaves) {
        Tree {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(root, "root");
            if (rootMaterial == null || !rootMaterial.matches("[A-Z_]{1,48}")) {
                throw new IllegalArgumentException("Invalid root material");
            }
            Objects.requireNonNull(leaves, "leaves");
            if (leaves.size() > MAX_LEAF_ROWS) throw new IllegalArgumentException("Too many leaf rows");
            List<Leaf> copy = List.copyOf(leaves);
            Set<Pos> positions = new HashSet<>();
            for (Leaf leaf : copy) {
                Objects.requireNonNull(leaf, "leaf");
                if (!positions.add(leaf.pos())) throw new IllegalArgumentException("Duplicate leaf coordinate");
            }
            leaves = copy;
        }
    }

    private final Path file;
    private final Map<UUID, Tree> state = new LinkedHashMap<>();
    private boolean healthy = true;

    /** Loads an existing schema-1 file. Invalid or unsafe files remain untouched and unhealthy. */
    GardenState(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        try {
            rejectSymlinkPath(this.file);
            if (Files.exists(this.file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(this.file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Garden state is not a regular file");
                }
                long size = Files.size(this.file);
                if (size > MAX_FILE_BYTES) throw new IOException("Garden state exceeds size limit");
                state.putAll(decode(Files.readString(this.file, StandardCharsets.UTF_8)));
            }
        } catch (IOException | RuntimeException invalid) {
            state.clear();
            healthy = false;
        }
    }

    synchronized boolean healthy() {
        return healthy;
    }

    synchronized List<Tree> trees() {
        return List.copyOf(state.values());
    }

    synchronized void put(Tree tree) throws IOException {
        Objects.requireNonNull(tree, "tree");
        ensureHealthy();
        Tree previous = state.get(tree.id());
        if (previous == null && state.size() >= MAX_TREES) throw new IOException("Garden tree limit reached");
        state.put(tree.id(), tree);
        persistOrMarkUnhealthy();
    }

    synchronized void remove(UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        ensureHealthy();
        if (!state.containsKey(id)) return;
        state.remove(id);
        persistOrMarkUnhealthy();
    }

    private void ensureHealthy() throws IOException {
        if (!healthy) throw new IOException("Garden state is unhealthy; refusing writes");
    }

    private void persistOrMarkUnhealthy() throws IOException {
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            healthy = false;
            if (failure instanceof IOException io) throw io;
            throw new IOException("Could not persist garden state", failure);
        }
    }

    private void persist() throws IOException {
        rejectSymlinkPath(file);
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Garden state parent directory is unavailable");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Garden state target is not a regular file");
        }
        byte[] bytes = encode(state).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Garden state exceeds size limit");
        Path temp = Files.createTempFile(parent, ".garden-state-", ".tmp");
        try {
            if (Files.isSymbolicLink(temp)) throw new IOException("Temporary state file is a symlink");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic garden-state replacement is unavailable", unsupported);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void rejectSymlinkPath(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink in garden-state path");
        }
    }

    private static String encode(Map<UUID, Tree> trees) {
        StringBuilder out = new StringBuilder();
        line(out, "schema", Integer.toString(SCHEMA));
        line(out, "tree.count", Integer.toString(trees.size()));
        int index = 0;
        for (Tree tree : trees.values()) {
            String prefix = "tree." + index++ + ".";
            line(out, prefix + "id", tree.id().toString());
            line(out, prefix + "owner", tree.owner().toString());
            line(out, prefix + "world", tree.world().toString());
            line(out, prefix + "root.x", Integer.toString(tree.root().x()));
            line(out, prefix + "root.y", Integer.toString(tree.root().y()));
            line(out, prefix + "root.z", Integer.toString(tree.root().z()));
            line(out, prefix + "root.material", tree.rootMaterial());
            line(out, prefix + "leaf.count", Integer.toString(tree.leaves().size()));
            for (int leafIndex = 0; leafIndex < tree.leaves().size(); leafIndex++) {
                Leaf leaf = tree.leaves().get(leafIndex);
                String leafPrefix = prefix + "leaf." + leafIndex + ".";
                line(out, leafPrefix + "x", Integer.toString(leaf.pos().x()));
                line(out, leafPrefix + "y", Integer.toString(leaf.pos().y()));
                line(out, leafPrefix + "z", Integer.toString(leaf.pos().z()));
                line(out, leafPrefix + "data", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(leaf.blockData().getBytes(StandardCharsets.UTF_8)));
                line(out, leafPrefix + "due", Long.toString(leaf.dueMillis()));
            }
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static Map<UUID, Tree> decode(String content) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String row : content.split("\\n", -1)) {
            if (row.endsWith("\r")) row = row.substring(0, row.length() - 1);
            if (row.isEmpty()) continue;
            int separator = row.indexOf('=');
            if (separator <= 0 || separator != row.lastIndexOf('=')) {
                throw new IOException("Malformed garden-state row");
            }
            String key = row.substring(0, separator);
            String value = row.substring(separator + 1);
            if (values.putIfAbsent(key, value) != null) throw new IOException("Duplicate garden-state key");
        }
        int schema = number(take(values, "schema"), "schema");
        if (schema != SCHEMA) throw new IOException("Unsupported garden-state schema");
        int treeCount = boundedCount(take(values, "tree.count"), MAX_TREES, "tree count");
        Map<UUID, Tree> trees = new LinkedHashMap<>();
        for (int index = 0; index < treeCount; index++) {
            String prefix = "tree." + index + ".";
            UUID id = uuid(take(values, prefix + "id"));
            UUID owner = uuid(take(values, prefix + "owner"));
            UUID world = uuid(take(values, prefix + "world"));
            Pos root = new Pos(number(take(values, prefix + "root.x"), "root x"),
                    number(take(values, prefix + "root.y"), "root y"),
                    number(take(values, prefix + "root.z"), "root z"));
            String material = take(values, prefix + "root.material");
            int leafCount = boundedCount(take(values, prefix + "leaf.count"), MAX_LEAF_ROWS, "leaf count");
            List<Leaf> leaves = new ArrayList<>(leafCount);
            for (int leafIndex = 0; leafIndex < leafCount; leafIndex++) {
                String leafPrefix = prefix + "leaf." + leafIndex + ".";
                Pos pos = new Pos(number(take(values, leafPrefix + "x"), "leaf x"),
                        number(take(values, leafPrefix + "y"), "leaf y"),
                        number(take(values, leafPrefix + "z"), "leaf z"));
                byte[] encoded = base64(take(values, leafPrefix + "data"));
                String data;
                try {
                    data = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(encoded)).toString();
                } catch (CharacterCodingException invalid) {
                    throw new IOException("Invalid UTF-8 block data", invalid);
                }
                long due = longNumber(take(values, leafPrefix + "due"), "leaf due");
                leaves.add(new Leaf(pos, data, due));
            }
            Tree tree = new Tree(id, owner, world, root, material, leaves);
            if (trees.putIfAbsent(id, tree) != null) throw new IOException("Duplicate garden tree id");
        }
        if (!values.isEmpty()) throw new IOException("Unexpected garden-state keys");
        return Collections.unmodifiableMap(trees);
    }

    private static String take(Map<String, String> values, String key) throws IOException {
        String value = values.remove(key);
        if (value == null) throw new IOException("Missing garden-state field: " + key);
        return value;
    }

    private static int boundedCount(String text, int max, String label) throws IOException {
        int count = number(text, label);
        if (count < 0 || count > max) throw new IOException("Out-of-range " + label);
        return count;
    }

    private static int number(String text, String label) throws IOException {
        if (!text.matches("-?(0|[1-9][0-9]*)")) throw new IOException("Invalid " + label);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid " + label, invalid);
        }
    }

    private static long longNumber(String text, String label) throws IOException {
        if (!text.matches("0|[1-9][0-9]*")) throw new IOException("Invalid " + label);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid " + label, invalid);
        }
    }

    private static UUID uuid(String text) throws IOException {
        if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IOException("Invalid UUID");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid UUID", invalid);
        }
    }

    private static byte[] base64(String text) throws IOException {
        if (text.length() > 2731 || !text.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("Invalid block-data encoding");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(text);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(text)) {
                throw new IOException("Non-canonical block-data encoding");
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid block-data encoding", invalid);
        }
    }
}
