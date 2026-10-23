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
    for chart in charts:
        if chart.get('cover') or chart.get('background'):
            continue
        try:
            path = relative(chart['path'])
            filename = relative(chart['mc_name'])
            if len(filename.parts) != 1:
                continue
            source = source_root / path / filename
            if source.is_symlink() or not contained(source, source_root) or source.stat().st_size > 16 * 1024 * 1024:
                continue
            # Ignore notes: only the cover/background metadata is used.
            meta = json.loads(source.read_text(encoding='utf-8-sig')).get('meta', {})
            candidates = []
            for name in [meta.get('cover'), meta.get('background')]:
                if not name:
                    continue
                local = relative(name)
                image = actual_image(source.parent, local)
                clean = (path / local).as_posix()
                digest = hashlib.sha256(clean.encode()).hexdigest()
                target = output / (digest + '.jpg')
                if digest in cached:
                    if cached[digest]: candidates.append(digest)
                    continue
                if target.is_file() and not target.is_symlink():
                    cached[digest] = True
                    candidates.append(digest)
                    continue
                cached[digest] = False
                if image.is_symlink() or not contained(image, source_root) or image.stat().st_size > 16 * 1024 * 1024:
                    continue
                with Image.open(image) as original:
                    if original.width * original.height > 16_000_000:
                        continue
                    thumb = ImageOps.fit(ImageOps.exif_transpose(original).convert('RGB'), (384, 384))
                    stream = BytesIO()
                    thumb.save(stream, format='JPEG', quality=85, optimize=True)
                write(target, stream.getvalue())
                candidates.append(digest)
                cached[digest] = True
            if candidates:
                chart['artwork_fallbacks'] = list(dict.fromkeys(candidates))
        except (OSError, ValueError, TypeError, KeyError, Image.DecompressionBombError):
            continue
