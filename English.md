# Legado Max

[中文](README.md) | [**English**](English.md)

An Android reader forked from [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3).

It keeps all upstream features and adds full support for the **.nex format**.

---

## The .nex Format

`.nex` is an ebook format designed and implemented by **ZW-SYS**, first defined on 2026-10-03.

It is essentially a ZIP archive:

    mybook.nex
    ├── manifest.json      title, author, format version, spec author
    ├── book.json          chapter list
    ├── content/
    │   ├── ch1.html       chapter body (HTML)
    │   └── ch2.html
    ├── assets/
    │   ├── img/           images
    │   └── video/         videos
    └── style/main.css     styles

### Origin

- **Designer**: ZW-SYS
- **First implemented**: this repository, commit `0d308f0` (2026-10-03)
- **Implementation code**: `NexConverter.kt`, `NexFile.kt`
- **Reference implementation**: https://github.com/ZW-SYS/legado-with-MD3-Max

This format is defined by ZW-SYS. Anyone may freely implement and use it, but please keep the attribution.

---

## New Features

### Conversion and Import

- **Import .nex** — supported by both "Add local" and "Smart scan" in the bookshelf
- **Convert to .nex** — bookshelf overflow menu → Convert to .nex, supports:
  - **EPUB**: reads real TOC titles from NCX / nav.xhtml, extracts images and videos
  - **DOCX**: prefers heading styles for chapters; when absent, recognizes chapter titles by text like "Chapter X", "卷X", numeric indices
  - **TXT**: auto-detects chapters

### .nex Management

- **Edit .nex (WebView editor)** — a built-in web-based rich text editor that supports:
  - Write chapter content; bold / italic / underline / strikethrough
  - Headings (H1 / H2 / H3), body text, blockquote
  - Font size, font family (Song / Kai / Hei / Source Serif / Source Sans)
  - Text color, highlight color
  - Insert images (picked from the system gallery, packed into the .nex automatically)
  - Insert videos (picked from the system file picker, packed into the .nex automatically)
  - Emoji panel
  - Unordered list, ordered list, center, left align, horizontal rule
  - Edit / preview toggle
  - Auto-split chapters by titles such as "第X章"
  - Live stats at the bottom: character count, chapter count, image count
  - Create a new .nex or edit an existing one
- **Edit title / author / cover** — legacy editor for quick metadata changes
- **Merge .nex** — merge two or more books into one; chapter titles get prefixed with the book name; no asset conflicts
- **Export EPUB / TXT** — convert a `.nex` to standard EPUB 3 or plain text, readable by other apps

### Reading Enhancements

- **Image display** — images inside `.nex` render correctly in the reader
- **Video playback** — chapters containing videos show a ▶ button at the top-right of the reader; tapping it opens the system player
- **Welcome message** — a random encouraging line is shown on every app launch
- **LAN sharing** — devices on the same Wi-Fi can browse and download your books via a browser

### UI and Updates

- **About page** — all links point to this repository, ZW-SYS/legado-with-MD3-Max
- **Update check** — reads new versions from this repository's Releases

---

## How to Use LAN Sharing

1. Open Legado Max on the phone, go to **Settings → Other**, enable "Start Web Service When App Opens".
2. Note the Web port (default 1122, configurable).
3. On another device on the same Wi-Fi, open in a browser:

       http://<phone-lan-ip>:1122/share

   For example, `http://192.168.1.46:1122/share`.
4. You will see the library; tap "Download" on any book.

---

## Installation

1. Download the latest APK from the [Releases](../../releases) page.
2. If you get a signature conflict, uninstall the old version first.

## Known Limitations

- Audio is not supported (and `.nex` does not generate audio).
- PDF conversion is not supported yet.
- Videos are played in an external player, not embedded inline in the text flow.

---

## User Agreement and Disclaimer

[Important Notice] Before downloading, installing, or using this software, please read and fully understand this agreement and disclaimer. By downloading, installing, or using this software, you acknowledge that you have read, understood, and accepted all of its contents.

### 1. Nature of the Software

This software is a user-configurable local tool for browsing web content. It provides technical functions such as web access, content parsing, text extraction, reading layout, and data management.

By default, this software does not bundle, embed, or provide any third-party website content, data resources, or parsing rules.

The developer does not provide any content operation, content storage, content publishing, or content distribution service.

Users may configure or import third-party rules at their own discretion to enable personalized browsing and processing of publicly available web content.

### 2. User Behavior and Rules

Users may create, edit, import, or use parsing rules shared by third parties (hereinafter referred to as "rules").

Rules are only used to define how web content is fetched, extracted, and displayed. Their source, legality, accuracy, and applicability must be judged and borne by the user.

When a user uses rules to access a third-party website, the network request is initiated directly from the user's device to the target website. This software only provides local parsing and display capability, and does not modify, edit, or redistribute third-party website content.

Users must comply with local laws and regulations, cybersecurity requirements, and the terms of service and copyright norms of the relevant websites. Users must not use this software to infringe intellectual property rights, illegally distribute content, obtain data without authorization, disrupt network services, or engage in other unlawful activities.

### 3. Third-Party Content and Communities

Any rule-sharing platforms, forums, chat groups, websites, or other communities established or maintained by third parties are independent third-party platforms and are not affiliated with the developer of this software.

The developer does not participate in the creation, publishing, operation, maintenance, or distribution of third-party rules, content, or communities, and does not assume an active obligation to review such content.

Any risk arising from the use of third-party rules or access to third-party websites — including but not limited to copyright disputes, data security risks, network access risks, or other legal risks — shall be borne by the relevant parties in accordance with the law.

### 4. Privacy and Data

The main functions of this software run on the user's local device. There is no self-hosted content server for serving web content.

This software does not actively collect, upload, or store users' reading content, rule lists, browsing history, or other personal privacy data.

To improve stability and compatibility, this software may integrate third-party analytics or crash reporting services (such as Firebase Crashlytics) to collect anonymized crash logs, performance information, and basic device information.

Some network, storage, or sync permissions are only used to implement functions the user has actively enabled, such as local backup, WebDAV sync, or cross-device data sync.

### 5. Intellectual Property Protection

The developer respects and protects the legitimate rights and interests of intellectual property owners, and opposes any infringement of copyright, trademark rights, or other legitimate rights.

Users must ensure that their use of this software to obtain, process, or access content complies with applicable laws and regulations and with the relevant rights claims.

If a rights holder believes that certain third-party rules are suspected of infringement, they may assert their rights against the actual hosting party of the relevant content in accordance with the law.

Rights holders may also submit a valid notice to the developer containing proof of identity, proof of ownership, specific rule information, and a related statement. The developer will take necessary measures against suspected infringing rules within the scope of reasonable technical capability.

---

## Credits

- [legado](https://github.com/gedoor/legado) original author gedoor
- [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) author HapeLee

---

[中文](README.md) | [**English**](English.md)