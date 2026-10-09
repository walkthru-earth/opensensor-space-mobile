import Foundation
import Network
import dnssd

/// precondition takes a synchronous autoclosure. This evaluates awaited or throwing checks first.
func check(_ condition: Bool, file: StaticString = #file, line: UInt = #line) {
    precondition(condition, file: file, line: line)
}

/// LAN socket plumbing. The file protocol is the shared engine's and is tested in `iosTest`.
enum TransferChecks {
    static func run() async throws {
        stateChecks()
        try frameBufferChecks()
        policyChecks()
        print("iOS LAN: claims, keepalive, framing and LAN policy passed")
    }

    private static func stateChecks() {
        var claims = SensorClaims()
        precondition(claims.connect(name: "first", device: "board"))
        claims.closeSession(name: "first")
        precondition(!claims.connect(name: "alias", device: "board"))
        claims.finishTask(name: "first")
        precondition(claims.connect(name: "alias", device: "board"))
        claims.finishTask(name: "alias")
        precondition(!claims.connect(name: "another-alias", device: "board"))
        claims.startTask(name: "alias")
        claims.closeSession(name: "alias")
        precondition(!claims.connect(name: "another-alias", device: "board"))
        claims.finishTask(name: "alias")
        precondition(claims.connect(name: "another-alias", device: "board"))
        var liveness = LanLiveness(lastSent: 0)
        precondition(!liveness.needsKeepalive(now: 119, collecting: false))
        // Frequent incoming LIVE pushes cannot reset the outgoing-traffic deadline.
        for now in stride(from: 10.0, through: 110.0, by: 10.0) {
            precondition(!liveness.needsKeepalive(now: now, collecting: false))
        }
        precondition(liveness.needsKeepalive(now: 120, collecting: false))
        precondition(!liveness.needsKeepalive(now: 120, collecting: true))
        liveness.sent(now: 120)
        precondition(!liveness.needsKeepalive(now: 121, collecting: false))
        precondition(liveness.needsKeepalive(now: 240, collecting: false))
    }

    /// LAN framing from an accumulating buffer: split prefixes, split bodies, batches, and the cap.
    private static func frameBufferChecks() throws {
        var frames = LanFrameBuffer()
        frames.append(Data([0x03]))
        check(try frames.next() == nil)
        frames.append(Data([0x00, 0x21, 0x01]))
        check(try frames.next() == nil)
        frames.append(Data([0x02, 0x02, 0x00, 0x07, 0x08, 0x01]))
        check(try frames.next() == Data([0x21, 0x01, 0x02]))
        check(try frames.next() == Data([0x07, 0x08]))
        check(try frames.next() == nil)
        precondition(frames.bufferedBytes == 1)
        frames.append(Data([0x00, 0x09]))
        check(try frames.next() == Data([0x09]))
        precondition(frames.bufferedBytes == 0)
        // A 64 KiB receive can carry many frames. All of them parse without losing order.
        var batch = Data()
        for index in 0..<200 { batch.append(contentsOf: [0x02, 0x00, 0x22, UInt8(index & 0xff)]) }
        frames.append(batch)
        for index in 0..<200 { check(try frames.next() == Data([0x22, UInt8(index & 0xff)])) }
        frames.cap = 4
        frames.append(Data([0x05, 0x00]))
        do {
            _ = try frames.next()
            fatalError("frame above the HELLO payload cap accepted")
        } catch is LanFrameError {}
        var zero = LanFrameBuffer()
        zero.append(Data([0x00, 0x00]))
        do {
            _ = try zero.next()
            fatalError("empty frame accepted")
        } catch is LanFrameError {}
    }

    private static func policyChecks() {
        precondition(LanPolicy.isLocalNetworkDenied(.dns(DNSServiceErrorType(kDNSServiceErr_PolicyDenied))))
        precondition(!LanPolicy.isLocalNetworkDenied(.dns(DNSServiceErrorType(kDNSServiceErr_NoSuchRecord))))
        precondition(!LanPolicy.isLocalNetworkDenied(.posix(.ENETDOWN)))
        precondition(LanPolicy.localNetworkAccess(.dns(DNSServiceErrorType(kDNSServiceErr_PolicyDenied))) == .denied)
        precondition(LanPolicy.localNetworkAccess(.dns(DNSServiceErrorType(kDNSServiceErr_NoSuchRecord))) == .unknown)
        precondition(LanPolicy.localNetworkAccess(.posix(.ENETDOWN)) == .unknown)
        let parameters = LanPolicy.connectionParameters()
        precondition(parameters.prohibitedInterfaceTypes == [.cellular])
        let tcp = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options
        precondition(tcp?.connectionTimeout == LanPolicy.connectTimeoutSeconds && tcp?.noDelay == true)
    }
}
