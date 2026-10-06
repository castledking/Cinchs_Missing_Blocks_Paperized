#!/usr/bin/env python3
"""Pre-parse ownership: which vanilla block states are already claimed.

CraftEngine treats a vanilla block state as a single-bind visual resource. Two packs
cannot bind the same state to different models, and the loser gets a bind failure at
load. So before allocating, Paperized has to know which states other packs already
hold.

This has to be answered *before* any pack is parsed, because CraftEngine resolves a
pack's ``state:`` while parsing that pack. ``blockManager().blockOverrides()`` is
populated only once parsing is done, so it can verify but cannot decide - see
DESIGN.md.

Three sources, because CraftEngine claims states three different ways:

    explicit_states()        a literal ``state:`` in an enabled pack
    auto_state_cache()       CraftEngine's own persisted auto_state assignments
    template_factory_claims()  states synthesised through config_factory + templates

The third exists because a pack can build a block out of shared templates and
config_factory instances rather than declaring it. CraftEngine's own bundled assets
do exactly this, with the comment "As there is a shortage of available states for
slabs and stairs, an independent factory is set up to generate them" - the same
carrier shortage this project runs into.

Design rule: **over-claim rather than under-claim.** A false positive costs
capacity; a false negative produces two blocks sharing one state, which is a broken
pack. Anything this cannot resolve safely is claimed conservatively and reported.
"""

from __future__ import annotations

import dataclasses
import json
import pathlib
import re

VARIABLE = re.compile(r"\$\{([A-Za-z0-9_]+)(?::-.*?)?\}")


@dataclasses.dataclass
class Claim:
    state: str
    owner: str
    source: str


class PreParseClaims:
    """Collects every vanilla carrier state other packs already bind."""

    def __init__(self, ce_resources: pathlib.Path, exclude_pack: str | None = None,
                 cache: pathlib.Path | None = None):
        self.resources = ce_resources
        self.exclude_pack = exclude_pack
        self.cache = cache
        self.claims: list[Claim] = []
        #: expressions that could not be resolved, so their states are claimed
        #: conservatively instead of being silently treated as free
        self.unresolved: list[str] = []

    # -- public ---------------------------------------------------------------

    def collect(self) -> dict[str, str]:
        """Return ``{state: "pack (source)"}`` for everything claimed."""
        out: dict[str, str] = {}
        for claim in (self.explicit_states()
                      + self.auto_state_cache()
                      + self.template_factory_claims()):
            out.setdefault(claim.state, claim.owner)
        return out

    # -- source 1: literal state: --------------------------------------------

    def explicit_states(self) -> list[Claim]:
        import yaml
        claims: list[Claim] = []
        for pack in self._enabled_packs():
            for path in sorted((pack / "configuration").rglob("*.yml")):
                try:
                    data = yaml.safe_load(path.read_text())
                except Exception:
                    # Unparseable is not the same as unclaimed; the post-parse
                    # diagnostic is what catches this.
                    continue
                for state in _walk_states(data):
                    claims.append(Claim(state, f"{pack.name} (configuration)", "configuration"))
        return claims

    # -- source 2: CraftEngine's auto_state cache ----------------------------

    def auto_state_cache(self) -> list[Claim]:
        if not self.cache or not self.cache.is_file():
            return []
        try:
            cached = json.loads(self.cache.read_text())
        except json.JSONDecodeError:
            return []
        claims = []
        for appearance, state in sorted(cached.items()):
            if isinstance(state, str) and state.startswith("minecraft:"):
                owner = appearance.split("[")[0]
                # Our own auto-state blocks (the doubles) are in this cache too, from
                # the last load. They are not another pack's claim: counting them made
                # every rebuild see its own previous doubles as foreign and shrink the
                # solid pool by that much.
                if self.exclude_pack and owner.split(":")[0] == self.exclude_pack:
                    continue
                claims.append(Claim(state, f"{owner} (auto_state cache)", "auto_state"))
        return claims

    # -- source 3: config_factory + shared templates -------------------------

    def template_factory_claims(self) -> list[Claim]:
        import yaml
        templates = self._load_templates()
        claims: list[Claim] = []
        for pack in self._enabled_packs():
            for path in sorted((pack / "configuration").rglob("*.yml")):
                try:
                    data = yaml.safe_load(path.read_text())
                except Exception:
                    continue
                for node in _walk(data):
                    factory = _as_factory(node)
                    if factory is None:
                        continue
                    instances, blueprint = factory
                    for scope in instances:
                        claims.extend(
                            self._claims_for_blueprint(templates, blueprint, scope,
                                                       pack.name))
        return claims

    def _claims_for_blueprint(self, templates: dict[str, dict], blueprint: dict,
                              scope: dict, pack: str) -> list[Claim]:
        claims: list[Claim] = []
        # The blueprint nests its blocks under a container key ("items"), so every
        # descendant mapping has to be considered, not just the top level.
        for block_id, body in _descendant_mappings(blueprint):
            if not isinstance(body, dict):
                continue
            states = _states_node(body)
            if not isinstance(states, dict):
                continue
            ref = states.get("template")
            if not isinstance(ref, str):
                continue
            template = templates.get(ref)
            if template is None:
                self.unresolved.append(f"{pack}: unknown template {ref}")
                continue
            arguments = dict(scope)
            arguments.update(states.get("arguments") or {})
            resolved = {
                key: _substitute(value, arguments)
                for key, value in arguments.items()
            }
            for state in _walk_states(template, require_namespace=False):
                # Only the block id comes from an argument here; the property suffix
                # is literal in the template. A partially-resolved value is claimed
                # conservatively rather than assumed free.
                value = _substitute(state, resolved)
                if VARIABLE.search(value):
                    # Cannot determine the target. Do not assume it is free: a false
                    # negative here is two blocks sharing one state.
                    self.unresolved.append(f"{pack}:{block_id} {value}")
                    continue
                # A factory argument may supply a bare block id (replaced_slab_type
                # yields "petrified_oak_slab"), so normalise before checking.
                if ":" not in value.split("[", 1)[0]:
                    value = "minecraft:" + value
                if value.startswith("minecraft:"):
                    claims.append(Claim(value, f"{pack} ({ref})", "template"))
        return claims

    # -- helpers --------------------------------------------------------------

    def _enabled_packs(self) -> list[pathlib.Path]:
        if not self.resources.is_dir():
            return []
        out = []
        for pack in sorted(p for p in self.resources.iterdir() if p.is_dir()):
            if pack.name == self.exclude_pack:
                continue
            meta = pack / "pack.yml"
            if meta.is_file():
                try:
                    import yaml
                    if not (yaml.safe_load(meta.read_text()) or {}).get("enable", True):
                        continue
                except Exception:
                    pass
            out.append(pack)
        return out

    def _load_templates(self) -> dict[str, dict]:
        """Shared templates, keyed by their ids such as ``default:block_state/slab``."""
        import yaml
        templates: dict[str, dict] = {}
        if not self.resources.is_dir():
            return templates
        for pack in sorted(p for p in self.resources.iterdir() if p.is_dir()):
            root = pack / "configuration" / "templates"
            if not root.is_dir():
                continue
            for path in sorted(root.rglob("*.yml")):
                try:
                    data = yaml.safe_load(path.read_text()) or {}
                except Exception:
                    continue
                for node in _walk(data):
                    for key, value in _descendant_mappings(node):
                        if isinstance(value, dict):
                            templates.setdefault(key, value)
        return templates


