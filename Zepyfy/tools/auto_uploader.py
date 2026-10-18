"""
Paso 2: sube la biblioteca a Firebase (Storage + Firestore).

Sube a Cloud Storage las fotos de artistas, portadas, letras y canciones de la
carpeta de la biblioteca, y crea en Firestore los documentos que usa la app
(`artistas`, `albums`, `canciones` y, si se indica, una `Playlist`), enlazando
cada canción con su álbum, sus artistas, su portada y su letra.
Las canciones que ya existen (mismo título y álbum) no se duplican.

Requisitos: config.ini con `library_dir` y `service_account_key`
(y opcionalmente `storage_bucket`), ver config.example.ini.
Ejecuta antes renamer_and_downloader.py para tener letras, portadas y artistas.

Uso:  python auto_uploader.py
"""
import json
import os
import re
import sys
import urllib.parse
import uuid
from collections import Counter

import firebase_admin
from firebase_admin import credentials, firestore, storage
from mutagen.id3 import ID3
from mutagen.mp3 import MP3

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
SERVICE_ACCOUNT_KEY = os.path.expanduser(get_setting("service_account_key"))

# FOLDERS
BASE_DB_FOLDER = library_dir()
ARTISTS_FOLDER = os.path.join(BASE_DB_FOLDER, ARTISTS_DIRNAME)
SONGS_FOLDER = os.path.join(BASE_DB_FOLDER, SONGS_DIRNAME)

# Se aceptan las carpetas en singular o en plural
LYRICS_FOLDER = os.path.join(BASE_DB_FOLDER, "Letra")
if not os.path.exists(LYRICS_FOLDER):
    LYRICS_FOLDER = os.path.join(BASE_DB_FOLDER, LYRICS_DIRNAME)

COVERS_FOLDER = os.path.join(BASE_DB_FOLDER, "Portada")
if not os.path.exists(COVERS_FOLDER):
    COVERS_FOLDER = os.path.join(BASE_DB_FOLDER, COVERS_DIRNAME)

# Rutas dentro de Cloud Storage
STORAGE_PATHS = {
    "artists": "fotosArtistas",
    "songs": "canciones",
    "lyrics": "letras",
    "covers": "portadas"
}


def resolve_bucket_name(key_path):
    """Bucket de config.ini o, si no se indica, el predeterminado del proyecto de la clave."""
    configured = get_setting("storage_bucket", required=False)
    if configured:
        return configured.removeprefix("gs://")
    with open(key_path, encoding="utf-8") as f:
        project_id = json.load(f).get("project_id")
    if not project_id:
        print("ERROR: no se pudo leer 'project_id' de la clave de servicio; indica storage_bucket en config.ini.")
        sys.exit(1)
    return f"{project_id}.firebasestorage.app"


# --- INITIALIZATION ---
if not os.path.exists(SERVICE_ACCOUNT_KEY):
    print(f"CRITICAL ERROR: Service account key not found at {SERVICE_ACCOUNT_KEY}")
    print("Revisa 'service_account_key' en config.ini.")
    sys.exit(1)

try:
    BUCKET_NAME = resolve_bucket_name(SERVICE_ACCOUNT_KEY)
    cred = credentials.Certificate(SERVICE_ACCOUNT_KEY)
    firebase_admin.initialize_app(cred, {
        'storageBucket': BUCKET_NAME
    })
    db = firestore.client()
    bucket = storage.bucket()
    print(f"Firebase initialized successfully (bucket: {BUCKET_NAME}).")
except Exception as e:
    print(f"Error initializing Firebase: {e}")
    sys.exit(1)


# --- HELPERS ---
def upload_file(local_path, destination_blob_name, content_type=None):
    """Sube un archivo al bucket y devuelve su URL de descarga (con token)."""
    try:
        blob = bucket.blob(destination_blob_name)
        if content_type:
            blob.content_type = content_type

        print(f"Uploading {local_path} -> {destination_blob_name}...")
        blob.upload_from_filename(local_path)

        # Token de descarga: el mismo mecanismo que usa la consola de Firebase
        blob.reload()
        if not blob.metadata or 'firebaseStorageDownloadTokens' not in blob.metadata:
            token = str(uuid.uuid4())
            blob.metadata = {"firebaseStorageDownloadTokens": token}
            blob.patch()
        else:
            token = blob.metadata['firebaseStorageDownloadTokens']

        safe_path = urllib.parse.quote(destination_blob_name, safe='')
        return f"https://firebasestorage.googleapis.com/v0/b/{bucket.name}/o/{safe_path}?alt=media&token={token}"
    except Exception as e:
        print(f"Error uploading {local_path}: {e}")
        return None


