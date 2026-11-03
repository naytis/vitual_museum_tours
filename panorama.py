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
