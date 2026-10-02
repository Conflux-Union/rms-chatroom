# RNNoise noise filter (vendored)

Client-side AI noise suppression for the microphone track, shared by web and
desktop. Runs [RNNoise](https://github.com/xiph/rnnoise) as a WASM
AudioWorklet and exposes it as a livekit-client `TrackProcessor`.

## Provenance

Vendored from [Dadadah/livekit-rnnoise-processor](https://github.com/Dadadah/livekit-rnnoise-processor)
(Apache-2.0, itself a fork of deepmindru-afk/denoise-plugin); see `LICENSE`.
Upstream's CDN loading was replaced with bundled assets (`?url` imports in
`noiseFilter.ts`).

| File | Role |
| --- | --- |
| `vendor/RNNoiseWorklet.ts` | Upstream worklet source (glue import path adjusted) |
| `vendor/MonoResampler.ts` | Upstream resampler (any rate &harr; 48 kHz) |
| `vendor/rnnoise.js` | Emscripten glue (`-s ENVIRONMENT=worklet`, ES6) |
| `vendor/rnnoise.wasm` | RNNoise model + runtime; custom sections stripped (~829 KB, mostly model weights) |
| `rnnoise-worklet.js` | Built self-contained worklet bundle — the only runtime asset loaded via `addModule` |

## Rebuilding the worklet bundle

After editing files under `vendor/`, regenerate the bundle (do not edit
`rnnoise-worklet.js` by hand):

```bash
cd packages/shared/src/audio/noise-filter
pnpm dlx esbuild vendor/RNNoiseWorklet.ts --bundle --minify --format=esm \
  --banner:js="/* Built from vendor/RNNoiseWorklet.ts (vendored from github.com/Dadadah/livekit-rnnoise-processor, Apache-2.0). Regenerate with the command in README.md; do not edit by hand. */" \
  --outfile=rnnoise-worklet.js
```

## Size note

The WASM data section is ~800 KB of model weights (gzip ~730 KB). It is a
content-hashed asset fetched once, only when joining voice with AI noise
suppression on. A rebuild with emscripten against the upstream "little"
model (`build.sh` in the upstream repo) could shrink it.
