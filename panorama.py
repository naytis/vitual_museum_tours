import argparse
import json
import os
import sys
import tempfile
import warnings
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError

FORMATS = {"JPEG", "PNG"}
PIXEL_LIMIT = 80_000_000


def read_panorama(path):
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        with Image.open(path) as source:
            if source.format not in FORMATS:
                raise ValueError("Поддерживаются только JPEG и PNG")
            if getattr(source, "n_frames", 1) != 1:
                raise ValueError("Требуется неподвижное изображение")
            if source.width * source.height > PIXEL_LIMIT:
                raise ValueError("Превышено ограничение 80 млн пикселей")
            source.load()
            image = ImageOps.exif_transpose(source)
            if image.width != 2 * image.height:
                image.close()
                raise ValueError("Требуется соотношение сторон 2:1")
            return image, source.format

def prepare(image, target, max_width, quality):
    width = min(image.width, max_width // 2 * 2)
    size = (width, width // 2)
    with image.convert("RGBA") as rgba:
        background = Image.new("RGB", image.size, "white")
        with background:
            background.paste(rgba, mask=rgba.getchannel("A"))
            with background.resize(size, Image.Resampling.LANCZOS) as scaled:
                with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as temp:
                    temp_path = Path(temp.name)
                try:
                    scaled.save(temp_path, format="JPEG", quality=quality, optimize=True)
                    os.replace(temp_path, target)
                finally:
                    temp_path.unlink(missing_ok=True)
    return size


def process_file(path, destination, max_width, quality):
    entry = {"source": path.name, "valid": False, "status": "error"}
    try:
        image, fmt = read_panorama(path)
        with image:
            entry.update(valid=True, sourceFormat=fmt, sourceWidth=image.width, sourceHeight=image.height)
            name = path.name + ".jpg"
            width, height = prepare(image, destination / name, max_width, quality)
            entry.update(status="ok", output=name, width=width, height=height)
    except UnidentifiedImageError:
        entry["error"] = "Файл не распознан как изображение"
    except (OSError, ValueError, Image.DecompressionBombError,
            Image.DecompressionBombWarning, SyntaxError, EOFError) as error:
        entry["error"] = str(error)
    return entry

def write_manifest(destination, entries):
    payload = {"schemaVersion": 1, "files": entries}
    with tempfile.NamedTemporaryFile(dir=destination, delete=False, mode="w",
                                     encoding="utf-8") as stream:
        temp = Path(stream.name)
        try:
            json.dump(payload, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        except BaseException:
            stream.close()
            temp.unlink(missing_ok=True)
            raise
    try:
        os.replace(temp, destination / "manifest.json")
    finally:
        temp.unlink(missing_ok=True)


def run(source, destination, max_width=4096, quality=85):
    if max_width < 2 or not 1 <= quality <= 95:
        raise ValueError("Ширина должна быть ≥ 2, качество — от 1 до 95")
    source, destination = source.resolve(), destination.resolve()
    if source.is_dir():
        files = sorted((p for p in source.iterdir() if p.is_file()), key=lambda p: p.name)
        input_dir = source
    else:
        files = [source]
        input_dir = source.parent
    if destination == input_dir or input_dir.is_relative_to(destination):
        raise ValueError("Выберите отдельный выходной каталог")
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("Для каждого запуска нужен пустой выходной каталог")
    entries = [process_file(p, destination, max_width, quality) for p in files]
    write_manifest(destination, entries)
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="файл или каталог")
    parser.add_argument("destination", type=Path, help="новый или пустой каталог")
    parser.add_argument("--max-width", type=int, default=4096)
    parser.add_argument("--quality", type=int, default=85)
    args = parser.parse_args()
    try:
        entries = run(args.source, args.destination, args.max_width, args.quality)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
    good = sum(item["status"] == "ok" for item in entries)
    print(f"Всего: {len(entries)}. Подготовлено: {good}. Ошибок: {len(entries)-good}.")
    return int(good != len(entries))


if __name__ == "__main__":
    sys.exit(main())
