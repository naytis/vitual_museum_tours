package ru.museum;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import ru.museum.Model.*;

/** Исполняемые проверки через обычную Java, без отдельной тестовой зависимости. */
public final class ComponentTest {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + (++checks) + " " + name);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejected(Action action, String name) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException expected) { check(true, name); return; }
        throw new AssertionError("Expected rejection: " + name);
    }

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args[0]).toAbsolutePath();
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), ".test-java-");
        try {
            TourService service = new TourService(new JsonRepository(root), root);
            String museum = service.createMuseum("Учебный музей");
            String tour = service.createTour(museum, "Маршрут двух залов");
            String rev = service.firstRevision(tour);
            String first = service.addHall(tour, rev, "Живопись");
            String second = service.addHall(tour, rev, "Графика");
            rejected(() -> service.submit(tour, rev), "Incomplete route cannot be submitted");
            var rows = JsonRepository.JSON.fromJson(Files.readString(manifest),
                    com.google.gson.JsonObject.class).getAsJsonArray("files");
            var output = rows.asList().stream().map(e -> e.getAsJsonObject())
                    .filter(e -> "ok".equals(e.get("status").getAsString()))
                    .map(e -> e.get("output").getAsString()).toList();
            service.importPanorama(tour, rev, first, manifest, output.get(0));
            service.importPanorama(tour, rev, second, manifest, output.get(1));
            check(service.getRevision(tour, rev).halls.get(0).panorama.width() == 640,
                    "Imports real Python manifest");
            String rejectedSource = rows.asList().stream().map(e -> e.getAsJsonObject())
                    .filter(e -> !"ok".equals(e.get("status").getAsString()))
                    .findFirst().orElseThrow().get("source").getAsString();
            rejected(() -> service.importPanorama(tour, rev, second, manifest, rejectedSource),
                    "Rejected Python file is not imported");
            service.setStart(tour, rev, first);
            check(!service.validate(tour, rev).isEmpty(), "Unreachable hall detected");
            service.addLink(tour, rev, first, second, 90, 0);
            rejected(() -> service.addLink(tour, rev, first, second, 90, 0), "Duplicate link rejected");
            String exhibit = service.addExhibit(tour, rev, first, "Пейзаж", "Автор", "1900", "Учебная карточка");
            check(!service.validate(tour, rev).isEmpty(), "Unplaced exhibit detected");
            rejected(() -> service.addPoint(tour, rev, second, exhibit, 0, 0),
                    "Cross-hall point rejected");
            rejected(() -> service.addPoint(tour, rev, first, exhibit, Double.NaN, 0),
                    "Invalid coordinates rejected");
            service.addPoint(tour, rev, first, exhibit, 0, 0);
            check(service.validate(tour, rev).isEmpty(), "Complete route validated");
            rejected(() -> service.publish(tour, rev), "Approval required before publication");
            service.submit(tour, rev);
            rejected(() -> service.addHall(tour, rev, "Поздняя правка"), "Review freezes editing");
            service.review(tour, rev, "Куратор", "Уточнить датировку", false);
            service.editExhibit(tour, rev, exhibit, "Пейзаж", "Автор", "1901", "Уточнённая карточка");
            service.submit(tour, rev);
            service.review(tour, rev, "Куратор", "Проверено", true);
            service.publish(tour, rev);
            rejected(() -> service.addHall(tour, rev, "Правка выпуска"), "Published release is immutable");
            Path export = args.length > 1 ? Path.of(args[1]) : root.resolve("release");
            service.export(tour, rev, export);
            check(Files.isRegularFile(export.resolve("tour.json")), "Exports published package");
            TourService.PublishedTour exported = JsonRepository.JSON.fromJson(
                    Files.readString(export.resolve("tour.json")), TourService.PublishedTour.class);
            check(exported.schemaVersion() == 1 && exported.title().equals("Маршрут двух залов")
                    && exported.revision().status == Status.PUBLISHED, "Export includes identity and schema version");
            for (Hall hall : service.getRevision(tour, rev).halls)
                check(Files.mismatch(root.resolve(hall.panorama.assetPath()),
                        export.resolve(hall.panorama.assetPath())) == -1, "Exported JPEG bytes unchanged");
            rejected(() -> service.export(tour, rev, export), "Export does not overwrite a directory");
            String next = service.newRevision(tour, rev);
            service.editExhibit(tour, next, exhibit, "Пейзаж", "Автор", "1902", "Вторая редакция");
            check(service.getRevision(tour, rev).exhibits.get(0).dating().equals("1901"),
                    "Draft is isolated from published content");
            service.submit(tour, next);
            service.review(tour, next, "Куратор", "Повторно проверено", true);
            service.publish(tour, next);
            check(service.getRevision(tour, rev).status == Status.ARCHIVED, "Previous release archived");
            check(service.getRevision(tour, next).status == Status.PUBLISHED, "New release published");
            TourService reopened = new TourService(new JsonRepository(root), root);
            check(reopened.getRevision(tour, next).exhibits.get(0).dating().equals("1902"),
                    "State restored from JSON");
            Catalog before = reopened.snapshot();
            Repository failing = new Repository() {
                public Catalog load() { return before; }
                public void save(Catalog catalog) throws IOException { throw new IOException("disk error"); }
            };
            TourService failed = new TourService(failing, root);
            rejected(() -> failed.createMuseum("Не сохранится"), "Save failure reported");
            check(failed.snapshot().museums.size() == 1, "Failed save leaves state unchanged");
            Path bad = root.resolve("bad.json");
            Files.writeString(bad, "{\"schemaVersion\":99,\"files\":[]}");
            rejected(() -> new ManifestImporter(root).importPanorama(bad, "x.jpg"), "Unknown manifest version rejected");
            Path outside = root.resolve("outside.jpg");
            Files.writeString(outside, "not used as image");
            Path manifests = Files.createDirectories(root.resolve("batch"));
            Files.writeString(manifests.resolve("manifest.json"),
                    "{\"schemaVersion\":1,\"files\":[{\"output\":\"../outside.jpg\",\"source\":\"x\",\"valid\":true,"
                    + "\"status\":\"ok\",\"width\":2,\"height\":1}]}");
            rejected(() -> new ManifestImporter(root).importPanorama(manifests.resolve("manifest.json"),
                    "../outside.jpg"), "Escaping batch directory rejected");
            Files.writeString(Path.of("evidence", "sample-catalog.json"),
                    JsonRepository.JSON.toJson(reopened.snapshot()));
            System.out.println("ALL " + checks + " CHECKS PASSED");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

