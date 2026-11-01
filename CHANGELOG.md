<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Changelog

**English** | [Español](CHANGELOG.es.md)

## Unreleased

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
