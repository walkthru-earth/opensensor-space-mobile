import Foundation
import Network
import dnssd

/// Network policy shared by Bonjour discovery and LAN sessions. Kept free of the Kotlin framework
/// so the native test harness can exercise it on the host.
enum LanPolicy {
    /// TCP connect attempts end after this many seconds instead of the system default.
    static let connectTimeoutSeconds = 10

    /// LAN sockets must stay on the local network. Cellular is prohibited; Wi-Fi and wired remain.
    static func connectionParameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = connectTimeoutSeconds
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.prohibitedInterfaceTypes = [.cellular]
        return parameters
    }

    /// Local Network access as the shared permission policy reads it (`IosAuthorization.localNetwork`).
    /// iOS has no status API for it, so it is unknown until the policy denial, then denied, which
    /// makes the shared screen offer the app's Settings page.
    enum LocalNetworkAccess: Sendable, Equatable {
        case unknown
        case denied
    }

    static func localNetworkAccess(_ error: NWError) -> LocalNetworkAccess {
        isLocalNetworkDenied(error) ? .denied : .unknown
    }

    /// TN3179: a browser waiting with kDNSServiceErr_PolicyDenied means Local Network access is off.
    /// Every other waiting reason (no Wi-Fi, prompt still showing) stays ambiguous.
    static func isLocalNetworkDenied(_ error: NWError) -> Bool {
        if case .dns(let code) = error { return code == DNSServiceErrorType(kDNSServiceErr_PolicyDenied) }
        return false
    }
}
