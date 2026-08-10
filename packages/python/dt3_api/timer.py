from typing import Protocol

class Timer(Protocol):
    def stop(self) -> None: ...
