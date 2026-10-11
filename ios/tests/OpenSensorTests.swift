import Testing

/// Hostless unit tests. The production archive, LAN policy and cancellation sources compile
/// directly into this bundle, so the tests need neither the app host nor the Kotlin framework.
/// verify-native.sh runs the same checks on the Mac host.
@Suite("Native archive, transfer and cancellation")
struct OpenSensorTests {
    @Test("Archive resume, receipts, export, CRC, conflicts, paths, symlinks and stale partials")
    func archive() throws {
        try ArchiveChecks.run()
    }

    @Test("LAN claims, keepalive, framing and policy")
    func transfer() async throws {
        try await TransferChecks.run()
    }

    @Test("Early cancellation, immediate completion and background cancellation")
    func cancellation() async {
        await CancellationChecks.run()
    }
}
