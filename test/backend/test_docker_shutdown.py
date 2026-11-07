"""Shutdown regression with owned in-process fixture, no Docker/native startup."""
import pytest
from fastapi.testclient import TestClient

from spacefit.api import create_app
from spacefit.config import Settings


def test_native_checker_is_closed_even_when_worker_cleanup_raises(tmp_path):
    app = create_app(Settings(data_dir=tmp_path, token="shutdown-fixture-only-token"))
    calls = []
    app.state.worker.start = lambda: calls.append("start")
    def failed_worker_close():
        calls.append("worker-close")
        raise RuntimeError("owned-process cleanup fixture failure")
    original_close = app.state.native.close
    app.state.worker.close = failed_worker_close
    def close_native():
        calls.append("native-close")
        original_close()
    app.state.native.close = close_native
    with pytest.raises(RuntimeError, match="owned-process cleanup fixture failure"):
        with TestClient(app) as client:
            assert client.get("/health").json()["status"] == "ok"
    assert calls == ["start", "worker-close", "native-close"]
    assert app.state.native.closed is True
