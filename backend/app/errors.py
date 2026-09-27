from __future__ import annotations


class ApiError(Exception):
    """A request that must be rejected; rendered as ``{"detail": {"code", "message"}}``."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
