// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.vcard

import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.EventKind
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.model.PostalAddress
import dev.liquidcontacts.core.text.Clean
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.io.text.VCardReader
import ezvcard.io.text.VCardWriter
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.ImageType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import ezvcard.property.Categories
import ezvcard.property.DateOrTimeProperty
import ezvcard.property.FormattedName
import ezvcard.property.Nickname
import ezvcard.property.Organization
import ezvcard.property.Note
import ezvcard.property.Photo
import ezvcard.property.Revision
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Uid
import ezvcard.property.Url
import ezvcard.property.VCardProperty
import ezvcard.util.PartialDate
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.io.StringWriter
import java.io.Writer
import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.Temporal

enum class VCardFormat { V3, V4 }

/** Límites de importación: un archivo enorme o con miles de fotos no puede colgar la app. */
data class VCardLimits(
    val maxBytes: Int = 20 * 1024 * 1024,
    val maxContacts: Int = 5000,
    /** Fotos más grandes se descartan (el contacto se importa igual, sin foto). */
    val maxPhotoBytes: Int = 1024 * 1024,
)

sealed interface VCardImport {
    /**
     * Lo que se pudo leer. [skipped]: tarjetas vacías o ilegibles que se saltearon;
     * [photosDropped]: fotos descartadas por tamaño o por ser un enlace (nunca se descargan);
     * [truncated]: el archivo tenía más de [VCardLimits.maxContacts] contactos.
     */
    data class Ok(val contacts: List<Contact>, val skipped: Int, val photosDropped: Int, val truncated: Boolean) : VCardImport

    data class TooLarge(val bytes: Long) : VCardImport

    /** No es un vCard o está tan roto que no se pudo leer ninguna tarjeta. */
    data object Invalid : VCardImport
}

/**
 * Lectura y escritura de vCard 2.1, 3.0 y 4.0 con ez-vcard, que resuelve líneas partidas,
 * juegos de caracteres y quoted-printable. Sin red: una foto que viene como enlace
 * (PHOTO;VALUE=uri:https://...) se descarta, nunca se descarga.
 */
object VCards {

    fun read(bytes: ByteArray, limits: VCardLimits = VCardLimits()): VCardImport {
        if (bytes.size > limits.maxBytes) return VCardImport.TooLarge(bytes.size.toLong())
        val out = ArrayList<Contact>()
        var skipped = 0
        var photosDropped = 0
        var truncated = false
        var sawAny = false
        try {
            VCardReader(decode(bytes)).use { reader ->
                while (true) {
                    val card = try {
                        reader.readNext() ?: break
                    } catch (e: Exception) {
                        // Una tarjeta rota no corta el resto.
                        skipped++
                        continue
                    }
                    sawAny = true
                    if (out.size >= limits.maxContacts) {
                        truncated = true
                        break
                    }
                    val (contact, dropped) = toContact(card, limits)
                    photosDropped += dropped
                    if (contact == null) skipped++ else out += contact
                }
            }
        } catch (e: Exception) {
            if (!sawAny) return VCardImport.Invalid
        }
        if (!sawAny) return VCardImport.Invalid
        return VCardImport.Ok(out, skipped, photosDropped, truncated)
    }

