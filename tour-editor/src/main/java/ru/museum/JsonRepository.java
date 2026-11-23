package ru.museum;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import ru.museum.Model.Catalog;

public final class JsonRepository implements Repository {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private final Path file;

    public JsonRepository(Path root) throws IOException {
        Files.createDirectories(root);
        file = root.resolve("catalog.json");
    }

    @Override public Catalog load() throws IOException {
        if (!Files.exists(file)) return new Catalog();
        try {
            Catalog catalog = JSON.fromJson(Files.readString(file), Catalog.class);
            if (catalog == null || catalog.schemaVersion != 1 || catalog.museums == null)
                throw new IOException("Неподдерживаемый или повреждённый каталог");
            return catalog;
        } catch (JsonParseException error) {
            throw new IOException("Ошибка JSON каталога", error);
        }
    }

    @Override public void save(Catalog catalog) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "catalog-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(catalog), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

