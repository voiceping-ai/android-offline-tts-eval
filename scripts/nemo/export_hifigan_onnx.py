#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any, Dict


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

    hifigan_onnx = out_dir / "hifigan.onnx"

    try:
        from nemo.collections.tts.models import HifiGanModel  # type: ignore
    except Exception as e:
        raise SystemExit(f"Failed to import NeMo HifiGanModel: {e}")

    print("Loading NeMo model: tts_hifigan")
    model = HifiGanModel.from_pretrained(model_name="tts_hifigan")
    model.eval()

    print(f"Exporting ONNX: {hifigan_onnx}")
    exported = False
    try:
        model.export(output=str(hifigan_onnx), check_trace=False)  # type: ignore[attr-defined]
        exported = True
    except TypeError:
        pass
    except Exception:
        pass

    if not exported:
        try:
            model.export(str(hifigan_onnx))  # type: ignore[attr-defined]
            exported = True
        except Exception as e:
            raise SystemExit(f"NeMo export failed. You may need to update this script for your NeMo version. Error: {e}")

    try:
        import onnx  # type: ignore
    except Exception as e:
        raise SystemExit(f"Missing onnx dependency: {e}")

    m = onnx.load(str(hifigan_onnx))
    if len(m.graph.input) < 1 or len(m.graph.output) < 1:
        raise SystemExit(f"Unexpected HiFiGAN ONNX IO. inputs={len(m.graph.input)} outputs={len(m.graph.output)}")

    old_in0 = m.graph.input[0].name
    old_out0 = m.graph.output[0].name
    m = _rename_onnx_values(m, {old_in0: "mel", old_out0: "audio"})
    onnx.save(m, str(hifigan_onnx))

    print("✓ Wrote bundle file:")
    print(f"  - {hifigan_onnx}")


if __name__ == "__main__":
    main()

