"""Shared SQLite test helper: commit on success, always close.

`with sqlite3.connect(...)` commits but never closes, which leaves the file
locked on Windows. `contextlib.closing` closes but skips the commit. This
context manager preserves both behaviours.
"""
import sqlite3
from contextlib import contextmanager


@contextmanager
def connect(path, **kwargs):
    connection = sqlite3.connect(path, **kwargs)
    try:
        with connection:
            yield connection
    finally:
        connection.close()
