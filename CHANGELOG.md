# Changelog

## 0.18 (versionName 0.18.0, code 110)
- File manager: removed the Internal storage / Downloads chips. A storage icon
  next to the settings button now switches between internal storage, SD card
  and USB drives (list refreshed each time you open it).
- Selection bar: always exactly two rows of five buttons; "All" toggles to
  "None" when everything is selected; unavailable actions are greyed out
  instead of hidden; the count is shown in the Cancel button.

## 0.17 (versionName 0.17.0, code 108)
- README.md is now your edited version.
- Built-in file manager, now the default page (bottom tabs: Files / Archives).
  Browse internal storage, SD cards and Downloads; tap a folder to enter,
  tap an archive to browse it, tap any other file to open it with an app.
  Long-press to select: Compress (7z, ZIP, TAR.GZ, TAR.XZ, optional password),
  Extract here, Browse, Copy, Cut / Paste, Rename, Share, Delete, New folder,
  hidden-files toggle. Long jobs use the background service and Performance limits.
- Needs "All files access" (Android 11+) or storage permission (Android 9-10);
  without it the Files tab explains how to allow it and Archives still works
  through the picker.
- Native: archives can be created straight from a list of paths, no staging copy.

## 0.16 (versionName 0.16.0, code 107)
- Archive contents are now browsed folder by folder: tap a folder to enter it,
  "Up" or the system Back button to go up, path shown at the top. Folders show
  file count and total size.
- "Extract folder" button extracts just the folder you are viewing (with its
  path), into the saved destination.

## 0.15 (versionName 0.15.0, code 106)
- Long jobs (extract, create, test, extract selected) run under a foreground
  service: they keep going with the screen off or the app in the background,
  with a progress notification and a Cancel button. A result notification
  appears if you were away. Android 13+ asks for notification permission once.
- Jobs no longer die if the screen is recreated (rotation etc.).
- Free-space check before extracting, with a warning dialog (Continue / Cancel).

## 0.14 (versionName 0.14.0, code 105)
- Real Settings page (gear icon, top right; system Back returns): theme,
  Advanced options switch, performance limits, All files access, about.
- Advanced options switch (off by default): when off, the Create card hides
  compression level, algorithm, solid, dictionary size and CPU threads, and
  creation uses the defaults. When on, they appear.
- The old dropdown settings menu is gone.

## 0.13 (versionName 0.13.0, code 104)
- Based on your edited MainActivity.kt (title, subtitle, footer kept; footer now says Rev 0.13).
- Performance row also on the Create card.
- Under a CPU limit, archive creation also uses fewer worker threads.

## 0.12.1 (code 103)
- Performance limits also appear as a collapsible row in the Extract card.

## 0.12 (versionName 0.12.0, versionCode 102)
- Settings menu > Performance: limit processor use (25-100%) and pause when the
  battery gets hot (36-50 °C, resumes 2 °C lower). Applies to extract, create,
  test and opening a single file. Both are off by default.
- Native progress timing counts the gap after the callback returns, so the
  limits measure real work.

## 0.11 (versionName 0.11.0, versionCode 101)
- With "Manage all files" on, extraction into a folder on internal storage writes
  straight to it: no copy, no extra cache space, faster. Otherwise unchanged.
- "Open destination folder" button after a successful extraction.
- The contents list shows file/folder counts and total unpacked size.

## 0.10 (versionName 0.10.0, versionCode 100)
- Wired in everything from 0.8: recent archives, archive tools (test integrity,
  search, extract selected, open/share one file), open archives from other apps.
- Create: TAR.GZ / TAR.XZ / TAR.BZ2, advanced 7z/ZIP options, Share button after creating.
- Settings menu (top right): theme (follow system / light / grey / AMOLED),
  "Manage all files" switch, version.
- Clears leftover temporary files at startup.

## 0.9.1  New formats shown in the Create menu
## 0.9    Grey and AMOLED themes
## 0.8    Engine: tar.* unwrap, test, partial extract, advanced create options
## 0.7    Create 7z / zip / tar
## 0.6    Extract into a per-archive subfolder
## 0.5    Remember the destination folder
## 0.4    Full C++ engine
## 0.3    C-SDK extract
