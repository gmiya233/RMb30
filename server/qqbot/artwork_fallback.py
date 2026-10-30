"""Read missing artwork metadata and create bot-owned thumbnails only."""
import hashlib
from io import BytesIO
import json
from pathlib import Path, PurePosixPath


def relative(value):
    value = str(value or '').replace('\\', '/')
    path = PurePosixPath(value)
    if not value or path.is_absolute() or '..' in path.parts:
        raise ValueError('Invalid artwork path')
    return path


def contained(path, root):
    return path.resolve().is_relative_to(root.resolve())


def actual_image(folder, relative_path):
    # Some chart packages were authored on Windows and differ only in filename case.
    result = folder
    for part in relative_path.parts:
        direct = result / part
        if direct.exists():
            result = direct
            continue
        matches = []
        for index, item in enumerate(result.iterdir()):
            if index > 1000:
                raise ValueError('Too many artwork directory entries')
            if item.name.casefold() == part.casefold():
                matches.append(item)
        if len(matches) != 1:
            raise ValueError('Missing or ambiguous artwork filename')
        result = matches[0]
    return result


def supplement(charts, source_root, output, write):
    from PIL import Image, ImageOps
    source_root, output = Path(source_root), Path(output)
    cached = {}
    errors = (OSError, ValueError, TypeError, KeyError, Image.DecompressionBombError)

    def thumbnail(local):
        clean = local.as_posix()
        digest = hashlib.sha256(clean.encode()).hexdigest()
        if digest in cached:
            return digest if cached[digest] else None
        cached[digest] = False
        target = output / (digest + '.jpg')
        if target.is_file() and not target.is_symlink() and 0 < target.stat().st_size <= 1048576:
            cached[digest] = True
            return digest
        image = actual_image(source_root, local)
        if image.is_symlink() or not contained(image, source_root) or image.stat().st_size > 16 * 1024 * 1024:
            return None
        with Image.open(image) as original:
            if original.width * original.height > 16_000_000:
                return None
            thumb = ImageOps.fit(ImageOps.exif_transpose(original).convert('RGB'), (384, 384))
            stream = BytesIO()
            thumb.save(stream, format='JPEG', quality=85, optimize=True)
        write(target, stream.getvalue())
        cached[digest] = True
        return digest

    for chart in charts:
        candidates = []
        # A nonempty database field is only a path, not proof a thumbnail exists.
        # Each candidate fails independently so a broken cover cannot hide a valid background.
        for name in [chart.get('cover'), chart.get('background')]:
            if not name:
                continue
            try:
                digest = thumbnail(relative(name))
                if digest:
                    candidates.append(digest)
            except errors:
                continue
        if not candidates:
            try:
                path = relative(chart['path'])
                filename = relative(chart['mc_name'])
                if len(filename.parts) != 1:
                    continue
                source = actual_image(source_root, path / filename)
                if source.is_symlink() or not contained(source, source_root) or source.stat().st_size > 16 * 1024 * 1024:
                    continue
                # Ignore notes: only the cover/background metadata is used.
                meta = json.loads(source.read_text(encoding='utf-8-sig')).get('meta', {})
                for name in [meta.get('cover'), meta.get('background')]:
                    if not name:
                        continue
                    try:
                        digest = thumbnail(path / relative(name))
                        if digest:
                            candidates.append(digest)
                    except errors:
                        continue
            except errors:
                continue
        if candidates:
            chart['artwork_fallbacks'] = list(dict.fromkeys(candidates))
