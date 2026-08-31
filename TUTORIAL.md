# Process Search — Tutorial

How to answer *"what can this machine make?"* in a pack with 341,845 recipes.

Everything here is typed into **JEI's normal search box**. There is no second UI to learn.

---

## Install

Drop `processsearch-0.2.0.jar` into your pack's `mods/` folder. Client-side only — servers neither
need it nor care.

Then join a world and **open JEI once**. The index builds quietly at 3 ms per tick from that
moment; in ATM10 it takes about 3 seconds of actual work spread over a minute of play. You do not
have to wait for it — search early and it finishes on the spot.

Check it took:

```
/processsearch stats
```

You want `4 of 4 prefixes registered` and `index: ready`.

---

## The 60-second version

| Type this | Get this |
|---|---|
| `>mixing` | everything a mixer can **make** |
| `<mixing` | everything you can **feed** a mixer |
| `*mixing` | the **machines** that do mixing (Mechanical Mixer, Basin) |
| `~compressed` | items that **are** compressed blocks |
| `>mixing/heat.heated` | things a mixer makes **with a blaze burner under it** |
| `>mixing -~dye` | mixer outputs, **minus** the dye flood |

Read the prefixes as questions:

```
>   what MAKES this?
<   what CONSUMES this?
*   what MACHINE does this?
~   what KIND of item is this?
```

Everything after `>` or `<` is `process` or `process/property`. That is the whole grammar.

---

## Lesson 1 — what can this machine make?

Open JEI, type:

```
>crushing
```

The item grid is now only things a Crushing Wheel produces. Not recipes — *items*. This is the
question JEI cannot otherwise answer, because JEI is organised around "click an item, see its
recipes", and you wanted to go the other direction.

The token is the **category name**, and you can use any part of it:

```
>crushing                  the bare category path
>create:crushing           the full id, if two mods collide
>crushing_wheels           the machine's own name also works
```

Not sure of the name? Never guess:

```
/processsearch facets crush
```

That prints every real token containing "crush". This matters more than it sounds — the token for
Create's fan washing is `fan_washing`, not `washing`, and you would have spent five minutes finding
that out by trial.

---

## Lesson 2 — what can I feed it?

Flip the prefix:

```
<crushing
```

Now you get every item that is *valid input* to a crushing recipe. This is the "I have a chest of
this, what can I do with it" question.

Combine with JEI's own `@mod` prefix to scope it:

```
<crushing @thermal
```

---

## Lesson 3 — the properties, and why they attach with `/`

Here is the problem this mod was actually built for.

Create's **heat requirement is a field on the recipe, not a category.** There is exactly one
`create:mixing` category whether or not a blaze burner sits under the basin. So no amount of
clicking through JEI separates "mixing" from "mixing that needs heat".

Properties attach to a process with a slash:

```
>mixing/heat.heated
```

*Things a mixer makes, but only with a heated basin.*

### Why not two separate words?

You might try `>mixing >heat.heated`. It looks equivalent and it is **not**.

JEI treats every search token independently and intersects the results. So that query really means:

> (items made by mixing) **AND** (items made by anything heated)

An ingot produced by *cold* mixing, and *separately* by a heated crushing recipe, satisfies both
halves and shows up — even though no heated mixer makes it. The compound token is a single fact
about a single recipe, so it cannot be fooled.

**Rule of thumb:** when the property belongs to the *same recipe* as the process, join them with
`/`. Use separate words only when you genuinely mean two independent conditions.

### The property vocabulary

Bare properties work too — `>heat.heated` means "made by anything heated, any machine".

| Property | Values | From |
|---|---|---|
| `heat.*` | `none`, `heated`, `superheated` | Create |
| `fluid.*` | `in`, `out` | Create, MI |
| `chance.*` | `certain`, `random` | Create, MI |
| `speed.*` | `fast`, `normal`, `slow` | Create, MI |
| `eu.*` | `lv`, `mv`, `hv`, `ev`, `superconductor` | Modern Industrialization |
| `shapeless` | — | any crafting recipe |
| `packing` | — | nugget↔ingot↔block round trips |

`chance.certain` is the quiet hero for automation. It means every output is guaranteed, so you can
build a ratio around it:

```
>crushing/chance.certain
```

versus `>crushing/chance.random`, which is where your throughput math goes to die.

---

## Lesson 4 — which machine do I need?

You know the process, not the block:

```
*mixing
```

→ Mechanical Mixer and Basin. That is it, just the machines.

```
*fan_washing      →  Encased Fan, Water
*assembler        →  MI Assembler
```

Useful when a pack has three mods that all "crush" things and you want to see which blocks are
actually involved before committing to one.

---

## Lesson 5 — clearing the noise

This is the other half of the original problem. Three item classes, each a `~` token, each meant to
be used **negated**:

