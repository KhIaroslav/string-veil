# String Veil benchmarks

Regenerate with `./gradlew :bytecode:benchmark`. Size and overhead are exact; **encode** (per-literal build cost) and **decode** (per-use runtime cost) are steady-state estimates (warmup + best-of-3) and indicative only. Compare before/after a change on the same machine in one run — the timings are not comparable across machines or CI runners.

## short (14 B)

| config | plain B | container B | overhead | encode | decode |
|---|--:|--:|--:|--:|--:|
| BIT_SHIFT x1 | 14 | 156 | 11.1× | 5539 ns | 1057 ns |
| BIT_XOR x1 | 14 | 156 | 11.1× | 5174 ns | 856 ns |
| BASE64 x1 | 14 | 172 | 12.3× | 5640 ns | 1031 ns |
| AES x1 | 14 | 204 | 14.6× | 10065 ns | 2773 ns |
| RANDOM_ALL x3 | 14 | 245 | 17.5× | 9494 ns | 1489 ns |
| RANDOM_ALL x8 | 14 | 443 | 31.7× | 19118 ns | 4534 ns |

## url (54 B)

| config | plain B | container B | overhead | encode | decode |
|---|--:|--:|--:|--:|--:|
| BIT_SHIFT x1 | 54 | 236 | 4.4× | 7563 ns | 1742 ns |
| BIT_XOR x1 | 54 | 236 | 4.4× | 7392 ns | 1904 ns |
| BASE64 x1 | 54 | 268 | 5.0× | 8175 ns | 2066 ns |
| AES x1 | 54 | 284 | 5.3× | 9758 ns | 3822 ns |
| RANDOM_ALL x3 | 54 | 341 | 6.3× | 12152 ns | 4280 ns |
| RANDOM_ALL x8 | 54 | 544 | 10.1× | 22519 ns | 11847 ns |

## long (2 KB)

| config | plain B | container B | overhead | encode | decode |
|---|--:|--:|--:|--:|--:|
| BIT_SHIFT x1 | 2304 | 4732 | 2.1× | 147772 ns | 55630 ns |
| BIT_XOR x1 | 2304 | 4732 | 2.1× | 143739 ns | 43954 ns |
| BASE64 x1 | 2304 | 6268 | 2.7× | 192730 ns | 59985 ns |
| AES x1 | 2304 | 4796 | 2.1× | 146205 ns | 37799 ns |
| RANDOM_ALL x3 | 2304 | 5635 | 2.4× | 184130 ns | 74619 ns |
| RANDOM_ALL x8 | 2304 | 6359 | 2.8× | 230184 ns | 84137 ns |