def ensure_artist_id(name, photo_url=None):
    """
    Devuelve el id del artista con ese nombre (actualizando su foto si se da una).
    Si no existe, lo crea. Así el id es siempre un id real de la base de datos.
    """
    try:
        docs = list(db.collection('artistas').where('nombre', '==', name).limit(1).stream())

        if docs:
            existing_doc = docs[0]
            if photo_url:
                existing_doc.reference.update({'url_fotoartista': photo_url})
            return existing_doc.id, name

        data = {'nombre': name}
        if photo_url:
            data['url_fotoartista'] = photo_url

        new_ref = db.collection('artistas').document()
        new_ref.set(data)
        print(f"  [DB] Created Artist: {name} ({new_ref.id})")
        return new_ref.id, name

    except Exception as e:
        print(f"Error accessing Artist DB: {e}")
        return None, None


def ensure_album_id(title, cover_url=None):
    """Devuelve el id del álbum con ese título; si no existe, lo crea."""
    try:
        docs = list(db.collection('albums').where('titulo', '==', title).limit(1).stream())

        if docs:
            existing_doc = docs[0]
            if cover_url:
                existing_doc.reference.update({'url_portada': cover_url})
            return existing_doc.id

        data = {
            'titulo': title,
            'artistas_principales': [],  # se rellena al final (paso 4)
            'url_portada': cover_url if cover_url else ""
        }

        new_ref = db.collection('albums').document()
        new_ref.set(data)
        print(f"  [DB] Created Album: {title} ({new_ref.id})")
        return new_ref.id

    except Exception as e:
        print(f"Error accessing Album DB: {e}")
        return None


# --- PROCESSORS ---

def process_artists_photos():
    print("\n--- 1. UPLOADING ARTIST PHOTOS ---")
    if not os.path.exists(ARTISTS_FOLDER):
        print(f"Folder not found: {ARTISTS_FOLDER}")
        return

    files = [f for f in os.listdir(ARTISTS_FOLDER) if f.lower().endswith(('.jpg', '.jpeg', '.png'))]
    for f in files:
        name_without_ext = os.path.splitext(f)[0]
        local_path = os.path.join(ARTISTS_FOLDER, f)

        # Nombre normalizado para la base de datos (p. ej. Måneskin -> Maneskin)
        clean_name = sanitize_filename(name_without_ext)

        # No volver a subir si el artista ya tiene foto
        docs = list(db.collection('artistas').where('nombre', '==', clean_name).limit(1).stream())
        if docs and docs[0].to_dict().get('url_fotoartista'):
            print(f"  [SKIP] Artist '{clean_name}' already has photo in DB.")
            continue

        blob_path = f"{STORAGE_PATHS['artists']}/{f}"
        url = upload_file(local_path, blob_path, content_type="image/jpeg")
        if url:
            ensure_artist_id(clean_name, photo_url=url)


def process_covers_and_lyrics():
    print("\n--- 2. UPLOADING COVERS & LYRICS ---")
    cover_map = {}
    lyrics_map = {}

    # Portadas
    if os.path.exists(COVERS_FOLDER):
        for f in os.listdir(COVERS_FOLDER):
            if f.lower().endswith(('.jpg', '.jpeg', '.png')):
                name = os.path.splitext(f)[0]
                url = upload_file(os.path.join(COVERS_FOLDER, f), f"{STORAGE_PATHS['covers']}/{f}", "image/jpeg")
                if url:
                    cover_map[name] = url

    # Letras
    if os.path.exists(LYRICS_FOLDER):
        for f in os.listdir(LYRICS_FOLDER):
            if f.lower().endswith('.txt'):
                name = os.path.splitext(f)[0]
                url = upload_file(os.path.join(LYRICS_FOLDER, f), f"{STORAGE_PATHS['lyrics']}/{f}", "text/plain")
                if url:
                    lyrics_map[name] = url

    return cover_map, lyrics_map


def update_playlist(playlist_name, song_ids):
    print(f"\n--- 5. UPDATING PLAYLIST: {playlist_name} ---")
    if not song_ids:
        print("  No songs IDs to add to playlist.")
        return

    try:
        docs = list(db.collection('Playlist').where('name', '==', playlist_name).limit(1).stream())

        if docs:
            # ArrayUnion evita duplicados dentro de la playlist
            docs[0].reference.update({
                'canciones': firestore.ArrayUnion(song_ids)
            })
            print(f"  [DB] Updated Playlist '{playlist_name}'. Added {len(song_ids)} songs.")
        else:
            new_playlist = {
                'name': playlist_name,
                'coverImageUrl': None,
                'canciones': song_ids
            }
            db.collection('Playlist').add(new_playlist)
            print(f"  [DB] Created Playlist '{playlist_name}' with {len(song_ids)} songs.")

    except Exception as e:
        print(f"  Error updating playlist: {e}")


