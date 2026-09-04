# V20.4 Golden post-migration verification

Canonical package: `D:\3.VScode\Mi\zui072（flash）\out\V20.4_Golden_20260903144915`

| Artifact | SHA-256 | Result |
| --- | --- | --- |
| `super.img` | `6124e7ddcdc8e656bda893158575ed22c4f240943a8b56c82b98546a666ba6c4` | PASS |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` | PASS |
| `vbmeta_system.img` | `9479cf42e908615517d585aee01c4b803706f50253fdf0ac8d5238cc65ec22fb` | PASS |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` | PASS |
| Final Closure ZIP | `582c1fcfaf5d4eac629c95f723b57d0a5492825d2e1408e6c29250608694a490` | PASS |

The package contains exactly the accepted four images, fixed-seven rawprogram XML, firehose,
SHA256SUMS and concise README. The already accepted Device Gate remains RunId
`20260903153438`; this cleanup did not build or flash anything.

```text
V20_4_GOLDEN_RUNTIME_SOURCE=29f23f8d590b88f0d472c12373366a9ef14e8330
V20_4_GOLDEN_VERIFIED=YES
ROM_BUILT=NO
DEVICE_FLASHED=NO
```
