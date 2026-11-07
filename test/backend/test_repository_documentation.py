"""Regression for local navigation after moving repository components.

Checks current developer documentation only. Historical execution reports keep
their original paths, and private/runtime directories are never scanned.
"""
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

import pytest


ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = sorted({
    ROOT / "README.md",
    ROOT / "CONTRIBUTING.md",
    ROOT / "apps/android/README.md",
    ROOT / "backend/README.md",
    ROOT / "infra/README.md",
    ROOT / "test/README.md",
    *(path for path in (ROOT / "docs").glob("*.md")
      if path.name not in {"TEST_RESULTS.md", "WORK_STATUS.md"}),
    *(ROOT / "docs/reports").glob("*.md"),
    *(ROOT / "infra/docker").glob("*/README.md"),
})


@pytest.mark.parametrize("document", DOCUMENTS, ids=lambda path: path.relative_to(ROOT).as_posix())
def test_current_documentation_local_links_resolve(document):
    content = document.read_text(encoding="utf-8-sig")
    for match in re.finditer(r"\]\(([^)]+)\)", content):
        reference = match.group(1).strip("<>")
        url = urlsplit(reference)
        if url.scheme or url.netloc or not url.path:
            continue
        target = (document.parent / unquote(url.path)).resolve()
        assert target.is_relative_to(ROOT), f"Local link escapes repository: {reference}"
        assert target.exists(), f"Broken local link in {document.relative_to(ROOT)}: {reference}"
