package ru.museum;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Scanner;
import ru.museum.Model.*;

public final class Main {
    private final Scanner input = new Scanner(System.in);
    private final TourService service;
    private String tourId;
    private String revisionId;

    private Main(TourService service) { this.service = service; }

    private String ask(String label) {
        System.out.print(label + " > ");
        return input.nextLine().trim();
    }

    private double angle(String label) { return Double.parseDouble(ask(label).replace(',', '.')); }

    private void select() {
        tourId = ask("ID экскурсии");
        revisionId = ask("ID редакции");
        service.getRevision(tourId, revisionId);
    }

    private void show() {
        for (Museum museum : service.snapshot().museums) {
            System.out.println("МУЗЕЙ " + museum.id + "  " + museum.name);
            for (Tour tour : museum.tours) {
                System.out.println("  ТУР " + tour.id + "  " + tour.title);
                for (Revision rev : tour.revisions) {
                    System.out.println("    РЕДАКЦИЯ " + rev.number + " " + rev.status + " " + rev.id);
                    for (Hall hall : rev.halls) System.out.println("      ЗАЛ " + hall.id + " " + hall.name
                            + (hall.panorama == null ? " [нет панорамы]" : " [" + hall.panorama.source() + "]"));
                    for (Exhibit exhibit : rev.exhibits)
                        System.out.println("      ЭКСПОНАТ " + exhibit.id() + " " + exhibit.title());
                    for (InteractivePoint point : rev.points) System.out.println("      МЕТКА " + point.id()
                            + " -> " + point.exhibitId());
                    for (NavigationLink link : rev.links) System.out.println("      ПЕРЕХОД " + link.id()
                            + " " + link.fromHallId() + " -> " + link.toHallId());
                    if (rev.reviewer != null) System.out.println("      Куратор: " + rev.reviewer
                            + ". Решение: " + rev.reviewNote);
                }
            }
        }
    }

    private void loop() {
        while (true) {
            System.out.println("""
                    
                    1 Каталог             2 Создать музей       3 Создать экскурсию
                    4 Выбрать редакцию    5 Добавить зал         6 Начальный зал
                    7 Импорт панорамы     8 Добавить экспонат    9 Изменить карточку
                    10 Поставить метку    11 Добавить переход   12 Удалить метку/переход
                    13 Проверить маршрут  14 На согласование    15 Решение куратора
                    16 Опубликовать       17 Новая редакция     18 Экспорт выпуска
                    19 Содержимое редакции                       0 Выход
                    """);
            if (tourId != null) System.out.println("Выбран тур " + tourId + ", редакция " + revisionId);
            if (!input.hasNextLine()) return;
            String command = ask("Команда");
            try {
                switch (command) {
                    case "0" -> { return; }
                    case "1" -> show();
                    case "2" -> System.out.println("ID музея " + service.createMuseum(ask("Название")));
                    case "3" -> {
                        tourId = service.createTour(ask("ID музея"), ask("Название экскурсии"));
                        revisionId = service.firstRevision(tourId);
                        System.out.println("Создана и выбрана первая редакция");
                    }
                    case "4" -> select();
                    case "5" -> System.out.println("ID зала " + service.addHall(tourId, revisionId, ask("Название зала")));
                    case "6" -> service.setStart(tourId, revisionId, ask("ID начального зала"));
                    case "7" -> service.importPanorama(tourId, revisionId, ask("ID зала"),
                            Path.of(ask("Путь к manifest.json без кавычек")), ask("Имя из поля output"));
                    case "8" -> System.out.println("ID экспоната " + service.addExhibit(tourId, revisionId,
                            ask("ID зала"), ask("Название"), ask("Автор"), ask("Датировка"), ask("Описание")));
                    case "9" -> service.editExhibit(tourId, revisionId, ask("ID экспоната"), ask("Название"),
                            ask("Автор"), ask("Датировка"), ask("Описание"));
                    case "10" -> System.out.println("ID метки " + service.addPoint(tourId, revisionId,
                            ask("ID зала"), ask("ID экспоната"), angle("yaw"), angle("pitch")));
                    case "11" -> System.out.println("ID перехода " + service.addLink(tourId, revisionId,
                            ask("ID исходного зала"), ask("ID целевого зала"), angle("yaw"), angle("pitch")));
                    case "12" -> service.removeElement(tourId, revisionId, ask("ID метки или перехода"));
                    case "13" -> {
                        var errors = service.validate(tourId, revisionId);
                        System.out.println(errors.isEmpty() ? "Маршрут готов к проверке куратором" : String.join("\n", errors));
                    }
                    case "14" -> service.submit(tourId, revisionId);
                    case "15" -> {
                        String who = ask("Имя куратора");
                        String decision = ask("Согласовать: да. Вернуть: нет");
                        if (!decision.equals("да") && !decision.equals("нет"))
                            throw new IllegalArgumentException("Введите да или нет");
                        service.review(tourId, revisionId, who, ask("Комментарий"), decision.equals("да"));
                    }
                    case "16" -> service.publish(tourId, revisionId);
                    case "17" -> revisionId = service.newRevision(tourId, revisionId);
                    case "18" -> service.export(tourId, revisionId, Path.of(ask("Новый каталог экспорта")));
                    case "19" -> System.out.println(JsonRepository.JSON.toJson(service.getRevision(tourId, revisionId)));
                    default -> throw new IllegalArgumentException("Неизвестная команда");
                }
            } catch (IOException | IllegalArgumentException error) {
                System.out.println("Действие не выполнено: " + error.getMessage());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length == 0 ? "data" : args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (var channel = FileChannel.open(root.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Этот каталог уже открыт другим экземпляром редактора");
            new Main(new TourService(new JsonRepository(root), root)).loop();
        }
    }
}

