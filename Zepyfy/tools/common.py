"""
Configuración y utilidades compartidas por las herramientas de Zepyfy
(renamer_and_downloader.py y auto_uploader.py).

La configuración se lee de `config.ini` (en esta misma carpeta, ver
`config.example.ini`). Cada valor también puede darse con una variable de
entorno, que tiene prioridad sobre el archivo.
"""
import configparser
import os
import re
import sys
import unicodedata
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
CONFIG_FILE = TOOLS_DIR / "config.ini"
CONFIG_SECTION = "zepyfy"

# Subcarpetas dentro de la carpeta de la biblioteca
SONGS_DIRNAME = "Cancion"
LYRICS_DIRNAME = "Letras"
COVERS_DIRNAME = "Portadas"
ARTISTS_DIRNAME = "Artistas"

# clave en config.ini -> variable de entorno equivalente
SETTINGS = {
    "library_dir": "ZEPYFY_LIBRARY_DIR",
    "genius_token": "GENIUS_TOKEN",
    "service_account_key": "FIREBASE_SERVICE_ACCOUNT",
    "storage_bucket": "FIREBASE_STORAGE_BUCKET",
}


def _read_config_file() -> dict:
    if not CONFIG_FILE.exists():
        return {}
    parser = configparser.ConfigParser(interpolation=None)
    parser.read(CONFIG_FILE, encoding="utf-8")
    if not parser.has_section(CONFIG_SECTION):
        return {}
    return dict(parser.items(CONFIG_SECTION))


_file_values = _read_config_file()


def get_setting(key: str, required: bool = True) -> str:
    """Valor de configuración: variable de entorno > config.ini. Sale con un mensaje claro si falta."""
    value = os.environ.get(SETTINGS[key]) or _file_values.get(key, "")
    value = value.strip().strip('"').strip("'")
    if required and not value:
        print(f"ERROR: falta '{key}' en la configuración.")
        if not CONFIG_FILE.exists():
            print(f"  Copia {TOOLS_DIR / 'config.example.ini'} como {CONFIG_FILE} y rellénalo,")
        else:
            print(f"  Añádelo en {CONFIG_FILE},")
        print(f"  o define la variable de entorno {SETTINGS[key]}.")
        sys.exit(1)
    return value


def library_dir() -> Path:
    """Carpeta raíz de la biblioteca (la que contiene Cancion/, Letras/, Portadas/, Artistas/)."""
    path = Path(os.path.expanduser(get_setting("library_dir")))
    if not path.is_dir():
        print(f"ERROR: la carpeta de la biblioteca no existe: {path}")
        sys.exit(1)
    return path


def sanitize_filename(name: str) -> str:
    """
    Nombre seguro para archivos y para identificar portadas, letras y artistas.

    Quita tildes y diacríticos de cualquier idioma (é -> e, ñ -> n, å -> a, ü -> u),
    apóstrofos y comillas, y cambia por '_' los caracteres no válidos en Windows.
    Los dos scripts usan esta misma función: así el nombre con el que se guarda
    una portada o una letra coincide siempre con el nombre con el que se busca.
    """
    decomposed = unicodedata.normalize("NFKD", name)
    without_accents = "".join(c for c in decomposed if not unicodedata.combining(c))
    for char in ("'", "’", "`", "´"):
        without_accents = without_accents.replace(char, "")
    return re.sub(r'[<>:"/\\|?*]', "_", without_accents).strip()


def split_artists(artist_tag: str) -> list:
    """Separa la etiqueta de artistas del MP3 ('A, B; C / D') en una lista."""
    return [a.strip() for a in re.split(r"[;/,]", artist_tag) if a.strip()]
