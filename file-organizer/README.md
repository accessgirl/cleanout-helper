# File Organizer (Android app)

Tidies up every file on your phone:

- **Sorts files into clear folders.** Photos by year and month, screenshots, videos, voice recordings, music by artist. Documents are sorted by topic: Bills & Receipts, Bank & Money, Taxes, Medical & Health, Insurance, Travel & Tickets, Work & Career, Legal & Contracts, IDs & Certificates, School, Manuals & Warranties, Home & Car and Recipes.
- **Renames files whose names don't say what they are.** It reads what's inside first:
  | Before | After |
  |---|---|
  | `IMG_20240512_143022.jpg` | `2024-05-12 14.30 Beach & Dog - Brighton.jpg` |
  | `download (3).pdf` | `Receipt - Walmart Supercenter (2024-05-12).pdf` |
  | `Screenshot_20240601-101010.png` | `2024-06-01 10.10 Screenshot - Your order has shipped.png` |
  | `doc_99812345.pptx` | `Quarterly Sales Review (2024-03-01).pptx` |
  | `invite.ics` | `Event - Dentist appointment (2024-07-04).ics` |

  Files that already have clear names, such as `Car insurance 2024.pdf`, keep them.
- **Finds duplicates.** Files that are exact copies (same content, even with different names) are found. One copy stays in its proper place. The extra copies go to **`Duplicates - Review`**, one numbered folder per set, so you can decide what to keep. **Nothing is ever deleted.**
- **Puts an index at the top of every folder.** It's a file called `000 INDEX - <folder>.txt`. It lists every file with its date, size, what it is, the first words inside it, and its old name, so you can find a document without opening them all. `000 MASTER INDEX - All Files.txt` lists everything.
- **Search.** Find a file by the words inside it, what's in a photo, where it was taken, or its old name. Tap a result to open it.
- **Safe.** You see the full plan (every move and rename) before anything happens. **Undo** puts every file back where it was, with its old name. Everything runs on the phone and nothing is uploaded.

Everything ends up in one folder, **`Organized Files`**, at the top of your phone's storage.

## Installing it on your phone

> This app is for **Android** phones. iPhones don't allow any app to reach all the files on the phone, so this kind of app can't exist there.

1. On your phone, open **https://github.com/accessgirl/cleanout-helper/releases/tag/file-organizer-latest** and tap **FileOrganizer.apk**.
   (The download appears once this code is on the `main` branch. Until then, you can get the APK from the **Actions** tab: open the latest *Build File Organizer* run and download **FileOrganizer-apk**.)
2. Open the downloaded file. Android will say installing apps from this source isn't allowed: tap **Settings**, turn on **Allow from this source**, go back and tap **Install**.
3. Open **File Organizer** and tap **Allow access**. Turn on **Allow access to manage all files**, then go back.

## Using it

1. **Choose folders.** Normal folders (Download, Documents, DCIM, Pictures, Movies, Music, Recordings …) are ticked. Folders made by apps (WhatsApp, Telegram …) start unticked, because moving their files can make them disappear inside that app. You can tick them if you want.
2. **Choose options:**
   - *Give unclear files clear names*
   - *Read the words in pictures and scanned PDFs* (slower, but it names screenshots and scans and lets you search inside them)
   - *Describe photos and videos* (slower: names photos by what's in them and the town they were taken in)
   - *Find duplicate copies*
3. Tap **Scan and show me the plan.** On a full phone this can take a while. It keeps going with the screen off, and progress shows in a notification.
4. Look through the plan (*All*, *Renamed* and *Duplicates* tabs), then tap **Organize now**.
5. Afterwards, open your Files app and go to **Organized Files**. To have the `000 INDEX` file show first, sort by **Name**. It also shows first when you sort by **newest**.

You can run it again whenever you like. Files already in `Organized Files` stay where they are, new files are added, new copies of files you already have are flagged as duplicates, and every index is rebuilt.

### Good to know

- Photos you've moved still show in Google Photos and your gallery, under the `Organized Files` folders. New photos keep going to the Camera folder as usual.
- Android's own folders (`Android`, ringtones, alarms, notification sounds) and hidden files are never touched.
- Place names come from the phone's built-in location lookup, which may need an internet connection. Without one, photos are named by date and what's in them.
- Text reading works best for English and other languages that use the Latin alphabet.
- Only the phone's main storage is organized, not an SD card.

## For developers

```
file-organizer/
  core/   the organizing logic in plain Kotlin: file types, clear-name rules, topic detection,
          readers for Word/Excel/PowerPoint/OpenDocument/text/email/contacts/calendar/eBooks/zips,
          duplicate finder, planner, mover with undo log, catalog and search, index writer.
          Runs and is tested on any computer.
  app/    the Android app: screens, background work, and readers that need the phone
          (photo details and GPS, ML Kit text reading and image labels, PDFs with PdfBox,
          music/video details, app installer names)
```

```bash
cd file-organizer
./gradlew :core:test                    # tests (no Android tools needed)
./gradlew :app:assembleRelease          # the APK (needs the Android SDK)
# Run the organizer on a folder on a computer (e.g. a copy of a phone's storage):
./gradlew :core:run --args="/path/to/folder --dry-run"
```

GitHub Actions (`.github/workflows/android.yml`) runs the tests and builds the APK on every change. On `main` it also publishes the APK as the `file-organizer-latest` release. The APK is signed with `app/sideload.keystore`, a fixed key that exists only so each new version installs over the last one. It is not a secret.
