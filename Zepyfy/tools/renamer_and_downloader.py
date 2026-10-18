"""
Paso 1: renombra los MP3 y descarga letras, portadas y fotos de artistas.

Lee los metadatos (ID3) de cada MP3 de <biblioteca>/Cancion y:
  1. Lo renombra a "Título (Artista).mp3".
  2. Descarga de Genius la letra (Letras/*.txt), la portada del álbum
     (Portadas/*.jpg) y la foto de cada artista (Artistas/*.jpg).
Lo que ya existe no se vuelve a descargar.

Requisitos: config.ini con `library_dir` y `genius_token` (ver config.example.ini)
y las dependencias de requirements.txt.

Uso:  python renamer_and_downloader.py
"""
import os
from io import BytesIO

import lyricsgenius
import requests
from mutagen.id3 import ID3
from mutagen.mp3 import MP3
from PIL import Image

from common import (
    ARTISTS_DIRNAME,
    COVERS_DIRNAME,
    LYRICS_DIRNAME,
    SONGS_DIRNAME,
    get_setting,
    library_dir,
    sanitize_filename,
    split_artists,
)

# --- CONFIGURATION ---
GENIUS_TOKEN = get_setting("genius_token")

# FOLDERS
BASE_PATH = library_dir()
SONGS_FOLDER = os.path.join(BASE_PATH, SONGS_DIRNAME)
LYRICS_FOLDER = os.path.join(BASE_PATH, LYRICS_DIRNAME)
COVERS_FOLDER = os.path.join(BASE_PATH, COVERS_DIRNAME)
ARTISTS_FOLDER = os.path.join(BASE_PATH, ARTISTS_DIRNAME)

IMAGE_MAX_SIZE = (500, 500)
REQUEST_TIMEOUT_S = 20


def setup_folders():
    os.makedirs(LYRICS_FOLDER, exist_ok=True)
    os.makedirs(COVERS_FOLDER, exist_ok=True)
    os.makedirs(ARTISTS_FOLDER, exist_ok=True)
    if not os.path.exists(SONGS_FOLDER):
        print(f"ERROR: Songs folder not found at {SONGS_FOLDER}")
        return False
    return True


def download_image(url, save_path):
    try:
        response = requests.get(url, timeout=REQUEST_TIMEOUT_S)
        if response.status_code == 200:
            img = Image.open(BytesIO(response.content))
            img.thumbnail(IMAGE_MAX_SIZE)
            if img.mode != 'RGB':
                img = img.convert('RGB')
            img.save(save_path, "JPEG", quality=90)
            return True
        return False
    except Exception as e:
        print(f"    -> Error downloading image: {e}")
        return False


