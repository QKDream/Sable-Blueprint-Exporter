# Sable Blueprint Exporter

Standalone NeoForge 1.21.1 mod (`modId=blueprintexporter`) that turns a **Sable blueprint** into
an OBJ + MTL + texture set you can open in Blender.

## Prerequisite: the Sable blueprint

This exporter does **not** create selections by itself — the blueprint (`.nbt`) has to come from the
Sable blueprint tool (`sable_schematic_api` / Photomancy). Save your structure with that tool first,
then export the saved blueprint.

## What it does

- bakes the blueprint through the game's **own block model pipeline**, on the client thread
- uses the **real `BlockState` and real `BlockEntity` instances**, so modded block models resolve the
  same way they do in game
- splits materials by render type (`solid` / `cutout` / `translucent`), writes `map_d` masks for cutout
  blocks and `d`/`Tr` for translucent ones, and keeps textures as RGBA PNG
- writes `<name>.obj`, `<name>.mtl`, `<name>.parts.json` and `textures/`

## Building

```
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

The build script compiles against the jars in your local Minecraft `mods` folder (see
`RELEASE-CHECKLIST.md` for the exact list and for what is still missing before publishing).

## Status

See `RELEASE-CHECKLIST.md`: `LICENSE`, real `issueTrackerURL`, a Gradle skeleton and `CHANGELOG.md`
are still missing. The jar is otherwise self-contained and does not depend on any other mod of ours.
