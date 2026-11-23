package ru.museum;

import java.io.IOException;
import ru.museum.Model.Catalog;

/** Сервис не зависит от конкретного способа хранения каталога. */
public interface Repository {
    Catalog load() throws IOException;
    void save(Catalog catalog) throws IOException;
}

