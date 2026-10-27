package space.opensensor.thing.testkit

import space.opensensor.thing.DataSource
import space.opensensor.thing.Evidence
import space.opensensor.thing.PublishGate
import space.opensensor.thing.PublishPolicy
import space.opensensor.thing.ThingKind
import space.opensensor.thing.ThingKinds
import space.opensensor.thing.UploadConsent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The checks every [ThingKind] passes, run from a kind's own `commonTest` so they gate on the JVM and
 * the iOS simulator (spec core.md section 8).
 */
object ThingKindContract {
    /** A Thing UUID the checks ask tickets for. */
    const val THING = "00000000-0000-4000-8000-000000000001"

    /**
     * Checks [kind]. Each of [recognized] is evidence of its own Things and [foreign] is evidence of
     * none. The publish checks hold for every kind.
     */
    fun check(kind: ThingKind, recognized: List<Evidence>, foreign: List<Evidence>) {
        assertTrue(kind.contractVersion >= 1, "${kind.id} has a contract version")
        for (evidence in recognized) {
            assertEquals(kind.id, assertNotNull(kind.recognize(evidence), "${kind.id} recognizes $evidence").kind)
        }
        for (evidence in foreign) assertNull(kind.recognize(evidence), "${kind.id} does not recognize $evidence")
        checkPublish(kind)
    }

    /**
     * A ticket goes only to a [DataSource.Recording] kind with [PublishPolicy.OptInCcBy] whose Thing
     * the owner opted in. Nothing is opted in by default.
     */
    fun checkPublish(kind: ThingKind) {
        val kinds = ThingKinds(listOf(kind))
        assertNull(PublishGate(kinds).ticket(kind.id, THING), "${kind.id} gets no ticket without an opt in")
        val publishable = (kind.source as? DataSource.Recording)?.publish is PublishPolicy.OptInCcBy
        val ticket = PublishGate(kinds, UploadConsent { it == THING }).ticket(kind.id, THING)
        if (publishable) {
            assertEquals(THING, assertNotNull(ticket, "${kind.id} gets a ticket once opted in").thing)
            assertNull(PublishGate(kinds, UploadConsent { it == THING }).ticket(kind.id, "another"), "the opt in is per Thing")
        } else {
            assertNull(ticket, "${kind.id} never gets a ticket")
        }
    }
}
