# Collage Maker (Android)

Make photo collages from **1 to 100 photos** in many styles. Every style adapts to however many photos you pick.

| Category | Styles |
|---|---|
| Grid | Grid, Columns, Strips |
| Mosaic | Center hero (big photo surrounded by small ones), Big left, Big top, Mosaic, Mixed sizes |
| Angled | Shattered, Diagonal, Pinwheel |
| Pattern | Diamonds, Diamond hero, Honeycomb, Circles, Triangles, Hearts |
| Scatter | Polaroids, Scattered |
| Shape | Heart, Circle, Star, **Text / Number** (photos fill any word or number, e.g. `LOVE`, `50`) |

Editing:
- **Shuffle** (top bar) re-arranges the layout and photo order.
- Drag a photo to move it inside its frame, pinch to zoom, double-tap to reset.
- Long-press a photo and drag it onto another to swap them.
- Tap a photo to select it, then **Replace** or **Remove** it in the Photos tab.
- **Ratio** tab: 1:1, 4:5, 3:4, 2:3, 9:16, A4, 4:3, 3:2, 16:9.
- **Style** tab: spacing, rounded corners, background colour.
- **Save** writes a 3000 px JPEG to `Pictures/CollageMaker`; **Share** sends it to any app.
- You can also share photos *to* Collage Maker from your gallery app.

## Building

There is no need for Android Studio. Pushing this folder to GitHub runs
`.github/workflows/build-apk.yml`, which builds `CollageMaker.apk` and publishes it
under the repository's **Releases** (and as an Actions artifact).

To build locally instead (needs JDK 17 + Android SDK + Gradle 8.11): `gradle assembleDebug`.
The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Note: each GitHub build is signed with a fresh debug key, so to install a newer build
over an older one you may need to uninstall the old app first.
