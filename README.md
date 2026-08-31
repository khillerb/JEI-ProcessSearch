# Process Search

A client-side JEI addon for **Minecraft 1.21.1 / NeoForge 21.1.x**, built for All The Mods 10.

JEI's item list is very good at *"what is this item?"* and has no answer at all for *"what can this
machine make?"*. In a 346-mod pack that second question is the one that matters when you are laying
out automation, and the answer is buried under thousands of near-identical recipes.

Four search prefixes answer it one step at a time; a process tree answers it for a whole chain.

This is the NeoForge/JEI original. `EMI-ProcessSearch` next door is the Fabric/EMI port, built for
Prominence II; the two are kept at feature parity, and where they differ the reason is the API.

**New here? Read [TUTORIAL.md](TUTORIAL.md)** — worked examples, gotchas, and which packs this runs
in. The rest of this file is the reference.

## The grammar

Four prefixes, one distinct question each. Everything after a prefix has one shape.

| Prefix | Question | Shape | Example |
|---|---|---|---|
| `>` | what **makes** this item? | `process[/property]` | `>mixing`, `>mixing/heat.heated` |
| `<` | what **consumes** this item? | `process[/property]` | `<crushing`, `<assembler/eu.hv` |
| `*` | what **machine runs** this process? | `process` | `*mixing` → Mechanical Mixer, Basin |
| `~` | what **kind of item** is this? | `class` | `~compressed`, `~decorative`, `~dye` |

These are registered into JEI's own prefix table, so they are not a separate UI and not a separate
search box. They inherit the whole grammar for free:

```
space   AND        >mixing >fluid.in
|       OR         >mixing|>crushing
-       NOT        >mixing -~dye
"..."   quoting    >"mechanical press"
```

and they compose with JEI's native `@mod`, `#tag`, `$tooltip`, `%creativetab`, `^colour` and `&id`.

### Why `process/property` is one token

The query this was built for is *ingots craftable by a heated mixer*:

```
#ingots >mixing/heat.heated
```

Create's heat requirement is a **field on the recipe, not a category** — there is one
`create:mixing` category whether or not a blaze burner sits under the basin — so no amount of
category browsing separates them.

The compound token is not just for readability. JEI intersects tokens **independently**: each token
maps to its own set of items and the sets are AND-ed. So the older two-token form
`>mixing >heat.heated` really means *"(made by mixing) AND (made by something heated)"* — an ingot
produced by cold mixing and, separately, by a heated crushing recipe satisfies both and matches
wrongly. A single `>mixing/heat.heated` token cannot be fooled that way.

Bare forms still work, because JEI matches substrings:

- `>mixing` — matches the compound too, so: anything a mixer makes
- `>heat.heated` — anything made by a heated recipe, whatever the machine
- `>mixing/heat.heated` — exact, no false positives

## Facet vocabulary

Tokens are derived from whatever mods are installed, so use `/processsearch facets <text>` to
discover them.

**From every category, every mod**

- the category id and its bare path — `>create:mixing`, `>mixing`
- the category's displayed title — `>fan_washing`
- the machines that run it — `>mechanical_mixer`, `>basin`
- `shapeless`, `packing` (nugget↔ingot↔block round trips)

**From Create, and every mod built on it**

One `instanceof ProcessingRecipe` covers the whole ecosystem — mixing, crushing, milling, pressing,
deploying, item application, spout filling, item draining, sawing, all four fan processes, packing,
compacting — plus Create Encased, Createaddition, Create: Dragons Plus and the other ~18 Create
addons in ATM10.

- `heat.none` · `heat.heated` · `heat.superheated`
- `fluid.in` · `fluid.out`
- `chance.certain` · `chance.random` — the difference between a crushing recipe you can build a
  ratio around and one you cannot
- `speed.fast` · `speed.normal` · `speed.slow`

