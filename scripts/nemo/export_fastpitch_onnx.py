#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


def _rename_onnx_values(onnx_model: Any, mapping: Dict[str, str]) -> Any:
    g = onnx_model.graph

    def rn(name: str) -> str:
        return mapping.get(name, name)

    for vi in list(g.input) + list(g.output) + list(g.value_info):
        vi.name = rn(vi.name)

    for init in g.initializer:
        init.name = rn(init.name)

    for node in g.node:
        node.input[:] = [rn(x) for x in node.input]
        node.output[:] = [rn(x) for x in node.output]

    return onnx_model


def _extract_symbols_and_ids(model: Any) -> Tuple[List[str], int, int]:
    symbols: Optional[List[str]] = None

    parser = getattr(model, "parser", None)
    if parser is not None and hasattr(parser, "labels"):
        try:
            symbols = list(parser.labels)  # type: ignore[attr-defined]
        except Exception:
            symbols = None

    if not symbols and hasattr(model, "labels"):
        try:
            symbols = list(getattr(model, "labels"))
        except Exception:
            symbols = None

    if not symbols:
        raise RuntimeError("Could not extract symbols from NeMo model. Update export_fastpitch_onnx.py to match your NeMo version.")

    def find_id(candidates: List[str]) -> Optional[int]:
        for c in candidates:
            try:
                return symbols.index(c)
            except ValueError:
                continue
        return None

    pad_id = find_id(["<pad>", "pad"]) or 0
    blank_id = find_id(["<blank>", "_", "blank"]) or 0
    return symbols, blank_id, pad_id


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--out_dir",
        default=None,
        help="Output bundle directory. Default: android-offline-tts-eval/artifacts/nemo_bundles/nemo-fastpitch-hifigan-en",
    )
    args = ap.parse_args()

    root = Path(__file__).resolve().parents[2]
    out_dir = Path(args.out_dir) if args.out_dir else (root / "artifacts" / "nemo_bundles" / "nemo-fastpitch-hifigan-en")
    out_dir.mkdir(parents=True, exist_ok=True)

    fastpitch_onnx = out_dir / "fastpitch.onnx"
    symbols_json = out_dir / "symbols.json"
    config_json = out_dir / "config.json"

    # Local NeMo restore + ONNX export.
    try:
        from nemo.collections.tts.models import FastPitchModel  # type: ignore
    except Exception as e:
        raise SystemExit(f"Failed to import NeMo FastPitchModel: {e}")

    print("Loading NeMo model: tts_en_fastpitch")
    model = FastPitchModel.from_pretrained(model_name="tts_en_fastpitch")
    model.eval()

    print(f"Exporting ONNX: {fastpitch_onnx}")
    exported = False
    try:
        model.export(output=str(fastpitch_onnx), check_trace=False)  # type: ignore[attr-defined]
        exported = True
    except TypeError:
        pass
    except Exception:
        # Fall through to alternate call styles.
        pass

    if not exported:
        try:
            model.export(str(fastpitch_onnx))  # type: ignore[attr-defined]
            exported = True
        except Exception as e:
            raise SystemExit(f"NeMo export failed. You may need to update this script for your NeMo version. Error: {e}")

    # Normalize IO names to match the Android engine expectations.
    try:
        import onnx  # type: ignore
    except Exception as e:
        raise SystemExit(f"Missing onnx dependency: {e}")

    m = onnx.load(str(fastpitch_onnx))
    if len(m.graph.input) < 2 or len(m.graph.output) < 1:
        raise SystemExit(f"Unexpected FastPitch ONNX IO. inputs={len(m.graph.input)} outputs={len(m.graph.output)}")

    old_in0 = m.graph.input[0].name
    old_in1 = m.graph.input[1].name
    old_out0 = m.graph.output[0].name

    mapping = {
        old_in0: "input_ids",
        old_in1: "input_lengths",
        old_out0: "mel",
    }
    m = _rename_onnx_values(m, mapping)
    onnx.save(m, str(fastpitch_onnx))

    # Export symbols/config for the Android char-only tokenizer.
    symbols, blank_id, pad_id = _extract_symbols_and_ids(model)

    sample_rate = 22050
    n_mels = 80
    try:
        cfg = getattr(model, "cfg", None)
        pre = getattr(cfg, "preprocessor", None) if cfg is not None else None
        if pre is not None:
            sample_rate = int(getattr(pre, "sample_rate", sample_rate))
            n_mels = int(getattr(pre, "n_mels", n_mels))
    except Exception:
        pass

    symbols_json.write_text(
        json.dumps(
            {
                "symbols": symbols,
                "blank_id": blank_id,
                "pad_id": pad_id,
                "add_blank": True,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    config_json.write_text(
        json.dumps(
            {
                "sample_rate": sample_rate,
                "n_mels": n_mels,
                "add_blank": True,
                "text_normalization": False,
                "blank_id": blank_id,
                "pad_id": pad_id,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    print("✓ Wrote bundle files:")
    print(f"  - {fastpitch_onnx}")
    print(f"  - {symbols_json}")
    print(f"  - {config_json}")


if __name__ == "__main__":
    main()

