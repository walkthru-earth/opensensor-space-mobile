/// Host entry point for verify-native.sh. The Xcode OpenSensorTests target runs the same checks.
@main struct NativeChecks {
    static func main() async throws {
        try ArchiveChecks.run()
        try await TransferChecks.run()
        await CancellationChecks.run()
    }
}