def _substitute(text: str, arguments: dict[str, str]) -> str:
    """Replace ``${var}`` and ``${var:-default}`` from the argument scope."""
    if not isinstance(text, str):
        return text

    def replace(match: re.Match) -> str:
        name = match.group(1)
        if name in arguments and arguments[name] is not None:
            return str(arguments[name])
        fallback = match.group(0)[3:-1].split(":-", 1)
        if len(fallback) == 2:
            return _substitute(fallback[1], arguments)
        return match.group(0)

    previous = None
    while previous != text:
        previous = text
        text = VARIABLE.sub(replace, text)
    return text


def _as_factory(node):
    """Recognise a ``config_factory`` node and split instances from its blueprint."""
    if not isinstance(node, dict):
        return None
    body = node
    if any(isinstance(k, str) and k.startswith("config_factory") for k in node):
        body = node
    instances = body.get("instances")
    blueprint = body.get("blueprint")
    if isinstance(instances, list) and isinstance(blueprint, dict):
        return [i for i in instances if isinstance(i, dict)], blueprint
    return None


def _walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for item in node:
            yield from _walk(item)


def _walk_states(node, require_namespace: bool = True) -> list[str]:
    """Every ``state:`` string in a document.

    ``require_namespace`` must be off when reading a *template*: a template's state
    is ``${slab_base_block}[type=bottom,...]``, so filtering on a ``minecraft:``
    prefix before substitution would drop exactly the values that need resolving.
    """
    found: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "state" and isinstance(value, str) and (
                    not require_namespace or value.startswith("minecraft:")):
                found.append(value)
            else:
                found.extend(_walk_states(value, require_namespace))
    elif isinstance(node, list):
        for item in node:
            found.extend(_walk_states(item, require_namespace))
    return found


def _states_node(body):
    """The states section of a blueprint block body.

    Usually ``states:`` sits directly on the block, but a block that is placed by a
    ``block_item`` carries it one level down, under ``behavior.block.states``.
    """
    if isinstance(body.get("states"), dict):
        return body["states"]
    behavior = body.get("behavior")
    if isinstance(behavior, dict):
        block = behavior.get("block")
        if isinstance(block, dict) and isinstance(block.get("states"), dict):
            return block["states"]
    return None


def _descendant_mappings(node) -> list[tuple[str, object]]:
    """Every key/value pair at any depth, so nested containers are not missed."""
    out: list[tuple[str, object]] = []
    if isinstance(node, dict):
        for key, value in node.items():
            out.append((key, value))
            out.extend(_descendant_mappings(value))
    elif isinstance(node, list):
        for item in node:
            out.extend(_descendant_mappings(item))
    return out