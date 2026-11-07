<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Changelog

**English** | [Español](CHANGELOG.es.md)

## 1.3.3

### Changed
- New app icons. The default (Liquid) icon has a sober graphite background instead of the cyan and pink glow, and its accent is now the same red across the suite. The Material icon is a solid color with a smaller white drawing, without the light-blue frame.
- The person in the default icon no longer reaches the edge of the card.
- Settings: "Accent color" is now just "Accent".

## 1.3.2

### Fixed
- Merging contacts from an address book did not delete the merged ones from the server (Samsung): now they are deleted right away, and the merge window shows a red warning.
- Deleting many contacts from an address book at once goes through the mass-deletion brake again: the server is not touched until it is confirmed on the server screen. If cancelled, they download again.

## 1.3.1

### Fixed
- Deleting a contact from an address book did not delete it from the server on some phones (Samsung): now it is deleted right away, and without a connection it waits until the connection is back.

### Changed
- Deleting a contact that is on the server shows a red warning that it is deleted there too (and from other devices that sync it) and that restoring it uploads it again.
- In the trash, contacts that came from the server say so; restoring them says they are uploaded again, and deleting them forever warns that it is the last copy.

## 1.3.0

### Changed
- Anything saved to a server address book (a new contact, an edit, an import, a deletion) syncs right away, without tapping "Sync now".
- Without a connection, the change stays on the phone and a window says it will sync on its own once the connection is back.
- Background sync and the certificate verification mode are dropdowns that open a window, and there is a new "Every 15 min" option.
- Each address book shows its server color (for example, Radicale's) as a dot before its name.

## 1.2.1

### Changed
- When importing a .vcf and in the trash, each contact shows its photo if it has one.

## 1.2.0

### Changed
- All pop-up windows now look the same: the long-press menus and the text prompts use the same centered glass card as the dialogs, with the buttons stacked and a Cancel button.
- Dropdowns that open a pop-up window: default account in Settings, where to save when importing a .vcf (also when opened from another app), and which contacts, label and format when exporting.

## 1.1.4

### Changes
- New Samsung and iPhone icons. The Motorola icon is removed: anyone using it moves to Liquid.
- Trash: the empty button is at the very top.

## 1.1.3

### Changes
- The Undo notice can be dismissed by sliding it in any direction.

## 1.1.2

### Changes
- Call opens the Liquid Phone keypad with the number typed in, if installed. Otherwise, whichever dialer there is.
- Faster tab change when tapping the bar.

## 1.1.1

### Changed
- Multi-select: the top shows the count with All; at the bottom the pill changes to the options and the new contact button goes away. It follows the Liquid Glass or Solid style and closes when nothing is left selected.
- "Save in" is chosen in a window. The server is always listed: if no address book is on yet, the row takes you to turn one on.
- Messaging app accounts such as WhatsApp or Signal are no longer offered for saving.
- The photo is framed 1:1 before saving and opens in a window over the current screen.

### Fixed
- On the plain background in light theme, the tab bar and selection bars now show the liquid glass effect, as they do in dark theme.
- Photo quality: the detail screen and the list showed the stretched 96 px thumbnail; they now use the large photo. It is saved with less compression.

## 1.1.0

### New
- Long-pressing a contact opens its options: call, message, favorite, edit, label, share, select and delete.
- Long-pressing the photo starts multi-select, with favorite, label, share, merge and delete.
- Tapping the photo opens it full screen (also from the detail screen).
- Trash: what you delete from the app stays for 30 days and can be restored. Deleting shows Undo.
- Server nickname, shown instead of the account's technical name.

### Changed
- "Save in" when creating a contact is an alphabetical dropdown, with the server's address books and their nickname.
- The CardDAV server comes first in Settings.
- Read-only address books and accounts that don't upload changes are no longer offered for saving.

## 1.0.2

### Changed
- App icons: the person's body is now whole and stays behind the circle or card edge, instead of a half circle with a flat base.

## 1.0.1

### New
- Contact list with search (ignores accents and ñ, also by number in any format), Spanish alphabetical order, side index and favorites.
- Contact details with call, message, email, map and website actions through the standard Android intents.
- Create, edit and delete contacts: several numbers, emails and addresses, birthdays and other dates (with or without year), notes, websites, labels and photo (with the system photo picker).
- Labels: create, rename and delete.
- Duplicates by confidence (same name, or a shared number or email with different names) with a merge preview where any contact can be left out; nothing is merged without confirmation.
- Default country (from the SIM the first time) and default area code, with a notice in the list when numbers lack an area code.
- Merging can be undone for 10 seconds, with an optional .vcf backup first, also when merging from an import; contacts with cards in several accounts show the account of each card when editing.
- Import .vcf files (vCard 2.1, 3.0, 4.0): preview, choice of account, contacts you already have are skipped or merged only if you ask. Size, count and photo limits; a broken file gives a clear message. Photos given as links are never downloaded.
- Export and share .vcf: all, selected or by label, with or without photos, in vCard 3.0 or 4.0. Sharing uses a temporary permission.
- Other apps can ask to view, add, edit or pick a contact and open a .vcf. Adding and editing only fill in the form: nothing is saved until you tap save. Picking returns only what you choose.
- Optional CardDAV server, with its own client: one Android account per address book, first sync that only downloads (with a summary and a backup), conflicts you resolve yourself, mass-delete protection in both directions, certificate fingerprint or your own CA, http only to local network or VPN with a warning. Without a server there are no connections.
- Fields the app doesn't show go back to the server as they came when a contact is edited.
- Appearance settings shared with the rest of the suite, and five app icons.
