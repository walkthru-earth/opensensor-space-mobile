import Testing

/// Hostless unit tests. The production archive, transfer engine and LAN policy sources compile
/// directly into this bundle, so the tests need neither the app host nor the Kotlin framework.
/// verify-native.sh runs the same checks on the Mac host.
@Suite("Native archive and transfer")
struct OpenSensorTests {
    @Test("Archive resume, receipts, export, CRC, conflicts, paths, symlinks and stale partials")
    func archive() throws {
        try ArchiveChecks.run()
    }

    @Test("Transfer window retry, framing, LAN policy, resume, progress, stations and per-file failures")
    func transfer() async throws {
        try await TransferChecks.run()
    }
}
