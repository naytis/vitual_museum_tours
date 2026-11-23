package ru.museum;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import ru.museum.Model.*;

public final class TourService {
    public record PublishedTour(int schemaVersion, String museumId, String museumName,
                                String tourId, String title, Revision revision) {}
    private final Repository repository;
    private final Path root;
    private Catalog catalog;

    public TourService(Repository repository, Path root) throws IOException {
        this.repository = repository;
        this.root = root;
        catalog = repository.load();
    }

    private <T> T copy(T value, Class<T> type) {
        return JsonRepository.JSON.fromJson(JsonRepository.JSON.toJson(value), type);
    }

    public Catalog snapshot() { return copy(catalog, Catalog.class); }

    /** Сначала сохраняем копию. При отказе записи рабочее состояние остаётся прежним. */
    private void change(Consumer<Catalog> operation) throws IOException {
        Catalog next = snapshot();
        operation.accept(next);
        repository.save(next);
        catalog = next;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Поле обязательно");
        return value.trim();
    }

    private static Museum museum(Catalog catalog, String id) {
        return catalog.museums.stream().filter(m -> m.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Музей не найден"));
    }

    private static Tour tour(Catalog catalog, String id) {
        return catalog.museums.stream().flatMap(m -> m.tours.stream())
                .filter(t -> t.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Экскурсия не найдена"));
    }

    private static Revision revision(Tour tour, String id) {
        return tour.revisions.stream().filter(r -> r.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Редакция не найдена"));
    }

    private static Hall hall(Revision revision, String id) {
        return revision.halls.stream().filter(h -> h.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Зал не найден"));
    }

    private static void draft(Revision revision) {
        if (revision.status != Status.DRAFT)
            throw new IllegalArgumentException("Правки доступны в черновике");
    }

    private void edit(String tourId, String revisionId, Consumer<Revision> action) throws IOException {
        change(next -> {
            Revision rev = revision(tour(next, tourId), revisionId);
            draft(rev);
            action.accept(rev);
        });
    }

    public String createMuseum(String name) throws IOException {
        Museum museum = new Museum();
        museum.name = text(name);
        change(next -> next.museums.add(museum));
        return museum.id;
    }

    public String createTour(String museumId, String title) throws IOException {
        Tour tour = new Tour();
        tour.title = text(title);
        Revision rev = new Revision();
        rev.number = 1;
        tour.revisions.add(rev);
        change(next -> museum(next, museumId).tours.add(tour));
        return tour.id;
    }

    public Revision getRevision(String tourId, String revisionId) {
        return copy(revision(tour(catalog, tourId), revisionId), Revision.class);
    }

    public String firstRevision(String tourId) { return tour(catalog, tourId).revisions.get(0).id; }

    public String addHall(String tourId, String revId, String name) throws IOException {
        Hall hall = new Hall();
        hall.name = text(name);
        edit(tourId, revId, rev -> rev.halls.add(hall));
        return hall.id;
    }

    public void setStart(String tourId, String revId, String hallId) throws IOException {
        edit(tourId, revId, rev -> { hall(rev, hallId); rev.startHallId = hallId; });
    }

    public void importPanorama(String tourId, String revId, String hallId,
                               Path manifest, String output) throws IOException {
        Revision before = getRevision(tourId, revId);
        draft(before);
        hall(before, hallId);
        Panorama panorama = new ManifestImporter(root).importPanorama(manifest, output);
        edit(tourId, revId, rev -> hall(rev, hallId).panorama = panorama);
    }

    public String addExhibit(String tourId, String revId, String hallId, String title,
                              String author, String dating, String description) throws IOException {
        Exhibit exhibit = new Exhibit(Model.id(), hallId, text(title), author, dating, text(description));
        edit(tourId, revId, rev -> { hall(rev, hallId); rev.exhibits.add(exhibit); });
        return exhibit.id();
    }

    public void editExhibit(String tourId, String revId, String id, String title,
                             String author, String dating, String description) throws IOException {
        edit(tourId, revId, rev -> {
            Exhibit old = rev.exhibits.stream().filter(e -> e.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Экспонат не найден"));
            rev.exhibits.set(rev.exhibits.indexOf(old),
                    new Exhibit(id, old.hallId(), text(title), author, dating, text(description)));
        });
    }

    private static void angles(double yaw, double pitch) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)
                || yaw < -180 || yaw >= 180 || pitch < -90 || pitch > 90)
            throw new IllegalArgumentException("Углы должны быть в пределах yaw [-180,180), pitch [-90,90]");
    }

    public String addPoint(String tourId, String revId, String hallId, String exhibitId,
                            double yaw, double pitch) throws IOException {
        angles(yaw, pitch);
        InteractivePoint point = new InteractivePoint(Model.id(), hallId, exhibitId, yaw, pitch);
        edit(tourId, revId, rev -> {
            hall(rev, hallId);
            if (rev.exhibits.stream().noneMatch(e -> e.id().equals(exhibitId) && e.hallId().equals(hallId)))
                throw new IllegalArgumentException("Карточка должна принадлежать выбранному залу");
            rev.points.add(point);
        });
        return point.id();
    }

    public String addLink(String tourId, String revId, String from, String to,
                           double yaw, double pitch) throws IOException {
        angles(yaw, pitch);
        NavigationLink link = new NavigationLink(Model.id(), from, to, yaw, pitch);
        edit(tourId, revId, rev -> {
            hall(rev, from); hall(rev, to);
            if (from.equals(to)) throw new IllegalArgumentException("Выберите другой зал");
            if (rev.links.stream().anyMatch(l -> l.fromHallId().equals(from) && l.toHallId().equals(to)))
                throw new IllegalArgumentException("Переход уже существует");
            rev.links.add(link);
        });
        return link.id();
    }

    public void removeElement(String tourId, String revId, String id) throws IOException {
        edit(tourId, revId, rev -> {
            boolean removed = rev.points.removeIf(p -> p.id().equals(id));
            removed |= rev.links.removeIf(l -> l.id().equals(id));
            if (!removed) throw new IllegalArgumentException("Метка или переход не найдены");
        });
    }

    public List<String> validate(String tourId, String revId) {
        Revision rev = getRevision(tourId, revId);
        List<String> errors = new ArrayList<>();
        Map<String, List<String>> edges = new HashMap<>();
        for (Hall hall : rev.halls) {
            edges.put(hall.id, new ArrayList<>());
            if (hall.panorama == null) errors.add("Нет панорамы: " + hall.name);
            else if (!Files.isRegularFile(root.resolve(hall.panorama.assetPath())))
                errors.add("Потерян ресурс: " + hall.name);
        }
        if (!edges.containsKey(rev.startHallId)) errors.add("Выберите начальный зал");
        for (NavigationLink link : rev.links) edges.get(link.fromHallId()).add(link.toHallId());
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (edges.containsKey(rev.startHallId)) queue.add(rev.startHallId);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (visited.add(id)) queue.addAll(edges.get(id));
        }
        for (Hall hall : rev.halls)
            if (!visited.contains(hall.id)) errors.add("Недостижимый зал: " + hall.name);
        for (Exhibit exhibit : rev.exhibits)
            if (rev.points.stream().noneMatch(p -> p.exhibitId().equals(exhibit.id())))
                errors.add("Карточка без метки: " + exhibit.title());
        return errors;
    }

    private void valid(String tourId, String revId) {
        List<String> errors = validate(tourId, revId);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    }

    public void submit(String tourId, String revId) throws IOException {
        valid(tourId, revId);
        edit(tourId, revId, rev -> { rev.status = Status.REVIEW; rev.reviewer = null; rev.reviewNote = null; });
    }

    public void review(String tourId, String revId, String reviewer, String note,
                         boolean approved) throws IOException {
        String who = text(reviewer);
        if (!approved) text(note);
        change(next -> {
            Revision rev = revision(tour(next, tourId), revId);
            if (rev.status != Status.REVIEW) throw new IllegalArgumentException("Редакция не на проверке");
            rev.status = approved ? Status.APPROVED : Status.DRAFT;
            rev.reviewer = who;
            rev.reviewNote = note;
        });
    }

    public void publish(String tourId, String revId) throws IOException {
        valid(tourId, revId);
        change(next -> {
            Tour tour = tour(next, tourId);
            Revision rev = revision(tour, revId);
            if (rev.status != Status.APPROVED) throw new IllegalArgumentException("Требуется согласование");
            for (Revision old : tour.revisions)
                if (old.status == Status.PUBLISHED) old.status = Status.ARCHIVED;
            rev.status = Status.PUBLISHED;
            rev.publishedAt = Instant.now().toString();
        });
    }

    public String newRevision(String tourId, String baseId) throws IOException {
        Revision base = getRevision(tourId, baseId);
        if (base.status != Status.PUBLISHED && base.status != Status.ARCHIVED)
            throw new IllegalArgumentException("Выберите опубликованную или архивную редакцию");
        base.id = Model.id();
        base.status = Status.DRAFT;
        base.reviewer = null; base.reviewNote = null; base.publishedAt = null;
        change(next -> {
            Tour tour = tour(next, tourId);
            if (tour.revisions.stream().anyMatch(r -> r.status == Status.DRAFT
                    || r.status == Status.REVIEW || r.status == Status.APPROVED))
                throw new IllegalArgumentException("У экскурсии уже есть рабочая редакция");
            base.number = tour.revisions.stream().mapToInt(r -> r.number).max().orElse(0) + 1;
            tour.revisions.add(base);
        });
        return base.id;
    }

    public void export(String tourId, String revId, Path destination) throws IOException {
        Revision rev = getRevision(tourId, revId);
        if (rev.status != Status.PUBLISHED) throw new IllegalArgumentException("Выберите опубликованный выпуск");
        valid(tourId, revId);
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("Каталог экспорта уже существует");
        Files.createDirectories(target.getParent());
        Path stage = Files.createTempDirectory(target.getParent(), ".export-");
        try {
            Files.createDirectory(stage.resolve("assets"));
            for (Hall hall : rev.halls)
                Files.copy(root.resolve(hall.panorama.assetPath()), stage.resolve(hall.panorama.assetPath()),
                        StandardCopyOption.REPLACE_EXISTING);
            Museum owner = catalog.museums.stream()
                    .filter(m -> m.tours.stream().anyMatch(t -> t.id.equals(tourId)))
                    .findFirst().orElseThrow();
            PublishedTour published = new PublishedTour(1, owner.id, owner.name,
                    tourId, tour(catalog, tourId).title, rev);
            Files.writeString(stage.resolve("tour.json"), JsonRepository.JSON.toJson(published));
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(stage)) {
                try (var files = Files.walk(stage)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        }
    }
}

