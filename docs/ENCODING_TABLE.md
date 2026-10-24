# VibraText default encoding table

_Generated from the code by `EncodingTableDocTest`. Do not edit by hand._

## How a character becomes vibration

Every character is one **code** (0–63). The code is written as **3 base-4 digits**, and each digit is
one of four vibration symbols. With the default symbol mapping:

| Digit | Symbol | Letter in the decoder | Feel |
|---|---|---|---|
| 0 | SHORT | S | one 150 ms pulse |
| 1 | LONG | L | one 500 ms pulse |
| 2 | DOUBLE | D | two 150 ms pulses, 120 ms apart |
| 3 | TRIPLE | T | three 150 ms pulses, 120 ms apart |

The mapping can be changed per profile (it must stay one-to-one). Gaps: 250 ms between symbols,
450 ms between characters, 800 ms after a space.

## Characters

| Character | Code | Symbols (default mapping) | Names |
|---|---|---|---|
| `a` | 0 | `S S S` | Short, Short, Short |
| `b` | 1 | `S S L` | Short, Short, Long |
| `c` | 2 | `S S D` | Short, Short, Double |
| `d` | 3 | `S S T` | Short, Short, Triple |
| `e` | 4 | `S L S` | Short, Long, Short |
| `f` | 5 | `S L L` | Short, Long, Long |
| `g` | 6 | `S L D` | Short, Long, Double |
| `h` | 7 | `S L T` | Short, Long, Triple |
| `i` | 8 | `S D S` | Short, Double, Short |
| `j` | 9 | `S D L` | Short, Double, Long |
| `k` | 10 | `S D D` | Short, Double, Double |
| `l` | 11 | `S D T` | Short, Double, Triple |
| `m` | 12 | `S T S` | Short, Triple, Short |
| `n` | 13 | `S T L` | Short, Triple, Long |
| `o` | 14 | `S T D` | Short, Triple, Double |
| `p` | 15 | `S T T` | Short, Triple, Triple |
| `q` | 16 | `L S S` | Long, Short, Short |
| `r` | 17 | `L S L` | Long, Short, Long |
| `s` | 18 | `L S D` | Long, Short, Double |
| `t` | 19 | `L S T` | Long, Short, Triple |
| `u` | 20 | `L L S` | Long, Long, Short |
| `v` | 21 | `L L L` | Long, Long, Long |
| `w` | 22 | `L L D` | Long, Long, Double |
| `x` | 23 | `L L T` | Long, Long, Triple |
| `y` | 24 | `L D S` | Long, Double, Short |
| `z` | 25 | `L D L` | Long, Double, Long |
| `0` | 26 | `L D D` | Long, Double, Double |
| `1` | 27 | `L D T` | Long, Double, Triple |
| `2` | 28 | `L T S` | Long, Triple, Short |
| `3` | 29 | `L T L` | Long, Triple, Long |
| `4` | 30 | `L T D` | Long, Triple, Double |
| `5` | 31 | `L T T` | Long, Triple, Triple |
| `6` | 32 | `D S S` | Double, Short, Short |
| `7` | 33 | `D S L` | Double, Short, Long |
| `8` | 34 | `D S D` | Double, Short, Double |
| `9` | 35 | `D S T` | Double, Short, Triple |
| space | 36 | `D L S` | Double, Long, Short |
| `.` | 37 | `D L L` | Double, Long, Long |
| `,` | 38 | `D L D` | Double, Long, Double |
| `?` | 39 | `D L T` | Double, Long, Triple |
| `!` | 40 | `D D S` | Double, Double, Short |
| `:` | 41 | `D D L` | Double, Double, Long |
| `;` | 42 | `D D D` | Double, Double, Double |
| `'` | 43 | `D D T` | Double, Double, Triple |
| `"` | 44 | `D T S` | Double, Triple, Short |
| `-` | 45 | `D T L` | Double, Triple, Long |
| `_` | 46 | `D T D` | Double, Triple, Double |
| `(` | 47 | `D T T` | Double, Triple, Triple |
| `)` | 48 | `T S S` | Triple, Short, Short |
| `/` | 49 | `T S L` | Triple, Short, Long |
| `@` | 50 | `T S D` | Triple, Short, Double |
| `#` | 51 | `T S T` | Triple, Short, Triple |
| `&` | 52 | `T L S` | Triple, Long, Short |
| `+` | 53 | `T L L` | Triple, Long, Long |
| `=` | 54 | `T L D` | Triple, Long, Double |
| `%` | 55 | `T L T` | Triple, Long, Triple |
| `*` | 56 | `T D S` | Triple, Double, Short |
| `$` | 57 | `T D L` | Triple, Double, Long |

Upper-case letters: by default case is **normalised** (everything is sent as lower case and capitals
cannot be restored when decoding). A profile may instead **preserve** case: a capital is sent as the
CASE_SHIFT code followed by the lower-case letter.

Accents are removed (é → e), typographic quotes and dashes become ASCII, whitespace runs become one
space, and any other character becomes `?` (or the message is skipped, if the profile says so).

## Reserved framing codes

These can never be assigned to a character, so framing can never be confused with content.

| Marker | Code | Symbols (default mapping) | Meaning | Pause after |
|---|---|---|---|---|
| START | 63 | `T T T` | message starts | 800 ms |
| SEPARATOR | 62 | `T T D` | next field begins (sender → message …) | 800 ms |
| END | 61 | `T T L` | message ends (the completion tick follows) | 800 ms |
| CASE_SHIFT | 60 | `T T S` | next letter is upper case | 450 ms |
| TRUNCATED | 59 | `T D T` | this field was shortened or left out | 450 ms |

## Message format

```
START  field1  [TRUNCATED]  SEPARATOR  field2  [TRUNCATED] …  END   (then one 50 ms completion tick)
```

Example, sender `Alex`, message `Hello.` in the default mode:

| # | Unit | Symbols |
|---|---|---|
| 1 | START | `T T T` |
| 2 | `a` | `S S S` |
| 3 | `l` | `S D T` |
| 4 | `e` | `S L S` |
| 5 | `x` | `L L T` |
| 6 | SEPARATOR | `T T D` |
| 7 | `h` | `S L T` |
| 8 | `e` | `S L S` |
| 9 | `l` | `S D T` |
| 10 | `l` | `S D T` |
| 11 | `o` | `S T D` |
| 12 | `.` | `D L L` |
| 13 | END | `T T L` |

The decoder screen accepts this notation: symbols `S L D T`, optionally `|` between characters, e.g.
`T T T | S S S | S D T`.
