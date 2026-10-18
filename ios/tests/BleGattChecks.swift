import CoreBluetooth
import Foundation
import Synchronization

/// Checks the production GATT queue of the Bluetooth physical link without a radio.
enum BleGattChecks {
    private final class Characteristic {}

    /// Counts each write completion, so a second call fails the check.
    private final class Completion: Sendable {
        private let results = Mutex<[Bool]>([])
        var values: [Bool] { results.withLock { $0 } }
        func record(_ ok: Bool) { results.withLock { $0.append(ok) } }
    }

    static func run() {
        operationsRunOneAtATimeInOrder()
        lateCallbacksAreIgnored()
        staleSenderIsRefused()
        writeFailureFailsEveryQueuedWriteOnce()
        cancellationFailsInFlightWriteOnce()
        nativeWriteFailureIsReported()
        brokenBondIsTold()
        pendingConnectsWaitAgainAfterTheRadioReturns()
        snapshotReadsWaitForAnIdleQueue()
        print("iOS Bluetooth GATT checks passed")
    }

    private static func snapshotReadsWaitForAnIdleQueue() {
        precondition(BleSnapshotReads.needed(mtu: 247), "a 247 byte MTU omits a full snapshot notification")
        precondition(BleSnapshotReads.needed(mtu: 482), "a 482 byte MTU omits the largest snapshot notification")
        precondition(!BleSnapshotReads.needed(mtu: 483), "a 483 byte MTU notifies every snapshot")
        precondition(
            !BleSnapshotReads.failedReadEndsLink(info: false, discovering: false), "a failed paced read keeps the link")
        precondition(BleSnapshotReads.failedReadEndsLink(info: false, discovering: true), "a failed discovery read ends it")
        precondition(BleSnapshotReads.failedReadEndsLink(info: true, discovering: false), "a failed INFO read ends it")
        let live = Characteristic(), status = Characteristic(), control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        queue.attach(sender: 1)
        let sent = Completion()
        queue.write(Data([1]), to: control, from: 1, done: sent.record)
        precondition(!queue.readSnapshots(live, status), "a queued request keeps snapshot reads out")
        guard case .write? = queue.next() else { fatalError("the request runs") }
        precondition(!queue.readSnapshots(live, status), "a request in flight keeps snapshot reads out")
        precondition(queue.finish(write: control, ok: true) == .written, "the request completes")
        precondition(queue.readSnapshots(live, status), "an idle queue takes snapshot reads")
        guard case .read(let a)? = queue.next(), a === live else { fatalError("live is read first") }
        precondition(queue.finish(read: live), "the live read completes")
        guard case .read(let b)? = queue.next(), b === status else { fatalError("status is read second") }
        queue.reset()
        precondition(queue.isIdle && queue.next() == nil, "a disconnect drops the reads")
    }

    private static func pendingConnectsWaitAgainAfterTheRadioReturns() {
        var armed = ArmedConnects()
        armed.arm(["A", "B", "C"])
        precondition(armed.rearm(waiting: [], current: nil) == ["A", "B", "C"], "the radio dropped every pending connect")
        precondition(armed.rearm(waiting: ["A"], current: "B") == ["C"], "one still waiting and the current one are skipped")
        armed.adopted("C")
        armed.release("A")
        precondition(armed.rearm(waiting: [], current: nil) == ["B"], "an adopted or released sensor stays off")
        armed.release(nil)
        precondition(armed.rearm(waiting: [], current: nil).isEmpty, "back on screen ends every pending connect")
    }

    private static func operationsRunOneAtATimeInOrder() {
        let info = Characteristic(), status = Characteristic(), control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        queue.start([.read(info), .subscribe(status)])
        queue.attach(sender: 1)
        let first = Completion()
        queue.write(Data([1]), to: control, from: 1, done: first.record)
        guard case .read(let a)? = queue.next(), a === info else { fatalError("the first queued read runs first") }
        precondition(queue.next() == nil, "only one operation is in flight")
        precondition(queue.finish(read: info), "the read completes")
        guard case .subscribe(let b)? = queue.next(), b === status else { fatalError("the subscription runs second") }
        precondition(queue.finish(subscribe: status), "the subscription completes")
        guard case .write(let c, let body, _)? = queue.next(), c === control, body == Data([1]) else {
            fatalError("the write runs last")
        }
        precondition(queue.finish(write: control, ok: true) == .written, "the write completes")
        precondition(first.values == [true], "the write completion runs once")
        precondition(queue.isIdle, "the queue drains")
    }

    private static func lateCallbacksAreIgnored() {
        let live = Characteristic(), status = Characteristic(), control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        queue.start([.subscribe(live)])
        _ = queue.next()
        // A notification value for the characteristic being subscribed does not finish the subscription.
        precondition(!queue.finish(read: live), "a notification is not a read completion")
        precondition(!queue.finish(subscribe: status), "another characteristic does not finish it")
        precondition(queue.finish(subscribe: live), "the subscription completes")
        precondition(!queue.finish(subscribe: live), "a repeated callback answers nothing")
        precondition(queue.finish(write: control, ok: true) == .stale, "a write callback with nothing in flight is stale")
        queue.attach(sender: 3)
        let done = Completion()
        queue.write(Data([2]), to: control, from: 3, done: done.record)
        _ = queue.next()
        queue.reset()
        precondition(done.values == [false], "a reset fails the write in flight once")
        precondition(queue.finish(write: control, ok: true) == .stale, "the old connection's late callback is stale")
        precondition(done.values == [false], "a late callback never completes a write again")
    }

