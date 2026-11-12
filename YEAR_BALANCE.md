# Evening out the years: concept

Status: **decided and built 2026-11-12 (D-361).** Felix's request: for playlists with unevenly spread years,
pick songs so that every year has a more even chance of coming up, as long as the playlist is large enough.
Felix's choices: years, not decades; the option only in Settings (default Gentle, with Off and Strong), not in
New game; shown in the game's info. The rules are in SPEC.md (Drawing). Sections 6 and 10 record what was
decided; the rest is the design as built.

Contents:
1. What "even" means
2. How drawing works today
3. The idea: a weighted shuffle
4. When a pool is large enough
5. What it does, in numbers
6. The setting and where it shows
7. Code changes
8. Edge cases
9. Tests
10. Open questions

## 1. What "even" means

- **Years, not decades.** Each year with songs in the pool should be about as likely as any other on each
  draw. A year with no songs can't come up. The year filter (from, to) still decides which years are in the
  pool.
- **As even as the pool allows, never at any price.** A year with two songs can't keep up with a year with
  eighty, unless those two songs come up in nearly every game. A limit (section 4) stops that. Below the
  limit, the years are evened out. Above it, a year comes up as often as the limit allows.
- **Nothing else changes.** Every song can still come up, no song repeats within a game, and a plain shuffle
  stays the default.

Why it helps: an 80s-heavy playlist plays mostly the 80s. In Closest Year, "1985" becomes a safe guess. In
Timeline, the timelines fill with cards a year apart. Evened out, the rounds vary more and the years are
spread wider.

## 2. How drawing works today

- `GameEngine.shufflePool` shuffles the whole pool once at the start, from the game's seed
  (`songs.sortedBy { it.songId }.shuffled(Random(seed))`). The order is saved with the game.
- Each draw, each skip's replacement and each bought card takes the top song (`pool.first()`). Timeline's
  starting cards are dealt from the top too.
- Skipped songs leave the game. A song that can't be played goes to the bottom (`replaceUnplayable`). Songs
  added during a game go in at a random position (`addToPool`).

So the only thing that decides which songs come up is **the pool's order at the start**. Evening out the
years therefore needs no change to drawing, skipping, buying or saved games, only a different shuffle.

## 3. The idea: a weighted shuffle

Instead of every order being equally likely, each song gets a **weight**, and songs with more weight tend
to land nearer the top. Songs in crowded years get less weight and songs in sparse years more, so each year
as a whole comes up about equally often.

**The shuffle:** each song gets a random key `-ln(u) / weight`, where `u` is drawn from the game's seeded
`Random`. The pool is then sorted by key, smallest first. (This is the known Efraimidis–Spirakis method.) It
is the same as drawing songs one by one, each in proportion to its weight. It also has three properties
that matter here:

- It is still a full shuffle of the whole pool, so every song stays in the game, just in a different order.
- The same songs and seed give the same order, as today.
- With all weights equal, it is a plain shuffle.

**The weights:**
1. Count the songs per year (`n` for each year).
2. Estimate how many songs a game uses (`E`, section 4).
3. Give each year a **target**: how many of its songs a game should play on average. The fairest split
   gives every year the same target, but a year never gets more than `cap × n`. That is, it never plays
   more than a set share of its songs per game. Years that hit their cap pass the rest of `E` on to the
   others. (This is the "water-filling" split, worked out in a few lines by bisection.)
4. Each song's weight is its year's target divided by `n`.

```kotlin
/** Songs per game each year should get: as equal as possible, at most [cap] of a year's songs (YEAR_BALANCE.md). */
fun targets(perYear: Map<Int, Int>, perGame: Int, cap: Double): Map<Int, Double>? {
    if (cap * perYear.values.sum() <= perGame) return null // too small: a plain shuffle
    var low = 0.0
    var high = perYear.values.max().toDouble()
    repeat(50) {
        val level = (low + high) / 2
        if (perYear.values.sumOf { minOf(cap * it, level) } < perGame) low = level else high = level
    }
    return perYear.mapValues { (_, n) -> minOf(cap * n, high) }
}
```

## 4. When a pool is large enough

"Large enough" means large relative to **one game**, not a fixed song count. 300 songs are plenty for a short
Closest Year game, and few for a long Timeline game with eight players.

- **The cap decides it automatically.** A small pool hits the cap everywhere, and the result is a plain
  shuffle (`YearBalancing.targets` returns null). The larger the pool, the closer it gets to fully even, with no
  threshold to jump.
- **The cap is the trade-off.** It is how often a song from a rare year may come back from game to game. With
  a cap of 0.5, a year's songs come up in half the games, a lone 1960 song in every second game. With 0.25,
  in every fourth.
- **Songs per game (`E`)** is estimated from the players and the rules chosen. It is set deliberately on the
  high side, because overestimating only evens out a little less:
  - **Timeline:** `players × startingCards + players × (cardsToWin − startingCards) × 2`. The × 2 assumes
    about half the placements are right. For 4 players and the default 10 cards, that is 76 songs.
  - **Closest Year:** `players × winsToWin` (each round gives one player a card). For Rank and Distance:
    `3 × winsToWin`, as a round there seldom gives the full points (each round is one song for everyone).
  - Later, this could come from the average length of games played (the player stats already record rounds).
