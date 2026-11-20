from collections.abc import Iterator
import json
import tempfile

from fastapi.responses import StreamingResponse


class DownloadResponse(StreamingResponse):
    def __init__(self, output, media_type, headers):
        self.output = output
        super().__init__(iter(lambda: output.read(64 * 1024), b""), media_type=media_type, headers=headers)

    async def __call__(self, scope, receive, send):
        try:
            await super().__call__(scope, receive, send)
        finally:
            self.output.close()


def json_chunks(value, encoder):
    if isinstance(value, Iterator):
        yield "["
        for index, row in enumerate(value):
            if index:
                yield encoder.item_separator
            yield from encoder.iterencode(row)
        yield "]"
    elif isinstance(value, dict):
        yield "{"
        for index, (key, item) in enumerate(value.items()):
            if index:
                yield encoder.item_separator
            yield encoder.encode(key) + encoder.key_separator
            yield from json_chunks(item, encoder)
        yield "}"
    else:
        yield from encoder.iterencode(value)


def spooled_response(chunks, media_type, headers=None, before_send=None):
    output = tempfile.SpooledTemporaryFile(max_size=256 * 1024)
    try:
        for chunk in chunks:
            output.write(chunk.encode("utf-8"))
        length = output.tell()
        output.seek(0)
        if before_send is not None:
            before_send()
        return DownloadResponse(output, media_type, {**(headers or {}), "Content-Length": str(length)})
    except BaseException:
        output.close()
        raise


def complete_json(value, headers=None, before_send=None):
    encoder = json.JSONEncoder(ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    return spooled_response(json_chunks(value, encoder), "application/json", headers, before_send)