    private static func staleSenderIsRefused() {
        let control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        let early = Completion()
        queue.write(Data([1]), to: control, from: 1, done: early.record)
        precondition(early.values == [false], "a write before the link is attached fails")
        queue.attach(sender: 2)
        let stale = Completion()
        queue.write(Data([1]), to: control, from: 1, done: stale.record)
        precondition(stale.values == [false], "an earlier link's write fails")
        let missing = Completion()
        queue.write(Data([1]), to: nil, from: 2, done: missing.record)
        precondition(missing.values == [false], "a write without a control characteristic fails")
        precondition(queue.isIdle, "refused writes are never queued")
        queue.reset()
        let afterReset = Completion()
        queue.write(Data([1]), to: control, from: 2, done: afterReset.record)
        precondition(afterReset.values == [false], "no port writes after the connection ended")
    }

    private static func writeFailureFailsEveryQueuedWriteOnce() {
        let control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        queue.attach(sender: 1)
        let first = Completion(), second = Completion(), third = Completion()
        queue.write(Data([1]), to: control, from: 1, done: first.record)
        queue.write(Data([2]), to: control, from: 1, done: second.record)
        queue.write(Data([3]), to: control, from: 1, done: third.record)
        _ = queue.next()
        precondition(queue.finish(write: control, ok: false) == .linkFailed, "a failed write poisons the link")
        precondition(first.values == [false] && second.values == [false] && third.values == [false], "each write fails once")
        precondition(queue.isIdle && queue.next() == nil, "nothing is left to write")
        queue.failAll()
        precondition(first.values.count == 1 && third.values.count == 1, "failing again calls no completion twice")
    }

    private static func cancellationFailsInFlightWriteOnce() {
        let control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        queue.attach(sender: 4)
        let inFlight = Completion(), queued = Completion()
        queue.write(Data([1]), to: control, from: 4, done: inFlight.record)
        queue.write(Data([2]), to: control, from: 4, done: queued.record)
        _ = queue.next()
        queue.reset()
        precondition(inFlight.values == [false] && queued.values == [false], "Stop fails each write once")
        // Stop then an immediate restart. The new connection's port writes, the old one cannot.
        queue.attach(sender: 5)
        let old = Completion(), fresh = Completion()
        queue.write(Data([3]), to: control, from: 4, done: old.record)
        queue.write(Data([4]), to: control, from: 5, done: fresh.record)
        precondition(old.values == [false], "the stopped link's late write fails")
        guard case .write(_, let body, _)? = queue.next(), body == Data([4]) else {
            fatalError("only the restarted link's write is queued")
        }
        precondition(queue.finish(write: control, ok: true) == .written && fresh.values == [true], "the restart writes")
    }

    private static func nativeWriteFailureIsReported() {
        let control = Characteristic()
        var queue = BleGattQueue<Characteristic>()
        queue.append(.write(control, Data([9]), done: nil))
        _ = queue.next()
        precondition(queue.finish(write: control, ok: false) == .nativeFailed, "a failed token write closes the connection")
    }

    private static func brokenBondIsTold() {
        let removed = NSError(domain: CBErrorDomain, code: CBError.Code.peerRemovedPairingInformation.rawValue)
        precondition(BleBondFailure.matches(removed, previouslyBonded: false), "removed keys are a broken bond")
        precondition(BleBondFailure.matches(removed, previouslyBonded: true), "removed keys are a broken bond")
        let refused: [NSError] = [
            NSError(domain: CBErrorDomain, code: CBError.Code.encryptionTimedOut.rawValue),
            NSError(domain: CBATTErrorDomain, code: CBATTError.Code.insufficientEncryption.rawValue),
            NSError(domain: CBATTErrorDomain, code: CBATTError.Code.insufficientAuthentication.rawValue),
        ]
        for error in refused {
            precondition(BleBondFailure.matches(error, previouslyBonded: true), "a refused bonded sensor is told apart")
            // A cancelled first pairing prompt reports the same errors, and it is an ordinary failure.
            precondition(!BleBondFailure.matches(error, previouslyBonded: false), "a first pairing refusal retries")
        }
        let ordinary: [NSError?] = [
            nil,
            NSError(domain: CBErrorDomain, code: CBError.Code.peripheralDisconnected.rawValue),
            NSError(domain: CBErrorDomain, code: CBError.Code.connectionTimeout.rawValue),
            NSError(domain: CBATTErrorDomain, code: CBATTError.Code.readNotPermitted.rawValue),
            // The same number in another domain is not a Bluetooth bond failure.
            NSError(domain: NSPOSIXErrorDomain, code: CBError.Code.peerRemovedPairingInformation.rawValue),
        ]
        for error in ordinary {
            precondition(!BleBondFailure.matches(error, previouslyBonded: true), "an ordinary loss still retries")
        }
    }
}
