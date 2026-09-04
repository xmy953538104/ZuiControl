# Original 072 post-migration verification

Source before move: `D:\3.VScode\Mi\【A官方】072`

Canonical destination: `D:\3.VScode\Mi\zui072（9008）`

The pre-move manifest contains 103 original files. Every destination file was rehashed after the
same-volume move: `103/103 PASS`, missing `0`, mismatch `0`. The manifest itself is preserved as
`ORIGINAL_SHA256.txt`; its SHA-256 is
`743f55f2f15bd63e3017c6da6127de79b9bddbf188e8c7cfecc69923d31ba64b`.

Core original identities:

| File | SHA-256 |
| --- | --- |
| `super.img` | `fb93005f082737ffdd6823b81a57b1207d93f4229e9b8446e6fcc70b7feca0f0` |
| `boot.img` | `33c8db854359a4f6ac706f69003fb0d71707e080fc9136e0d7202008c2573460` |
| `vbmeta_system.img` | `6711249b3ac06fd980fffb6b0367ee9178408762338addd35b3149678b745653` |
| `vbmeta.img` | `b751712ce683c1829ccfd86c82ff5fb8dc693d5cc4bcc559af3213929570c13b` |
| `prog_firehose_ddr-TB321FC.elf` | `a3e32e981ffd1e96d28418bafa779c33c51db79c6a9ad1ef7ae9b98d54de505d` |

`README_原始包禁止修改.txt` and `ORIGINAL_SHA256.txt` are new management metadata and
were intentionally excluded from the original-file equality count.

```text
ORIGINAL_072_VERIFIED=YES_103_OF_103
ORIGINAL_072_MODIFIED=NO
```