- **Longer games still work.** If a game runs past `E`, the order simply continues, and later draws drift back
  towards the crowded years that have songs left.

## 5. What it does, in numbers

Simulated over 4000 games with an 80s-heavy playlist covering 1960–2023: 56 % of its songs from the 80s,
22 % from the 90s, and 1960 with just 2 songs. The game is Timeline with 4 players and 10 cards (`E` = 76).

| 600 songs                   | Plain (today) | Cap 0.25 | Cap 0.5 |
|-----------------------------|---------------|----------|---------|
| Songs from the 80s          | 56 %          | 35 %     | 22 %    |
| Different years in a game   | 30            | 40       | 48      |
| Songs from the busiest year | 5.1           | 2.7      | 1.7     |
| A 1960 song comes up in     | 13 % of games | 25 %     | 45 %    |

The same playlist at other sizes:
- **1500 songs:** both caps give an even spread, about 16 % per decade (the 2020s 6 %, as they cover only
  4 years). That's 46 different years per game, and a 1960 song comes up in 23 % of games.
- **300 songs:** cap 0.25 is still a plain shuffle. Cap 0.5 brings the 80s down to 36 %.
- **136 songs:** a plain shuffle with either cap. A game plays more than half of the pool anyway.

The simulated shares match the targets closely; `YearBalancingTest` (section 9) checks the same in the engine.

## 6. The setting and where it shows

- **Setting:** `YearBalance` with `OFF`, `GENTLE` (cap 0.25) and `STRONG` (cap 0.5), stored in the app settings
  (`AppSettings.yearBalance`, default `GENTLE`) and copied into each game's `CommonSettings.yearBalance`. The
  engine's field defaults to `OFF`, so a game saved before reads as Off.
- **Settings only:** a panel "Song choice" with "Even out the years" and the three choices, each with a hint.
  New game doesn't show it; every game takes the value from Settings. Play again keeps the finished game's value,
  as it does for playback.
- **During a game:** the game's info lists "Even out the years: Gentle" (or Off, Strong) with the other
  settings. Guests see the same in their info, which is plain text lines (`InfoLine`), so the guest protocol
  doesn't change.

## 7. Code changes

- `core-engine/YearBalancing.kt`: `enum class YearBalance(val cap: Double?)`, and `YearBalancing` with
  `songsPerGame`, `targets`, `weights` and `weightedShuffle`, all pure.
- `core-engine/Settings.kt`: `CommonSettings.yearBalance`.
- `GameEngine.shufflePool(songs, seed, balance, perGame)`: with `OFF` (or a pool too small) exactly the plain
  `shuffled(Random(seed))` of before. Otherwise the weighted keys from the same `Random(seed)`. `start` passes the
  game's setting and its estimated songs.
- `data/SettingsRepository.kt`: `AppSettings.yearBalance`, passed on by `defaultGameSettings`.
- `ui/SettingsScreen.kt`: the panel. `ui/GameInfo.kt`: the info line and `YearBalance.label`.
- `SPEC.md` (settings table and Drawing), `DECISIONS.md` (D-361).

## 8. Edge cases

- **Year corrections during a game** (MusicBrainz lookups, `applyLookupYear`) change a song's year but not its
  place in the order. A few songs' corrections don't matter for the spread.
- **Songs added during a game** keep their random position.
- **Skips and unplayable songs** work as today. A skipped song leaves the game, and the replacement is the
  next one in the order, so it is evened out too. An unplayable song goes to the bottom.
- **Timeline starting cards** come from the evened-out order too, so the first cards are spread wider.
- **Closest Year's lowest guessable year** follows the pool's earliest song, as today. The order doesn't
  change it.
- **A pool from one or two years:** nothing to even out, so the result is a plain shuffle (all targets equal).
- **Remote guests and the co-host** only see the drawn songs, so nothing changes for them.

## 9. Tests

`YearBalancingTest` in core-engine:
- Off, or a pool too small for the game, gives exactly the old order for the same seed.
- With balancing on, the order is still the whole pool, and the same for a seed.
- `targets` add up to a game, none is above `cap × n`, an even pool gives equal weights, and a pool too small
  gives null.
- Over 2000 seeded games on a skewed pool, each year's average count in a game is within a tolerance of its
  target. The 80s drop from over half the pool to under 30 % of a game.
- The estimated songs per game for each mode, and a started game dealing from the balanced order.

The desktop screen tour also captures the game's info.

## 10. Decided (Felix, 2026-11-12)

1. **Levels:** Off, Gentle and Strong; Gentle is the default.
2. **Years, not decades.**
3. **Where:** only in Settings, not in New game; shown in the game's info.
4. **Not part of this:** remembering songs played in earlier games (a separate feature, if wanted later).