def process_songs_and_db(cover_map, lyrics_map):
    print("\n--- 3. PROCESSING SONGS & DATABASE ---")

    if not os.path.exists(SONGS_FOLDER):
        print(f"Folder not found: {SONGS_FOLDER}")
        return []

    files = [f for f in os.listdir(SONGS_FOLDER) if f.lower().endswith('.mp3')]
    album_artists_map = {}
    processed_song_ids = []  # ids de todas las canciones procesadas (nuevas o ya existentes)

    for f in files:
        print(f"\nProcessing Song: {f}")
        local_path = os.path.join(SONGS_FOLDER, f)

        try:
            audio = MP3(local_path, ID3=ID3)

            # 1. Metadatos
            title = str(audio.get("TIT2")).strip() if audio.get("TIT2") else "Unknown"
            album_name = str(audio.get("TALB")).strip() if audio.get("TALB") else "Unknown Album"
            duration = int(audio.info.length)

            artist_tag = str(audio.get("TPE1")) if audio.get("TPE1") else "Unknown"
            artist_names = split_artists(artist_tag)

            # ¿Ya existe? (mismo título y álbum) -> no duplicar
            existing_songs = list(db.collection('canciones').where('titulo', '==', title).stream())
            existing_id = next(
                (s.id for s in existing_songs if s.to_dict().get('album_nombre') == album_name),
                None
            )
            if existing_id:
                print(f"  [SKIP] Song '{title}' (Album: {album_name}) already exists in DB.")
                processed_song_ids.append(existing_id)
                continue

            # 2. Artistas y álbum con ids reales
            real_artist_ids = []
            real_artist_names = []

            for art_name in artist_names:
                clean_art_name = sanitize_filename(art_name)
                aid, aname = ensure_artist_id(clean_art_name)
                if aid:
                    real_artist_ids.append(aid)
                    real_artist_names.append(aname)

            sanitized_album_name = sanitize_filename(album_name)
            cover_url = cover_map.get(sanitized_album_name, "")

            real_album_id = ensure_album_id(album_name, cover_url)

            album_artists_map.setdefault(album_name, []).extend(real_artist_names)

            # 3. Letra: archivo "Título (Artista).txt" cuyo título coincide
            lyric_url = ""
            normalized_title = sanitize_filename(title).lower()
            for lyr_filename, lyr_u in lyrics_map.items():
                base_lyr_name = re.sub(r'\s*\(.*?\)', '', lyr_filename).strip().lower()
                if base_lyr_name == normalized_title:
                    lyric_url = lyr_u
                    break

            # 4. Subir el audio
            blob_path = f"{STORAGE_PATHS['songs']}/{f}"
            song_url = upload_file(local_path, blob_path, "audio/mpeg")

            if not song_url:
                print("  Failed to upload song file. Skipping DB entry.")
                continue

            # 5. Documento de la canción
            song_doc = {
                'titulo': title,
                'album': real_album_id,
                'album_nombre': album_name,
                'artistas': real_artist_ids,
                'artistas_nombres': real_artist_names,
                'duracion': duration,
                'url_cancion': song_url,
                'url_letra': lyric_url,
                'url_portada': cover_url
            }

            _, song_ref = db.collection('canciones').add(song_doc)
            print(f"  [DB] Saved Song: '{title}' -> Album ID: {real_album_id}")
            processed_song_ids.append(song_ref.id)

        except Exception as e:
            print(f"  Error processing {f}: {e}")

    # --- 4. ARTISTAS PRINCIPALES DE CADA ÁLBUM (los que más canciones tienen en él) ---
    print("\n--- 4. UPDATING ALBUM PRIMARY ARTISTS ---")
    for alb_name, all_artists in album_artists_map.items():
        if not all_artists:
            continue

        counts = Counter(all_artists)
        max_val = counts.most_common(1)[0][1]
        main_artists = [a for a, c in counts.items() if c == max_val]

        docs = list(db.collection('albums').where('titulo', '==', alb_name).limit(1).stream())
        if docs:
            docs[0].reference.update({'artistas_principales': main_artists})
            print(f"  Updated Album '{alb_name}' primary artists: {main_artists}")

    return processed_song_ids


def ask_playlist():
    """Pregunta si las canciones forman parte de una playlist. Devuelve su nombre o None."""
    while True:
        try:
            ans = input("¿Las canciones pertenecen a una playlist? (0=No; 1=SI): ").strip()
        except (KeyboardInterrupt, EOFError):
            sys.exit(0)
        if ans == '0':
            return None
        if ans == '1':
            name = input("Introduce el nombre de la playlist: ").strip()
            if name:
                return name
            print("El nombre no puede estar vacío.")
        else:
            print("Por favor, introduce 0 o 1.")


def main():
    print("Starting Auto-Uploader...")

    # 0. ¿Playlist?
    playlist_name = ask_playlist()

    # 1. Fotos de artistas
    process_artists_photos()

    # 2. Portadas y letras
    c_map, l_map = process_covers_and_lyrics()

    # 3. Canciones (crea álbumes y canciones enlazando ids)
    song_ids = process_songs_and_db(c_map, l_map)

    # 5. Playlist
    if playlist_name and song_ids:
        update_playlist(playlist_name, song_ids)

    print("\nDone.")


if __name__ == "__main__":
    main()
