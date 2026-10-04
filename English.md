# ReadMax

An Android reader forked from [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3).

Keeps every feature of the original, plus full support for the **.nex format**.

---

## The .nex Format

`.nex` is an ebook format designed and implemented by **ZW-SYS**, first defined on 2026-10-03.

It is essentially a ZIP archive:

    mybook.nex
    ├── manifest.json      title, author, format version, author credit
    ├── book.json          chapter list
    ├── content/
    │   ├── ch1.html       chapter body (HTML)
    │   └── ch2.html
    ├── assets/
    │   ├── img/           images
    │   └── video/         videos
    └── style/main.css     styles

### Format Origin

- **Author**: ZW-SYS
- **First implementation**: commit `0d308f0` (2026-10-03)
- **Implementation files**: `NexConverter.kt`, `NexFile.kt`
- **Reference implementation**: https://github.com/ZW-SYS/legado-with-MD3-Max

This format is defined by ZW-SYS. Anyone is free to implement and use it, but please keep the credit.

---

## New Features

### Convert & Import

- **Import .nex** — supported in "Add Local" and "Smart Scan"
- **Convert to .nex** — Shelf menu → Convert to .nex, supports:
  - **EPUB**: reads real chapter titles from NCX / nav.xhtml, extracts images and videos
  - **DOCX**: splits by heading styles; if no headings, auto-detects "Chapter X", "第X章", "卷X", numeric numbering, etc.
  - **TXT**: auto-detects chapters

### .nex Management

- **Edit .nex** — change title, author, cover
- **Merge .nex** — combine two or more books into one; chapter titles get a book-name prefix, assets stay separate
- **Export to EPUB / TXT** — convert `.nex` to standard EPUB 3 or plain text, readable in other apps

### Reading Enhancements

- **Images** — images inside `.nex` render normally in the reader
- **Video** — chapters containing video show a ▶ button in the top-right corner; tap to play in the system player
- **Splash encouragement** — a random encouraging line on each launch
- **LAN sharing** — other devices on the same Wi-Fi can download your books via a browser

---

## How to Use LAN Sharing

1. Open ReadMax, go to **Settings → Advanced**, enable "Start Web Service on App Launch"
2. Note the Web port (default 1122, changeable)
3. On another device connected to the same Wi-Fi, open:

       http://your-phone-LAN-IP:1122/share

   e.g. `http://192.168.1.46:1122/share`
4. You will see the books in your library; tap "Download"

---

## Installation

1. Download the latest APK from the [Releases](../../releases) page
2. If a signature conflict occurs, uninstall the old version first

## Known Limitations

- No audio support (no audio is generated inside `.nex`)
- PDF conversion not supported yet
- Video plays in an external player, not embedded in the reading flow

---

## Terms of Use and Disclaimer

[Special Notice] Before downloading, installing, or using this software, please read and fully understand this agreement and disclaimer. By downloading, installing, or using this software, you are deemed to have read, understood, and accepted all of its contents.

### 1. Nature of the Software

This software is a user-configurable local web content browsing tool that provides web access, content parsing, text extraction, reading layout, and data management.

The software does not pre-install, bundle, or provide any third-party website content, data resources, or parsing rules by default.

The developer does not provide any content operation, storage, publishing, or distribution services.

Users may configure or import third-party rules based on their own needs to achieve personalized browsing and processing of publicly available web content.

### 2. User Conduct and Rules

Users may create, edit, import, or use parsing rules shared by third parties (hereinafter referred to as "rules").

Such rules are only used to define how web content is fetched, extracted, and displayed. Their source, legality, accuracy, and applicability are the sole responsibility of the user.

When users use rules to access third-party websites, the relevant network requests are initiated and received directly from the user's device to the target website. This software only provides local parsing and display capabilities and does not modify, edit, or redistribute third-party website content.

Users must comply with local laws and regulations, network security requirements, and the terms of service and copyright rules of the relevant websites. Users must not use this software to infringe intellectual property, illegally distribute content, obtain unauthorized data, damage network services, or engage in other illegal activities.

### 3. Third-Party Content and Communities

Any rule-sharing platform, forum, group, website, or other community established or maintained by a third party is an independently operated third-party platform and has no affiliation with the developer of this software.

The developer does not participate in the creation, publication, operation, maintenance, or distribution of third-party rules, content, or communities, and does not assume an active review obligation for such content.

Risks arising from users' use of third-party rules or access to third-party websites, including but not limited to copyright disputes, data security risks, and network access risks, shall be borne by the relevant parties in accordance with the law.

### 4. Privacy and Data

The main functions of this software run on the user's local device. It does not operate its own content servers for providing web content services.

This software does not actively collect, upload, or store the user's reading content, rule list, browsing history, or other personal privacy data.

To improve stability and compatibility, this software may integrate third-party analytics or crash reporting services (such as Firebase Crashlytics) to collect anonymized crash logs, performance information, and basic device information.

Certain network, storage, or sync permissions are only used to implement features the user actively enables, such as local backup, WebDAV sync, or cross-device data sync.

### 5. Intellectual Property Protection

The developer respects and protects the lawful rights of intellectual property owners and opposes any infringement of copyright, trademark, or other lawful rights.

Users are responsible for ensuring that their use of this software to obtain, process, or access content complies with applicable laws, regulations, and rights claims.

If a rights holder believes that certain third-party rules may infringe their rights, they may assert their rights against the actual hosting party of the content in accordance with the law.

Rights holders may also submit valid notices to the developer containing identity proof, ownership proof, specific rule information, and relevant explanations. The developer will take necessary measures against allegedly infringing rules within the scope of reasonable technical capability.

---

## Acknowledgements

- [legado](https://github.com/gedoor/legado) original author gedoor
- [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) author HapeLee