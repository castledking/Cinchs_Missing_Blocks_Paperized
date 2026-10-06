#!/usr/bin/env python3
"""Shared helpers for the regression fixtures.

Rule these enforce: **no fixture names a Paperized content id.**

Fixtures used to hardcode things like ``polished_calcite_stairs``. When allocation
changed and those blocks became deferred, the fixture did not fail - it quietly
stopped asserting anything, or raised a KeyError that looked like a product bug.
Three separate fixtures have now been caught by that, so the rule is enforced here
instead of by convention.

A fixture asks for a *probe*: a block of some family that the build actually
served. If none exists, that is an explicit failure - ``no served <family> block
available for fixture`` - rather than a fallback to a remembered id.
"""

from __future__ import annotations


class NoProbeError(RuntimeError):
    """A fixture asked for content that allocation did not serve."""


def served_blocks(content: dict) -> dict[str, dict]:
    """Blocks the allocation actually emitted, keyed by id."""
    alloc = content.get("allocation") or {}
    assigned = alloc.get("assigned") or {}
    return {bid: body for bid, body in (content.get("blocks") or {}).items()
            if bid in assigned}


def deferred_ids(content: dict) -> set[str]:
    alloc = content.get("allocation") or {}
    # allocation.json records per-block exhaustion under "unsupported" and family
    # deferrals under "unsupported_block_ids"; both are reasons a block is absent.
    per_block = set((alloc.get("unsupported") or {}).keys())
    per_family = {bid for ids in (alloc.get("unsupported_block_ids") or {}).values()
                  for bid in ids}
    return per_block | per_family


def is_deferred(content: dict, family: str) -> bool:
    """Whether every block of a family was deliberately deferred.

    A fixture whose assertion is *about* a family should skip, not fail, when that
    family is intentionally not shipped. Failing would make a correct product
    decision look like a broken test.
    """
    served = served_blocks(content)
    deferred = deferred_ids(content)
    for bid, body in served.items():
        if body.get("family") == family and bid not in deferred:
            return False
    return True


def probe(content: dict, family: str, contains: str | None = None) -> str:
    """A served block of ``family``, optionally matching a name fragment.

    Raises NoProbeError rather than guessing, so a family disappearing from the
    allocation is reported as what it is.
    """
    served = served_blocks(content)
    for bid, body in sorted(served.items()):
        if body.get("family") != family:
            continue
        if contains and contains not in bid:
            continue
        return bid
    raise NoProbeError(
        f"no served {family} block available for fixture"
        + (f" matching {contains!r}" if contains else "")
        + f" (served families: "
          f"{sorted({b.get('family') for b in served.values()})})")


def probe_any(content: dict, families: tuple[str, ...] = ()) -> str:
    """A served block from any of ``families``, else the first served block."""
    served = served_blocks(content)
    for family in families:
        try:
            return probe(content, family)
        except NoProbeError:
            continue
    if not served:
        raise NoProbeError("no served blocks available for fixture")
    return sorted(served)[0]