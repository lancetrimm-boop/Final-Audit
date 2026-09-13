# Aura Promotional Demo Library Pipeline

This directory contains the infrastructure for populating a clean Android test device with a curated media collection designed to demonstrate Aura's media intelligence capabilities.

## Pipeline Architecture

- `photos/`: Category-organized public-domain images.
- `videos/`: Category-organized public-domain videos.
- `manifest.csv`: Metadata and licensing information for all assets.
- `deploy_demo_library.py`: Automated tool to push media to a connected device via ADB.
- `reset_demo_environment.py`: Automated tool to remove demo media from the device.

## Usage Workflow

1. **Populate Directories**: Place licensed media into the `photos/` and `videos/` subdirectories.
2. **Update Manifest**: Ensure `manifest.csv` accurately reflects the files and their licenses.
3. **Connect Device**: Ensure a single physical Android device is connected via ADB.
4. **Deploy**: Run `python deploy_demo_library.py`.
5. **Aura Ingestion**: Launch Aura on the device. Wait for the "Aura is learning..." indicator to complete.
6. **Execute Script**: Follow the [Demonstration Script](#demonstration-script) below for recording.

---

## Demonstration Script (Phase 9)

| # | Workflow | Starting State | Action | Expected Result |
|---|---|---|---|---|
| 01 | Library Browse | Library Screen | Scroll through gallery | Curated categories appear with high-quality thumbnails. |
| 02 | Semantic Search | Library Screen | Search: "red sports car" | Only relevant automobile images are displayed. |
| 03 | Visual Reference | Library Screen | Long-press a dog photo | Selection mode active; item selected. |
| 04 | Visual Search | Selection Mode | Tap "Search Similar" | Gallery updates to show other animals/dogs. |
| 05 | Similarity Drill | Search Results | Click a result; Swipe up | "See Similar" available in player menu. |
| 06 | Multi-Reference | Search Results | Click + icon; Pick a forest | Multi-visual search active (Dog + Forest). |
| 07 | Interaction | Player Menu | Tap "More Like This" | Interaction recorded; recommendation weights adjusted. |
| 08 | Slideshow | Library Screen | TopBar -> Slideshow | Intelligent transitions between diverse media. |

---

## Promotional Shot List (Phase 11)

**Target Platform**: TikTok / Reels

- **SHOT 1**: Aura app icon launch -> Splash screen -> Smooth transition to Library.
- **SHOT 2**: User typing "sunset" in search bar.
- **SHOT 3**: Cinematic reveal of the sunset result set.
- **SHOT 4**: Haptic long-press on a modern architecture photo.
- **SHOT 5**: Visual search results appearing instantly.
- **SHOT 6**: Transitioning from a single reference to a multi-reference search (Architecture + Blue Sky).
- **SHOT 7**: Quick swipe-up in player to show "Intelligence core" processing.
- **SHOT 8**: Montage of the "Intelligent Slideshow" in action.
- **SHOT 9**: Final logo reveal: **AURA MEDIA PLAYER - Separate signal from noise.**

---

## Licensing & Metadata (Phase 2)

All media in this dataset must be public domain (CC0) or appropriately licensed. Consult `manifest.csv` for attribution requirements.

**DO NOT** commit personal or copyrighted media to this directory.