    /**
     * Texto del archivo: UTF-8 si lo es (sin la marca BOM); si no, Windows-1252, que incluye a
     * Latin-1 y es lo que exportaban muchos celulares viejos en vCard 2.1. Los valores en
     * quoted-printable con su propio CHARSET los decodifica después ez-vcard.
     */
    internal fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("\uFEFF")
    }

    /** vCard de texto con todos los contactos. Sin PRODID (no identifica a la app). */
    fun write(contacts: List<Contact>, format: VCardFormat = VCardFormat.V3, includePhoto: Boolean = true): String =
        StringWriter().also { write(it, contacts.asSequence(), format, includePhoto) }.toString()

    /** Igual, de a uno: una agenda grande con fotos no se arma entera en memoria. */
    fun write(out: Writer, contacts: Sequence<Contact>, format: VCardFormat = VCardFormat.V3, includePhoto: Boolean = true) {
        val version = if (format == VCardFormat.V4) VCardVersion.V4_0 else VCardVersion.V3_0
        // VCardWriter directo: la fachada Ezvcard arrastra clases de freemarker, que no se incluye.
        val w = VCardWriter(out, version)
        w.isAddProdId = false
        contacts.forEach { w.write(toVCard(it, version, includePhoto)) }
        w.flush()
    }

    // ---------------------------------------------------------------- ida y vuelta con el servidor

    /** El contacto de una vCard de una sola tarjeta (por ejemplo, del servidor). Null si no se entiende. */
    fun contactOf(text: String): Contact? = parseOne(text)?.let { toContact(it, VCardLimits()).first }

    fun uidOf(text: String): String? = parseOne(text)?.uid?.value?.takeIf { it.isNotBlank() }

    /**
     * La vCard sin las fotos que trae adentro, para guardarla como referencia sin ocupar lugar.
     * Una foto que viene como enlace se conserva (se vuelve a subir tal cual, nunca se descarga).
     */
    fun withoutEmbeddedPhoto(text: String): String? {
        val card = parseOne(text) ?: return null
        card.photos.filter { it.data != null }.forEach { card.removeProperty(it) }
        return writeCard(card)
    }

    /**
     * La vCard para subir después de editar: parte de [original] (la del servidor) y reescribe solo
     * lo que cambió. Lo que la app no modela (propiedades X-, IMPP, RELATED, GEO, un ORG de varios
     * niveles, parámetros...) vuelve al servidor tal como vino, igual que lo modelado que no se tocó.
     */
    fun update(original: String?, c: Contact, uid: String): String {
        val card = original?.let(::parseOne) ?: VCard(VCardVersion.V3_0)
        val version = if (card.version == VCardVersion.V4_0) VCardVersion.V4_0 else VCardVersion.V3_0
        val before = toContact(card, VCardLimits()).first ?: Contact()
        val firstFree = (card.properties.mapNotNull { Regex("(?i)^item(\\d+)$").find(it.group.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val fresh = toVCard(c, version, includePhoto = true, firstItem = firstFree)

        fun replace(vararg types: Class<out VCardProperty>, extended: List<String> = emptyList()) {
            // Las etiquetas propias de lo que se reemplaza se van con ello.
            val old = card.properties.filter { p -> types.any { it.isInstance(p) } } + extended.flatMap { card.getExtendedProperties(it) }
            val groups = old.mapNotNull { it.group?.lowercase() }.toSet()
            old.forEach { card.removeProperty(it) }
            card.getExtendedProperties("X-ABLABEL").filter { it.group?.lowercase() in groups }.toList().forEach { card.removeProperty(it) }
            val added = fresh.properties.filter { p -> types.any { it.isInstance(p) } } + extended.flatMap { fresh.getExtendedProperties(it) }
            added.forEach { card.addProperty(it) }
            val newGroups = added.mapNotNull { it.group?.lowercase() }.toSet()
            fresh.getExtendedProperties("X-ABLABEL").filter { it.group?.lowercase() in newGroups }.forEach { card.addProperty(it) }
        }
        fun t(s: String?) = s?.trim().orEmpty()
        if (before.name.shown != c.name.shown) replace(FormattedName::class.java, StructuredName::class.java)
        if (t(before.nickname) != t(c.nickname)) replace(Nickname::class.java)
        if (t(before.organization) != t(c.organization)) replace(Organization::class.java)
        if (t(before.jobTitle) != t(c.jobTitle)) replace(Title::class.java)
        if (before.phones.map { Triple(it.number, it.kind, it.label) } != c.phones.map { Triple(it.number, it.kind, it.label) }) replace(Telephone::class.java)
        if (before.emails.map { Triple(it.address.lowercase(), it.kind, it.label) } != c.emails.map { Triple(it.address.lowercase(), it.kind, it.label) }) replace(ezvcard.property.Email::class.java)
        if (before.addresses.map { Triple(it.formatted, it.kind, it.label) } != c.addresses.map { Triple(it.formatted, it.kind, it.label) }) replace(Address::class.java)
        if (before.events.toSet() != c.events.toSet()) replace(Birthday::class.java, Anniversary::class.java, extended = listOf("X-ABDATE"))
        if (before.websites != c.websites) replace(Url::class.java)
        if (t(before.note) != t(c.note)) replace(Note::class.java)
        if (before.groups != c.groups) replace(Categories::class.java)
        // La foto no se guarda en la referencia: si hay en el celu, va esa; un enlace queda si no hay.
        card.photos.filter { it.data != null || c.photo != null }.toList().forEach { card.removeProperty(it) }
        fresh.photos.forEach { card.addProperty(it) }
        if (card.uid == null) card.uid = Uid(uid)
        card.revision = Revision(java.time.Instant.now())
        card.version = version
        return writeCard(card)
    }

    private fun parseOne(text: String): VCard? = runCatching { VCardReader(text).use { it.readNext() } }.getOrNull()

    private fun writeCard(card: VCard): String {
        val out = StringWriter()
        val version = if (card.version == VCardVersion.V4_0) VCardVersion.V4_0 else VCardVersion.V3_0
        val w = VCardWriter(out, version)
        w.isAddProdId = false
        w.write(card)
        w.flush()
        return out.toString()
    }

    // ---------------------------------------------------------------- vCard → Contact

    private fun toContact(card: VCard, limits: VCardLimits): Pair<Contact?, Int> {
        val n: StructuredName? = card.structuredName
        val formatted = card.formattedName?.value?.trim().orEmpty()
        val name = Name(
            display = formatted.clean().orEmpty(),
            given = n?.given?.clean(),
            family = n?.family?.clean(),
            middle = n?.additionalNames?.joinToString(" ")?.clean(),
            prefix = n?.prefixes?.joinToString(" ")?.clean(),
            suffix = n?.suffixes?.joinToString(" ")?.clean(),
        )
        val phones = card.telephoneNumbers.mapNotNull { t ->
            val number = Clean.phone(t.text ?: t.uri?.number) ?: return@mapNotNull null
            val custom = customLabel(card, t.group)
            Phone(number, if (custom != null) LabelKind.CUSTOM else phoneKind(t.types), custom, t.types.contains(TelephoneType.PREF) || t.pref == 1)
        }
        val emails = card.emails.mapNotNull { e ->
            val address = Clean.email(e.value) ?: return@mapNotNull null
            val custom = customLabel(card, e.group)
            Email(address, if (custom != null) LabelKind.CUSTOM else emailKind(e.types), custom, e.types.contains(EmailType.PREF) || e.pref == 1)
        }
        val addresses = card.addresses.mapNotNull { a ->
            val custom = customLabel(card, a.group)
            toAddress(a).let { if (custom != null) it.copy(kind = LabelKind.CUSTOM, label = custom) else it }.takeIf { it.formatted.isNotBlank() }
        }
        val events = buildList {
            card.birthdays.forEach { b -> toEvent(b, EventKind.BIRTHDAY)?.let(::add) }
            card.anniversaries.forEach { a -> toEvent(a, EventKind.ANNIVERSARY)?.let(::add) }
            // Fechas con nombre al estilo Apple: itemN.X-ABDATE + itemN.X-ABLabel.
            card.getExtendedProperties("X-ABDATE").forEach { p ->
                val label = p.group?.let { g -> card.getExtendedProperties("X-ABLABEL").firstOrNull { it.group.equals(g, ignoreCase = true) }?.value }
                    ?.removePrefix("_\$!<")?.removeSuffix(">!\$_")
                parseDate(p.value)?.let { (m, d, y) ->
                    // Así escriben el aniversario en 3.0 DAVx5, Apple y esta misma app: es un aniversario, no una etiqueta propia.
                    runCatching {
                        when {
                            label.equals("Anniversary", true) -> ContactEvent(m, d, y, EventKind.ANNIVERSARY)
                            label.isNullOrBlank() || label.equals("Other", true) -> ContactEvent(m, d, y, EventKind.OTHER, label)
                            else -> ContactEvent(m, d, y, EventKind.CUSTOM, label)
                        }
                    }.getOrNull()?.let(::add)
                }
            }
        }.distinct()
        var dropped = 0
        val photo = card.photos.firstNotNullOfOrNull { p: Photo ->
            val data = p.data
            when {
                data == null -> {
                    dropped++ // Enlace a una foto: nunca se descarga.
                    null
                }
                data.size > limits.maxPhotoBytes -> {
                    dropped++
                    null
                }
                else -> data
            }
        }
        val contact = Contact(
            name = name,
            nickname = card.nickname?.values?.firstOrNull()?.clean(),
            organization = card.organization?.values?.firstOrNull()?.clean(),
            jobTitle = card.titles.firstOrNull()?.value?.clean(),
            phones = phones.take(MAX_ITEMS),
            emails = emails.take(MAX_ITEMS),
            addresses = addresses.take(MAX_ITEMS),
            events = events.take(MAX_ITEMS),
            websites = card.urls.mapNotNull { Clean.url(it.value) }.take(MAX_ITEMS),
            note = Clean.multiline(card.notes.mapNotNull { it.value }.joinToString("\n\n")),
            groups = card.categoriesList.flatMap { it.values }.mapNotNull { it.clean() }.take(MAX_ITEMS).toSet(),
            photo = photo,
        )
        // Una tarjeta sin nada que la identifique no se importa.
        val empty = contact.displayName.isBlank() && photo == null
        return (if (empty) null else contact) to dropped
    }

    /** Etiqueta propia al estilo Apple (itemN.TEL + itemN.X-ABLabel); las predefinidas _$!<Home>!$_ no cuentan. */
    private fun customLabel(card: VCard, group: String?): String? {
        if (group == null) return null
        val text = card.getExtendedProperties("X-ABLABEL").firstOrNull { it.group.equals(group, ignoreCase = true) }?.value ?: return null
        return if (text.startsWith("_\$!<")) null else Clean.line(text)
    }

    private fun toAddress(a: Address): PostalAddress = PostalAddress(
        street = listOfNotNull(a.poBox, a.extendedAddress, a.streetAddress).filter { it.isNotBlank() }.joinToString(", ").clean(),
        city = a.locality?.clean(),
        region = a.region?.clean(),
        postcode = a.postalCode?.clean(),
        country = a.country?.clean(),
        kind = when {
            a.types.contains(AddressType.WORK) -> LabelKind.WORK
            a.types.contains(AddressType.HOME) -> LabelKind.HOME
            else -> LabelKind.OTHER
        },
    )

    private fun toEvent(p: DateOrTimeProperty, kind: EventKind): ContactEvent? {
        p.date?.let { t -> return fromTemporal(t, kind, omitYear = p.getParameter("X-APPLE-OMIT-YEAR") != null) }
        p.partialDate?.let { pd ->
            val month = pd.month ?: return null
            val day = pd.date ?: return null
            return runCatching { ContactEvent(month, day, pd.year, kind) }.getOrNull()
        }
        // Texto libre ("--0314", "14/03"): se intenta leer; si no, se descarta.
        return parseDate(p.text)?.let { (m, d, y) -> runCatching { ContactEvent(m, d, y, kind) }.getOrNull() }
    }

    private fun fromTemporal(t: Temporal, kind: EventKind, omitYear: Boolean): ContactEvent? {
        if (!t.isSupported(ChronoField.MONTH_OF_YEAR) || !t.isSupported(ChronoField.DAY_OF_MONTH)) return null
        val year = if (omitYear || !t.isSupported(ChronoField.YEAR)) null else t.get(ChronoField.YEAR)
        return runCatching { ContactEvent(t.get(ChronoField.MONTH_OF_YEAR), t.get(ChronoField.DAY_OF_MONTH), year, kind) }.getOrNull()
    }

    /** "1990-03-14", "19900314", "--0314", "--03-14" → (mes, día, año o null). */
    internal fun parseDate(text: String?): Triple<Int, Int, Int?>? {
        val t = text?.trim()?.substringBefore('T') ?: return null
        Regex("^--(\\d{2})-?(\\d{2})$").find(t)?.let { return Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        Regex("^(\\d{4})-?(\\d{2})-?(\\d{2})$").find(t)?.let {
            val y = it.groupValues[1].toInt()
            // 1604 es el año que usa Apple para "sin año".
            return Triple(it.groupValues[2].toInt(), it.groupValues[3].toInt(), y.takeIf { y != 1604 })
        }
        return null
    }

    private fun phoneKind(types: List<TelephoneType>): LabelKind = when {
        types.contains(TelephoneType.FAX) && types.contains(TelephoneType.WORK) -> LabelKind.FAX_WORK
        types.contains(TelephoneType.FAX) -> LabelKind.FAX_HOME
        types.contains(TelephoneType.PAGER) -> LabelKind.PAGER
        types.contains(TelephoneType.CELL) -> LabelKind.MOBILE
        types.contains(TelephoneType.WORK) -> LabelKind.WORK
        types.contains(TelephoneType.HOME) -> LabelKind.HOME
        types.any { it.value.equals("MAIN", ignoreCase = true) } -> LabelKind.MAIN
        types.isEmpty() || types.all { it == TelephoneType.VOICE || it == TelephoneType.PREF } -> LabelKind.MOBILE
        else -> LabelKind.OTHER
    }

    private fun emailKind(types: List<EmailType>): LabelKind = when {
        types.contains(EmailType.WORK) -> LabelKind.WORK
        types.contains(EmailType.HOME) -> LabelKind.HOME
        else -> LabelKind.OTHER
    }

    // ---------------------------------------------------------------- Contact → vCard

    private fun toVCard(c: Contact, version: VCardVersion, includePhoto: Boolean, firstItem: Int = 1): VCard = VCard().apply {
        var item = firstItem - 1
        fun labelGroup(label: String?): String? {
            if (label.isNullOrBlank()) return null
            val group = "item${++item}"
            addExtendedProperty("X-ABLabel", label).group = group
            return group
        }
        val n = c.name
        setFormattedName(c.displayName)
        structuredName = StructuredName().apply {
            given = n.given ?: n.display.takeIf { n.family.isNullOrBlank() }
            family = n.family
            n.middle?.let { additionalNames.add(it) }
            n.prefix?.let { prefixes.add(it) }
            n.suffix?.let { suffixes.add(it) }
        }
        c.nickname?.let { nickname = Nickname().apply { values.add(it) } }
        if (!c.organization.isNullOrBlank()) organization = Organization().apply { values.add(c.organization) }
        c.jobTitle?.let { addTitle(it) }
        c.phones.forEach { p ->
            addTelephoneNumber(Telephone(p.number).apply {
                when (p.kind) {
                    LabelKind.MOBILE -> types.add(TelephoneType.CELL)
                    LabelKind.HOME -> types.add(TelephoneType.HOME)
                    LabelKind.WORK -> types.add(TelephoneType.WORK)
                    LabelKind.FAX_WORK -> { types.add(TelephoneType.FAX); types.add(TelephoneType.WORK) }
                    LabelKind.FAX_HOME -> { types.add(TelephoneType.FAX); types.add(TelephoneType.HOME) }
                    LabelKind.PAGER -> types.add(TelephoneType.PAGER)
                    LabelKind.MAIN -> types.add(TelephoneType.get("MAIN"))
                    LabelKind.CUSTOM -> {
                        types.add(TelephoneType.VOICE)
                        group = labelGroup(p.label)
                    }
                    else -> types.add(TelephoneType.VOICE)
                }
                if (p.primary) {
                    if (version == VCardVersion.V4_0) pref = 1 else types.add(TelephoneType.PREF)
                }
            })
        }
        c.emails.forEach { e ->
            val mail = addEmail(e.address, *listOfNotNull(
                when (e.kind) {
                    LabelKind.HOME -> EmailType.HOME
                    LabelKind.WORK -> EmailType.WORK
                    else -> null
                },
            ).toTypedArray())
            if (e.kind == LabelKind.CUSTOM) mail.group = labelGroup(e.label)
        }
        c.addresses.forEach { a ->
            addAddress(Address().apply {
                streetAddress = a.street
                locality = a.city
                region = a.region
                postalCode = a.postcode
                country = a.country
                when (a.kind) {
                    LabelKind.HOME -> types.add(AddressType.HOME)
                    LabelKind.WORK -> types.add(AddressType.WORK)
                    LabelKind.CUSTOM -> group = labelGroup(a.label)
                    else -> Unit
                }
            })
        }
        c.events.forEach { e ->
            when (e.kind) {
                EventKind.BIRTHDAY -> birthdays.add(dateProperty(e, version, ::Birthday, ::Birthday))
                EventKind.ANNIVERSARY -> if (version == VCardVersion.V4_0) {
                    anniversaries.add(dateProperty(e, version, ::Anniversary, ::Anniversary))
                } else {
                    appleDate(++item, e, "_\$!<Anniversary>!\$_")
                }
                else -> appleDate(++item, e, e.label ?: "Other")
            }
        }
        c.websites.forEach { addUrl(it) }
        c.note?.let { addNote(it) }
        if (c.groups.isNotEmpty()) addCategories(Categories().apply { values.addAll(c.groups.sorted()) })
        if (includePhoto) c.photo?.let { addPhoto(Photo(it, if (it.isPng()) ImageType.PNG else ImageType.JPEG)) }
    }

    /** Fecha con año, o sin año: "--MMDD" en 4.0 y, en 3.0, el año 1604 que entienden Apple y Android. */
    private fun <T : DateOrTimeProperty> dateProperty(e: ContactEvent, version: VCardVersion, full: (Temporal) -> T, partial: (PartialDate) -> T): T =
        when {
            e.year != null -> full(LocalDate.of(e.year, e.month, e.day))
            version == VCardVersion.V4_0 -> partial(PartialDate.builder().month(e.month).date(e.day).build())
            else -> full(LocalDate.of(1604, e.month, e.day)).apply { addParameter("X-APPLE-OMIT-YEAR", "1604") }
        }

    private fun VCard.appleDate(item: Int, e: ContactEvent, label: String) {
        val date = if (e.year != null) "%04d-%02d-%02d".format(e.year, e.month, e.day) else "1604-%02d-%02d".format(e.month, e.day)
        addExtendedProperty("X-ABDATE", date).group = "item$item"
        addExtendedProperty("X-ABLabel", label).group = "item$item"
    }

    private fun ByteArray.isPng(): Boolean = size > 4 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte()

    private fun String.clean(): String? = Clean.line(this)

    /** Teléfonos, emails, direcciones... por contacto: una tarjeta con miles no pasa. */
    private const val MAX_ITEMS = 100
}
