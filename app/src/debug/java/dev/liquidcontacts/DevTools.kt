// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.contacts.EditableContact
import dev.liquidcontacts.contacts.SystemContacts
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import kotlin.random.Random

/**
 * Solo en debug y profile: una agenda grande de datos INVENTADOS para probar el rendimiento y la
 * interfaz sin usar contactos reales. Todos van en la etiqueta [LABEL] (en la cuenta local) y cada
 * ficha queda marcada con un SOURCE_ID propio: "Borrar contactos de prueba" borra solo esas fichas.
 *
 * Los números usan el formato argentino con abonados al azar; los emails, el dominio reservado
 * example.com (no existe ninguna casilla real ahí).
 */
object DevTools {
    const val available = true
    const val LABEL = "Prueba (debug)"
    private const val SOURCE = "liquidcontacts-debug-"

    private val given = listOf(
        "Ana", "Ángela", "Beatriz", "Bruno", "Camila", "Carlos", "Cecilia", "Damián", "Daniela", "Emilia", "Esteban", "Facundo",
        "Florencia", "Gabriel", "Gonzalo", "Héctor", "Inés", "Ignacio", "Julián", "Julieta", "Lautaro", "Lucía", "Martín", "María José",
        "Matías", "Micaela", "Nahuel", "Noelia", "Óscar", "Paula", "Ramiro", "Rocío", "Santiago", "Sofía", "Tomás", "Úrsula",
        "Valentina", "Víctor", "Ximena", "Yamila", "Zoe", "Ñuke",
    )
    private val family = listOf(
        "Acosta", "Benítez", "Castro", "Díaz", "Escobar", "Fernández", "Giménez", "Gómez", "Herrera", "Ibáñez", "Juárez", "López",
        "Martínez", "Muñoz", "Núñez", "Ñancucheo", "Ortiz", "Peña", "Quiroga", "Ríos", "Sánchez", "Torres", "Ugarte", "Vázquez",
        "Zabala", "O'Neill", "De la Fuente",
    )
    private val companies = listOf("Taller Sur", "Panadería La Espiga", "Estudio Delta", "Ferretería Centro", null, null, null)
    private val areas = listOf("11" to 8, "351" to 7, "221" to 7, "261" to 7, "341" to 7, "2966" to 6)

    fun generate(contacts: SystemContacts, count: Int): Int {
        val rnd = Random(20261031)
        val account = contacts.localAccount()
        var made = 0
        repeat(count) { i ->
            val g = given[rnd.nextInt(given.size)]
            val f = family[rnd.nextInt(family.size)]
            val (area, len) = areas[rnd.nextInt(areas.size)]
            val subscriber = (1..len).map { if (it == 1) rnd.nextInt(2, 10) else rnd.nextInt(10) }.joinToString("")
            val number = when (i % 5) {
                0 -> "+54 9 $area $subscriber"
                1 -> "0$area 15 $subscriber"
                // Algunos sin código de área, para probar el aviso.
                2 -> if (area == "11") "15 $subscriber" else "0$area 15 $subscriber"
                3 -> "0$area $subscriber"
                else -> "+54$area$subscriber"
            }
            val c = Contact(
                name = Name(given = g, family = f),
                organization = companies[rnd.nextInt(companies.size)],
                phones = listOf(Phone(number, if (i % 5 == 3) LabelKind.HOME else LabelKind.MOBILE, primary = true)),
                emails = if (i % 3 == 0) listOf(Email("${g.lowercase().filter { it.isLetter() }}.${i}@example.com")) else emptyList(),
                events = if (i % 7 == 0) listOf(ContactEvent(rnd.nextInt(1, 13), rnd.nextInt(1, 29), if (i % 2 == 0) rnd.nextInt(1950, 2010) else null)) else emptyList(),
                groups = setOf(LABEL),
                starred = i % 40 == 0,
            )
            // La ficha nace marcada con su origen: "borrar" solo toca las que tienen esa marca.
            runCatching { contacts.save(EditableContact(null, account, c), photoChanged = false, sourceId = "$SOURCE$i") }.onSuccess { made++ }
        }
        return made
    }

    /**
     * Un contacto inventado con fichas en dos cuentas: la local y una cuenta de prueba que no
     * existe en el sistema. Sirve para probar la edición por ficha y la unión sin borrado.
     */
    fun generateMultiAccount(contacts: SystemContacts): Int {
        val local = contacts.save(
            EditableContact(null, contacts.localAccount(), Contact(name = Name(given = "Fichas", family = "Dos Cuentas"), phones = listOf(Phone("011 4000-1111")))),
            photoChanged = false,
            sourceId = SOURCE + "multi-1",
        )
        val other = contacts.save(
            EditableContact(null, ContactAccount("dev.liquidcontacts.test", "cuenta-prueba@example.com"), Contact(name = Name(given = "Fichas", family = "Otra Cuenta"), emails = listOf(Email("fichas@example.com")))),
            photoChanged = false,
            sourceId = SOURCE + "multi-2",
        )
        contacts.merge(listOf(local, other), Contact())
        return 1
    }

    /**
     * Borra solo las fichas que creó [generate] (por su SOURCE_ID "liquidcontacts-debug-N"), una
     * por una: si alguna se unió a un contacto real, el contacto real queda. Después borra la
     * etiqueta de prueba si quedó vacía. Nunca borra por nombre ni por etiqueta.
     */
    fun clear(contacts: SystemContacts): Int {
        val raws = contacts.rawIdsWithSourcePrefix(SOURCE)
        raws.forEach(contacts::deleteRaw)
        contacts.groups().filter { it.title == LABEL && it.count == 0 }.forEach { contacts.deleteGroup(it.id) }
        return raws.size
    }
}