def process_songs():
    print("--- Starting Auto-Renamer & Downloader ---")

    if not setup_folders():
        return

    try:
        genius = lyricsgenius.Genius(GENIUS_TOKEN)
        genius.verbose = False
        genius.skip_non_songs = True
        genius.excluded_terms = ["(Remix)", "(Live)"]
    except Exception as e:
        print(f"Error initializing Genius: {e}")
        return

    files = [f for f in os.listdir(SONGS_FOLDER) if f.lower().endswith('.mp3')]

    if not files:
        print("No .mp3 files found.")
        return

    processed_albums = set()
    processed_artists = set()

    for filename in files:
        file_path = os.path.join(SONGS_FOLDER, filename)

        try:
            # --- READ METADATA ---
            audio = MP3(file_path, ID3=ID3)
            title = str(audio.get("TIT2")).strip() if audio.get("TIT2") else ""

            # Artista principal (el primero) para el nombre del archivo y la búsqueda
            artist_tag = audio.get("TPE1")
            artist = ""
            if artist_tag:
                artists = str(artist_tag).split(',')
                if artists:
                    artist = artists[0].strip()

            album = str(audio.get("TALB")).strip() if audio.get("TALB") else ""

            if not title or not artist:
                print(f"Skipping '{filename}': Missing Metadata.")
                continue

            # --- 1. RENAME ---
            safe_title = sanitize_filename(title)
            safe_artist = sanitize_filename(artist)

            new_filename = f"{safe_title} ({safe_artist}).mp3"
            new_path = os.path.join(SONGS_FOLDER, new_filename)

            if filename != new_filename:
                if not os.path.exists(new_path):
                    os.rename(file_path, new_path)
                    print(f"\n[RENAME] {filename} -> {new_filename}")
                else:
                    print(f"\n[SKIP RENAME] Target '{new_filename}' already exists.")
            else:
                print(f"\n[OK] {filename}")

            # --- SEARCH GENIUS ---
            # Una sola búsqueda por canción: sirve para la letra y como portada de reserva
            song_obj = None
            try:
                lyric_filename = f"{safe_title} ({safe_artist}).txt"
                lyric_path = os.path.join(LYRICS_FOLDER, lyric_filename)

                need_lyrics = not os.path.exists(lyric_path)

                # ¿Hace falta portada? (solo si la canción tiene álbum)
                need_cover = False
                if album:
                    safe_album = sanitize_filename(album)
                    cover_filename = f"{safe_album}.jpg"
                    cover_path = os.path.join(COVERS_FOLDER, cover_filename)
                    if not os.path.exists(cover_path) and album not in processed_albums:
                        need_cover = True

                if need_lyrics or need_cover:
                    print(f"  [SEARCH] Genius search for '{title}' by '{artist}'...")
                    song_obj = genius.search_song(title, artist)

                # --- 2. DOWNLOAD LYRICS ---
                if need_lyrics:
                    if song_obj and song_obj.lyrics:
                        with open(lyric_path, "w", encoding="utf-8") as f:
                            f.write(song_obj.lyrics)
                        print(f"    -> Saved lyrics to '{lyric_filename}'")
                    else:
                        print("    -> Lyrics not found.")
                else:
                    print(f"  [LYRICS] Exists: {lyric_filename}")

                # --- 3. DOWNLOAD COVER ---
                # 1) ¿Ya existe el archivo?  2) ¿Ya se intentó en esta ejecución?
                # 3) Buscar el álbum en Genius.  4) Si no, usar la imagen de la canción.
                if album:
                    safe_album = sanitize_filename(album)
                    cover_filename = f"{safe_album}.jpg"
                    cover_path = os.path.join(COVERS_FOLDER, cover_filename)

                    if os.path.exists(cover_path):
                        print(f"  [COVER] Exists: {cover_filename}")
                        processed_albums.add(album)

                    elif album not in processed_albums:
                        print(f"  [COVER] Searching cover for album '{album}'...")
                        found_cover = False

                        # A. Buscar el álbum
                        try:
                            album_obj = genius.search_album(album, artist)
                            if album_obj and album_obj.cover_art_url:
                                print(f"    -> Found Album art: {album_obj.cover_art_url}")
                                if download_image(album_obj.cover_art_url, cover_path):
                                    found_cover = True
                                    print(f"    -> Saved cover to '{cover_filename}'")
                        except Exception as e:
                            print(f"    -> Album search warning: {e}")

                        # B. Reserva: imagen de la canción
                        if not found_cover and song_obj and song_obj.song_art_image_url:
                            print(f"    -> Fallback to Song art: {song_obj.song_art_image_url}")
                            if download_image(song_obj.song_art_image_url, cover_path):
                                found_cover = True
                                print(f"    -> Saved cover to '{cover_filename}'")

                        # Se marca aunque falle, para no repetir la búsqueda con cada
                        # canción del mismo álbum
                        processed_albums.add(album)

                        if not found_cover:
                            print("    -> Cover NOT found (Album search empty & No Song fallback).")

                # --- 4. DOWNLOAD ARTIST PHOTO ---
                artist_names = split_artists(str(artist_tag)) if artist_tag else []

                for art_name in artist_names:
                    safe_art_name = sanitize_filename(art_name)
                    art_filename = f"{safe_art_name}.jpg"
                    art_path = os.path.join(ARTISTS_FOLDER, art_filename)

                    if os.path.exists(art_path) or art_name in processed_artists:
                        continue

                    print(f"  [ARTIST] Searching photo for '{art_name}'...")
                    found_art = False

                    # A. Si es el artista principal de la canción ya encontrada, usar su foto
                    if song_obj and song_obj.primary_artist:
                        if song_obj.primary_artist.name.lower() == art_name.lower():
                            if song_obj.primary_artist.image_url:
                                print(f"    -> Found via Song: {song_obj.primary_artist.image_url}")
                                if download_image(song_obj.primary_artist.image_url, art_path):
                                    found_art = True

                    # B. Búsqueda explícita del artista
                    if not found_art:
                        try:
                            a_obj = genius.search_artist(art_name, max_songs=1, get_full_info=True)
                            if a_obj and a_obj.image_url:
                                print(f"    -> Found via Search: {a_obj.image_url}")
                                if download_image(a_obj.image_url, art_path):
                                    found_art = True
                        except Exception as e:
                            print(f"    -> Artist search error: {e}")

                    if found_art:
                        print(f"    -> Saved artist photo to '{art_filename}'")
                    else:
                        print(f"    -> Artist photo not found for '{art_name}'")

                    processed_artists.add(art_name)

            except Exception as e:
                print(f"  Error in Genius section: {e}")

        except Exception as e:
            print(f"Error processing file {filename}: {e}")

    print("\nProcessing Complete.")


if __name__ == "__main__":
    process_songs()
