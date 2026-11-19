"""FastAPI bounds for values bound to SQLite integer parameters."""

from typing import Annotated

from fastapi import Path, Query
from pydantic import Field


SQLITE_MAX = 2**63 - 1
SQLITE_MIN = -2**63
SqliteInt = Annotated[int, Field(ge=SQLITE_MIN, le=SQLITE_MAX)]
SqlitePath = Annotated[int, Path(ge=SQLITE_MIN, le=SQLITE_MAX)]
Offset = Annotated[int, Query(ge=0, le=SQLITE_MAX)]
