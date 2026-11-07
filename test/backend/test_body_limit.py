import asyncio
import pytest
from spacefit.api import BodyLimit


@pytest.mark.parametrize("chunks,expected", [([b"123", b"456"], 413), ([b"12", b"345"], 200)])
def test_chunked_body_without_content_length_is_bounded(chunks, expected):
    async def scenario():
        queue = [{"type": "http.request", "body": chunk, "more_body": i < len(chunks) - 1}
                 for i, chunk in enumerate(chunks)]
        sent = []
        async def receive():
            return queue.pop(0)
        async def send(message):
            sent.append(message)
        async def consumer(scope, receive, send):
            while (await receive()).get("more_body", False):
                pass
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": b"ok"})
        await BodyLimit(consumer, limit=5)({"type": "http"}, receive, send)
        assert [entry["status"] for entry in sent if entry["type"] == "http.response.start"] == [expected]
    asyncio.run(scenario())
