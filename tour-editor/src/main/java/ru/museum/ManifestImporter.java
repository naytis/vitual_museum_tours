package ru.museum;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import ru.museum.Model.Panorama;

/** Импортирует готовые файлы. Размеры и результат проверки получает от Python. */
public final class ManifestImporter {
    private final Path root;
    public ManifestImporter(Path root) { this.root = root; }

    public Panorama importPanorama(Path manifest, String output) throws IOException {
        try {
            JsonObject doc = JsonRepository.JSON.fromJson(Files.readString(manifest), JsonObject.class);
            if (doc == null || doc.get("schemaVersion").getAsInt() != 1)
                throw new IOException("Неподдерживаемая версия манифеста");
            JsonObject selected = null;
            for (JsonElement item : doc.getAsJsonArray("files")) {
                JsonObject row = item.getAsJsonObject();
                if (row.has("output") && output.equals(row.get("output").getAsString())) {
                    if (selected != null) throw new IOException("Имя output повторяется");
                    selected = row;
                }
            }
            if (selected == null) throw new IOException("Файл отсутствует в манифесте");
            if (!selected.get("valid").getAsBoolean()
                    || !"ok".equals(selected.get("status").getAsString()))
                throw new IOException("Python не подтвердил готовность файла");
            int width = selected.get("width").getAsInt();
            int height = selected.get("height").getAsInt();
            if (width <= 0 || height <= 0)
                throw new IOException("В манифесте отсутствуют корректные размеры");
            Path base = manifest.toRealPath().getParent();
            Path source = base.resolve(output).normalize().toRealPath();
            if (!source.startsWith(base) || !Files.isRegularFile(source))
                throw new IOException("Ресурс должен находиться рядом с манифестом");
            Files.createDirectories(root.resolve("assets"));
            Path temp = Files.createTempFile(root.resolve("assets"), "import-", ".tmp");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var input = new DigestInputStream(Files.newInputStream(source), digest)) {
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                String asset = "assets/" + HexFormat.of().formatHex(digest.digest()) + ".jpg";
                Path target = root.resolve(asset);
                if (!Files.exists(target)) Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                return new Panorama(asset, selected.get("source").getAsString(), width, height);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException
                 | NullPointerException error) {
            throw new IOException("Некорректный манифест: " + error.getMessage(), error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

