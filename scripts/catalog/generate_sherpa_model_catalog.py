#!/usr/bin/env python3
"""
Generate a sherpa-onnx TTS model catalog from public Hugging Face repos.

This script is intentionally dependency-free (stdlib only) so it can run in
minimal environments.

It writes to:
  VoicePingAndroidOfflineTtsEval/app/src/main/assets/model_catalog.json
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple


AUTHOR = "csukuangfj"
SEARCH_TERMS = ["vits", "matcha", "kokoro", "kitten"]
LIMIT = 1000
REV = "main"
OFFICIAL_COQUI_REPO = "vits-coqui-en-ljspeech"

SCHEMA_VERSION = 1

ROOT_DIR = Path(__file__).resolve().parents[2]
ASSET_PATH = ROOT_DIR / "VoicePingAndroidOfflineTtsEval" / "app" / "src" / "main" / "assets" / "model_catalog.json"


def _http_get_json(url: str, *, timeout_sec: int = 30, retries: int = 4) -> Optional[Dict[str, Any]]:
    last_err: Optional[BaseException] = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "android-offline-tts-eval/1.0"})
            with urllib.request.urlopen(req, timeout=timeout_sec) as r:
                if r.status != 200:
                    return None
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            # Gated/private models return 401. Just skip them.
            if e.code in (401, 403, 404):
                return None
            last_err = e
        except Exception as e:  # noqa: BLE001
            last_err = e

        # Backoff (best-effort).
        time.sleep(0.35 * (attempt + 1))

    if last_err is not None:
        print(f"[warn] request failed after retries: {url}: {last_err}", file=sys.stderr)
    return None


def _hf_list_models(author: str, search: str, limit: int) -> List[str]:
    q = urllib.parse.urlencode({"author": author, "search": search, "limit": str(limit)})
    url = f"https://huggingface.co/api/models?{q}"
    data = _http_get_json(url)
    if not data:
        return []
    out: List[str] = []
    for obj in data if isinstance(data, list) else []:
        mid = obj.get("modelId")
        if isinstance(mid, str) and mid:
            out.append(mid)
    return out


def _hf_siblings(repo_id: str) -> Optional[List[str]]:
    url = f"https://huggingface.co/api/models/{repo_id}"
    data = _http_get_json(url)
    if not data:
        return None
    sibs = data.get("siblings")
    if not isinstance(sibs, list):
        return []
    files: List[str] = []
    for s in sibs:
        if not isinstance(s, dict):
            continue
        name = s.get("rfilename")
        if isinstance(name, str) and name:
            files.append(name)
    return sorted(set(files))


def _normalize_id(repo_name: str) -> str:
    # Lowercase + normalize separators.
    s = repo_name.strip().lower().replace("_", "-")
    s = re.sub(r"[^a-z0-9\\-]+", "-", s)
    s = re.sub(r"-{2,}", "-", s).strip("-")
    return s


def _root_files(files: Sequence[str]) -> List[str]:
    return [f for f in files if "/" not in f]


def _pick_one(candidates: Sequence[str], available: Set[str]) -> Optional[str]:
    for c in candidates:
        if c in available:
            return c
    return None


def _pick_single_root_onnx(root: Sequence[str]) -> Optional[str]:
    onnx = [f for f in root if f.endswith(".onnx")]
    if len(onnx) == 1:
        return onnx[0]
    return None


def _pick_vits_onnx(root: Sequence[str]) -> Optional[str]:
    avail = set(root)
    return (
        _pick_one(["model.int8.onnx", "model.fp16.onnx", "model.onnx"], avail)
        or _pick_single_root_onnx(root)
    )


def _pick_kokoro_onnx(root: Sequence[str]) -> Optional[str]:
    avail = set(root)
    return _pick_one(["model.int8.onnx", "model.onnx"], avail) or _pick_single_root_onnx(root)


def _pick_kitten_onnx(root: Sequence[str]) -> Optional[str]:
    avail = set(root)
    return (
        _pick_one(["model.fp16.onnx", "model.onnx", "model.int8.onnx"], avail)
        or _pick_single_root_onnx(root)
    )


_MATCHA_STEPS_RE = re.compile(r"^model-steps-(\d+)\.onnx$")


def _pick_matcha_acoustic(root: Sequence[str]) -> Optional[str]:
    avail = set(root)
    if "model-steps-3.onnx" in avail:
        return "model-steps-3.onnx"
    step_files: List[Tuple[int, str]] = []
    for f in root:
        m = _MATCHA_STEPS_RE.match(f)
        if not m:
            continue
        step_files.append((int(m.group(1)), f))
    if step_files:
        step_files.sort(key=lambda x: x[0])
        return step_files[0][1]
    if "model.onnx" in avail:
        return "model.onnx"
    return _pick_single_root_onnx(root)


def _has_prefix(files: Sequence[str], prefix: str) -> bool:
    return any(f.startswith(prefix) for f in files)


def _collect_root_matching(root: Sequence[str], pattern: str) -> List[str]:
    rx = re.compile(pattern)
    return sorted([f for f in root if rx.match(f)])


def _best_effort_language(repo_name: str) -> str:
    # Common patterns we see in csukuangfj TTS repos.
    m = re.search(r"\b([a-z]{2}_[A-Z]{2})\b", repo_name)
    if m:
        return m.group(1)
    m = re.search(r"\b([a-z]{2}-[A-Z]{2})\b", repo_name)
    if m:
        return m.group(1)
    m = re.search(r"\b([a-z]{2}_[a-z]{2})\b", repo_name)
    if m:
        return m.group(1)
    m = re.search(r"\b([a-z]{2})\b", repo_name)
    if m:
        return m.group(1)
    return "unknown"


def _display_name(repo_name: str, model_type: str, *, variant: str = "") -> str:
    if model_type == "vits":
        if repo_name.startswith("vits-piper-"):
            rest = repo_name[len("vits-piper-") :]
            parts = rest.split("-")
            locale = parts[0] if parts else "unknown"
            voice = parts[1] if len(parts) >= 2 else ""
            qual = " ".join(parts[2:]) if len(parts) >= 3 else ""
            bits = " ".join(x for x in [locale.replace("_", "-"), voice, qual] if x).strip()
            return f"Piper VITS ({bits})"
        if repo_name.startswith("vits-coqui-"):
            return f"Coqui VITS ({repo_name[len('vits-coqui-'):]})"
        if repo_name.startswith("vits-mimic3-"):
            return f"Mimic3 VITS ({repo_name[len('vits-mimic3-'):]})"
        if repo_name.startswith("icefall-tts-"):
            return f"Icefall VITS ({repo_name[len('icefall-tts-'):]})"
        return f"VITS ({repo_name})"

    if model_type == "matcha":
        base = repo_name
        if base.startswith("matcha-"):
            base = base[len("matcha-") :]
        name = f"Matcha ({base})"
        if variant:
            name += f" + {variant}"
        return name

    if model_type == "kokoro":
        base = repo_name
        if base.startswith("kokoro-"):
            base = base[len("kokoro-") :]
        return f"Kokoro ({base})"

    if model_type == "kitten":
        base = repo_name
        if base.startswith("kitten-"):
            base = base[len("kitten-") :]
        return f"Kitten ({base})"

    return repo_name


def _make_entry(
    *,
    model_id: str,
    display_name: str,
    engine: str,
    model_type: str,
    source_repo: str,
    files: Sequence[str],
    prefixes: Sequence[str],
    dependencies: Sequence[str],
    languages: str,
    description: str,
) -> Dict[str, Any]:
    return {
        "id": model_id,
        "display_name": display_name,
        "engine": engine,
        "model_type": model_type,
        "source": {"kind": "hf", "repo": source_repo, "rev": REV},
        "files": list(files),
        "prefixes": list(prefixes),
        **({"dependencies": list(dependencies)} if dependencies else {}),
        "meta": {
            "languages": languages,
            "description": description,
            "size_hint_mb": 0,
        },
    }


def _classify_repo(repo_id: str, repo_name: str, files: Sequence[str]) -> List[Dict[str, Any]]:
    root = _root_files(files)
    root_set = set(root)
    has_dict = _has_prefix(files, "dict/")
    has_espeak = _has_prefix(files, "espeak-ng-data/")

    tokens = "tokens.txt" in root_set
    voices = "voices.bin" in root_set

    def dep_espeak() -> List[str]:
        return ["sherpa-espeak-ng-data"] if has_espeak else []

    prefixes = ["dict/"] if has_dict else []

    # Matcha
    if "matcha" in repo_name or any(_MATCHA_STEPS_RE.match(f) for f in root):
        if not tokens:
            return []
        acoustic = _pick_matcha_acoustic(root)
        if not acoustic:
            return []

        base_id = _normalize_id(repo_name)
        lang = _best_effort_language(repo_name)
        common_files = [acoustic, "tokens.txt"]
        common_prefixes = prefixes

        out: List[Dict[str, Any]] = []

        out.append(
            {
                "id": f"{base_id}-hifigan",
                "display_name": _display_name(repo_name, "matcha", variant="HiFiGAN"),
                "engine": "sherpa_offline_tts",
                "model_type": "matcha",
                "source": {"kind": "hf", "repo": repo_id, "rev": REV},
                "files": common_files,
                "prefixes": common_prefixes,
                "dependencies": ["sherpa-hifigan-v3", *dep_espeak()],
                "meta": {
                    "languages": lang,
                    "description": "Matcha acoustic model via sherpa-onnx OfflineTtsMatchaModelConfig.",
                    "size_hint_mb": 0,
                },
            }
        )

        out.append(
            {
                "id": f"{base_id}-vocos",
                "display_name": _display_name(repo_name, "matcha", variant="Vocos"),
                "engine": "sherpa_offline_tts",
                "model_type": "matcha",
                "source": {"kind": "hf", "repo": repo_id, "rev": REV},
                "files": common_files,
                "prefixes": common_prefixes,
                "dependencies": ["sherpa-vocos-22khz-univ", *dep_espeak()],
                "meta": {
                    "languages": lang,
                    "description": "Matcha acoustic model via sherpa-onnx OfflineTtsMatchaModelConfig.",
                    "size_hint_mb": 0,
                },
            }
        )

        return out

    # Kokoro
    if "kokoro" in repo_name:
        if not tokens or not voices:
            return []
        onnx = _pick_kokoro_onnx(root)
        if not onnx:
            return []

        extra_lex = _collect_root_matching(root, r"^lexicon.*\.txt$")
        extra_fst = _collect_root_matching(root, r"^.*\.fst$")
        files_out = [onnx, "tokens.txt", "voices.bin", *extra_lex, *extra_fst]

        return [
            _make_entry(
                model_id=_normalize_id(repo_name),
                display_name=_display_name(repo_name, "kokoro"),
                engine="sherpa_offline_tts",
                model_type="kokoro",
                source_repo=repo_id,
                files=files_out,
                prefixes=prefixes,
                dependencies=dep_espeak(),
                languages=_best_effort_language(repo_name),
                description="Kokoro TTS via sherpa-onnx OfflineTtsKokoroModelConfig.",
            )
        ]

    # Kitten
    if "kitten" in repo_name:
        if not tokens or not voices:
            return []
        onnx = _pick_kitten_onnx(root)
        if not onnx:
            return []

        files_out = [onnx, "tokens.txt", "voices.bin"]
        return [
            _make_entry(
                model_id=_normalize_id(repo_name),
                display_name=_display_name(repo_name, "kitten"),
                engine="sherpa_offline_tts",
                model_type="kitten",
                source_repo=repo_id,
                files=files_out,
                prefixes=prefixes,
                dependencies=dep_espeak(),
                languages=_best_effort_language(repo_name),
                description="Kitten TTS via sherpa-onnx OfflineTtsKittenModelConfig.",
            )
        ]

    # VITS
    if "vits" in repo_name:
        # Keep only one official Coqui model to avoid catalog bloat.
        if repo_name.startswith("vits-coqui-") and repo_name != OFFICIAL_COQUI_REPO:
            return []

        if not tokens:
            return []
        onnx = _pick_vits_onnx(root)
        if not onnx:
            return []

        lex = _collect_root_matching(root, r"^lexicon.*\.txt$|^lexicon\.txt$")
        fars = _collect_root_matching(root, r"^.*\.far$")
        fsts = _collect_root_matching(root, r"^.*\.fst$")
        files_out = [onnx, "tokens.txt", *lex, *fars, *fsts]

        return [
            _make_entry(
                model_id=_normalize_id(repo_name),
                display_name=_display_name(repo_name, "vits"),
                engine="sherpa_offline_tts",
                model_type="vits",
                source_repo=repo_id,
                files=files_out,
                prefixes=prefixes,
                dependencies=dep_espeak(),
                languages=_best_effort_language(repo_name),
                description="VITS voice via sherpa-onnx OfflineTtsVitsModelConfig.",
            )
        ]

    return []


def _fixed_entries() -> List[Dict[str, Any]]:
    # These are not discovered reliably via search terms and must always exist.
    return [
        {
            "id": "android-system-tts",
            "display_name": "Android System TTS",
            "engine": "android_system_tts",
            "model_type": "system",
            "source": {"kind": "system"},
            "files": [],
            "prefixes": [],
            "meta": {"languages": "system", "description": "Android TextToSpeech baseline.", "size_hint_mb": 0},
        },
        {
            "id": "nemo-fastpitch-hifigan-en",
            "display_name": "NVIDIA NeMo FastPitch + HiFiGAN (EN)",
            "engine": "nemo_ort",
            "model_type": "nemo_fastpitch_hifigan",
            "source": {"kind": "local_bundle", "bundle_name": "nemo-fastpitch-hifigan-en"},
            "files": ["fastpitch.onnx", "hifigan.onnx", "symbols.json", "config.json"],
            "prefixes": [],
            "meta": {
                "languages": "en",
                "description": "Local-import ONNX bundle exported from NVIDIA NeMo models. Push via adb.",
                "size_hint_mb": 0,
            },
        },
        {
            "id": "sherpa-espeak-ng-data",
            "display_name": "espeak-ng-data (shared)",
            "engine": "asset_only",
            "model_type": "asset",
            # Use a known-public repo that already contains espeak-ng-data/.
            "source": {"kind": "hf", "repo": "csukuangfj/vits-piper-en_US-amy-low", "rev": REV},
            "files": [],
            "prefixes": ["espeak-ng-data/"],
            "meta": {
                "languages": "n/a",
                "description": "Shared espeak-ng-data directory used by many sherpa-onnx TTS models.",
                "size_hint_mb": 0,
            },
        },
        {
            "id": "sherpa-hifigan-v3",
            "display_name": "HiFiGAN Vocoder (v3)",
            "engine": "asset_only",
            "model_type": "vocoder",
            "source": {"kind": "hf", "repo": "csukuangfj/sherpa-onnx-hifigan", "rev": REV},
            "files": ["hifigan_v3.onnx"],
            "prefixes": [],
            "meta": {"languages": "n/a", "description": "Vocoder dependency for Matcha models.", "size_hint_mb": 0},
        },
        {
            "id": "sherpa-vocos-22khz-univ",
            "display_name": "Vocos Vocoder (22kHz univ)",
            "engine": "asset_only",
            "model_type": "vocoder",
            # NOTE: csukuangfj/sherpa-onnx-vocos is gated (401) without a HF token.
            # The same file is available in the public k2-fsa model bundle.
            "source": {"kind": "hf", "repo": "k2-fsa/sherpa-onnx-models", "rev": REV},
            "files": ["vocoder-models/vocos-22khz-univ.onnx"],
            "prefixes": [],
            "meta": {"languages": "n/a", "description": "Vocoder dependency for Matcha (Vocos) models.", "size_hint_mb": 0},
        },
    ]


def main() -> int:
    discovered: Set[str] = set()
    for term in SEARCH_TERMS:
        for mid in _hf_list_models(AUTHOR, term, LIMIT):
            if mid.startswith(f"{AUTHOR}/"):
                discovered.add(mid)

    print(f"Discovered {len(discovered)} candidate repos from search terms: {', '.join(SEARCH_TERMS)}")

    entries: List[Dict[str, Any]] = []
    skipped = 0
    gated = 0

    # Cache siblings lookups so repeated modelIds (from multiple searches) don't refetch.
    sib_cache: Dict[str, Optional[List[str]]] = {}

    for repo_id in sorted(discovered):
        repo_name = repo_id.split("/", 1)[1]
        if repo_id not in sib_cache:
            sib_cache[repo_id] = _hf_siblings(repo_id)
        files = sib_cache[repo_id]
        if files is None:
            gated += 1
            continue

        classified = _classify_repo(repo_id, repo_name, files)
        if not classified:
            skipped += 1
            continue
        entries.extend(classified)

    # Deterministic de-dup by id.
    by_id: Dict[str, Dict[str, Any]] = {}
    for e in entries:
        by_id[e["id"]] = e

    dyn_entries = [by_id[k] for k in sorted(by_id.keys())]
    models = _fixed_entries() + dyn_entries

    out = {"schema_version": SCHEMA_VERSION, "models": models}
    ASSET_PATH.parent.mkdir(parents=True, exist_ok=True)
    ASSET_PATH.write_text(json.dumps(out, indent=2, sort_keys=False) + "\n", encoding="utf-8")

    print(f"Wrote {ASSET_PATH} (models={len(models)}, fixed={len(_fixed_entries())}, dynamic={len(dyn_entries)})")
    print(f"Skipped (unclassified or missing files): {skipped}")
    print(f"Gated/private (401/403/404): {gated}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