| Token | What it removes | Scale in ATM10 |
|---|---|---|
| `~compressed` | All The Compressed tier blocks (1x–9x of everything) | ~3600 recipes |
| `~decorative` | bibliocraft, chipped, framedblocks, xtonesreworked, all of mcw* | ~5000 recipes |
| `~dye` | Create: Dragons Plus dye conversions and fan-coloring | 102 of its 103 mixing recipes |

```
>crafting -~compressed -~decorative
```

The leading `-` is JEI's own NOT operator, so this composes with everything.

Note `~` classifies the **item**, not the recipe — it works off the item's mod, which is why it
catches the thousands of furniture blocks that have no interesting recipe at all.

Add your own in `config/processsearch-client.toml` under `decorativeModIds`.

---

## Lesson 6 — filtering recipe pages

Filtering the item grid never solved the whole problem. Press `R` on a Mechanical Mixer and you
still get two thousand pages.

**Whatever is in the search box now filters those pages too.**

1. Type `>mixing/heat.heated` in JEI's search box
2. Click an ingot, press `R`
3. Only the heated mixing pages remain

A grey `filtered: 12 of 2043` line appears on the recipe screen, so pages never vanish
mysteriously.

Two things worth knowing:

- Only `>`, `<` and `*` apply here. On a recipe page a recipe either has a facet or it does not, so
  `>` and `<` filter identically. `~` describes items, so it is ignored.
- If a filter would empty a category completely, you get the **full** list instead. Better than
  being stranded on a blank page wondering what broke.

Turn it off with `enableRecipePageFilter = false`.

---

## Lesson 7 — the process tree

The prefixes answer one step at a time. When you want the *chain*, hover an item and press a key:

```
<   what can this be processed INTO?     (follows outputs forward)
>   what are all the ways to MAKE this?  (follows inputs backward)
```

Careful: this is the mirror of the search prefixes, where `>` means "made by". On the tree the arrow
points the way the chain runs.

Hover cobblestone, press `<`. You get **cobblestone, alone** — because machines are opt-in.

Press **Filters**. Every machine that touches cobblestone is listed, busiest first, with how many
recipes each contributed. Tick Stonecutting. The graph rebuilds: cobblestone at the top,
Stonecutting below it, and what it makes below that. Nothing else.

That is deliberate. In a pack this size the alternative — everything except what you thought to
exclude — is a wall of boxes you have to dismantle before you can read anything.

### Following a chain

Click one of those items. It becomes the **focus**: the graph re-centres on it, walks one more step,
and a breadcrumb at the top shows the way back. Click the breadcrumb to jump back to any earlier
step; `Backspace` goes up one.

You are never shown the whole graph, only where you are and a few steps out. Everything left out is
a `+N` chip, and every chip goes somewhere.

| | |
|---|---|
| click an item | follow it |
| click a machine | list its recipes |
| click a `+N` chip | open what it stands for |
| click an item on the bottom row | start a fresh tree there |
| click a breadcrumb | jump back to that step |
| right-click an item | start a fresh graph from there |
| drag / scroll | pan / zoom |
| `Backspace` / Back | up one step |
| `F` / Fit | frame what is on screen |
| `Home` / Root | back to the start |
| Filters | pick which machines to follow |
| Esc | back to the game |

Right-clicking a row in the recipe list opens it in JEI proper, which draws a recipe far better than
a list ever will.

### Zooming out

Past 55% the tree switches to **compact**: labels go, icons grow, and the boxes shrink to squares so
the whole tree narrows by about four times. Keep scrolling and the icons keep growing to meet you —
30px of box at the threshold, 156 at the 8% floor — so at the floor you are looking at a very wide
graph whose icons are still recognisable. Hover anything to get its name back.

### When a chip is hiding what you wanted

The chips under a machine open the **siblings page**: a plain scrolling list of everything that
machine makes, with **no caps and no filters at all**, including what your search excluded and what
the layer budget cut. When you are sure a recipe exists and the graph will not show it, that is the
page to open. Click any row for a fresh tree rooted there.

### It is capped, on purpose

**What is drawn**: seven rows (`treeViewLayers`), twelve machines around the focus and six items
under each; layers past that fan out by two and share `treeVisiblePerLayer` (72).

**What is walked**: 32 machines per item, 32 items per machine, 6000 nodes for the session.

All of these are sliders in **Mods → Process Search → Config**.

---

## Combining with JEI's own prefixes

These are real JEI prefixes, so they mix freely with the ones you already use:

```
#ingots >mixing/heat.heated        the original question, in full
@create >crushing/chance.certain   Create-only, no RNG outputs
>mixing|>crushing                  either machine  (| is OR)
>"mechanical press"                quoting works
```

JEI holds `@` mod, `#` tag, `$` tooltip, `%` creative tab, `^` colour, `&` id. This mod adds
`>` `<` `*` `~`. If you also run JEI Recipe Manager, it holds `-` and `+`.