**From Modern Industrialization** (3745 recipes, the pack's largest namespace)

MI has the same gift: one `MachineRecipe` class backs every machine it ships, so one adapter covers
the mod and Extended Industrialization with it.

- `eu.lv` … `eu.superconductor` — read from `CableTier.allTiers()`, so addon-registered tiers land
  in a real bucket instead of being lumped into the last one
- `speed.*`, `chance.*`, `fluid.*`

**Item classes** (`~`)

| Class | What it catches |
|---|---|
| `~compressed` | All The Compressed — ~3600 recipes of pure tier noise, every block 1x through 9x |
| `~decorative` | furniture and block-variant mods: bibliocraft, chipped, framedblocks, xtonesreworked, the whole mcw* family |
| `~dye` | Create: Dragons Plus, whose 102 of 103 mixing recipes are dye conversions plus a `fan_coloring` category generated per dyeable item per colour |

Mostly used negated, to clear the grid:

```
>crafting -~compressed -~decorative
```

Item classes are computed from the item's namespace, not from recipes, so they catch the thousands
of furniture blocks that have no interesting recipe at all.

## Filtering recipe pages

Filtering the item grid never solved the whole problem: pressing `R` on a Mechanical Mixer still
hands you two thousand pages.

Whatever is in JEI's search box now **also filters the recipe pages**. Type
`>mixing/heat.heated`, click an ingot, press `R`, and only the heated mixing pages remain. A grey
`filtered: 12 of 2043` line on the recipe GUI says so, because pages silently vanishing would read
as a broken mod.

There is deliberately no second search box — same box, same grammar, nothing new to learn. Only the
process prefixes apply here (`>`, `<`, `*`); on a recipe page a recipe either carries a facet or it
does not, so `>` and `<` filter identically. If a filter would empty a category completely, the full
list is shown instead rather than stranding you on a blank page.

Turn it off with `enableRecipePageFilter = false`.

## The process tree

Hover an item in JEI and press `<` or `>`:

- `<` — **what can this be processed into?** Follows outputs forward.
- `>` — **what are all the ways I can produce this?** Follows inputs backward.

That is the mirror of the search prefixes, where `>` means "made by". On the tree the arrow points
the way the chain runs, which is the reading that makes sense once you are looking at a chain rather
than a single item.

You get a pan/zoom graph alternating items and machines, running **vertically** in the direction of
the question: `<` puts the root at the top and grows downward into what it becomes, `>` puts it at
the bottom and grows upward into what makes it.

A machine node is a whole recipe *category*: the Crushing Wheels node stands for all 47 crushing
recipes that take cobblestone, badged with the count, drawn once. Click it for the list of the
actual 47, and right-click a row there to open it in JEI proper.

No mixin is involved. JEI publishes `getIngredientUnderMouse` on both overlays and NeoForge
publishes the key event, so the hotkey is API on both ends — unlike the EMI port, which has to
inject into EMI's key handler.

### Nothing is followed until you say so

The tree opens showing the item alone, because **machines are opt-in**. Press **Filters** and you get
every machine that touches it, busiest first, with what each contributed; tick in the ones you care
about and the graph rebuilds. Choices are saved.

That sounds backwards until you try the alternative. In a large pack, "everything except what I have
thought to exclude" is a wall of boxes you then have to dismantle. "Nothing except what I asked for"
is a question you can actually read the answer to.

### Focus and context, not the whole graph

What is drawn is never the whole walk. It is seven layers by default — focus, then machines and
items three times over — plus where you came from, a breadcrumb of the full path, and a `+N` chip
anywhere a layer budget left something out.

Detail decays with distance. The focus's own machines and their items get the full allowance; layers
past that fan out by two and share a per-layer budget. The bottom row has nothing hanging off it, so
it packs into 3 × 2 blocks instead of a long single row.

Clicking an item makes it the focus and walks one more hop; the graph underneath keeps everything
already explored, so going back up costs nothing. Depth is unbounded because you only ever pay for
where you are standing.

| | |
|---|---|
| click an item | follow it — it becomes the focus |
| click a machine | list its recipes |
| click a `+N` chip | open what it stands for |
| click a leaf item | start a fresh tree there |
| click a breadcrumb | jump back to that step |
| right-click an item | re-root into a fresh graph |
| drag / scroll | pan / zoom |
| `Backspace` / Back | up one step |
| `F` / Fit | frame what is on screen |
| `Home` / Root | back to the start |
| Filters | choose which machines to follow |
| Esc | back to the game |

Zoom out past 55% and the view switches to **compact**: labels go, the icons grow to fill the box,
and the boxes shrink to squares, so the tree narrows by about four times rather than merely getting
smaller. It keeps going — the box grows from 30px at the threshold to 156 at the 8% floor, so an
icon that far out is still 12 real pixels. Connectors are drawn at `1.25 / zoom` graph units so they
stay a hairline at any scale.

### The search box filters it

All three halves are used:

- **Facet tokens decide which recipes are followed.** `>mixing` means only mixing steps.
- **Negated terms remove items outright.** `-~decorative` means those items are never drawn and
  never expanded.
- **Positive terms highlight and prioritise.** Matching items are tinted, and they are the branches
  drawn deeper when a layer budget has to choose. The search shapes the view; it never shrinks it.

The item half is answered by this mod rather than by JEI: plain text matches the display name,
`@mod` the namespace, and `~class` our own index. JEI's `#tooltip` and `$tag` are deliberately not
answered — both live inside JEI's own suffix tree, built for the item list rather than for arbitrary
questions, and getting one wrong in the exclusion direction would silently delete branches. A term
this cannot answer simply does not match, which for an exclusion means "keep it".

### It skips the steps that go nowhere

Anvil repairing, grindstone and enchanting hand back an item of the same kind they consumed, which
turns any tool into an endless loop. Rather than naming them, the tree drops **identity recipes** —
those whose outputs are all things they also consume. Because keys collapse component variants, an
enchanted sword and a plain one are the same thing here, which is why enchanting falls out of the
rule rather than needing to be listed.

### What it costs

JEI has no equivalent of EMI's pre-built by-input and by-output maps — its lookup API is per recipe
type, so "everything that consumes cobblestone" would mean walking every category on every step. So
the adjacency is built here, during the index pass that already visits every recipe.

Only the `(category, recipe)` pair is stored, never the ingredients: resolving what a recipe contains
means having JEI run the category's layout builder, and the walk only needs that for the handful of
recipes it actually expands. Setting `enableProcessTree = false` skips the whole structure, which is
the only part of this mod with a memory cost worth mentioning.

## What this does *not* do

Exclusion is set subtraction over items. If an item is produced by *both* a dye recipe and an
ordinary one, `-~dye` still excludes it.

Items are keyed by their `Item`, not by `ItemStack`, so component/NBT variants collapse into one
entry. That is the right trade for automation questions and is why the index stays small.

## Index reuse

**It only builds once per set of recipes.** JEI rebuilds its runtime on every world join, but going
singleplayer → menu → server on the same pack does not change a single recipe, and rebuilding the
index for it is pure waste.

On disconnect the index is *retired* rather than cleared: the maps stay exactly where they are and
only stop reporting themselves ready. On the next join the recipes are fingerprinted — category
count, recipe count, and an order-independent sum of recipe ids — and a match restores the index
instead of building it.

Taking the fingerprint costs one pass over the recipe lists and nothing else, which is the point:
the expensive half of a build is the adapter chain and JEI's layout builder, neither of which the
check touches. It is worth taking even when it fails.

One honest limit: recipes that are not datapack recipes have no stable id — they are generated by
their category, and their identity hash changes every reload. Those contribute their count only, so
a category that silently rewrites the same number of generated recipes would not be caught. Every
real pack change moves a count or an id.

## When the index builds

Nothing happens until you open JEI. From then it builds a slice per client tick (3 ms by default,
configurable) on the client thread — never on a worker, because modded recipe categories are under
no obligation to be safe to touch off-thread. Search with a prefix before it has finished and it
completes synchronously rather than returning a wrong answer.

Measured on this pack (ATM10 To The Sky, 345 mods):

```
341,845 recipes across 412 categories
 39,704 made-by entries, 43,641 used-in entries
```

`/processsearch stats` reports the build as *work* time and *elapsed* time separately. Only the
first is a cost — the second is mostly waiting between ticks, and a large gap between them means
the budget is doing its job.

## Commands

```
/processsearch stats            prefixes, state, counts, build time
/processsearch facets <text>    discover the searchable tokens
/processsearch rebuild          drop and rebuild the index
```

`stats` reports how many of the four prefixes actually registered. That matters: the mixin config
fails soft so a JEI update cannot brick a live pack, which means a missed hook would otherwise be
invisible.

## Config

**Main menu → Mods → Process Search → Config.** NeoForge generates the whole screen from the config
spec, so every comment below is a tooltip there and every range is a slider. No Cloth Config, no Mod
Menu, nothing to install — which is the one place the NeoForge side is simpler than the Fabric one.

Underneath it is `config/processsearch-client.toml`.

| Key | Default | |
|---|---|---|
| `madeByPrefix` / `usedInPrefix` | `>` / `<` | change if another mod claims one |
| `machineForPrefix` / `itemClassPrefix` | `*` / `~` | |
| `enableCreateFacets` | `true` | heat, fluid, chance, speed |
| `enableModernIndustrializationFacets` | `true` | eu tier, speed, chance |
| `enableCatalystFacets` | `true` | powers `*` and machine-name search |
| `enableRecipePageFilter` | `true` | apply the search box to recipe pages |
| `reuseIndexAcrossWorlds` | `true` | keep the index on leaving a world, reuse it if the recipes match |
| `enableProcessTree` | `true` | the `<` / `>` graph screens, and the adjacency they need |
| `treeConsumersKey` / `treeProducersKey` | `shift+comma` / `shift+period` | any Minecraft key name; these are `<` and `>` on a US layout only |
| `treeViewLayers` | `7` | rows drawn including the focus; walk depth derives from it |
| `treeVisibleMachines` / `treeVisibleItemsPerMachine` | `12` / `6` | how much is **drawn** around the focus |
| `treeVisiblePerLayer` | `72` | nodes drawn on each layer past the focus's items |
| `treeMaxProcessesPerItem` / `treeMaxItemsPerProcess` | `32` / `32` | how much the **walk** keeps |
| `treeMaxNodes` | `6000` | safety backstop on the accumulated graph |
| `treeMinZoomPercent` | `8` | how far out you can scroll, and how far Fit will go |
| `treeHideIdentityRecipes` | `true` | drop steps that hand back an item they also consumed |
| `treeIncludedCategories` | empty | opt-in machine list, edited by the Filters button |
| `decorativeModIds` | 13 mods | what `~decorative` catches |
| `trimModIds` | 4 mods | what `~trim` catches |
| `compressedModIds` | `allthecompressed` | what `~compressed` catches |
| `dyeCategoryIds` / `dyeRecipePatterns` | CDP defaults | what `~dye` catches |
| `excludedCategories` | empty | skip a slow or noisy category |
| `buildTimeBudgetMillisPerTick` | `3` | |

`treeIncludedCategories` is in the screen but is not meant to be edited there. It is whatever
machines the graph in front of you happens to touch, so the Filters button on the graph is the only
place that has the list to offer.

Prefix characters are config-driven because the useful ones are contested. JEI already claims
`@ # $ % ^ &`, and JEI Recipe Manager — also in ATM10 — claims `-` and `+`. If a prefix is already
taken, this mod logs an error and declines rather than stealing it.

## Building

The compile-time jars are vendored in `libs/` straight from the instance, because this mod hooks
JEI *internals* (`mezz.jei.gui.search`, `mezz.jei.gui.recipes`, `mezz.jei.core.search`) and reads
Create's and MI's recipe fields — none of which are published API.

```bash
./gradlew build
```

Then copy `build/libs/processsearch-0.2.0.jar` into the pack's `mods/` folder.
