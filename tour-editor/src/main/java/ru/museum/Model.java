package ru.museum;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Объекты предметной области. Идентификаторы объектов редакции локальны для неё. */
public final class Model {
    private Model() {}
    public static String id() { return UUID.randomUUID().toString(); }

    public enum Status { DRAFT, REVIEW, APPROVED, PUBLISHED, ARCHIVED }

    public static final class Catalog {
        public int schemaVersion = 1;
        public List<Museum> museums = new ArrayList<>();
    }

    public static final class Museum {
        public String id = Model.id();
        public String name;
        public List<Tour> tours = new ArrayList<>();
    }

    public static final class Tour {
        public String id = Model.id();
        public String title;
        public List<Revision> revisions = new ArrayList<>();
    }

    public static final class Revision {
        public String id = Model.id();
        public int number;
        public Status status = Status.DRAFT;
        public String startHallId;
        public List<Hall> halls = new ArrayList<>();
        public List<Exhibit> exhibits = new ArrayList<>();
        public List<InteractivePoint> points = new ArrayList<>();
        public List<NavigationLink> links = new ArrayList<>();
        public String reviewer;
        public String reviewNote;
        public String publishedAt;
    }

    public static final class Hall {
        public String id = Model.id();
        public String name;
        public Panorama panorama;
    }

    public record Panorama(String assetPath, String source, int width, int height) {}
    public record Exhibit(String id, String hallId, String title, String author,
                          String dating, String description) {}
    public record InteractivePoint(String id, String hallId, String exhibitId,
                                   double yaw, double pitch) {}
    public record NavigationLink(String id, String fromHallId, String toHallId,
                                 double yaw, double pitch) {}
}

