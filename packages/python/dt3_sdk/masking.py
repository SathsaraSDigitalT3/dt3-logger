"""Sensitive-data masking utilities for DT3 Commons log events."""

from __future__ import annotations

from copy import deepcopy
from typing import Any, Iterable, List, Mapping, Optional, Set, Tuple

DEFAULT_SENSITIVE_FIELDS = (
    "password",
    "passwd",
    "pwd",
    "secret",
    "token",
    "access_token",
    "refresh_token",
    "authorization",
    "api_key",
    "apikey",
    "private_key",
    "credit_card",
    "card_number",
    "ssn",
    "nic",
    "national_id",
    "email",
    "phone",
    "prompt",
    "response",
    "kavia.prompt",
    "kavia.response",
)


class MaskingEngine:
    """Recursively masks sensitive values without mutating source data."""

    def __init__(
        self,
        sensitive_fields: Optional[Iterable[str]] = None,
        replacement_value: str = "[REDACTED]",
        track_masked_fields: bool = False,
        enabled: bool = True,
    ) -> None:
        """Create a masking engine with configurable masking rules.

        Args:
            sensitive_fields: Additional field names to mask, matched case-insensitively.
            replacement_value: Value substituted for sensitive field values.
            track_masked_fields: Whether masked field paths are returned by ``mask``.
            enabled: Whether masking is active.
        """
        configured_fields = sensitive_fields or ()
        self._sensitive_fields: Set[str] = {
            field.casefold()
            for field in (*DEFAULT_SENSITIVE_FIELDS, *configured_fields)
            if isinstance(field, str)
        }
        self.replacement_value = replacement_value
        self.track_masked_fields = track_masked_fields
        self.enabled = enabled

    # PUBLIC_INTERFACE
    def mask(self, data: Any) -> Tuple[Any, List[str]]:
        """Return a masked copy of data and, when configured, masked field paths.

        Args:
            data: A mapping, list, primitive, or nested combination to process.

        Returns:
            A tuple containing a deep-copied masked value and masked field paths.
            The paths list is empty unless ``track_masked_fields`` is enabled.
        """
        if not self.enabled:
            return deepcopy(data), []

        masked_data, masked_fields = self._mask_value(data, path="")
        return masked_data, masked_fields if self.track_masked_fields else []

    def _mask_value(self, value: Any, path: str) -> Tuple[Any, List[str]]:
        """Recursively process one value while preserving its container structure."""
        if isinstance(value, Mapping):
            masked_mapping = {}
            masked_fields: List[str] = []

            for key, child_value in value.items():
                key_text = str(key)
                child_path = f"{path}.{key_text}" if path else key_text

                if key_text.casefold() in self._sensitive_fields:
                    masked_mapping[key] = self.replacement_value
                    masked_fields.append(child_path)
                    continue

                masked_child, child_fields = self._mask_value(child_value, child_path)
                masked_mapping[key] = masked_child
                masked_fields.extend(child_fields)

            return masked_mapping, masked_fields

        if isinstance(value, list):
            masked_list = []
            masked_fields = []

            for index, child_value in enumerate(value):
                child_path = f"{path}[{index}]"
                masked_child, child_fields = self._mask_value(child_value, child_path)
                masked_list.append(masked_child)
                masked_fields.extend(child_fields)

            return masked_list, masked_fields

        if isinstance(value, tuple):
            masked_items = []
            masked_fields = []

            for index, child_value in enumerate(value):
                child_path = f"{path}[{index}]"
                masked_child, child_fields = self._mask_value(child_value, child_path)
                masked_items.append(masked_child)
                masked_fields.extend(child_fields)

            return tuple(masked_items), masked_fields

        return deepcopy(value), []