---

## Worked examples

**"What ingots can a heated mixer make?"** — the original question

```
#ingots >mixing/heat.heated
```

**"I have a pile of raw ore. What processes it?"**

```
<crushing|<milling|<macerator
```

**"Show me MI recipes I can actually power right now"** — early game, LV only

```
>modern_industrialization >eu.lv
```

**"What does the Encased Fan make when I wash things?"**

```
>fan_washing
```

**"Clean view of what crafting can make"** — no compression, no furniture, no round trips

```
>crafting -~compressed -~decorative -packing
```

**"What needs a superheated basin?"**

```
>heat.superheated
```

---

## Gotchas

**Exclusion is set subtraction over items.** If an item is made by *both* a dye recipe and an
ordinary one, `-~dye` still removes it. There is no way around this — it is how JEI's filter works.

**Items are keyed by item type, not stack.** Enchanted books, potions and other component variants
collapse into one entry. Right trade for automation questions, wrong if you wanted a specific NBT
variant.

**Substring matching everywhere.** `>ing` matches `mixing`. Usually helpful, occasionally
surprising. Use the full compound token when you want precision.

**Only Create and Modern Industrialization have deep facets.** Every other mod still gets category,
machine-name and title tokens — so `>sieve`, `>enriching`, `>alloy_smelter` all work — they just do
not have mod-specific properties like `heat.*` yet.

---

## Troubleshooting

**`stats` says fewer than 4 of 4 prefixes registered.**
Something else claimed the character. The mixins fail soft on purpose so a JEI update cannot brick
your world, which is exactly why this is reported out loud. Change the character in
`config/processsearch-client.toml` (`madeByPrefix`, `usedInPrefix`, `machineForPrefix`,
`itemClassPrefix`) and restart.

**A `>` search returns nothing.**
Check `/processsearch stats` shows `index: ready`. If it says `not built`, open JEI once. Then
confirm the token exists with `/processsearch facets <partial name>` — the token is very often not
the word you assumed.

**Recipe pages did not filter.**
The filter only engages when the search box contains at least one `>`, `<` or `*` token in *every*
`|` alternative. A query that is partly plain text deliberately leaves recipe pages alone.

**Index build feels slow.**
It is capped at 3 ms/tick. `stats` reports work time and elapsed time separately — a big gap
between them means the cap is working, not that something is wrong. Raise
`buildTimeBudgetMillisPerTick` if you want it done sooner, or add slow categories to
`excludedCategories`.

**Start over:** `/processsearch rebuild`

---

## Which packs does this work in?

**Requires: Minecraft 1.21.1 · NeoForge · JEI 19.21+**

### All The Mods 10: To The Sky — yes, this is what it was built for

Verified running there: 341,845 recipes across 412 categories indexed, both Create (6.0.10) and
Modern Industrialization (2.4.2) adapters active.

### Prominence II: Hasturian Era — use the EMI port

Prominence II is **Minecraft 1.20.1 on Fabric, using EMI**, so this jar will not load there. Three
independent things differ: the loader (Fabric), the MC version (1.20.1) and the recipe viewer (EMI).

A separate project next door, `EMI-ProcessSearch`, covers it. It shares this one's ideas and almost
none of its code, because every hook here — `ElementPrefixParser`, `IngredientFilter`,
`FocusedRecipes`, `PrefixInfo` — is JEI-internal.

Exactly one part of that port was genuinely hard, and it is the prefixes. EMI keeps its in
`dev.emi.emi.search.QueryType`, which is a Java **enum** — you cannot add a constant to an enum at
runtime, so there is no equivalent of appending to JEI's `char -> PrefixInfo` map. The way in is one
level lower: `EmiSearch$CompiledQuery.addQuery`, the private helper EMI calls once per parsed token.
Claiming our tokens there and cancelling gets the rest of the grammar — NOT, OR, AND, and
composition with `@mod` and friends — for free.

Everything else turned out *easier* on EMI:

- `EmiRecipe.getInputs()/getOutputs()` are plain lists, so the four-adapter chain — including the
  expensive `GenericJeiAdapter` that has JEI build a category layout just to see what a recipe
  contains — collapses into a single read.
- `EmiRecipe.getBackingRecipe()` resolves the datapack recipe by id, so Create's `ProcessingRecipe`
  and MI's `MachineRecipe` are reachable with no reflection and no mixin on either mod.
- `EmiApi.setPages` is one choke point for the recipe screen, where JEI needed two hooks.
- `fluid.*` and `chance.*` fall out of `EmiStack` itself, so on EMI they work for every mod rather
  than only for the two with hand-written adapters.

### Other packs

Any 1.21.1 NeoForge pack with JEI will work. Without Create or MI you lose `heat.*`, `eu.*`,
`chance.*` and `speed.*`, but categories, machine names and item classes all still function — which
is still the core "what makes this" capability.
