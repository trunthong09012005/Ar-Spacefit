import pytest

from native_fixture_workspace import new_native_workspace


@pytest.fixture
def docker_tmp_path(tmp_path):
    """Only opt-in native integration tests request this scoped workspace."""
    return new_native_workspace(tmp_path)
