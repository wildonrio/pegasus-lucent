System logotypes are sourced from the official ES-DE `system-logos` theme
asset repository:

https://gitlab.com/es-de/themes/system-logos

The white variants are rasterized to transparent PNGs without redrawing or
altering their geometry, so every authentic logotype remains legible on the
dark carousel instead of repeating the hardware already in the wallpaper.
## Official-color variants

Thorium uses official color geometry where a platform has a recognizable color
master and a white knockout for intrinsically monochrome marks. Official SVGs
are cached in `../logos-official`; the reproducible dark-surface conversion is
`../../../build_platform_color_logos.py`.

- https://commons.wikimedia.org/wiki/File:Game_Boy_Color_logo.svg
- https://commons.wikimedia.org/wiki/File:Game_Boy_Advance_logo.svg
- https://commons.wikimedia.org/wiki/File:Nintendo_3DS_logo.svg
- https://commons.wikimedia.org/wiki/File:Nintendo_64_wordmark.svg
- https://commons.wikimedia.org/wiki/File:Super_Nintendo_Entertainment_System_logo.svg
- https://commons.wikimedia.org/wiki/File:WiiU.svg
- https://commons.wikimedia.org/wiki/File:Playstation_logo_colour.svg
- https://commons.wikimedia.org/wiki/File:Sega_genesis_logo.svg

The North American 16-bit Sega card intentionally uses the Genesis wordmark,
not the Mega Drive regional mark.
